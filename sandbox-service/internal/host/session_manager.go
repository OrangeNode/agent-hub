package host

import (
	"errors"
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
	ConversationID string
	VM             VM
	AgentURL       string
	LastActiveAt   time.Time
}

type SessionManager struct {
	factory     VMFactory
	maxActive   int
	idleTimeout time.Duration

	mu       sync.Mutex
	sessions map[string]*Session
}

func NewSessionManager(factory VMFactory, maxActive int, idleTimeout time.Duration) *SessionManager {
	if idleTimeout <= 0 {
		idleTimeout = 5 * time.Minute
	}
	return &SessionManager{
		factory:     factory,
		maxActive:   maxActive,
		idleTimeout: idleTimeout,
		sessions:    make(map[string]*Session),
	}
}

func (m *SessionManager) GetOrCreate(conversationID string) (*Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	if session, ok := m.sessions[conversationID]; ok {
		session.LastActiveAt = now
		return session, nil
	}
	if m.maxActive > 0 && len(m.sessions) >= m.maxActive {
		return nil, errors.New("当前沙箱会话数量已达到上限")
	}

	vm, err := m.factory.Start()
	if err != nil {
		return nil, err
	}
	session := &Session{
		ConversationID: conversationID,
		VM:             vm,
		AgentURL:       vm.AgentURL(),
		LastActiveAt:   now,
	}
	m.sessions[conversationID] = session
	return session, nil
}

func (m *SessionManager) ReapIdle(now time.Time) error {
	m.mu.Lock()
	var idle []VM
	for id, session := range m.sessions {
		if now.Sub(session.LastActiveAt) >= m.idleTimeout {
			idle = append(idle, session.VM)
			delete(m.sessions, id)
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

func (m *SessionManager) HasSession(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.sessions[id]
	return ok
}
