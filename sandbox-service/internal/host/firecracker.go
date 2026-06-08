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

	return &FirecrackerVM{
		id:       id,
		agentURL: agentURL,
		cmd:      cmd,
	}, nil
}

type FirecrackerVM struct {
	id       string
	agentURL string
	cmd      *exec.Cmd

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
	if err := cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
		return fmt.Errorf("停止 Firecracker 进程失败: %w", err)
	}
	if err := cmd.Wait(); err != nil && !errors.Is(err, os.ErrProcessDone) && !isExitError(err) {
		return fmt.Errorf("等待 Firecracker 进程退出失败: %w", err)
	}
	return nil
}

func isExitError(err error) bool {
	var exitErr *exec.ExitError
	return errors.As(err, &exitErr)
}
