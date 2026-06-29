package main

import (
	"testing"
	"time"
)

func TestDefaultObjectStoreRootUsesRemoteObjectStoreByDefault(t *testing.T) {
	t.Setenv("SANDBOX_OBJECT_LOCAL_DIR", "")

	if got := defaultObjectStoreRoot(); got != "" {
		t.Fatalf("defaultObjectStoreRoot() = %q, want empty string", got)
	}
}

func TestDefaultObjectStoreRootUsesExplicitLocalDir(t *testing.T) {
	t.Setenv("SANDBOX_OBJECT_LOCAL_DIR", "/tmp/agent-hub-objectstore")

	if got := defaultObjectStoreRoot(); got != "/tmp/agent-hub-objectstore" {
		t.Fatalf("defaultObjectStoreRoot() = %q", got)
	}
}

func TestConfigureBeijingTimeZoneSetsLocalTime(t *testing.T) {
	original := time.Local
	t.Cleanup(func() {
		time.Local = original
	})

	if err := configureBeijingTimeZone(); err != nil {
		t.Fatalf("configureBeijingTimeZone() error = %v", err)
	}

	now := time.Date(2026, 6, 11, 10, 0, 0, 0, time.Local)
	if got := now.Format("-07:00"); got != "+08:00" {
		t.Fatalf("time.Local offset = %s, want +08:00", got)
	}
}
