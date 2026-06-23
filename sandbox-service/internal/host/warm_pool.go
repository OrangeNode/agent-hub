package host

import (
	"sync"
	"time"
)

const DefaultWarmPoolSize = 5

const defaultWarmPoolRetryDelay = 30 * time.Second

type WarmPool struct {
	factory    VMFactory
	targetSize int

	mu               sync.Mutex
	pool             []VM
	starting         int
	hotAcquireCount  int64
	coldStartCount   int64
	lastFillAt       time.Time
	lastSuccessAt    time.Time
	lastFillError    string
	nextRetryAt      time.Time
	retryBackoff     func(int) time.Duration
	trigger          chan struct{}
	maintainerStop   chan struct{}
	maintainerDone   chan struct{}
	maintainerActive bool
}

func NewWarmPool(factory VMFactory, targetSize int) *WarmPool {
	if targetSize <= 0 {
		targetSize = DefaultWarmPoolSize
	}
	return &WarmPool{
		factory:    factory,
		targetSize: targetSize,
		pool:       make([]VM, 0, targetSize),
		trigger:    make(chan struct{}, 1),
	}
}

func (p *WarmPool) Fill() error {
	p.recordFillStarted()
	err := p.fill()
	if err != nil {
		p.recordFillFailed(err)
		return err
	}
	p.recordFillSucceeded()
	return nil
}

func (p *WarmPool) fill() error {
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
		p.hotAcquireCount++
		p.mu.Unlock()
		p.TriggerFill()
		return vm, nil
	}
	p.coldStartCount++
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

func (p *WarmPool) StartMaintainer() {
	p.mu.Lock()
	if p.maintainerActive {
		p.mu.Unlock()
		p.TriggerFill()
		return
	}
	stop := make(chan struct{})
	done := make(chan struct{})
	p.maintainerStop = stop
	p.maintainerDone = done
	p.maintainerActive = true
	p.mu.Unlock()

	go p.runMaintainer(stop, done)
	p.TriggerFill()
}

func (p *WarmPool) StopMaintainer() {
	p.mu.Lock()
	if !p.maintainerActive {
		p.mu.Unlock()
		return
	}
	stop := p.maintainerStop
	done := p.maintainerDone
	p.maintainerStop = nil
	p.maintainerDone = nil
	p.maintainerActive = false
	close(stop)
	p.mu.Unlock()

	<-done
}

func (p *WarmPool) TriggerFill() {
	p.mu.Lock()
	active := p.maintainerActive
	trigger := p.trigger
	p.mu.Unlock()

	if active {
		select {
		case trigger <- struct{}{}:
		default:
		}
		return
	}
	go func() {
		_ = p.Fill()
	}()
}

func (p *WarmPool) SetRetryBackoffForTest(backoff func(int) time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.retryBackoff = backoff
}

func (p *WarmPool) runMaintainer(stop <-chan struct{}, done chan<- struct{}) {
	defer close(done)
	for {
		select {
		case <-stop:
			return
		case <-p.trigger:
		}

		attempt := 0
		for {
			err := p.Fill()
			if err == nil {
				p.clearNextRetry()
				break
			}
			attempt++
			delay := p.retryDelay(attempt)
			p.scheduleNextRetry(delay)
			timer := time.NewTimer(delay)
			select {
			case <-stop:
				stopTimer(timer)
				return
			case <-p.trigger:
				stopTimer(timer)
				continue
			case <-timer.C:
				continue
			}
		}
	}
}

func (p *WarmPool) retryDelay(attempt int) time.Duration {
	p.mu.Lock()
	backoff := p.retryBackoff
	p.mu.Unlock()
	if backoff == nil {
		return defaultWarmPoolRetryDelay
	}
	delay := backoff(attempt)
	if delay <= 0 {
		return defaultWarmPoolRetryDelay
	}
	return delay
}

func (p *WarmPool) recordFillStarted() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.lastFillAt = time.Now()
}

func (p *WarmPool) recordFillSucceeded() {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now()
	p.lastSuccessAt = now
	p.lastFillError = ""
	p.nextRetryAt = time.Time{}
}

func (p *WarmPool) recordFillFailed(err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if err == nil {
		p.lastFillError = ""
		return
	}
	p.lastFillError = err.Error()
}

func (p *WarmPool) scheduleNextRetry(delay time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.nextRetryAt = time.Now().Add(delay)
}

func (p *WarmPool) clearNextRetry() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.nextRetryAt = time.Time{}
}

func stopTimer(timer *time.Timer) {
	if timer == nil {
		return
	}
	if timer.Stop() {
		return
	}
	select {
	case <-timer.C:
	default:
	}
}
