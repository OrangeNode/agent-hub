package main

import "testing"

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
