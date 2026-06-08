package host

import (
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestSessionManagerReusesSessionForSameConversation(t *testing.T) {
	baseTime := time.Date(2026, 6, 8, 10, 0, 0, 0, time.UTC)
	now := baseTime
	factory := &fakeVMFactory{}
	manager := NewSessionManager(factory, 2, 5*time.Minute)
	manager.setClockForTest(func() time.Time {
		return now
	})

	first, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	now = now.Add(time.Second)

	second, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	if first.ID == "" {
		t.Fatal("expected session ID")
	}
	if first.ID != second.ID {
		t.Fatalf("session ID changed: first=%q second=%q", first.ID, second.ID)
	}
	if factory.StartCount() != 1 {
		t.Fatalf("starts = %d", factory.StartCount())
	}
	if !first.LastActiveAt.Equal(baseTime) {
		t.Fatalf("first LastActiveAt = %s", first.LastActiveAt)
	}
	if !second.LastActiveAt.Equal(now) {
		t.Fatalf("second LastActiveAt = %s", second.LastActiveAt)
	}
	if !manager.HasSession(first.ID) {
		t.Fatal("expected manager to have session")
	}
	if manager.HasSession("conversation-1") {
		t.Fatal("expected HasSession to use session ID, not conversation ID")
	}
	got, ok := manager.Get(first.ID)
	if !ok {
		t.Fatal("expected Get() to find session")
	}
	if got.ID != first.ID {
		t.Fatal("expected Get() to return the original session")
	}
}

func TestSessionManagerReturnsSessionCopies(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	session.ConversationID = "modified"
	session.LastActiveAt = time.Time{}

	got, ok := manager.Get(session.ID)
	if !ok {
		t.Fatal("expected Get() to find session")
	}
	if got.ConversationID != "conversation-1" {
		t.Fatalf("ConversationID = %q", got.ConversationID)
	}
	if got.LastActiveAt.IsZero() {
		t.Fatal("LastActiveAt was modified through returned value")
	}
}

func TestSessionManagerRejectsWhenCapacityReached(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 1, 5*time.Minute)

	if _, err := manager.GetOrCreate("conversation-1"); err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	_, err := manager.GetOrCreate("conversation-2")
	if err == nil {
		t.Fatal("expected capacity error")
	}
	if err.Error() != "当前沙箱会话数量已达到上限" {
		t.Fatalf("error = %q", err.Error())
	}
}

func TestSessionManagerReapsIdleSessionsAndStopsVM(t *testing.T) {
	baseTime := time.Date(2026, 6, 8, 10, 0, 0, 0, time.UTC)
	now := baseTime
	vm := &fakeVM{id: "vm-1"}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{vm}}, 1, 10*time.Millisecond)
	manager.setClockForTest(func() time.Time {
		return now
	})

	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	if err := manager.ReapIdle(baseTime.Add(time.Second)); err != nil {
		t.Fatalf("ReapIdle() error = %v", err)
	}

	if manager.HasSession(session.ID) {
		t.Fatal("expected idle session to be removed")
	}
	if _, ok := manager.Get(session.ID); ok {
		t.Fatal("expected idle session to be unavailable by session ID")
	}
	if vm.StopCount() != 1 {
		t.Fatalf("stops = %d", vm.StopCount())
	}
}

func TestSessionManagerRefreshPreventsIdleReap(t *testing.T) {
	baseTime := time.Date(2026, 6, 8, 10, 0, 0, 0, time.UTC)
	now := baseTime
	vm := &fakeVM{id: "vm-1"}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{vm}}, 1, 10*time.Millisecond)
	manager.setClockForTest(func() time.Time {
		return now
	})

	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	now = baseTime.Add(9 * time.Millisecond)
	if _, err := manager.GetOrCreate("conversation-1"); err != nil {
		t.Fatalf("GetOrCreate(reuse) error = %v", err)
	}

	if err := manager.ReapIdle(baseTime.Add(15 * time.Millisecond)); err != nil {
		t.Fatalf("ReapIdle() error = %v", err)
	}

	if !manager.HasSession(session.ID) {
		t.Fatal("expected refreshed session to remain active")
	}
	if vm.StopCount() != 0 {
		t.Fatalf("stops = %d", vm.StopCount())
	}
}

