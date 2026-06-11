package host

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"
)

type VM interface {
	ID() string
	AgentURL() string
	Stop() error
}

type VMFactory interface {
	Start() (VM, error)
}

type Session struct {
	ID             string
	ConversationID string
	VM             VM
	AgentURL       string
	LastActiveAt   time.Time
}

type SessionManager struct {
	factory     VMFactory
	maxActive   int
	idleTimeout time.Duration

	mu             sync.Mutex
	sessions       map[string]*Session
	byConversation map[string]string
	activeUses     map[string]int
	nextID         int
	starting       int
	clock          func() time.Time
	runtimeID      string
}

type ReapResult struct {
	ClosedSessions []string `json:"closedSessions"`
	StoppedVMs     []string `json:"stoppedVms"`
	Errors         []string `json:"errors"`
}

type reapTarget struct {
	sessionID string
	vm        VM
	vmID      string
}

func NewSessionManager(factory VMFactory, maxActive int, idleTimeout time.Duration) *SessionManager {
	if idleTimeout <= 0 {
		idleTimeout = 5 * time.Minute
	}
	return &SessionManager{
		factory:        factory,
		maxActive:      maxActive,
		idleTimeout:    idleTimeout,
		sessions:       make(map[string]*Session),
		byConversation: make(map[string]string),
		activeUses:     make(map[string]int),
		nextID:         1,
		clock:          time.Now,
		runtimeID:      newRuntimeID(),
	}
}

func (m *SessionManager) GetOrCreate(conversationID string) (Session, error) {
	m.mu.Lock()
	idle := m.collectIdleLocked(m.nowLocked())
	if len(idle) > 0 {
		m.mu.Unlock()
		if err := stopVMs(idle); err != nil {
			return Session{}, err
		}
		m.mu.Lock()
	}
	if session, ok := m.sessionByConversationLocked(conversationID); ok {
		snapshot := m.touchLocked(session)
		m.mu.Unlock()
		return snapshot, nil
	}
	if m.maxActive > 0 && len(m.sessions)+m.starting >= m.maxActive {
		m.mu.Unlock()
		return Session{}, errors.New("当前沙箱会话数量已达到上限")
	}
	m.starting++
	m.mu.Unlock()

	vm, err := m.factory.Start()
	if err != nil {
		m.mu.Lock()
		m.starting--
		m.mu.Unlock()
		return Session{}, err
	}

	m.mu.Lock()
	m.starting--
	if session, ok := m.sessionByConversationLocked(conversationID); ok {
		snapshot := m.touchLocked(session)
		m.mu.Unlock()
		if err := vm.Stop(); err != nil {
			return snapshot, fmt.Errorf("停止重复沙箱 VM 失败: %w", err)
		}
		return snapshot, nil
	}

	sessionID := fmt.Sprintf("sbx_%s_%06d", m.runtimeID, m.nextID)
	m.nextID++
	session := &Session{
		ID:             sessionID,
		ConversationID: conversationID,
		VM:             vm,
		AgentURL:       vm.AgentURL(),
		LastActiveAt:   m.nowLocked(),
	}
	m.sessions[sessionID] = session
	m.byConversation[conversationID] = sessionID
	snapshot := *session
	m.mu.Unlock()
	return snapshot, nil
}

func (m *SessionManager) ReapIdle(now time.Time) error {
	result := m.ReapIdleWithResult(now)
	if len(result.Errors) == 0 {
		return nil
	}
	errs := make([]error, 0, len(result.Errors))
	for _, message := range result.Errors {
		errs = append(errs, errors.New(message))
	}
	return errors.Join(errs...)
}

func (m *SessionManager) ReapIdleWithResult(now time.Time) ReapResult {
	m.mu.Lock()
	targets := m.collectIdleTargetsLocked(now)
	m.mu.Unlock()

	return stopReapTargets(targets)
}

