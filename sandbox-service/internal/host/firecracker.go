package host

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	defaultFirecrackerBinary = "firecracker"
	defaultFirecrackerRunDir = "/var/lib/agent-hub-sandbox/run"
	defaultGuestAgentPort    = 8081
	defaultGuestNetworkCIDR  = "172.16.200.0/24"
	defaultTapNamePrefix     = "tap-ah"
)

type CommandRunner interface {
	Run(name string, args ...string) error
}

type OSCommandRunner struct{}

func (OSCommandRunner) Run(name string, args ...string) error {
	cmd := exec.Command(name, args...)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("%s %s 执行失败: %w: %s", name, strings.Join(args, " "), err, strings.TrimSpace(string(output)))
	}
	return nil
}

type FirecrackerFactory struct {
	BinaryPath       string
	RunDir           string
	KernelImagePath  string
	RootDrivePath    string
	GuestAgentURL    string
	GuestAgentPort   int
	GuestNetworkCIDR string
	TapNamePrefix    string
	VMCpu            int
	VMMemoryMB       int
	VMDiskGB         int
	KernelArgs       string
	StartTimeout     time.Duration
	CommandRunner    CommandRunner

	mu          sync.Mutex
	nextNetwork int
}

func (f *FirecrackerFactory) Start() (VM, error) {
	if err := f.validate(); err != nil {
		return nil, err
	}

	network, err := f.allocateNetwork()
	if err != nil {
		return nil, err
	}
	runDir := f.runDir()
	id := fmt.Sprintf("fc-%d", time.Now().UnixNano())
	vmRunDir := filepath.Join(runDir, id)
	log.Printf("Firecracker VM 准备启动: id=%s runDir=%s kernel=%s rootfs=%s cpu=%d memoryMB=%d",
		id, vmRunDir, f.KernelImagePath, f.RootDrivePath, f.VMCpu, f.VMMemoryMB)
	if err := os.MkdirAll(vmRunDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建 Firecracker 运行目录失败: %w", err)
	}

	rootfsPath := filepath.Join(vmRunDir, "rootfs.ext4")
	log.Printf("Firecracker VM 复制 rootfs: id=%s target=%s", id, rootfsPath)
	if err := copyFile(f.RootDrivePath, rootfsPath); err != nil {
		_ = os.RemoveAll(vmRunDir)
		return nil, fmt.Errorf("复制 rootfs 镜像失败: %w", err)
	}

	runner := f.runner()
	log.Printf("Firecracker VM 创建网络: id=%s tap=%s hostIP=%s guestIP=%s agentURL=%s",
		id, network.TapName, network.HostIP, network.GuestIP, network.AgentURL)
	if err := setupTap(runner, network); err != nil {
		_ = os.RemoveAll(vmRunDir)
		return nil, err
	}

	apiSocket := filepath.Join(vmRunDir, "firecracker.socket")
	cmd := exec.Command(f.binaryPath(), "--api-sock", apiSocket)
	cmd.Dir = vmRunDir
	cmd.Stdout = fileOrDiscard(filepath.Join(vmRunDir, "firecracker.stdout.log"))
	cmd.Stderr = fileOrDiscard(filepath.Join(vmRunDir, "firecracker.stderr.log"))
	if err := cmd.Start(); err != nil {
		_ = cleanupTap(runner, network.TapName)
		_ = os.RemoveAll(vmRunDir)
		return nil, fmt.Errorf("启动 Firecracker 进程失败: %w", err)
	}
	log.Printf("Firecracker 进程已启动: id=%s pid=%d apiSocket=%s", id, cmd.Process.Pid, apiSocket)

	vm := &FirecrackerVM{
		id:       id,
		agentURL: network.AgentURL,
		cmd:      cmd,
		runDir:   vmRunDir,
		tapName:  network.TapName,
		runner:   runner,
		waitCh:   make(chan error, 1),
	}
	go vm.wait()

	if err := waitForSocket(apiSocket, cmd, 5*time.Second); err != nil {
		_ = vm.Stop()
		return nil, err
	}
	log.Printf("Firecracker API socket 已就绪: id=%s", id)
	if err := f.configureAndStart(apiSocket, vmRunDir, rootfsPath, network); err != nil {
		_ = vm.Stop()
		return nil, err
	}
	log.Printf("Firecracker InstanceStart 已发送: id=%s", id)
	if err := waitForAgent(network.AgentURL, f.startTimeout()); err != nil {
		_ = vm.Stop()
		return nil, err
	}
	log.Printf("sandbox-agent 已就绪: id=%s agentURL=%s", id, network.AgentURL)
	return vm, nil
}

