package host

import "testing"

func TestWarmPoolFillStartsVMsUntilTargetSize(t *testing.T) {
	factory := &fakeVMFactory{}
	pool := NewWarmPool(factory, 3)

	if err := pool.Fill(); err != nil {
		t.Fatalf("Fill() error = %v", err)
	}

	if pool.Size() != 3 {
		t.Fatalf("Size() = %d", pool.Size())
	}
	if factory.starts != 3 {
		t.Fatalf("starts = %d", factory.starts)
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
	if factory.starts != 1 {
		t.Fatalf("starts = %d", factory.starts)
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
	if factory.starts != 1 {
		t.Fatalf("starts = %d", factory.starts)
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
	if vm.stops != 0 {
		t.Fatalf("stops = %d", vm.stops)
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
	if second.stops != 1 {
		t.Fatalf("stops = %d", second.stops)
	}
}

func TestWarmPoolUsesDefaultTargetForNonPositiveSize(t *testing.T) {
	pool := NewWarmPool(&fakeVMFactory{}, 0)

	if pool.TargetSize() != DefaultWarmPoolSize {
		t.Fatalf("TargetSize() = %d", pool.TargetSize())
	}
}
