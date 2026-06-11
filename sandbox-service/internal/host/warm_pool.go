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

func (p *WarmPool) StopAll() error {
	p.mu.Lock()
	vms := append([]VM(nil), p.pool...)
	p.pool = nil
	p.mu.Unlock()

	return stopVMs(vms)
}

func (p *WarmPool) Rebuild() error {
	if err := p.StopAll(); err != nil {
		return err
	}
	return p.Fill()
}

func (p *WarmPool) StopByID(vmID string) (VMMetadata, bool, error) {
	p.mu.Lock()
	index := -1
	var vm VM
	for i, candidate := range p.pool {
		if candidate.ID() == vmID {
			index = i
			vm = candidate
			break
		}
	}
	if index >= 0 {
		p.pool = append(p.pool[:index], p.pool[index+1:]...)
	}
	p.mu.Unlock()
	if vm == nil {
		return VMMetadata{}, false, nil
	}

	metadata := metadataForVM(vm)
	return metadata, true, vm.Stop()
}

func (p *WarmPool) Size() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.pool)
}

func (p *WarmPool) TargetSize() int {
	return p.targetSize
}
