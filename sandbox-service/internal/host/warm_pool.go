package host

import "sync"

const DefaultWarmPoolSize = 5

type WarmPool struct {
	factory    VMFactory
	targetSize int

	mu       sync.Mutex
	pool     []VM
	starting int
}

func NewWarmPool(factory VMFactory, targetSize int) *WarmPool {
	if targetSize <= 0 {
		targetSize = DefaultWarmPoolSize
	}
	return &WarmPool{
		factory:    factory,
		targetSize: targetSize,
		pool:       make([]VM, 0, targetSize),
	}
}

func (p *WarmPool) Fill() error {
	for {
		p.mu.Lock()
		needed := p.targetSize - len(p.pool) - p.starting
		if needed > 0 {
			p.starting++
		}
		p.mu.Unlock()
		if needed <= 0 {
			return nil
		}

		vm, err := p.factory.Start()
		if err != nil {
			p.mu.Lock()
			p.starting--
			p.mu.Unlock()
			return err
		}

		p.mu.Lock()
		p.starting--
		if len(p.pool) < p.targetSize {
			p.pool = append(p.pool, vm)
			p.mu.Unlock()
			continue
		}
		p.mu.Unlock()
		return vm.Stop()
	}
}

func (p *WarmPool) Acquire() (VM, error) {
	p.mu.Lock()
	if len(p.pool) > 0 {
		last := len(p.pool) - 1
		vm := p.pool[last]
		p.pool = p.pool[:last]
		p.mu.Unlock()
		return vm, nil
	}
	p.mu.Unlock()

	return p.factory.Start()
}

func (p *WarmPool) Release(vm VM) error {
	if vm == nil {
		return nil
	}

	p.mu.Lock()
	if len(p.pool) < p.targetSize {
		p.pool = append(p.pool, vm)
		p.mu.Unlock()
		return nil
	}
	p.mu.Unlock()

	return vm.Stop()
}

func (p *WarmPool) Size() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.pool)
}

func (p *WarmPool) TargetSize() int {
	return p.targetSize
}
