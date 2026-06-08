package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	ListenAddr        string
	InternalToken     string
	WarmPoolSize      int
	MaxActiveSessions int
	IdleTimeout       time.Duration
	VMCpu             int
	VMMemoryMB        int
	VMDiskGB          int
	ExecTimeout       time.Duration
	StdoutMaxBytes    int
	StderrMaxBytes    int
	ObjectEndpoint    string
	ObjectAccessKey   string
	ObjectSecretKey   string
	ObjectBucket      string
	ObjectSecure      bool
	NetworkAllowCIDRs []string
}

func Load() (Config, error) {
	token := strings.TrimSpace(os.Getenv("SANDBOX_INTERNAL_TOKEN"))
	if token == "" {
		return Config{}, errors.New("SANDBOX_INTERNAL_TOKEN 不能为空")
	}

	vmCpu, err := intEnv("SANDBOX_VM_CPU", 1)
	if err != nil {
		return Config{}, err
	}
	vmMemoryMB, err := intEnv("SANDBOX_VM_MEMORY_MB", 1024)
	if err != nil {
		return Config{}, err
	}
	vmDiskGB, err := intEnv("SANDBOX_VM_DISK_GB", 5)
	if err != nil {
		return Config{}, err
	}
	warmPoolSize, err := intEnv("SANDBOX_WARM_POOL_SIZE", 2)
	if err != nil {
		return Config{}, err
	}
	maxActiveSessions, err := intEnv("SANDBOX_MAX_ACTIVE_SESSIONS", 5)
	if err != nil {
		return Config{}, err
	}
	stdoutMaxBytes, err := intEnv("SANDBOX_STDOUT_MAX_BYTES", 1048576)
	if err != nil {
		return Config{}, err
	}
	stderrMaxBytes, err := intEnv("SANDBOX_STDERR_MAX_BYTES", 1048576)
	if err != nil {
		return Config{}, err
	}
	idleTimeout, err := durationEnv("SANDBOX_IDLE_TIMEOUT", 5*time.Minute)
	if err != nil {
		return Config{}, err
	}
	execTimeoutSeconds, err := intEnv("SANDBOX_EXEC_TIMEOUT_SECONDS", 60)
	if err != nil {
		return Config{}, err
	}
	objectSecure, err := boolEnv("SANDBOX_OBJECT_SECURE", false)
	if err != nil {
		return Config{}, err
	}

	return Config{
		ListenAddr:        stringEnv("SANDBOX_LISTEN_ADDR", ":8090"),
		InternalToken:     token,
		WarmPoolSize:      warmPoolSize,
		MaxActiveSessions: maxActiveSessions,
		IdleTimeout:       idleTimeout,
		VMCpu:             vmCpu,
		VMMemoryMB:        vmMemoryMB,
		VMDiskGB:          vmDiskGB,
		ExecTimeout:       time.Duration(execTimeoutSeconds) * time.Second,
		StdoutMaxBytes:    stdoutMaxBytes,
		StderrMaxBytes:    stderrMaxBytes,
		ObjectEndpoint:    stringEnv("SANDBOX_OBJECT_ENDPOINT", "127.0.0.1:9000"),
		ObjectAccessKey:   stringEnv("SANDBOX_OBJECT_ACCESS_KEY", "agenthub"),
		ObjectSecretKey:   stringEnv("SANDBOX_OBJECT_SECRET_KEY", "agenthub123"),
		ObjectBucket:      stringEnv("SANDBOX_OBJECT_BUCKET", "agent-hub-sandbox-artifacts"),
		ObjectSecure:      objectSecure,
		NetworkAllowCIDRs: csvEnv("SANDBOX_NETWORK_ALLOW_CIDRS", []string{"127.0.0.1/32"}),
	}, nil
}

func stringEnv(name, defaultValue string) string {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return defaultValue
	}
	return value
}

func intEnv(name string, defaultValue int) (int, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return defaultValue, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("%s 配置无效: %w", name, err)
	}
	return parsed, nil
}

func boolEnv(name string, defaultValue bool) (bool, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return defaultValue, nil
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return false, fmt.Errorf("%s 配置无效: %w", name, err)
	}
	return parsed, nil
}

func durationEnv(name string, defaultValue time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return defaultValue, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("%s 配置无效: %w", name, err)
	}
	return parsed, nil
}

func csvEnv(name string, defaultValue []string) []string {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return defaultValue
	}

	items := strings.Split(value, ",")
	result := make([]string, 0, len(items))
	for _, item := range items {
		item = strings.TrimSpace(item)
		if item != "" {
			result = append(result, item)
		}
	}
	if len(result) == 0 {
		return defaultValue
	}
	return result
}
