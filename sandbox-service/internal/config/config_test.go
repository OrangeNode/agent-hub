package config

import (
	"testing"
	"time"
)

func TestLoadUsesDefaults(t *testing.T) {
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.ListenAddr != ":8090" {
		t.Fatalf("ListenAddr = %q", cfg.ListenAddr)
	}
	if cfg.WarmPoolSize != 2 {
		t.Fatalf("WarmPoolSize = %d", cfg.WarmPoolSize)
	}
	if cfg.MaxActiveSessions != 5 {
		t.Fatalf("MaxActiveSessions = %d", cfg.MaxActiveSessions)
	}
	if cfg.IdleTimeout != 5*time.Minute {
		t.Fatalf("IdleTimeout = %s", cfg.IdleTimeout)
	}
	if cfg.InternalToken != "secret" {
		t.Fatalf("InternalToken = %q", cfg.InternalToken)
	}
}

func TestLoadRejectsBlankToken(t *testing.T) {
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "")
	_, err := Load()
	if err == nil {
		t.Fatal("expected error for blank token")
	}
}
