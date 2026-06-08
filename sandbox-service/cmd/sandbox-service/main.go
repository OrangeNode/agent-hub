package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"

	"agent-hub/sandbox-service/internal/auth"
	"agent-hub/sandbox-service/internal/config"
	"agent-hub/sandbox-service/internal/host"
	"agent-hub/sandbox-service/internal/objectstore"
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
	store, err := objectstore.NewDefaultStore(objectstore.DefaultStoreConfig{
		Endpoint:  cfg.ObjectEndpoint,
		AccessKey: cfg.ObjectAccessKey,
		SecretKey: cfg.ObjectSecretKey,
		Secure:    cfg.ObjectSecure,
		LocalDir:  defaultObjectStoreRoot(),
	})
	if err != nil {
		log.Fatal(err)
	}
	handler := auth.RequireToken(cfg.InternalToken)(host.NewRouterWithManager(
		manager,
		host.WithArtifactSyncer(syncer),
		host.WithUploader(store),
		host.WithDownloader(store),
	))
	if err := http.ListenAndServe(cfg.ListenAddr, handler); err != nil {
		log.Fatal(err)
	}
}

func defaultObjectStoreRoot() string {
	if value := os.Getenv("SANDBOX_OBJECT_LOCAL_DIR"); value != "" {
		return value
	}
	cwd, err := os.Getwd()
	if err != nil {
		return filepath.Join("sandbox-service", "build", "objectstore")
	}
	if filepath.Base(cwd) == "sandbox-service" {
		return filepath.Join(cwd, "build", "objectstore")
	}
	return filepath.Join(cwd, "sandbox-service", "build", "objectstore")
}

type warmPoolFactory struct {
	pool *host.WarmPool
}

func (f warmPoolFactory) Start() (host.VM, error) {
	return f.pool.Acquire()
}
