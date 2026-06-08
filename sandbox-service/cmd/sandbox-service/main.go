package main

import (
	"log"
	"net/http"

	"agent-hub/sandbox-service/internal/auth"
	"agent-hub/sandbox-service/internal/config"
	"agent-hub/sandbox-service/internal/host"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}

	factory := host.FirecrackerFactory{
		VMCpu:      cfg.VMCpu,
		VMMemoryMB: cfg.VMMemoryMB,
		VMDiskGB:   cfg.VMDiskGB,
	}
	warmPool := host.NewWarmPool(factory, cfg.WarmPoolSize)
	manager := host.NewSessionManager(warmPoolFactory{pool: warmPool}, cfg.MaxActiveSessions, cfg.IdleTimeout)
	syncer := host.NewArtifactSyncer(cfg.ObjectBucket)
	handler := auth.RequireToken(cfg.InternalToken)(host.NewRouterWithManager(manager, host.WithArtifactSyncer(syncer)))
	if err := http.ListenAndServe(cfg.ListenAddr, handler); err != nil {
		log.Fatal(err)
	}
}

type warmPoolFactory struct {
	pool *host.WarmPool
}

func (f warmPoolFactory) Start() (host.VM, error) {
	return f.pool.Acquire()
}
