package host

import (
	"errors"
	"sync"
	"testing"
	"time"
)

func TestWarmPoolFillStartsVMsUntilTargetSize(t *testing.T) {
	factory := &fakeVMFactory{}
	pool := NewWarmPool(factory, 3)

	if err := pool.Fill(); err != nil {
		t.Fatalf("Fill() error = %v", err)
	}

	if pool.Size() != 3 {
		t.Fatalf("Size() = %d", pool.Size())
	}
	if factory.StartCount() != 3 {
		t.Fatalf("starts = %d", factory.StartCount())
	}
}

func TestWarmPoolAcquireReusesPooledVMFirst(t *testing.T) {
	factory := &fakeVMFactory{}
	pool := NewWarmPool(factory, 1)
	if err := pool.Fill(); err != nil {
		t.Fatalf("Fill() error = %v", err)
	}

	vm, err := pool.Acquire()
	if err != nil {
		t.Fatalf("Acquire() error = %v", err)
	}

	if vm.ID() != "vm-1" {
		t.Fatalf("vm.ID() = %q", vm.ID())
	}
	if pool.Size() != 0 {
		t.Fatalf("Size() = %d", pool.Size())
	}
	if factory.StartCount() != 1 {
		t.Fatalf("starts = %d", factory.StartCount())
	}
}

func TestWarmPoolAcquireTriggersAsyncRefill(t *testing.T) {
	factory := &fakeVMFactory{}
	pool := NewWarmPool(factory, 1)
	if err := pool.Fill(); err != nil {
		t.Fatalf("Fill() error = %v", err)
	}

	vm, err := pool.Acquire()
	if err != nil {
		t.Fatalf("Acquire() error = %v", err)
	}
	if vm.ID() != "vm-1" {
		t.Fatalf("vm.ID() = %q", vm.ID())
	}

	waitWarmUntil(t, 200*time.Millisecond, func() bool {
		return pool.Size() == 1 && factory.StartCount() == 2
	})
	snapshot := pool.Snapshot()
	if snapshot.HotAcquireCount != 1 {
		t.Fatalf("HotAcquireCount = %d", snapshot.HotAcquireCount)
	}
	if snapshot.ColdStartCount != 0 {
		t.Fatalf("ColdStartCount = %d", snapshot.ColdStartCount)
	}
}

func TestWarmPoolAcquireStartsWhenPoolIsEmpty(t *testing.T) {
	factory := &fakeVMFactory{}
	pool := NewWarmPool(factory, 1)

	vm, err := pool.Acquire()
	if err != nil {
		t.Fatalf("Acquire() error = %v", err)
	}

	if vm.ID() != "vm-1" {
		t.Fatalf("vm.ID() = %q", vm.ID())
	}
	if factory.StartCount() != 1 {
		t.Fatalf("starts = %d", factory.StartCount())
	}
	if pool.Snapshot().ColdStartCount != 1 {
		t.Fatalf("ColdStartCount = %d", pool.Snapshot().ColdStartCount)
	}
}

func TestWarmPoolReleaseKeepsVMWhenNotFull(t *testing.T) {
	pool := NewWarmPool(&fakeVMFactory{}, 1)
	vm := &fakeVM{id: "vm-1"}

	if err := pool.Release(vm); err != nil {
		t.Fatalf("Release() error = %v", err)
	}

	if pool.Size() != 1 {
		t.Fatalf("Size() = %d", pool.Size())
	}
	if vm.StopCount() != 0 {
		t.Fatalf("stops = %d", vm.StopCount())
	}
}

func TestWarmPoolReleaseStopsVMWhenFull(t *testing.T) {
	pool := NewWarmPool(&fakeVMFactory{}, 1)
	first := &fakeVM{id: "vm-1"}
	second := &fakeVM{id: "vm-2"}

	if err := pool.Release(first); err != nil {
		t.Fatalf("Release(first) error = %v", err)
	}
	if err := pool.Release(second); err != nil {
		t.Fatalf("Release(second) error = %v", err)
	}

	if pool.Size() != 1 {
		t.Fatalf("Size() = %d", pool.Size())
	}
	if second.StopCount() != 1 {
		t.Fatalf("stops = %d", second.StopCount())
	}
}

func TestWarmPoolConcurrentFillDoesNotStartMoreThanTarget(t *testing.T) {
	factory := newBlockingWarmFactory()
	pool := NewWarmPool(factory, 2)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := pool.Fill(); err != nil {
				t.Errorf("Fill() error = %v", err)
			}
		}()
	}

	deadline := time.After(100 * time.Millisecond)
	for factory.StartCount() < 2 {
		select {
		case <-factory.started:
		case <-deadline:
			t.Fatalf("starts = %d, want 2", factory.StartCount())
		}
	}
	time.Sleep(20 * time.Millisecond)
	if factory.StartCount() > 2 {
		t.Fatalf("starts = %d, want at most 2", factory.StartCount())
	}

	factory.ReleaseAll()
	wg.Wait()
	if pool.Size() != 2 {
		t.Fatalf("Size() = %d", pool.Size())
	}
}

func TestWarmPoolMaintainerRetriesAfterFailure(t *testing.T) {
	wantErr := errors.New("temporary firecracker failure")
	factory := &flakyWarmFactory{err: wantErr}
	pool := NewWarmPool(factory, 1)
	pool.SetRetryBackoffForTest(func(int) time.Duration {
		return 10 * time.Millisecond
	})
	pool.StartMaintainer()
	defer pool.StopMaintainer()

	waitWarmUntil(t, 200*time.Millisecond, func() bool {
		return pool.Snapshot().LastFillError == wantErr.Error()
	})
	firstSnapshot := pool.Snapshot()
	if firstSnapshot.NextRetryAt == nil {
		t.Fatal("expected NextRetryAt after failed fill")
	}

	factory.SetError(nil)
	waitWarmUntil(t, 500*time.Millisecond, func() bool {
		snapshot := pool.Snapshot()
		return snapshot.Size == 1 && snapshot.LastFillError == "" && snapshot.LastSuccessAt != nil
	})
}

func TestWarmPoolUsesDefaultTargetForNonPositiveSize(t *testing.T) {
	pool := NewWarmPool(&fakeVMFactory{}, 0)

	if pool.TargetSize() != DefaultWarmPoolSize {
		t.Fatalf("TargetSize() = %d", pool.TargetSize())
	}
}

func waitWarmUntil(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("condition was not met before timeout")
}

type blockingWarmFactory struct {
	mu       sync.Mutex
	starts   int
	started  chan struct{}
	released chan struct{}
	once     sync.Once
}

type flakyWarmFactory struct {
	mu     sync.Mutex
	err    error
	starts int
}

func (f *flakyWarmFactory) Start() (VM, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.starts++
	if f.err != nil {
		return nil, f.err
	}
	return &fakeVM{id: "vm-flaky"}, nil
}

func (f *flakyWarmFactory) SetError(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.err = err
}

func newBlockingWarmFactory() *blockingWarmFactory {
	return &blockingWarmFactory{
		started:  make(chan struct{}, 16),
		released: make(chan struct{}),
	}
}

func (f *blockingWarmFactory) Start() (VM, error) {
	f.mu.Lock()
	f.starts++
	start := f.starts
	f.mu.Unlock()

	f.started <- struct{}{}
	<-f.released
	return &fakeVM{id: "vm-blocking-" + string(rune('0'+start))}, nil
}

func (f *blockingWarmFactory) StartCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.starts
}

func (f *blockingWarmFactory) ReleaseAll() {
	f.once.Do(func() {
		close(f.released)
	})
}