func (f *FirecrackerFactory) validate() error {
	if strings.TrimSpace(f.KernelImagePath) == "" {
		return errors.New("SANDBOX_KERNEL_IMAGE 不能为空")
	}
	if strings.TrimSpace(f.RootDrivePath) == "" {
		return errors.New("SANDBOX_ROOTFS_IMAGE 不能为空")
	}
	if abs, err := filepath.Abs(f.KernelImagePath); err == nil {
		f.KernelImagePath = abs
	}
	if abs, err := filepath.Abs(f.RootDrivePath); err == nil {
		f.RootDrivePath = abs
	}
	if _, err := os.Stat(f.KernelImagePath); err != nil {
		return fmt.Errorf("kernel 镜像不可用: %w", err)
	}
	if _, err := os.Stat(f.RootDrivePath); err != nil {
		return fmt.Errorf("rootfs 镜像不可用: %w", err)
	}
	if f.VMCpu <= 0 {
		return errors.New("SANDBOX_VM_CPU 必须大于 0")
	}
	if f.VMMemoryMB <= 0 {
		return errors.New("SANDBOX_VM_MEMORY_MB 必须大于 0")
	}
	if _, err := netip.ParsePrefix(f.guestNetworkCIDR()); err != nil {
		return fmt.Errorf("SANDBOX_GUEST_NETWORK_CIDR 配置无效: %w", err)
	}
	if !isValidTapName(f.tapNamePrefix() + "000001") {
		return errors.New("SANDBOX_TAP_NAME_PREFIX 只能包含字母、数字、下划线和短横线")
	}
	return nil
}

func (f *FirecrackerFactory) configureAndStart(apiSocket string, vmRunDir string, rootfsPath string, network vmNetwork) error {
	api := newFirecrackerAPI(apiSocket)
	if err := api.putJSON("/logger", map[string]any{
		"log_path":        filepath.Join(vmRunDir, "firecracker.log"),
		"level":           "Info",
		"show_level":      true,
		"show_log_origin": true,
	}); err != nil {
		return err
	}
	if err := api.putJSON("/machine-config", map[string]any{
		"vcpu_count":   f.VMCpu,
		"mem_size_mib": f.VMMemoryMB,
		"smt":          false,
	}); err != nil {
		return err
	}
	if err := api.putJSON("/boot-source", map[string]any{
		"kernel_image_path": f.KernelImagePath,
		"boot_args":         f.bootArgs(network),
	}); err != nil {
		return err
	}
	if err := api.putJSON("/drives/rootfs", map[string]any{
		"drive_id":       "rootfs",
		"path_on_host":   rootfsPath,
		"is_root_device": true,
		"is_read_only":   false,
	}); err != nil {
		return err
	}
	if err := api.putJSON("/network-interfaces/eth0", map[string]any{
		"iface_id":      "eth0",
		"guest_mac":     network.GuestMAC,
		"host_dev_name": network.TapName,
	}); err != nil {
		return err
	}
	return api.putJSON("/actions", map[string]any{"action_type": "InstanceStart"})
}

