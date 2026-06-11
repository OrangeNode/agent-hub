package host

import (
	"testing"
	"time"
)

func TestIdleReaperStopsIdleSessions(t *testing.T) {
	baseTime := time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC)
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
	now = baseTime.Add(20 * time.Millisecond)

	reaper := NewIdleReaper(manager, time.Millisecond)
	reaper.clock = func() time.Time {
		return now
	}
	reaper.Start()
	defer reaper.Stop()

	waitUntil(t, 200*time.Millisecond, func() bool {
		return vm.StopCount() == 1 && !manager.HasSession(session.ID)
	})
}

func TestIdleReaperSkipsActiveSessions(t *testing.T) {
	baseTime := time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC)
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
	if _, ok := manager.BeginUse(session.ID); !ok {
		t.Fatal("expected BeginUse() to find session")
	}
	now = baseTime.Add(20 * time.Millisecond)

	reaper := NewIdleReaper(manager, time.Millisecond)
	reaper.clock = func() time.Time {
		return now
	}
	reaper.Start()
	time.Sleep(20 * time.Millisecond)
	reaper.Stop()

	if vm.StopCount() != 0 {
		t.Fatalf("StopCount() = %d", vm.StopCount())
	}
	if !manager.HasSession(session.ID) {
		t.Fatal("active session should not be reaped")
	}
}

func TestIdleReaperStopBeforeStartDoesNotBlock(t *testing.T) {
	reaper := NewIdleReaper(NewSessionManager(&fakeVMFactory{}, 1, time.Minute), time.Millisecond)
	stopped := make(chan struct{})
	go func() {
		reaper.Stop()
		close(stopped)
	}()

	select {
	case <-stopped:
	case <-time.After(50 * time.Millisecond):
		t.Fatal("Stop() should not block before Start()")
	}
}

func waitUntil(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("condition was not satisfied before timeout")
}