func TestSessionManagerStopsDuplicateVMWhenConcurrentCreateWins(t *testing.T) {
	factory := newBlockingFirstFactory()
	manager := NewSessionManager(factory, 2, 5*time.Minute)

	var first Session
	var firstErr error
	done := make(chan struct{})
	go func() {
		defer close(done)
		first, firstErr = manager.GetOrCreate("conversation-1")
	}()

	<-factory.firstStarted
	var second Session
	var secondErr error
	secondDone := make(chan struct{})
	go func() {
		defer close(secondDone)
		second, secondErr = manager.GetOrCreate("conversation-1")
	}()

	select {
	case <-secondDone:
	case <-time.After(100 * time.Millisecond):
		factory.ReleaseFirst()
		<-done
		<-secondDone
		t.Fatal("GetOrCreate held manager lock while starting VM")
	}
	if secondErr != nil {
		t.Fatalf("GetOrCreate(second) error = %v", secondErr)
	}
	factory.ReleaseFirst()
	<-done
	if firstErr != nil {
		t.Fatalf("GetOrCreate(first) error = %v", firstErr)
	}

	if first.ID != second.ID {
		t.Fatalf("session ID mismatch: first=%q second=%q", first.ID, second.ID)
	}
	if factory.StartCount() != 2 {
		t.Fatalf("starts = %d", factory.StartCount())
	}
	if factory.FirstVM().StopCount() != 1 {
		t.Fatalf("duplicate VM stops = %d", factory.FirstVM().StopCount())
	}
}

func TestSessionManagerGeneratesStableSequentialSessionIDs(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 2, 5*time.Minute)

	first, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate(first) error = %v", err)
	}
	second, err := manager.GetOrCreate("conversation-2")
	if err != nil {
		t.Fatalf("GetOrCreate(second) error = %v", err)
	}

	if first.ID != "sbx_000001" {
		t.Fatalf("first.ID = %q", first.ID)
	}
	if second.ID != "sbx_000002" {
		t.Fatalf("second.ID = %q", second.ID)
	}
}

func TestSessionManagerReturnsFactoryError(t *testing.T) {
	wantErr := errors.New("start failed")
	manager := NewSessionManager(&fakeVMFactory{err: wantErr}, 1, 5*time.Minute)

	_, err := manager.GetOrCreate("conversation-1")
	if !errors.Is(err, wantErr) {
		t.Fatalf("error = %v, want %v", err, wantErr)
	}
}

type fakeVM struct {
	mu    sync.Mutex
	id    string
	url   string
	stops int
}

func (v *fakeVM) ID() string {
	return v.id
}

func (v *fakeVM) AgentURL() string {
	if v.url != "" {
		return v.url
	}
	return "http://127.0.0.1:8081"
}

func (v *fakeVM) Stop() error {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.stops++
	return nil
}

func (v *fakeVM) StopCount() int {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.stops
}

type fakeVMFactory struct {
	mu     sync.Mutex
	vms    []*fakeVM
	err    error
	starts int
}

func (f *fakeVMFactory) Start() (VM, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err != nil {
		return nil, f.err
	}
	f.starts++
	if len(f.vms) >= f.starts {
		return f.vms[f.starts-1], nil
	}
	return &fakeVM{id: fmt.Sprintf("vm-%d", f.starts)}, nil
}

func (f *fakeVMFactory) StartCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.starts
}

type blockingFirstFactory struct {
	mu           sync.Mutex
	starts       int
	first        *fakeVM
	firstStarted chan struct{}
	releaseFirst chan struct{}
}

func newBlockingFirstFactory() *blockingFirstFactory {
	return &blockingFirstFactory{
		firstStarted: make(chan struct{}),
		releaseFirst: make(chan struct{}),
	}
}

func (f *blockingFirstFactory) Start() (VM, error) {
	f.mu.Lock()
	f.starts++
	start := f.starts
	vm := &fakeVM{id: fmt.Sprintf("vm-%d", start)}
	if start == 1 {
		f.first = vm
	}
	f.mu.Unlock()

	if start == 1 {
		close(f.firstStarted)
		<-f.releaseFirst
	}
	return vm, nil
}

func (f *blockingFirstFactory) ReleaseFirst() {
	close(f.releaseFirst)
}

func (f *blockingFirstFactory) StartCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.starts
}

func (f *blockingFirstFactory) FirstVM() *fakeVM {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.first
}
