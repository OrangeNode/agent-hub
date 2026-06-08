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
	}
}

func (m *SessionManager) GetOrCreate(conversationID string) (*Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	if sessionID, ok := m.byConversation[conversationID]; ok {
		if session, ok := m.sessions[sessionID]; ok {
			session.LastActiveAt = now
			return session, nil
		}
		delete(m.byConversation, conversationID)
	}
	if m.maxActive > 0 && len(m.sessions) >= m.maxActive {
		return nil, errors.New("当前沙箱会话数量已达到上限")
	}

	vm, err := m.factory.Start()
	if err != nil {
		return nil, err
	}
	sessionID := fmt.Sprintf("sbx_%06d", m.nextID)
	m.nextID++
	session := &Session{
		ID:             sessionID,
		ConversationID: conversationID,
		VM:             vm,
		AgentURL:       vm.AgentURL(),
		LastActiveAt:   now,
	}
	m.sessions[sessionID] = session
	m.byConversation[conversationID] = sessionID
	return session, nil
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

func (m *SessionManager) Get(sessionID string) (*Session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	session, ok := m.sessions[sessionID]
	return session, ok
}

func (m *SessionManager) HasSession(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.sessions[id]
	return ok
}
