package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
	_ "time/tzdata"

	"agent-hub/sandbox-service/internal/auth"
	"agent-hub/sandbox-service/internal/config"
	"agent-hub/sandbox-service/internal/host"
	"agent-hub/sandbox-service/internal/objectstore"
)

func main() {
	if err := configureBeijingTimeZone(); err != nil {
		log.Fatal(err)
	}
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("sandbox-service 配置加载完成: listen=%s vmMode=%s warmPool=%d maxActive=%d",
		cfg.ListenAddr, cfg.VMMode, cfg.WarmPoolSize, cfg.MaxActiveSessions)

	factory, err := newVMFactory(cfg)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("sandbox-service VM 工厂已初始化: mode=%s", cfg.VMMode)
	warmPool := host.NewWarmPool(factory, cfg.WarmPoolSize)
	manager := host.NewSessionManager(warmPoolFactory{pool: warmPool}, cfg.MaxActiveSessions, cfg.IdleTimeout)
	warmPool.StartMaintainer()
	defer warmPool.StopMaintainer()
	reaper := host.NewIdleReaper(manager, host.DefaultIdleReaperInterval(cfg.IdleTimeout))
	reaper.Start()
	defer reaper.Stop()
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
	adminServer := &host.AdminServer{
		Manager:   manager,
		WarmPool:  warmPool,
		StartedAt: time.Now(),
		LogFile:   strings.TrimSpace(os.Getenv("SANDBOX_LOG_FILE")),
		Scanner: host.ResourceScanner{
			RunDir:              cfg.FirecrackerRunDir,
			TapNamePrefix:       cfg.TapNamePrefix,
			StartingGracePeriod: cfg.StartingGracePeriod,
		},
		Config: host.AdminConfig{
			RunDir:              cfg.FirecrackerRunDir,
			TapNamePrefix:       cfg.TapNamePrefix,
			StartingGracePeriod: cfg.StartingGracePeriod,
			MaxLogTailLines:     500,
		},
	}
	handler := auth.RequireToken(cfg.InternalToken)(host.NewRouterWithManager(
		manager,
		host.WithArtifactSyncer(syncer),
		host.WithUploader(store),
		host.WithDownloader(store),
		host.WithAdminServer(adminServer),
	))
	log.Printf("sandbox-service 开始监听: %s", cfg.ListenAddr)
	if err := http.ListenAndServe(cfg.ListenAddr, handler); err != nil {
		log.Fatal(err)
	}
}

func newVMFactory(cfg config.Config) (host.VMFactory, error) {
	switch strings.ToLower(strings.TrimSpace(cfg.VMMode)) {
	case "", "firecracker":
		log.Printf("使用 Firecracker VM: bin=%s kernel=%s rootfs=%s runDir=%s guestCIDR=%s tapPrefix=%s",
			cfg.FirecrackerBinaryPath, cfg.FirecrackerKernelPath, cfg.FirecrackerRootfsPath,
			cfg.FirecrackerRunDir, cfg.GuestNetworkCIDR, cfg.TapNamePrefix)
		return &host.FirecrackerFactory{
			BinaryPath:       cfg.FirecrackerBinaryPath,
			RunDir:           cfg.FirecrackerRunDir,
			KernelImagePath:  cfg.FirecrackerKernelPath,
			RootDrivePath:    cfg.FirecrackerRootfsPath,
			GuestAgentPort:   cfg.GuestAgentPort,
			GuestNetworkCIDR: cfg.GuestNetworkCIDR,
			TapNamePrefix:    cfg.TapNamePrefix,
			VMCpu:            cfg.VMCpu,
			VMMemoryMB:       cfg.VMMemoryMB,
			VMDiskGB:         cfg.VMDiskGB,
		}, nil
	case "dev-agent":
		log.Printf("使用 dev-agent 模式: agentURL=%s", cfg.GuestAgentURL)
		return &staticAgentFactory{agentURL: cfg.GuestAgentURL}, nil
	default:
		return nil, fmt.Errorf("不支持的 SANDBOX_VM_MODE: %s", cfg.VMMode)
	}
}

func defaultObjectStoreRoot() string {
	return strings.TrimSpace(os.Getenv("SANDBOX_OBJECT_LOCAL_DIR"))
}

func configureBeijingTimeZone() error {
	location, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		return err
	}
	time.Local = location
	return nil
}

type warmPoolFactory struct {
	pool *host.WarmPool
}

func (f warmPoolFactory) Start() (host.VM, error) {
	return f.pool.Acquire()
}

type staticAgentFactory struct {
	agentURL string
	nextID   int
}

func (f *staticAgentFactory) Start() (host.VM, error) {
	if strings.TrimSpace(f.agentURL) == "" {
		f.agentURL = "http://127.0.0.1:8081"
	}
	f.nextID++
	return staticAgentVM{id: fmt.Sprintf("dev-agent-%d", f.nextID), agentURL: f.agentURL}, nil
}

type staticAgentVM struct {
	id       string
	agentURL string
}

func (v staticAgentVM) ID() string {
	return v.id
}

func (v staticAgentVM) AgentURL() string {
	return v.agentURL
}

func (v staticAgentVM) Metadata() host.VMMetadata {
	return host.VMMetadata{
		ID:       v.id,
		AgentURL: v.agentURL,
	}
}

func (v staticAgentVM) Stop() error {
	return nil
}
