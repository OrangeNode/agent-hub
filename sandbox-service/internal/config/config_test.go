package config

import (
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestLoadUsesDefaults(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.ListenAddr != ":8090" {
		t.Fatalf("ListenAddr = %q", cfg.ListenAddr)
	}
	if cfg.WarmPoolSize != 5 {
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
	if cfg.VMCpu != 1 {
		t.Fatalf("VMCpu = %d", cfg.VMCpu)
	}
	if cfg.VMMemoryMB != 1024 {
		t.Fatalf("VMMemoryMB = %d", cfg.VMMemoryMB)
	}
	if cfg.VMDiskGB != 5 {
		t.Fatalf("VMDiskGB = %d", cfg.VMDiskGB)
	}
	if cfg.ExecTimeout != 60*time.Second {
		t.Fatalf("ExecTimeout = %s", cfg.ExecTimeout)
	}
	if cfg.StdoutMaxBytes != 1048576 {
		t.Fatalf("StdoutMaxBytes = %d", cfg.StdoutMaxBytes)
	}
	if cfg.StderrMaxBytes != 1048576 {
		t.Fatalf("StderrMaxBytes = %d", cfg.StderrMaxBytes)
	}
	if cfg.ObjectEndpoint != "127.0.0.1:9000" {
		t.Fatalf("ObjectEndpoint = %q", cfg.ObjectEndpoint)
	}
	if cfg.ObjectAccessKey != "agenthub" {
		t.Fatalf("ObjectAccessKey = %q", cfg.ObjectAccessKey)
	}
	if cfg.ObjectSecretKey != "agenthub123" {
		t.Fatalf("ObjectSecretKey = %q", cfg.ObjectSecretKey)
	}
	if cfg.ObjectBucket != "agent-hub-sandbox-artifacts" {
		t.Fatalf("ObjectBucket = %q", cfg.ObjectBucket)
	}
	if cfg.ObjectSecure {
		t.Fatal("ObjectSecure = true")
	}
	if !reflect.DeepEqual(cfg.NetworkAllowCIDRs, []string{"127.0.0.1/32"}) {
		t.Fatalf("NetworkAllowCIDRs = %#v", cfg.NetworkAllowCIDRs)
	}
}

func TestLoadRejectsBlankToken(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "")
	_, err := Load()
	if err == nil {
		t.Fatal("expected error for blank token")
	}
	if err.Error() != "SANDBOX_INTERNAL_TOKEN 不能为空" {
		t.Fatalf("error = %q", err.Error())
	}
}

func TestLoadUsesOverrides(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
	t.Setenv("SANDBOX_OBJECT_SECURE", "true")
	t.Setenv("SANDBOX_NETWORK_ALLOW_CIDRS", "10.0.0.0/8, 192.168.0.0/16")
	t.Setenv("SANDBOX_IDLE_TIMEOUT", "30s")
	t.Setenv("SANDBOX_EXEC_TIMEOUT_SECONDS", "15")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}

	if !cfg.ObjectSecure {
		t.Fatal("ObjectSecure = false")
	}
	if !reflect.DeepEqual(cfg.NetworkAllowCIDRs, []string{"10.0.0.0/8", "192.168.0.0/16"}) {
		t.Fatalf("NetworkAllowCIDRs = %#v", cfg.NetworkAllowCIDRs)
	}
	if cfg.IdleTimeout != 30*time.Second {
		t.Fatalf("IdleTimeout = %s", cfg.IdleTimeout)
	}
	if cfg.ExecTimeout != 15*time.Second {
		t.Fatalf("ExecTimeout = %s", cfg.ExecTimeout)
	}
}

func TestLoadAcceptsValidCsvCIDRs(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
	t.Setenv("SANDBOX_NETWORK_ALLOW_CIDRS", "10.0.0.0/8, ::1/128")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}

	if !reflect.DeepEqual(cfg.NetworkAllowCIDRs, []string{"10.0.0.0/8", "::1/128"}) {
		t.Fatalf("NetworkAllowCIDRs = %#v", cfg.NetworkAllowCIDRs)
	}
}