func (f *FirecrackerFactory) allocateNetwork() (vmNetwork, error) {
	prefix, err := netip.ParsePrefix(f.guestNetworkCIDR())
	if err != nil {
		return vmNetwork{}, err
	}
	if !prefix.Addr().Is4() {
		return vmNetwork{}, errors.New("guest 网络当前只支持 IPv4")
	}
	if prefix.Bits() > 30 {
		return vmNetwork{}, errors.New("guest 网络 CIDR 至少需要容纳一个 /30 子网")
	}

	f.mu.Lock()
	index := f.nextNetwork
	f.nextNetwork++
	f.mu.Unlock()

	total := uint32(1) << uint32(32-prefix.Bits())
	offset := uint32(index * 4)
	if offset+3 >= total {
		return vmNetwork{}, errors.New("guest 网络地址池已耗尽")
	}
	base := addIPv4(prefix.Masked().Addr(), offset)
	hostIP := addIPv4(base, 1)
	guestIP := addIPv4(base, 2)
	tapName := buildTapName(f.tapNamePrefix(), index+1)
	if !isValidTapName(tapName) {
		return vmNetwork{}, errors.New("生成的 tap 名称非法")
	}
	port := f.GuestAgentPort
	if port <= 0 {
		port = defaultGuestAgentPort
	}
	return vmNetwork{
		TapName:  tapName,
		HostIP:   hostIP,
		GuestIP:  guestIP,
		GuestMAC: buildGuestMAC(index + 1),
		AgentURL: fmt.Sprintf("http://%s:%d", guestIP, port),
	}, nil
}

func (f *FirecrackerFactory) bootArgs(network vmNetwork) string {
	if strings.TrimSpace(f.KernelArgs) != "" {
		return strings.TrimSpace(f.KernelArgs)
	}
	return fmt.Sprintf("console=ttyS0 reboot=k panic=1 pci=off root=/dev/vda rw ip=%s::%s:255.255.255.252::eth0:off",
		network.GuestIP, network.HostIP)
}

func (f *FirecrackerFactory) binaryPath() string {
	if strings.TrimSpace(f.BinaryPath) == "" {
		return defaultFirecrackerBinary
	}
	return strings.TrimSpace(f.BinaryPath)
}

func (f *FirecrackerFactory) runDir() string {
	if strings.TrimSpace(f.RunDir) == "" {
		return defaultFirecrackerRunDir
	}
	return strings.TrimSpace(f.RunDir)
}

func (f *FirecrackerFactory) guestNetworkCIDR() string {
	if strings.TrimSpace(f.GuestNetworkCIDR) == "" {
		return defaultGuestNetworkCIDR
	}
	return strings.TrimSpace(f.GuestNetworkCIDR)
}

func (f *FirecrackerFactory) tapNamePrefix() string {
	if strings.TrimSpace(f.TapNamePrefix) == "" {
		return defaultTapNamePrefix
	}
	return strings.TrimSpace(f.TapNamePrefix)
}

func (f *FirecrackerFactory) startTimeout() time.Duration {
	if f.StartTimeout <= 0 {
		return 60 * time.Second
	}
	return f.StartTimeout
}

func (f *FirecrackerFactory) runner() CommandRunner {
	if f.CommandRunner != nil {
		return f.CommandRunner
	}
	return OSCommandRunner{}
}

type vmNetwork struct {
	TapName  string
	HostIP   netip.Addr
	GuestIP  netip.Addr
	GuestMAC string
	AgentURL string
}

func setupTap(runner CommandRunner, network vmNetwork) error {
	if err := runner.Run("ip", "tuntap", "add", "dev", network.TapName, "mode", "tap"); err != nil {
		return fmt.Errorf("创建 tap 设备失败: %w", err)
	}
	if err := runner.Run("ip", "addr", "add", network.HostIP.String()+"/30", "dev", network.TapName); err != nil {
		_ = cleanupTap(runner, network.TapName)
		return fmt.Errorf("配置 tap 地址失败: %w", err)
	}
	if err := runner.Run("ip", "link", "set", network.TapName, "up"); err != nil {
		_ = cleanupTap(runner, network.TapName)
		return fmt.Errorf("启用 tap 设备失败: %w", err)
	}
	return nil
}

func cleanupTap(runner CommandRunner, tapName string) error {
	if strings.TrimSpace(tapName) == "" {
		return nil
	}
	return runner.Run("ip", "link", "delete", tapName)
}

type FirecrackerVM struct {
	id       string
	agentURL string
	cmd      *exec.Cmd
	runDir   string
	tapName  string
	runner   CommandRunner
	waitCh   chan error

	mu      sync.Mutex
	stopped bool
}

func (v *FirecrackerVM) ID() string {
	return v.id
}

func (v *FirecrackerVM) AgentURL() string {
	return v.agentURL
}