func (m *SessionManager) Close(sessionID string) ReapResult {
	m.mu.Lock()
	session, ok := m.sessions[sessionID]
	if !ok {
		m.mu.Unlock()
		return ReapResult{Errors: []string{fmt.Sprintf("sandbox session 不存在: %s", sessionID)}}
	}
	delete(m.sessions, sessionID)
	delete(m.byConversation, session.ConversationID)
	delete(m.activeUses, sessionID)
	target := reapTarget{
		sessionID: session.ID,
		vm:        session.VM,
		vmID:      session.VM.ID(),
	}
	m.mu.Unlock()

	return stopReapTargets([]reapTarget{target})
}

func (m *SessionManager) collectIdleLocked(now time.Time) []VM {
	targets := m.collectIdleTargetsLocked(now)
	vms := make([]VM, 0, len(targets))
	for _, target := range targets {
		vms = append(vms, target.vm)
	}
	return vms
}

func (m *SessionManager) collectIdleTargetsLocked(now time.Time) []reapTarget {
	if now.IsZero() {
		now = m.nowLocked()
	}
	var targets []reapTarget
	for id, session := range m.sessions {
		if m.activeUses[id] > 0 {
			continue
		}
		if now.Sub(session.LastActiveAt) >= m.idleTimeout {
			targets = append(targets, reapTarget{
				sessionID: id,
				vm:        session.VM,
				vmID:      session.VM.ID(),
			})
			delete(m.sessions, id)
			delete(m.byConversation, session.ConversationID)
			delete(m.activeUses, id)
		}
	}
	return targets
}

func stopVMs(vms []VM) error {
	var errs []error
	for _, vm := range vms {
		if err := vm.Stop(); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

func stopReapTargets(targets []reapTarget) ReapResult {
	result := ReapResult{}
	for _, target := range targets {
		result.ClosedSessions = append(result.ClosedSessions, target.sessionID)
		if err := target.vm.Stop(); err != nil {
			result.Errors = append(result.Errors, fmt.Sprintf("停止 VM %s 失败: %v", target.vmID, err))
			continue
		}
		result.StoppedVMs = append(result.StoppedVMs, target.vmID)
	}
	return result
}

func (m *SessionManager) Get(sessionID string) (Session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	session, ok := m.sessions[sessionID]
	if !ok {
		return Session{}, false
	}
	return *session, true
}

func (m *SessionManager) BeginUse(sessionID string) (Session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	session, ok := m.sessions[sessionID]
	if !ok {
		return Session{}, false
	}
	m.activeUses[sessionID]++
	return m.touchLocked(session), true
}

func (m *SessionManager) EndUse(sessionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.activeUses[sessionID] > 1 {
		m.activeUses[sessionID]--
		return
	}
	delete(m.activeUses, sessionID)
	if session, ok := m.sessions[sessionID]; ok {
		_ = m.touchLocked(session)
	}
}

func (m *SessionManager) HasSession(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.sessions[id]
	return ok
}

func (m *SessionManager) sessionByConversationLocked(conversationID string) (*Session, bool) {
	sessionID, ok := m.byConversation[conversationID]
	if !ok {
		return nil, false
	}
	session, ok := m.sessions[sessionID]
	if !ok {
		delete(m.byConversation, conversationID)
		return nil, false
	}
	return session, true
}

func (m *SessionManager) touchLocked(session *Session) Session {
	session.LastActiveAt = m.nowLocked()
	return *session
}

func (m *SessionManager) nowLocked() time.Time {
	if m.clock == nil {
		return time.Now()
	}
	return m.clock()
}

func (m *SessionManager) setClockForTest(clock func() time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.clock = clock
}

func newRuntimeID() string {
	bytes := make([]byte, 6)
	if _, err := rand.Read(bytes); err == nil {
		return hex.EncodeToString(bytes)
	}
	return fmt.Sprintf("%012x", time.Now().UnixNano()&0xffffffffffff)
}