func TestLoadRejectsInvalidIntWithVariableName(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
	t.Setenv("SANDBOX_VM_CPU", "invalid")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error for invalid int")
	}
	if !strings.Contains(err.Error(), "SANDBOX_VM_CPU 配置无效:") {
		t.Fatalf("error = %q", err.Error())
	}
}

func TestLoadRejectsNonPositiveIntsWithVariableName(t *testing.T) {
	cases := []struct {
		name  string
		value string
	}{
		{name: "SANDBOX_VM_CPU", value: "0"},
		{name: "SANDBOX_VM_MEMORY_MB", value: "-1"},
		{name: "SANDBOX_VM_DISK_GB", value: "0"},
		{name: "SANDBOX_WARM_POOL_SIZE", value: "-1"},
		{name: "SANDBOX_MAX_ACTIVE_SESSIONS", value: "0"},
		{name: "SANDBOX_STDOUT_MAX_BYTES", value: "-1"},
		{name: "SANDBOX_STDERR_MAX_BYTES", value: "0"},
		{name: "SANDBOX_EXEC_TIMEOUT_SECONDS", value: "-1"},
	}

	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			clearConfigEnv(t)
			t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
			t.Setenv(tt.name, tt.value)

			_, err := Load()
			if err == nil {
				t.Fatal("expected error for non-positive int")
			}
			expected := tt.name + " 配置必须大于 0"
			if err.Error() != expected {
				t.Fatalf("error = %q, want %q", err.Error(), expected)
			}
		})
	}
}

func TestLoadRejectsNonPositiveIdleTimeoutWithVariableName(t *testing.T) {
	cases := []string{"0s", "-1s"}

	for _, value := range cases {
		t.Run(value, func(t *testing.T) {
			clearConfigEnv(t)
			t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
			t.Setenv("SANDBOX_IDLE_TIMEOUT", value)

			_, err := Load()
			if err == nil {
				t.Fatal("expected error for non-positive duration")
			}
			if err.Error() != "SANDBOX_IDLE_TIMEOUT 配置必须大于 0" {
				t.Fatalf("error = %q", err.Error())
			}
		})
	}
}

func TestLoadRejectsMalformedCIDRWithVariableName(t *testing.T) {
	clearConfigEnv(t)
	t.Setenv("SANDBOX_INTERNAL_TOKEN", "secret")
	t.Setenv("SANDBOX_NETWORK_ALLOW_CIDRS", "127.0.0.1/32,invalid")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error for malformed CIDR")
	}
	if !strings.Contains(err.Error(), "SANDBOX_NETWORK_ALLOW_CIDRS 配置无效:") {
		t.Fatalf("error = %q", err.Error())
	}
}

func clearConfigEnv(t *testing.T) {
	t.Helper()

	names := []string{
		"SANDBOX_LISTEN_ADDR",
		"SANDBOX_WARM_POOL_SIZE",
		"SANDBOX_MAX_ACTIVE_SESSIONS",
		"SANDBOX_IDLE_TIMEOUT",
		"SANDBOX_VM_CPU",
		"SANDBOX_VM_MEMORY_MB",
		"SANDBOX_VM_DISK_GB",
		"SANDBOX_EXEC_TIMEOUT_SECONDS",
		"SANDBOX_STDOUT_MAX_BYTES",
		"SANDBOX_STDERR_MAX_BYTES",
		"SANDBOX_OBJECT_ENDPOINT",
		"SANDBOX_OBJECT_ACCESS_KEY",
		"SANDBOX_OBJECT_SECRET_KEY",
		"SANDBOX_OBJECT_BUCKET",
		"SANDBOX_OBJECT_SECURE",
		"SANDBOX_NETWORK_ALLOW_CIDRS",
		"SANDBOX_INTERNAL_TOKEN",
	}
	for _, name := range names {
		t.Setenv(name, "")
	}
}
