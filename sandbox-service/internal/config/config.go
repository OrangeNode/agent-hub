package config

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	ListenAddr            string
	InternalToken         string
	VMMode                string
	WarmPoolSize          int
	MaxActiveSessions     int
	IdleTimeout           time.Duration
	VMCpu                 int
	VMMemoryMB            int
	VMDiskGB              int
	FirecrackerBinaryPath string
	FirecrackerKernelPath string
	FirecrackerRootfsPath string
	FirecrackerRunDir     string
	GuestAgentURL         string
	GuestAgentPort        int
	GuestNetworkCIDR      string
	TapNamePrefix         string
	ExecTimeout           time.Duration
	StdoutMaxBytes        int
	StderrMaxBytes        int
	ObjectEndpoint        string
	ObjectAccessKey       string
	ObjectSecretKey       string
	ObjectBucket          string
	ObjectSecure          bool
	NetworkAllowCIDRs     []string
}

func Load() (Config, error) {
	token := strings.TrimSpace(os.Getenv("SANDBOX_INTERNAL_TOKEN"))
	if token == "" {
		return Config{}, errors.New("SANDBOX_INTERNAL_TOKEN 不能为空")
	}

	vmCpu, err := positiveIntEnv("SANDBOX_VM_CPU", 1)
	if err != nil {
		return Config{}, err
	}
	vmMemoryMB, err := positiveIntEnv("SANDBOX_VM_MEMORY_MB", 1024)
	if err != nil {
		return Config{}, err
	}
	vmDiskGB, err := positiveIntEnv("SANDBOX_VM_DISK_GB", 5)
	if err != nil {
		return Config{}, err
	}
	guestAgentPort, err := positiveIntEnv("SANDBOX_GUEST_AGENT_PORT", 8081)
	if err != nil {
		return Config{}, err
	}
	guestNetworkCIDR, err := ipv4CIDREnv("SANDBOX_GUEST_NETWORK_CIDR", "172.16.200.0/24")
	if err != nil {
		return Config{}, err
	}
	warmPoolSize, err := positiveIntEnv("SANDBOX_WARM_POOL_SIZE", 5)
	if err != nil {
		return Config{}, err
	}
	maxActiveSessions, err := positiveIntEnv("SANDBOX_MAX_ACTIVE_SESSIONS", 5)
	if err != nil {
		return Config{}, err
	}
	stdoutMaxBytes, err := positiveIntEnv("SANDBOX_STDOUT_MAX_BYTES", 1048576)
	if err != nil {
		return Config{}, err
	}
	stderrMaxBytes, err := positiveIntEnv("SANDBOX_STDERR_MAX_BYTES", 1048576)
	if err != nil {
		return Config{}, err
	}
	idleTimeout, err := positiveDurationEnv("SANDBOX_IDLE_TIMEOUT", 5*time.Minute)
	if err != nil {
		return Config{}, err
	}
	execTimeoutSeconds, err := positiveIntEnv("SANDBOX_EXEC_TIMEOUT_SECONDS", 60)
	if err != nil {
		return Config{}, err
	}
	objectSecure, err := boolEnv("SANDBOX_OBJECT_SECURE", false)
	if err != nil {
		return Config{}, err
	}
	networkAllowCIDRs, err := cidrListEnv("SANDBOX_NETWORK_ALLOW_CIDRS", []string{"127.0.0.1/32"})
	if err != nil {
		return Config{}, err
	}

	return Config{
		ListenAddr:            stringEnv("SANDBOX_LISTEN_ADDR", ":8090"),
		InternalToken:         token,
		VMMode:                stringEnv("SANDBOX_VM_MODE", "firecracker"),
		WarmPoolSize:          warmPoolSize,
		MaxActiveSessions:     maxActiveSessions,
		IdleTimeout:           idleTimeout,
		VMCpu:                 vmCpu,
		VMMemoryMB:            vmMemoryMB,
		VMDiskGB:              vmDiskGB,
		FirecrackerBinaryPath: stringEnv("SANDBOX_FIRECRACKER_BIN", "firecracker"),
		FirecrackerKernelPath: stringEnv("SANDBOX_KERNEL_IMAGE", ""),
		FirecrackerRootfsPath: stringEnv("SANDBOX_ROOTFS_IMAGE", ""),
		FirecrackerRunDir:     stringEnv("SANDBOX_FIRECRACKER_RUN_DIR", "/var/lib/agent-hub-sandbox/run"),
		GuestAgentURL:         stringEnv("SANDBOX_GUEST_AGENT_URL", "http://127.0.0.1:8081"),
		GuestAgentPort:        guestAgentPort,
		GuestNetworkCIDR:      guestNetworkCIDR,
		TapNamePrefix:         stringEnv("SANDBOX_TAP_NAME_PREFIX", "tap-ah"),
		ExecTimeout:           time.Duration(execTimeoutSeconds) * time.Second,
		StdoutMaxBytes:        stdoutMaxBytes,
		StderrMaxBytes:        stderrMaxBytes,
		ObjectEndpoint:        stringEnv("SANDBOX_OBJECT_ENDPOINT", "127.0.0.1:9000"),
		ObjectAccessKey:       stringEnv("SANDBOX_OBJECT_ACCESS_KEY", "agenthub"),
		ObjectSecretKey:       stringEnv("SANDBOX_OBJECT_SECRET_KEY", "agenthub123"),
		ObjectBucket:          stringEnv("SANDBOX_OBJECT_BUCKET", "agent-hub-sandbox-artifacts"),
		ObjectSecure:          objectSecure,
		NetworkAllowCIDRs:     networkAllowCIDRs,
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

func positiveIntEnv(name string, defaultValue int) (int, error) {
	value, err := intEnv(name, defaultValue)
	if err != nil {
		return 0, err
	}
	if value <= 0 {
		return 0, fmt.Errorf("%s 配置必须大于 0", name)
	}
	return value, nil
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

func positiveDurationEnv(name string, defaultValue time.Duration) (time.Duration, error) {
	value, err := durationEnv(name, defaultValue)
	if err != nil {
		return 0, err
	}
	if value <= 0 {
		return 0, fmt.Errorf("%s 配置必须大于 0", name)
	}
	return value, nil
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

func cidrListEnv(name string, defaultValue []string) ([]string, error) {
	values := csvEnv(name, defaultValue)
	for _, value := range values {
		if _, err := netip.ParsePrefix(value); err != nil {
			return nil, fmt.Errorf("%s 配置无效: %w", name, err)
		}
	}
	return values, nil
}

func ipv4CIDREnv(name string, defaultValue string) (string, error) {
	value := stringEnv(name, defaultValue)
	prefix, err := netip.ParsePrefix(value)
	if err != nil {
		return "", fmt.Errorf("%s 配置无效: %w", name, err)
	}
	if !prefix.Addr().Is4() {
		return "", fmt.Errorf("%s 当前只支持 IPv4 CIDR", name)
	}
	return value, nil
}