func (v *FirecrackerVM) Stop() error {
	log.Printf("Firecracker VM 准备停止: id=%s tap=%s runDir=%s", v.id, v.tapName, v.runDir)
	v.mu.Lock()
	if v.stopped {
		v.mu.Unlock()
		return nil
	}
	v.stopped = true
	cmd := v.cmd
	v.mu.Unlock()

	var errs []error
	if cmd != nil && cmd.Process != nil {
		select {
		case err := <-v.waitCh:
			if err != nil && !errors.Is(err, os.ErrProcessDone) && !isExitError(err) {
				errs = append(errs, fmt.Errorf("等待 Firecracker 进程退出失败: %w", err))
			}
		default:
			if err := cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
				errs = append(errs, fmt.Errorf("停止 Firecracker 进程失败: %w", err))
			}
			if err := <-v.waitCh; err != nil && !errors.Is(err, os.ErrProcessDone) && !isExitError(err) {
				errs = append(errs, fmt.Errorf("等待 Firecracker 进程退出失败: %w", err))
			}
		}
	}
	if err := cleanupTap(v.runner, v.tapName); err != nil {
		errs = append(errs, err)
	}
	if v.runDir != "" {
		if err := os.RemoveAll(v.runDir); err != nil {
			errs = append(errs, err)
		}
	}
	if len(errs) == 0 {
		log.Printf("Firecracker VM 已停止: id=%s", v.id)
	}
	return errors.Join(errs...)
}

func (v *FirecrackerVM) wait() {
	err := v.cmd.Wait()
	v.waitCh <- err
	close(v.waitCh)
}

type firecrackerAPI struct {
	client *http.Client
}

func newFirecrackerAPI(socketPath string) firecrackerAPI {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network string, address string) (net.Conn, error) {
			var dialer net.Dialer
			return dialer.DialContext(ctx, "unix", socketPath)
		},
	}
	return firecrackerAPI{client: &http.Client{Transport: transport, Timeout: 10 * time.Second}}
}

func (a firecrackerAPI) putJSON(path string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPut, "http://unix"+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := a.client.Do(req)
	if err != nil {
		return fmt.Errorf("调用 Firecracker API %s 失败: %w", path, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("Firecracker API %s 返回 %d: %s", path, resp.StatusCode, strings.TrimSpace(string(data)))
	}
	return nil
}

func waitForSocket(socketPath string, cmd *exec.Cmd, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		if _, err := os.Stat(socketPath); err == nil {
			return nil
		}
		if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
			return errors.New("Firecracker 进程在 API socket 就绪前退出")
		}
		if time.Now().After(deadline) {
			return errors.New("等待 Firecracker API socket 超时")
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func waitForAgent(agentURL string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	client := &http.Client{Timeout: time.Second}
	for {
		resp, err := client.Get(strings.TrimRight(agentURL, "/") + "/health")
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return nil
			}
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("等待 sandbox-agent 就绪超时: %s", agentURL)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func fileOrDiscard(path string) io.Writer {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return io.Discard
	}
	return file
}

func copyFile(source string, target string) error {
	if err := cloneOrSparseCopy(source, target); err == nil {
		return nil
	}

	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(output, input); err != nil {
		_ = output.Close()
		return err
	}
	return output.Close()
}

func cloneOrSparseCopy(source string, target string) error {
	cmd := exec.Command("cp", "--reflink=auto", "--sparse=always", source, target)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("cp sparse copy failed: %w: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func addIPv4(addr netip.Addr, offset uint32) netip.Addr {
	raw := addr.As4()
	value := uint32(raw[0])<<24 | uint32(raw[1])<<16 | uint32(raw[2])<<8 | uint32(raw[3])
	value += offset
	return netip.AddrFrom4([4]byte{
		byte(value >> 24),
		byte(value >> 16),
		byte(value >> 8),
		byte(value),
	})
}

func buildTapName(prefix string, index int) string {
	if len(prefix) > 9 {
		prefix = prefix[:9]
	}
	return fmt.Sprintf("%s%06d", prefix, index%1000000)
}

func buildGuestMAC(index int) string {
	return fmt.Sprintf("AA:FC:%02X:%02X:%02X:%02X",
		byte(index>>24), byte(index>>16), byte(index>>8), byte(index))
}

func isExitError(err error) bool {
	var exitErr *exec.ExitError
	return errors.As(err, &exitErr)
}
