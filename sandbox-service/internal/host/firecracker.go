package host

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

type FirecrackerFactory struct {
	BinaryPath    string
	RunDir        string
	GuestAgentURL string
	VMCpu         int
	VMMemoryMB    int
	VMDiskGB      int
}

func (f FirecrackerFactory) Start() (VM, error) {
	binaryPath := f.BinaryPath
	if binaryPath == "" {
		binaryPath = "firecracker"
	}
	runDir := f.RunDir
	if runDir == "" {
		runDir = filepath.Join(os.TempDir(), "agent-hub-firecracker")
	}

	id := fmt.Sprintf("fc-%d", time.Now().UnixNano())
	vmRunDir := filepath.Join(runDir, id)
	if err := os.MkdirAll(vmRunDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建 Firecracker 运行目录失败: %w", err)
	}

	apiSocket := filepath.Join(vmRunDir, "firecracker.socket")
	cmd := exec.Command(binaryPath, "--api-sock", apiSocket)
	cmd.Dir = vmRunDir
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("启动 Firecracker 进程失败: %w", err)
	}

	agentURL := f.GuestAgentURL
	if agentURL == "" {
		agentURL = "http://127.0.0.1:8081"
	}

	vm := &FirecrackerVM{
		id:       id,
		agentURL: agentURL,
		cmd:      cmd,
		runDir:   vmRunDir,
		waitCh:   make(chan error, 1),
	}
	go vm.wait()
	return vm, nil
}

type FirecrackerVM struct {
	id       string
	agentURL string
	cmd      *exec.Cmd
	runDir   string
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
	v.mu.Lock()
	if v.stopped {
		v.mu.Unlock()
		return nil
	}
	v.stopped = true
	cmd := v.cmd
	v.mu.Unlock()

	if cmd == nil || cmd.Process == nil {
		return nil
	}
	select {
	case err := <-v.waitCh:
		if err != nil && !errors.Is(err, os.ErrProcessDone) {
			return fmt.Errorf("等待 Firecracker 进程退出失败: %w", err)
		}
		return nil
	default:
	}

	if err := cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
		return fmt.Errorf("停止 Firecracker 进程失败: %w", err)
	}
	if err := <-v.waitCh; err != nil && !errors.Is(err, os.ErrProcessDone) && !isExitError(err) {
		return fmt.Errorf("等待 Firecracker 进程退出失败: %w", err)
	}
	return nil
}

func (v *FirecrackerVM) wait() {
	err := v.cmd.Wait()
	v.waitCh <- err
	close(v.waitCh)
	if v.runDir != "" {
		go func() {
			_ = os.RemoveAll(v.runDir)
		}()
	}
}

func isExitError(err error) bool {
	var exitErr *exec.ExitError
	return errors.As(err, &exitErr)
}
