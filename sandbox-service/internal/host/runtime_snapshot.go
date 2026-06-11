package host

import (
	"sort"
	"time"
)

const (
	RuntimeStatusActive = "ACTIVE"
	RuntimeStatusIdle   = "IDLE"
)

type VMMetadata struct {
	ID       string `json:"vmId"`
	AgentURL string `json:"agentUrl"`
	RunDir   string `json:"runDir,omitempty"`
	TapName  string `json:"tapName,omitempty"`
	PID      int    `json:"pid,omitempty"`
}

type MetadataProvider interface {
	Metadata() VMMetadata
}

type RuntimeSessionSnapshot struct {
	SandboxSessionID   string    `json:"sandboxSessionId"`
	ConversationID     string    `json:"conversationId"`
	VMID               string    `json:"vmId"`
	AgentURL           string    `json:"agentUrl"`
	LastActiveAt       time.Time `json:"lastActiveAt"`
	ActiveUses         int       `json:"activeUses"`
	IdleSeconds        int64     `json:"idleSeconds"`
	IdleTimeoutSeconds int64     `json:"idleTimeoutSeconds"`
	RunDir             string    `json:"runDir,omitempty"`
	TapName            string    `json:"tapName,omitempty"`
	FirecrackerPID     int       `json:"firecrackerPid,omitempty"`
	RuntimeStatus      string    `json:"runtimeStatus"`
}

type WarmPoolSnapshot struct {
	TargetSize int          `json:"targetSize"`
	Size       int          `json:"size"`
	Starting   int          `json:"starting"`
	VMs        []VMMetadata `json:"vms"`
}

func (m *SessionManager) Snapshot(now time.Time) []RuntimeSessionSnapshot {
	m.mu.Lock()
	defer m.mu.Unlock()
	if now.IsZero() {
		now = m.nowLocked()
	}

	snapshots := make([]RuntimeSessionSnapshot, 0, len(m.sessions))
	for _, session := range m.sessions {
		metadata := metadataForVM(session.VM)
		idleSeconds := int64(now.Sub(session.LastActiveAt).Seconds())
		if idleSeconds < 0 {
			idleSeconds = 0
		}
		activeUses := m.activeUses[session.ID]
		status := RuntimeStatusActive
		if activeUses == 0 && idleSeconds >= int64(m.idleTimeout.Seconds()) {
			status = RuntimeStatusIdle
		}
		snapshots = append(snapshots, RuntimeSessionSnapshot{
			SandboxSessionID:   session.ID,
			ConversationID:     session.ConversationID,
			VMID:               metadata.ID,
			AgentURL:           firstNonEmpty(metadata.AgentURL, session.AgentURL),
			LastActiveAt:       session.LastActiveAt,
			ActiveUses:         activeUses,
			IdleSeconds:        idleSeconds,
			IdleTimeoutSeconds: int64(m.idleTimeout.Seconds()),
			RunDir:             metadata.RunDir,
			TapName:            metadata.TapName,
			FirecrackerPID:     metadata.PID,
			RuntimeStatus:      status,
		})
	}
	sort.Slice(snapshots, func(i, j int) bool {
		return snapshots[i].SandboxSessionID < snapshots[j].SandboxSessionID
	})
	return snapshots
}

func (p *WarmPool) Snapshot() WarmPoolSnapshot {
	p.mu.Lock()
	vms := append([]VM(nil), p.pool...)
	starting := p.starting
	targetSize := p.targetSize
	p.mu.Unlock()

	metadata := make([]VMMetadata, 0, len(vms))
	for _, vm := range vms {
		metadata = append(metadata, metadataForVM(vm))
	}
	return WarmPoolSnapshot{
		TargetSize: targetSize,
		Size:       len(vms),
		Starting:   starting,
		VMs:        metadata,
	}
}

func metadataForVM(vm VM) VMMetadata {
	if vm == nil {
		return VMMetadata{}
	}
	if provider, ok := vm.(MetadataProvider); ok {
		metadata := provider.Metadata()
		if metadata.ID == "" {
			metadata.ID = vm.ID()
		}
		if metadata.AgentURL == "" {
			metadata.AgentURL = vm.AgentURL()
		}
		return metadata
	}
	return VMMetadata{
		ID:       vm.ID(),
		AgentURL: vm.AgentURL(),
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}
