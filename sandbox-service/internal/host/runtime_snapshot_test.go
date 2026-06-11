package host

import (
	"testing"
	"time"
)

func TestSessionManagerSnapshotIncludesIdleAndActiveUses(t *testing.T) {
	baseTime := time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC)
	now := baseTime
	vm := &fakeVM{
		id:      "fc-1",
		url:     "http://172.16.0.2:8081",
		runDir:  "/srv/sandbox/run/fc-1",
		tapName: "tap-ah000001",
		pid:     1234,
	}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{vm}}, 1, 10*time.Second)
	manager.setClockForTest(func() time.Time {
		return now
	})

	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	idleSnapshots := manager.Snapshot(baseTime.Add(11 * time.Second))
	if len(idleSnapshots) != 1 {
		t.Fatalf("snapshot len = %d", len(idleSnapshots))
	}
	idle := idleSnapshots[0]
	if idle.SandboxSessionID != session.ID {
		t.Fatalf("SandboxSessionID = %q", idle.SandboxSessionID)
	}
	if idle.ConversationID != "conversation-1" {
		t.Fatalf("ConversationID = %q", idle.ConversationID)
	}
	if idle.VMID != "fc-1" {
		t.Fatalf("VMID = %q", idle.VMID)
	}
	if idle.AgentURL != "http://172.16.0.2:8081" {
		t.Fatalf("AgentURL = %q", idle.AgentURL)
	}
	if !idle.LastActiveAt.Equal(baseTime) {
		t.Fatalf("LastActiveAt = %s", idle.LastActiveAt)
	}
	if idle.ActiveUses != 0 {
		t.Fatalf("ActiveUses = %d", idle.ActiveUses)
	}
	if idle.IdleSeconds != 11 {
		t.Fatalf("IdleSeconds = %d", idle.IdleSeconds)
	}
	if idle.IdleTimeoutSeconds != 10 {
		t.Fatalf("IdleTimeoutSeconds = %d", idle.IdleTimeoutSeconds)
	}
	if idle.RunDir != "/srv/sandbox/run/fc-1" {
		t.Fatalf("RunDir = %q", idle.RunDir)
	}
	if idle.TapName != "tap-ah000001" {
		t.Fatalf("TapName = %q", idle.TapName)
	}
	if idle.FirecrackerPID != 1234 {
		t.Fatalf("FirecrackerPID = %d", idle.FirecrackerPID)
	}
	if idle.RuntimeStatus != "IDLE" {
		t.Fatalf("RuntimeStatus = %q", idle.RuntimeStatus)
	}

	now = baseTime.Add(11 * time.Second)
	if _, ok := manager.BeginUse(session.ID); !ok {
		t.Fatal("expected BeginUse() to find session")
	}
	activeSnapshots := manager.Snapshot(baseTime.Add(30 * time.Second))
	active := activeSnapshots[0]
	if active.ActiveUses != 1 {
		t.Fatalf("ActiveUses = %d", active.ActiveUses)
	}
	if active.RuntimeStatus != "ACTIVE" {
		t.Fatalf("RuntimeStatus = %q", active.RuntimeStatus)
	}
}

func TestSessionManagerSnapshotDoesNotExposeMutableSessionPointers(t *testing.T) {
	baseTime := time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC)
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "fc-1"}}}, 1, time.Minute)
	manager.setClockForTest(func() time.Time {
		return baseTime
	})

	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	snapshots := manager.Snapshot(baseTime)
	snapshots[0].ConversationID = "modified"
	snapshots[0].LastActiveAt = time.Time{}

	got, ok := manager.Get(session.ID)
	if !ok {
		t.Fatal("expected session to exist")
	}
	if got.ConversationID != "conversation-1" {
		t.Fatalf("ConversationID = %q", got.ConversationID)
	}
	if got.LastActiveAt.IsZero() {
		t.Fatal("LastActiveAt was modified through returned snapshot")
	}
}

func TestWarmPoolSnapshotReportsPooledVMs(t *testing.T) {
	pool := NewWarmPool(&fakeVMFactory{}, 2)
	first := &fakeVM{id: "fc-1", url: "http://172.16.0.2:8081", runDir: "/run/fc-1", tapName: "tap-ah000001", pid: 111}
	second := &fakeVM{id: "fc-2", url: "http://172.16.0.6:8081", runDir: "/run/fc-2", tapName: "tap-ah000002", pid: 222}

	if err := pool.Release(first); err != nil {
		t.Fatalf("Release(first) error = %v", err)
	}
	if err := pool.Release(second); err != nil {
		t.Fatalf("Release(second) error = %v", err)
	}

	snapshot := pool.Snapshot()
	if snapshot.TargetSize != 2 {
		t.Fatalf("TargetSize = %d", snapshot.TargetSize)
	}
	if snapshot.Size != 2 {
		t.Fatalf("Size = %d", snapshot.Size)
	}
	if snapshot.Starting != 0 {
		t.Fatalf("Starting = %d", snapshot.Starting)
	}
	if len(snapshot.VMs) != 2 {
		t.Fatalf("VMs len = %d", len(snapshot.VMs))
	}
	if snapshot.VMs[0].ID != "fc-1" || snapshot.VMs[1].ID != "fc-2" {
		t.Fatalf("VM IDs = %#v", snapshot.VMs)
	}
	if snapshot.VMs[0].RunDir != "/run/fc-1" || snapshot.VMs[1].TapName != "tap-ah000002" {
		t.Fatalf("VM metadata = %#v", snapshot.VMs)
	}

	snapshot.VMs[0].ID = "modified"
	again := pool.Snapshot()
	if again.VMs[0].ID != "fc-1" {
		t.Fatalf("pooled VM metadata was modified through snapshot: %#v", again.VMs[0])
	}
}
