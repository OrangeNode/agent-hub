package host

import (
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestSessionManagerReusesSessionForSameConversation(t *testing.T) {
	factory := &fakeVMFactory{}
	manager := NewSessionManager(factory, 2, 5*time.Minute)

	first, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	oldLastActiveAt := time.Now().Add(-time.Minute)
	first.LastActiveAt = oldLastActiveAt

	second, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}

	if first != second {
		t.Fatal("expected same session for same conversation")
	}
	if factory.starts != 1 {
		t.Fatalf("starts = %d", factory.starts)
	}
	if !second.LastActiveAt.After(oldLastActiveAt) {
		t.Fatalf("LastActiveAt was not refreshed: %s", second.LastActiveAt)
	}
	if !manager.HasSession("conversation-1") {
		t.Fatal("expected manager to have session")
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
	vm := &fakeVM{id: "vm-1"}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{vm}}, 1, 10*time.Millisecond)

	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	session.LastActiveAt = time.Now().Add(-time.Second)

	if err := manager.ReapIdle(time.Now()); err != nil {
		t.Fatalf("ReapIdle() error = %v", err)
	}

	if manager.HasSession("conversation-1") {
		t.Fatal("expected idle session to be removed")
	}
	if vm.stops != 1 {
		t.Fatalf("stops = %d", vm.stops)
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
	v.stops++
	return nil
}

type fakeVMFactory struct {
	vms    []*fakeVM
	err    error
	starts int
}

func (f *fakeVMFactory) Start() (VM, error) {
	if f.err != nil {
		return nil, f.err
	}
	f.starts++
	if len(f.vms) >= f.starts {
		return f.vms[f.starts-1], nil
	}
	return &fakeVM{id: fmt.Sprintf("vm-%d", f.starts)}, nil
}
