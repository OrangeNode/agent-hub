package host

import (
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
	nextID         int
	starting       int
	clock          func() time.Time
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
		nextID:         1,
		clock:          time.Now,
	}
}

func (m *SessionManager) GetOrCreate(conversationID string) (Session, error) {
	m.mu.Lock()
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

	sessionID := fmt.Sprintf("sbx_%06d", m.nextID)
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
	m.mu.Lock()
	var idle []VM
	for id, session := range m.sessions {
		if now.Sub(session.LastActiveAt) >= m.idleTimeout {
			idle = append(idle, session.VM)
			delete(m.sessions, id)
			delete(m.byConversation, session.ConversationID)
		}
	}
	m.mu.Unlock()

	var errs []error
	for _, vm := range idle {
		if err := vm.Stop(); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
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
