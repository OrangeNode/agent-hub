package host

import (
	"log"
	"sync"
	"time"
)

type IdleReaper struct {
	Manager  *SessionManager
	Interval time.Duration
	StopCh   chan struct{}

	clock     func() time.Time
	done      chan struct{}
	mu        sync.Mutex
	startOnce sync.Once
	stopOnce  sync.Once
}

func NewIdleReaper(manager *SessionManager, interval time.Duration) *IdleReaper {
	if interval <= 0 {
		interval = time.Minute
	}
	return &IdleReaper{
		Manager:  manager,
		Interval: interval,
		StopCh:   make(chan struct{}),
		clock:    time.Now,
	}
}

func DefaultIdleReaperInterval(idleTimeout time.Duration) time.Duration {
	interval := idleTimeout / 2
	if interval > time.Minute {
		interval = time.Minute
	}
	if interval < 10*time.Second {
		interval = 10 * time.Second
	}
	return interval
}

func (r *IdleReaper) Start() {
	if r == nil || r.Manager == nil {
		return
	}
	r.startOnce.Do(func() {
		done := make(chan struct{})
		r.mu.Lock()
		r.done = done
		r.mu.Unlock()
		go r.loop(done)
	})
}

func (r *IdleReaper) Stop() {
	if r == nil {
		return
	}
	r.stopOnce.Do(func() {
		close(r.StopCh)
		r.mu.Lock()
		done := r.done
		r.mu.Unlock()
		if done != nil {
			<-done
		}
	})
}

func (r *IdleReaper) loop(done chan struct{}) {
	defer close(done)
	ticker := time.NewTicker(r.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			result := r.Manager.ReapIdleWithResult(r.now())
			if len(result.ClosedSessions) > 0 || len(result.Errors) > 0 {
				log.Printf("sandbox idle reaper 执行完成: closedSessions=%d stoppedVMs=%d errors=%d",
					len(result.ClosedSessions), len(result.StoppedVMs), len(result.Errors))
			}
		case <-r.StopCh:
			return
		}
	}
}

func (r *IdleReaper) now() time.Time {
	if r.clock != nil {
		return r.clock()
	}
	return time.Now()
}
