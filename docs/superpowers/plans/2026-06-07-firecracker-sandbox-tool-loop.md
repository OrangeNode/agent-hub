# Firecracker Sandbox Tool Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 Firecracker microVM 沙箱服务与 Agent Runtime Tool Loop 的端到端工具执行闭环。

**Architecture:** 新增独立 Go `sandbox-service` 管理 Firecracker microVM、session 池、guest-agent、文件工具和产物同步。后端新增 `agent-hub-sandbox` 模块保存业务态、调用 sandbox-service、执行工具，并扩展 `agent-hub-ai` 与 `agent-hub-chat` 完成 OpenAI Compatible / Anthropic Tool Calling、聊天 SSE 工具事件和最终回答生成。

**Tech Stack:** Go 1.22+、Firecracker、Spring Boot 4、JDK 21、MyBatis-Plus、Flyway、MinIO、Vue 3、TypeScript、SSE。

---

## Scope Check

本计划覆盖两个强依赖子系统：独立 `sandbox-service` 和 Agent Hub Tool Loop。用户要求“沙箱 + Tool Loop 一起交付”，因此不拆分成多个计划；计划按可独立验证的任务串联，每个任务完成后都可以运行对应测试或 smoke test。

## File Structure

新增 Go 服务：

- `sandbox-service/go.mod`：Go 模块定义。
- `sandbox-service/cmd/sandbox-service/main.go`：宿主侧 sandbox-service 入口。
- `sandbox-service/cmd/sandbox-agent/main.go`：microVM 内 guest-agent 入口。
- `sandbox-service/internal/config/config.go`：环境变量配置解析。
- `sandbox-service/internal/auth/token.go`：内部 token 鉴权中间件。
- `sandbox-service/internal/policy/command_policy.go`：简单命令安全校验。
- `sandbox-service/internal/workspace/path_policy.go`：工作区路径校验。
- `sandbox-service/internal/agent/server.go`：guest-agent HTTP API。
- `sandbox-service/internal/agent/executor.go`：guest-agent 非 root 命令执行器。
- `sandbox-service/internal/agent/files.go`：guest-agent 文件读写和扫描。
- `sandbox-service/internal/host/firecracker.go`：Firecracker 进程和 socket 管理。
- `sandbox-service/internal/host/network.go`：microVM tap 网络和白名单规则生成。
- `sandbox-service/internal/host/session_manager.go`：session 生命周期。
- `sandbox-service/internal/host/warm_pool.go`：warm pool。
- `sandbox-service/internal/host/artifact_sync.go`：产物扫描和同步。
- `sandbox-service/internal/objectstore/objectstore.go`：对象存储上传和下载接口。
- `sandbox-service/internal/objectstore/minio.go`：MinIO/S3 兼容实现。
- `sandbox-service/internal/host/api.go`：sandbox-service 内部 HTTP/SSE API。
- `sandbox-service/internal/sse/sse.go`：SSE 写入工具。
- `sandbox-service/scripts/build-minimal-rootfs.sh`：minimal rootfs 构建脚本。
- `sandbox-service/scripts/run-dev-firecracker.sh`：服务器集成验证脚本。

后端新增/修改：

- `backend/pom.xml`：新增 `agent-hub-sandbox` 模块。
- `backend/agent-hub-sandbox/pom.xml`：sandbox 后端模块。
- `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/**`：sandbox client、service、entity、mapper、tool。
- `backend/agent-hub-server/src/main/resources/db/migration/V7__init_sandbox_tool_loop.sql`：数据库迁移。
- `backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxMigrationSmokeTest.java`：迁移 smoke test。
- `backend/agent-hub-common/src/main/java/com/agenthub/common/api/ErrorCode.java`：新增带中文说明的错误码。
- `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/**`：扩展通用附件导入支持。
- `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/**`：Tool Calling 抽象与协议适配。
- `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`：接入 Tool Loop。
- `backend/agent-hub-sse/src/main/java/com/agenthub/sse/**`：新增工具 SSE 事件。

前端新增/修改：

- `user-web/src/api/chat.ts`：解析工具 SSE 事件。
- `user-web/src/views/ChatView.vue`：展示工具日志和产物。
- `user-web/src/views/chat-tool-events.helpers.ts`：工具事件状态整理。
- `user-web/tests/chat-tool-events.spec.ts`：工具事件前端测试。

---

### Task 1: Go sandbox-service 骨架、配置和鉴权

**Files:**
- Create: `sandbox-service/go.mod`
- Create: `sandbox-service/cmd/sandbox-service/main.go`
- Create: `sandbox-service/internal/config/config.go`
- Create: `sandbox-service/internal/config/config_test.go`
- Create: `sandbox-service/internal/auth/token.go`
- Create: `sandbox-service/internal/auth/token_test.go`
- Create: `sandbox-service/internal/host/api.go`
- Create: `sandbox-service/internal/host/api_test.go`

- [ ] **Step 1: Write failing config tests**

Create `sandbox-service/internal/config/config_test.go`:

```go
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
```

- [ ] **Step 2: Write failing token middleware tests**

Create `sandbox-service/internal/auth/token_test.go`:

```go
package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRequireTokenAllowsMatchingBearerToken(t *testing.T) {
	called := false
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusNoContent)
	}))
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Authorization", "Bearer secret")
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, req)

	if res.Code != http.StatusNoContent || !called {
		t.Fatalf("expected handler to run, code=%d called=%v", res.Code, called)
	}
}

func TestRequireTokenRejectsMissingToken(t *testing.T) {
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("handler should not run")
	}))
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/health", nil))

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("code = %d", res.Code)
	}
}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```powershell
cd sandbox-service
go test ./internal/config ./internal/auth
```

Expected: FAIL，因为 Go 模块和对应函数尚不存在。

- [ ] **Step 4: Implement config and auth**

Create `sandbox-service/go.mod`:

```go
module agent-hub/sandbox-service

go 1.22
```

Create `sandbox-service/internal/config/config.go`:

```go
package config

import (
	"errors"
	"os"
	"strconv"
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
	cfg := Config{
		ListenAddr:        getenv("SANDBOX_LISTEN_ADDR", ":8090"),
		InternalToken:     os.Getenv("SANDBOX_INTERNAL_TOKEN"),
		WarmPoolSize:      getint("SANDBOX_WARM_POOL_SIZE", 2),
		MaxActiveSessions: getint("SANDBOX_MAX_ACTIVE_SESSIONS", 5),
		IdleTimeout:       getduration("SANDBOX_IDLE_TIMEOUT", 5*time.Minute),
		VMCpu:             getint("SANDBOX_VM_CPU", 1),
		VMMemoryMB:        getint("SANDBOX_VM_MEMORY_MB", 1024),
		VMDiskGB:          getint("SANDBOX_VM_DISK_GB", 5),
		ExecTimeout:       time.Duration(getint("SANDBOX_EXEC_TIMEOUT_SECONDS", 60)) * time.Second,
		StdoutMaxBytes:    getint("SANDBOX_STDOUT_MAX_BYTES", 1048576),
		StderrMaxBytes:    getint("SANDBOX_STDERR_MAX_BYTES", 1048576),
		ObjectEndpoint:    getenv("SANDBOX_OBJECT_ENDPOINT", "127.0.0.1:9000"),
		ObjectAccessKey:   getenv("SANDBOX_OBJECT_ACCESS_KEY", "agenthub"),
		ObjectSecretKey:   getenv("SANDBOX_OBJECT_SECRET_KEY", "agenthub123"),
		ObjectBucket:      getenv("SANDBOX_OBJECT_BUCKET", "agent-hub-sandbox-artifacts"),
		ObjectSecure:      getenv("SANDBOX_OBJECT_SECURE", "false") == "true",
		NetworkAllowCIDRs: splitCSV(getenv("SANDBOX_NETWORK_ALLOW_CIDRS", "127.0.0.1/32")),
	}
	if cfg.InternalToken == "" {
		return Config{}, errors.New("SANDBOX_INTERNAL_TOKEN 不能为空")
	}
	return cfg, nil
}

func getenv(key string, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}

func getint(key string, fallback int) int {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func getduration(key string, fallback time.Duration) time.Duration {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func splitCSV(value string) []string {
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}
```

Then add imports:

```go
import "strings"
```

Create `sandbox-service/internal/auth/token.go`:

```go
package auth

import (
	"net/http"
)

func RequireToken(token string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Authorization") != "Bearer "+token {
				http.Error(w, "未授权的内部服务请求", http.StatusUnauthorized)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
```

Create `sandbox-service/internal/host/api.go`:

```go
package host

import (
	"net/http"
)

func NewRouter() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	return mux
}
```

Create `sandbox-service/cmd/sandbox-service/main.go`:

```go
package main

import (
	"log"
	"net/http"

	"agent-hub/sandbox-service/internal/auth"
	"agent-hub/sandbox-service/internal/config"
	"agent-hub/sandbox-service/internal/host"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	handler := auth.RequireToken(cfg.InternalToken)(host.NewRouter())
	log.Printf("sandbox-service listening on %s", cfg.ListenAddr)
	log.Fatal(http.ListenAndServe(cfg.ListenAddr, handler))
}
```

- [ ] **Step 5: Run tests**

Run:

```powershell
cd sandbox-service
go test ./...
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add sandbox-service
git commit -m "新增沙箱服务基础骨架"
```

---

### Task 2: Go 命令与路径安全策略

**Files:**
- Create: `sandbox-service/internal/policy/command_policy.go`
- Create: `sandbox-service/internal/policy/command_policy_test.go`
- Create: `sandbox-service/internal/workspace/path_policy.go`
- Create: `sandbox-service/internal/workspace/path_policy_test.go`

- [ ] **Step 1: Write failing command policy tests**

Create `sandbox-service/internal/policy/command_policy_test.go`:

```go
package policy

import "testing"

func TestValidateCommandAllowsCommonCommands(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	req := CommandRequest{Command: "python3 main.py", WorkingDir: "/workspace", TimeoutSeconds: 30}
	if err := policy.Validate(req); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateCommandRejectsDangerousKeywords(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"sudo whoami", "systemctl restart ssh", "mount /dev/sda /tmp/x"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsDangerousPaths(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"rm -rf /", "cat /etc/passwd", "ls /proc"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsOutOfWorkspaceWorkingDir(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	err := policy.Validate(CommandRequest{Command: "pwd", WorkingDir: "/tmp", TimeoutSeconds: 30})
	if err == nil {
		t.Fatal("expected /tmp working dir to be rejected")
	}
}
```

- [ ] **Step 2: Write failing path policy tests**

Create `sandbox-service/internal/workspace/path_policy_test.go`:

```go
package workspace

import "testing"

func TestResolveAllowsWorkspacePath(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.Resolve("/workspace/input/data.csv")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if path != "/workspace/input/data.csv" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveRejectsTraversal(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.Resolve("/workspace/../etc/passwd"); err == nil {
		t.Fatal("expected traversal to be rejected")
	}
}

func TestResolveInputOnlyRejectsArtifacts(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.ResolveInput("/workspace/artifacts/report.csv"); err == nil {
		t.Fatal("expected non-input target to be rejected")
	}
}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```powershell
cd sandbox-service
go test ./internal/policy ./internal/workspace
```

Expected: FAIL，因为策略类型尚不存在。

- [ ] **Step 4: Implement command policy**

Create `sandbox-service/internal/policy/command_policy.go`:

```go
package policy

import (
	"errors"
	"strings"
)

type CommandRequest struct {
	Command        string
	WorkingDir     string
	TimeoutSeconds int
}

type CommandPolicy struct {
	maxCommandLength int
	maxTimeout       int
}

func NewCommandPolicy(maxCommandLength int, maxTimeout int) CommandPolicy {
	return CommandPolicy{maxCommandLength: maxCommandLength, maxTimeout: maxTimeout}
}

func (p CommandPolicy) Validate(req CommandRequest) error {
	command := strings.TrimSpace(req.Command)
	if command == "" {
		return errors.New("命令不能为空")
	}
	if len(command) > p.maxCommandLength {
		return errors.New("命令长度超过限制")
	}
	if !strings.HasPrefix(cleanSlash(req.WorkingDir), "/workspace") {
		return errors.New("工作目录必须位于 /workspace")
	}
	if req.TimeoutSeconds <= 0 || req.TimeoutSeconds > p.maxTimeout {
		return errors.New("命令超时时间超过限制")
	}
	normalized := " " + strings.ToLower(command) + " "
	for _, keyword := range []string{
		"shutdown", "reboot", "poweroff", "halt", "mount", "umount",
		"modprobe", "insmod", "sysctl", "sudo", "su", "systemctl", "service",
	} {
		if strings.Contains(normalized, " "+keyword+" ") {
			return errors.New("命令包含禁止的操作")
		}
	}
	for _, pattern := range []string{"rm -rf /", "rm -rf /*", "/etc", "/proc", "/sys", "/dev", "/root"} {
		if strings.Contains(normalized, pattern) {
			return errors.New("命令包含禁止访问的路径")
		}
	}
	return nil
}

func cleanSlash(value string) string {
	return strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
}
```

- [ ] **Step 5: Implement path policy**

Create `sandbox-service/internal/workspace/path_policy.go`:

```go
package workspace

import (
	"errors"
	"path"
	"strings"
)

type PathPolicy struct {
	root string
}

func NewPathPolicy(root string) PathPolicy {
	return PathPolicy{root: strings.TrimRight(root, "/")}
}

func (p PathPolicy) Resolve(input string) (string, error) {
	cleaned := path.Clean(strings.ReplaceAll(input, "\\", "/"))
	if cleaned == "." || cleaned == "/" {
		cleaned = p.root
	}
	if !strings.HasPrefix(cleaned, p.root+"/") && cleaned != p.root {
		return "", errors.New("路径不在允许访问范围内")
	}
	return cleaned, nil
}

func (p PathPolicy) ResolveInput(input string) (string, error) {
	resolved, err := p.Resolve(input)
	if err != nil {
		return "", err
	}
	if !strings.HasPrefix(resolved, p.root+"/input/") {
		return "", errors.New("上传文件只能写入 /workspace/input")
	}
	return resolved, nil
}
```

- [ ] **Step 6: Run tests**

Run:

```powershell
cd sandbox-service
go test ./internal/policy ./internal/workspace
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add sandbox-service/internal/policy sandbox-service/internal/workspace
git commit -m "新增沙箱命令与路径安全校验"
```

---

### Task 3: guest-agent 执行与文件 API

**Files:**
- Create: `sandbox-service/cmd/sandbox-agent/main.go`
- Create: `sandbox-service/internal/agent/server.go`
- Create: `sandbox-service/internal/agent/server_test.go`
- Create: `sandbox-service/internal/agent/executor.go`
- Create: `sandbox-service/internal/agent/executor_test.go`
- Create: `sandbox-service/internal/agent/files.go`
- Create: `sandbox-service/internal/agent/files_test.go`

- [ ] **Step 1: Write failing executor test**

Create `sandbox-service/internal/agent/executor_test.go`:

```go
package agent

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestExecutorStreamsStdoutAndCompletes(t *testing.T) {
	executor := NewExecutor(1024, 1024)
	var stdout strings.Builder
	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "printf hello",
		WorkingDir:     ".",
		TimeoutSeconds: 5,
	}, func(event ExecEvent) {
		if event.Type == "stdout_delta" {
			stdout.WriteString(event.Content)
		}
	})
	if result.ExitCode != 0 {
		t.Fatalf("ExitCode = %d stderr=%q", result.ExitCode, result.StderrExcerpt)
	}
	if stdout.String() != "hello" {
		t.Fatalf("stdout = %q", stdout.String())
	}
}

func TestExecutorTimesOut(t *testing.T) {
	executor := NewExecutor(1024, 1024)
	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "sleep 2",
		WorkingDir:     ".",
		TimeoutSeconds: 1,
	}, func(event ExecEvent) {})
	if !result.TimedOut {
		t.Fatal("expected timeout")
	}
	if result.Duration <= 0 || result.Duration > 1500*time.Millisecond {
		t.Fatalf("unexpected duration %s", result.Duration)
	}
}
```

- [ ] **Step 2: Write failing file API test**

Create `sandbox-service/internal/agent/files_test.go`:

```go
package agent

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFileServiceWriteReadAndList(t *testing.T) {
	root := t.TempDir()
	service := NewFileService(root)
	target := filepath.Join(root, "main.py")
	if err := service.WriteFile(target, []byte("print('ok')"), false); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	content, truncated, err := service.ReadFile(target, 100)
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	if truncated || string(content) != "print('ok')" {
		t.Fatalf("content=%q truncated=%v", string(content), truncated)
	}
	files, err := service.ListFiles(root, false, 20)
	if err != nil {
		t.Fatalf("ListFiles() error = %v", err)
	}
	if len(files) != 1 || files[0].Name != "main.py" {
		t.Fatalf("files = %#v", files)
	}
}

func TestFileServiceScansArtifactsWithSha256(t *testing.T) {
	root := t.TempDir()
	artifactDir := filepath.Join(root, "artifacts")
	if err := os.MkdirAll(artifactDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(artifactDir, "report.txt"), []byte("ok"), 0644); err != nil {
		t.Fatal(err)
	}
	service := NewFileService(root)
	artifacts, err := service.ScanArtifacts(filepath.Join(root, "artifacts"))
	if err != nil {
		t.Fatalf("ScanArtifacts() error = %v", err)
	}
	if len(artifacts) != 1 || artifacts[0].RelativePath != "report.txt" || artifacts[0].Sha256 == "" {
		t.Fatalf("artifacts = %#v", artifacts)
	}
}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```powershell
cd sandbox-service
go test ./internal/agent
```

Expected: FAIL，因为 guest-agent 组件尚不存在。

- [ ] **Step 4: Implement executor**

Create `sandbox-service/internal/agent/executor.go`:

```go
package agent

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"os/exec"
	"time"
)

type ExecRequest struct {
	Command        string `json:"command"`
	WorkingDir     string `json:"workingDir"`
	TimeoutSeconds int    `json:"timeoutSeconds"`
}

type ExecEvent struct {
	Type    string `json:"type"`
	Content string `json:"content,omitempty"`
}

type ExecResult struct {
	ExitCode        int
	StdoutExcerpt   string
	StderrExcerpt   string
	StdoutTruncated bool
	StderrTruncated bool
	TimedOut         bool
	Duration         time.Duration
}

type Executor struct {
	stdoutMaxBytes int
	stderrMaxBytes int
}

func NewExecutor(stdoutMaxBytes int, stderrMaxBytes int) Executor {
	return Executor{stdoutMaxBytes: stdoutMaxBytes, stderrMaxBytes: stderrMaxBytes}
}

func (e Executor) Exec(ctx context.Context, req ExecRequest, emit func(ExecEvent)) ExecResult {
	started := time.Now()
	timeout := time.Duration(req.TimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.CommandContext(runCtx, "bash", "-lc", req.Command)
	cmd.Dir = req.WorkingDir
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		return ExecResult{ExitCode: -1, StderrExcerpt: err.Error(), Duration: time.Since(started)}
	}

	var stdoutBuf bytes.Buffer
	var stderrBuf bytes.Buffer
	done := make(chan struct{}, 2)
	go streamPipe(stdout, "stdout_delta", e.stdoutMaxBytes, &stdoutBuf, emit, done)
	go streamPipe(stderr, "stderr_delta", e.stderrMaxBytes, &stderrBuf, emit, done)
	err := cmd.Wait()
	<-done
	<-done

	exitCode := cmd.ProcessState.ExitCode()
	if err != nil && exitCode == 0 {
		exitCode = -1
	}
	return ExecResult{
		ExitCode:        exitCode,
		StdoutExcerpt:   stdoutBuf.String(),
		StderrExcerpt:   stderrBuf.String(),
		StdoutTruncated: stdoutBuf.Len() >= e.stdoutMaxBytes,
		StderrTruncated: stderrBuf.Len() >= e.stderrMaxBytes,
		TimedOut:         runCtx.Err() == context.DeadlineExceeded,
		Duration:         time.Since(started),
	}
}

func streamPipe(reader io.Reader, eventType string, limit int, excerpt *bytes.Buffer, emit func(ExecEvent), done chan<- struct{}) {
	defer func() { done <- struct{}{} }()
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 1024), 64*1024)
	for scanner.Scan() {
		chunk := scanner.Text()
		emit(ExecEvent{Type: eventType, Content: chunk + "\n"})
		if excerpt.Len() < limit {
			remaining := limit - excerpt.Len()
			if len(chunk)+1 > remaining {
				excerpt.WriteString(chunk[:remaining])
			} else {
				excerpt.WriteString(chunk + "\n")
			}
		}
	}
}
```

- [ ] **Step 5: Implement file service**

Create `sandbox-service/internal/agent/files.go`:

```go
package agent

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
)

type FileInfo struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	Size  int64  `json:"size"`
	IsDir bool   `json:"isDir"`
}

type ArtifactInfo struct {
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
	Sha256       string `json:"sha256"`
}

type FileService struct {
	root string
}

func NewFileService(root string) FileService {
	return FileService{root: root}
}

func (s FileService) WriteFile(path string, content []byte, appendMode bool) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	flag := os.O_CREATE | os.O_WRONLY
	if appendMode {
		flag |= os.O_APPEND
	} else {
		flag |= os.O_TRUNC
	}
	file, err := os.OpenFile(path, flag, 0644)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = file.Write(content)
	return err
}

func (s FileService) ReadFile(path string, maxBytes int) ([]byte, bool, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, false, err
	}
	defer file.Close()
	limited := io.LimitReader(file, int64(maxBytes)+1)
	content, err := io.ReadAll(limited)
	if err != nil {
		return nil, false, err
	}
	if len(content) > maxBytes {
		return content[:maxBytes], true, nil
	}
	return content, false, nil
}

func (s FileService) ListFiles(path string, recursive bool, maxFiles int) ([]FileInfo, error) {
	files := make([]FileInfo, 0)
	if recursive {
		err := filepath.WalkDir(path, func(current string, d os.DirEntry, err error) error {
			if err != nil || current == path || len(files) >= maxFiles {
				return err
			}
			info, statErr := d.Info()
			if statErr != nil {
				return statErr
			}
			files = append(files, FileInfo{Name: d.Name(), Path: current, Size: info.Size(), IsDir: d.IsDir()})
			return nil
		})
		return files, err
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		return nil, err
	}
	for _, entry := range entries {
		if len(files) >= maxFiles {
			break
		}
		info, err := entry.Info()
		if err != nil {
			return nil, err
		}
		files = append(files, FileInfo{Name: entry.Name(), Path: filepath.Join(path, entry.Name()), Size: info.Size(), IsDir: entry.IsDir()})
	}
	return files, nil
}

func (s FileService) ScanArtifacts(artifactDir string) ([]ArtifactInfo, error) {
	artifacts := make([]ArtifactInfo, 0)
	err := filepath.WalkDir(artifactDir, func(current string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		info, statErr := d.Info()
		if statErr != nil {
			return statErr
		}
		hash, hashErr := sha256File(current)
		if hashErr != nil {
			return hashErr
		}
		relative, relErr := filepath.Rel(artifactDir, current)
		if relErr != nil {
			return relErr
		}
		artifacts = append(artifacts, ArtifactInfo{RelativePath: filepath.ToSlash(relative), Size: info.Size(), Sha256: hash})
		return nil
	})
	return artifacts, err
}

func sha256File(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
```

- [ ] **Step 6: Implement guest-agent server**

Create `sandbox-service/internal/agent/server.go` with `/exec/stream`, `/files`, `/files/content`, `/artifacts` handlers that call `Executor` and `FileService`. Use the exact event names `started`、`stdout_delta`、`stderr_delta`、`completed`、`error` in response bodies.

Required handler skeleton:

```go
package agent

import (
	"encoding/json"
	"net/http"
)

type Server struct {
	executor Executor
	files    FileService
}

func NewServer(executor Executor, files FileService) *Server {
	return &Server{executor: executor, files: files}
}

func (s *Server) Router() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	mux.HandleFunc("POST /exec/stream", s.handleExecStream)
	return mux
}

func (s *Server) handleExecStream(w http.ResponseWriter, r *http.Request) {
	var req ExecRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "请求参数不正确", http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	writeAgentEvent(w, "started", map[string]string{"status": "started"})
	result := s.executor.Exec(r.Context(), req, func(event ExecEvent) {
		writeAgentEvent(w, event.Type, map[string]string{"content": event.Content})
	})
	writeAgentEvent(w, "completed", result)
}

func writeAgentEvent(w http.ResponseWriter, event string, payload any) {
	encoded, _ := json.Marshal(payload)
	_, _ = w.Write([]byte("event: " + event + "\n"))
	_, _ = w.Write([]byte("data: " + string(encoded) + "\n\n"))
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
}
```

Create `sandbox-service/cmd/sandbox-agent/main.go`:

```go
package main

import (
	"log"
	"net/http"
	"os"

	"agent-hub/sandbox-service/internal/agent"
)

func main() {
	workspace := os.Getenv("SANDBOX_WORKSPACE")
	if workspace == "" {
		workspace = "/workspace"
	}
	server := agent.NewServer(agent.NewExecutor(1048576, 1048576), agent.NewFileService(workspace))
	log.Fatal(http.ListenAndServe(":8081", server.Router()))
}
```

- [ ] **Step 7: Run tests**

Run:

```powershell
cd sandbox-service
go test ./internal/agent
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add sandbox-service/cmd/sandbox-agent sandbox-service/internal/agent
git commit -m "新增沙箱 guest-agent 执行与文件能力"
```

---

### Task 4: session 管理、warm pool 和 Firecracker 适配

**Files:**
- Create: `sandbox-service/internal/host/session_manager.go`
- Create: `sandbox-service/internal/host/session_manager_test.go`
- Create: `sandbox-service/internal/host/warm_pool.go`
- Create: `sandbox-service/internal/host/warm_pool_test.go`
- Create: `sandbox-service/internal/host/firecracker.go`
- Create: `sandbox-service/internal/host/network.go`
- Create: `sandbox-service/internal/host/network_test.go`
- Create: `sandbox-service/scripts/build-minimal-rootfs.sh`
- Create: `sandbox-service/scripts/run-dev-firecracker.sh`

- [ ] **Step 1: Write failing session manager tests**

Create `sandbox-service/internal/host/session_manager_test.go`:

```go
package host

import (
	"testing"
	"time"
)

func TestSessionManagerReusesConversationSession(t *testing.T) {
	manager := NewSessionManager(NewFakeVMFactory(), 5, 5*time.Minute)
	first, err := manager.GetOrCreate("conv-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	second, err := manager.GetOrCreate("conv-1")
	if err != nil {
		t.Fatalf("GetOrCreate() second error = %v", err)
	}
	if first.ID != second.ID {
		t.Fatalf("session should be reused: %s != %s", first.ID, second.ID)
	}
}

func TestSessionManagerRejectsCapacityExceeded(t *testing.T) {
	manager := NewSessionManager(NewFakeVMFactory(), 1, 5*time.Minute)
	if _, err := manager.GetOrCreate("conv-1"); err != nil {
		t.Fatalf("first session error = %v", err)
	}
	if _, err := manager.GetOrCreate("conv-2"); err == nil {
		t.Fatal("expected capacity exceeded")
	}
}

func TestSessionManagerReapsIdleSessions(t *testing.T) {
	factory := NewFakeVMFactory()
	manager := NewSessionManager(factory, 5, time.Millisecond)
	session, err := manager.GetOrCreate("conv-1")
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)
	manager.ReapIdle(time.Now())
	if manager.HasSession(session.ID) {
		t.Fatal("idle session should be removed")
	}
	if factory.StopCount != 1 {
		t.Fatalf("StopCount = %d", factory.StopCount)
	}
}
```

- [ ] **Step 2: Implement fake-backed session manager**

Create `sandbox-service/internal/host/session_manager.go`:

```go
package host

import (
	"errors"
	"fmt"
	"sync"
	"time"
)

type VM interface {
	ID() string
	AgentURL() string
	Stop() error
}

type VMFactory interface {
	Start() (VM, error)
}

type Session struct {
	ID             string
	ConversationID string
	VM             VM
	LastActiveAt   time.Time
}

type SessionManager struct {
	mu          sync.Mutex
	factory     VMFactory
	maxActive   int
	idleTimeout time.Duration
	sessions    map[string]*Session
	byConv      map[string]string
	nextID      int
}

func NewSessionManager(factory VMFactory, maxActive int, idleTimeout time.Duration) *SessionManager {
	return &SessionManager{
		factory:     factory,
		maxActive:   maxActive,
		idleTimeout: idleTimeout,
		sessions:    map[string]*Session{},
		byConv:      map[string]string{},
	}
}

func (m *SessionManager) GetOrCreate(conversationID string) (*Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if existingID, ok := m.byConv[conversationID]; ok {
		session := m.sessions[existingID]
		session.LastActiveAt = time.Now()
		return session, nil
	}
	if len(m.sessions) >= m.maxActive {
		return nil, errors.New("当前沙箱会话数量已达到上限")
	}
	vm, err := m.factory.Start()
	if err != nil {
		return nil, err
	}
	m.nextID++
	session := &Session{
		ID:             fmt.Sprintf("sbx_%06d", m.nextID),
		ConversationID: conversationID,
		VM:             vm,
		LastActiveAt:   time.Now(),
	}
	m.sessions[session.ID] = session
	m.byConv[conversationID] = session.ID
	return session, nil
}

func (m *SessionManager) ReapIdle(now time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, session := range m.sessions {
		if now.Sub(session.LastActiveAt) >= m.idleTimeout {
			_ = session.VM.Stop()
			delete(m.byConv, session.ConversationID)
			delete(m.sessions, id)
		}
	}
}

func (m *SessionManager) HasSession(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.sessions[id]
	return ok
}
```

Add fake types in `sandbox-service/internal/host/session_manager_test.go`:

```go
type FakeVM struct {
	id string
}

func (f FakeVM) ID() string { return f.id }
func (f FakeVM) AgentURL() string { return "http://127.0.0.1:8081" }
func (f FakeVM) Stop() error { return nil }

type FakeVMFactory struct {
	NextID    int
	StopCount int
}

func NewFakeVMFactory() *FakeVMFactory { return &FakeVMFactory{} }

func (f *FakeVMFactory) Start() (VM, error) {
	f.NextID++
	return &countingVM{owner: f, id: "vm"}, nil
}

type countingVM struct {
	owner *FakeVMFactory
	id    string
}

func (v *countingVM) ID() string { return v.id }
func (v *countingVM) AgentURL() string { return "http://127.0.0.1:8081" }
func (v *countingVM) Stop() error { v.owner.StopCount++; return nil }
```

- [ ] **Step 3: Write Firecracker adapter skeleton**

Create `sandbox-service/internal/host/firecracker.go`:

```go
package host

import (
	"fmt"
	"os/exec"
)

type FirecrackerFactory struct {
	BinaryPath string
	RunDir     string
}

type FirecrackerVM struct {
	id       string
	agentURL string
	process  *exec.Cmd
}

func (f FirecrackerFactory) Start() (VM, error) {
	id := "fc-" + randomSuffix()
	cmd := exec.Command(f.BinaryPath, "--api-sock", f.RunDir+"/"+id+"/firecracker.sock")
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("沙箱虚拟机启动失败: %w", err)
	}
	return &FirecrackerVM{id: id, agentURL: "http://172.16.0.2:8081", process: cmd}, nil
}

func (v *FirecrackerVM) ID() string { return v.id }
func (v *FirecrackerVM) AgentURL() string { return v.agentURL }
func (v *FirecrackerVM) Stop() error {
	if v.process != nil && v.process.Process != nil {
		return v.process.Process.Kill()
	}
	return nil
}

func randomSuffix() string {
	return fmt.Sprintf("%d", timeNowUnixNano())
}

func timeNowUnixNano() int64 {
	return time.Now().UnixNano()
}
```

Then add the missing import:

```go
import "time"
```

这个骨架只负责定义 Firecracker 进程适配边界；真实 API socket 配置、rootfs、kernel、tap 和网络白名单在服务器集成阶段按部署文档接入。

- [ ] **Step 4: Add network whitelist rule generator**

Create `sandbox-service/internal/host/network_test.go`:

```go
package host

import (
	"strings"
	"testing"
)

func TestNetworkPolicyRendersDropByDefaultRules(t *testing.T) {
	policy := NetworkPolicy{
		TapName:     "tap-sbx-1",
		GuestCIDR:   "172.16.0.2/32",
		AllowCIDRs:  []string{"10.0.0.10/32", "10.0.0.20/32"},
	}
	rules := policy.RenderNFTablesRules()
	joined := strings.Join(rules, "\n")
	if !strings.Contains(joined, "10.0.0.10/32") || !strings.Contains(joined, "drop") {
		t.Fatalf("rules = %s", joined)
	}
}
```

Create `sandbox-service/internal/host/network.go`:

```go
package host

type NetworkPolicy struct {
	TapName    string
	GuestCIDR  string
	AllowCIDRs []string
}

func (p NetworkPolicy) RenderNFTablesRules() []string {
	rules := []string{
		"add table inet agent_hub_sandbox",
		"add chain inet agent_hub_sandbox forward { type filter hook forward priority 0; policy drop; }",
	}
	for _, cidr := range p.AllowCIDRs {
		rules = append(rules,
			"add rule inet agent_hub_sandbox forward ip saddr "+p.GuestCIDR+" ip daddr "+cidr+" accept",
			"add rule inet agent_hub_sandbox forward ip saddr "+cidr+" ip daddr "+p.GuestCIDR+" ct state established,related accept",
		)
	}
	rules = append(rules, "add rule inet agent_hub_sandbox forward ip saddr "+p.GuestCIDR+" drop")
	return rules
}
```

During server deployment, apply these rules with `nft`. The Go unit test only verifies rule generation; applying rules is covered by the server smoke script because it needs root privileges.

- [ ] **Step 5: Add rootfs scripts**

Create `sandbox-service/scripts/build-minimal-rootfs.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOTFS_DIR="${ROOTFS_DIR:-build/rootfs}"
mkdir -p "$ROOTFS_DIR"
echo "请在 Firecracker 服务器上基于发行版工具生成 minimal rootfs，并安装 bash python3 pip node npm git curl zip unzip tar jq ca-certificates。"
echo "生成完成后将 sandbox-agent 复制到 /usr/local/bin/sandbox-agent，并配置 systemd 或 init 脚本启动。"
```

Create `sandbox-service/scripts/run-dev-firecracker.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

export SANDBOX_INTERNAL_TOKEN="${SANDBOX_INTERNAL_TOKEN:-dev-secret}"
export SANDBOX_LISTEN_ADDR="${SANDBOX_LISTEN_ADDR:-:8090}"
./sandbox-service
```

- [ ] **Step 6: Run tests**

Run:

```powershell
cd sandbox-service
go test ./internal/host
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add sandbox-service/internal/host sandbox-service/scripts
git commit -m "新增沙箱会话池与 Firecracker 适配骨架"
```

---

### Task 5: sandbox-service 内部 API、SSE 代理和产物同步

**Files:**
- Create: `sandbox-service/internal/sse/sse.go`
- Create: `sandbox-service/internal/sse/sse_test.go`
- Create: `sandbox-service/internal/host/artifact_sync.go`
- Create: `sandbox-service/internal/host/artifact_sync_test.go`
- Create: `sandbox-service/internal/objectstore/objectstore.go`
- Create: `sandbox-service/internal/objectstore/minio.go`
- Modify: `sandbox-service/internal/host/api.go`
- Modify: `sandbox-service/internal/host/api_test.go`
- Modify: `sandbox-service/cmd/sandbox-service/main.go`

- [ ] **Step 1: Write failing SSE writer test**

Create `sandbox-service/internal/sse/sse_test.go`:

```go
package sse

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWriteEvent(t *testing.T) {
	res := httptest.NewRecorder()
	WriteEvent(res, "stdout_delta", map[string]string{"content": "hello"})
	body := res.Body.String()
	if !strings.Contains(body, "event: stdout_delta") {
		t.Fatalf("missing event line: %q", body)
	}
	if !strings.Contains(body, `"content":"hello"`) {
		t.Fatalf("missing data: %q", body)
	}
}
```

- [ ] **Step 2: Write failing artifact sync test**

Create `sandbox-service/internal/host/artifact_sync_test.go`:

```go
package host

import "testing"

func TestArtifactSyncDetectsNewAndChangedFiles(t *testing.T) {
	syncer := NewArtifactSyncer()
	first := []ArtifactFile{{RelativePath: "a.txt", Sha256: "1", Size: 1}}
	events := syncer.Diff("conv-1", first)
	if len(events) != 1 {
		t.Fatalf("events = %#v", events)
	}
	events = syncer.Diff("conv-1", first)
	if len(events) != 0 {
		t.Fatalf("duplicate events = %#v", events)
	}
	changed := []ArtifactFile{{RelativePath: "a.txt", Sha256: "2", Size: 1}}
	events = syncer.Diff("conv-1", changed)
	if len(events) != 1 || events[0].Version != 2 {
		t.Fatalf("changed events = %#v", events)
	}
}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```powershell
cd sandbox-service
go test ./internal/sse ./internal/host
```

Expected: FAIL，因为 SSE writer 和 artifact syncer 尚不存在。

- [ ] **Step 4: Implement SSE writer**

Create `sandbox-service/internal/sse/sse.go`:

```go
package sse

import (
	"encoding/json"
	"net/http"
)

func WriteEvent(w http.ResponseWriter, event string, payload any) {
	encoded, _ := json.Marshal(payload)
	_, _ = w.Write([]byte("event: " + event + "\n"))
	_, _ = w.Write([]byte("data: " + string(encoded) + "\n\n"))
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
}
```

- [ ] **Step 5: Implement artifact diff**

Create `sandbox-service/internal/host/artifact_sync.go`:

```go
package host

type ArtifactFile struct {
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
	Sha256       string `json:"sha256"`
}

type ArtifactEvent struct {
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
	Sha256       string `json:"sha256"`
	Version      int    `json:"version"`
	StorageKey  string `json:"storageKey"`
}

type ArtifactSyncer struct {
	seen map[string]map[string]string
	versions map[string]map[string]int
}

func NewArtifactSyncer() *ArtifactSyncer {
	return &ArtifactSyncer{seen: map[string]map[string]string{}, versions: map[string]map[string]int{}}
}

func (s *ArtifactSyncer) Diff(conversationID string, files []ArtifactFile) []ArtifactEvent {
	if s.seen[conversationID] == nil {
		s.seen[conversationID] = map[string]string{}
		s.versions[conversationID] = map[string]int{}
	}
	events := make([]ArtifactEvent, 0)
	for _, file := range files {
		if s.seen[conversationID][file.RelativePath] == file.Sha256 {
			continue
		}
		s.seen[conversationID][file.RelativePath] = file.Sha256
		s.versions[conversationID][file.RelativePath]++
		events = append(events, ArtifactEvent{
			RelativePath: file.RelativePath,
			Size: file.Size,
			Sha256: file.Sha256,
			Version: s.versions[conversationID][file.RelativePath],
			StorageKey: "sandbox-artifacts/" + conversationID + "/" + file.Sha256 + "/" + file.RelativePath,
		})
	}
	return events
}
```

- [ ] **Step 6: Add object store interface**

Create `sandbox-service/internal/objectstore/objectstore.go`:

```go
package objectstore

import "context"

type UploadRequest struct {
	Bucket      string
	ObjectKey   string
	FilePath    string
	ContentType string
}

type Downloader interface {
	Download(ctx context.Context, bucket string, objectKey string, targetPath string) error
}

type Uploader interface {
	Upload(ctx context.Context, request UploadRequest) error
}
```

Create `sandbox-service/internal/objectstore/minio.go`:

```go
package objectstore

import (
	"context"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type MinioStore struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Secure    bool
}

func (m MinioStore) Upload(ctx context.Context, request UploadRequest) error {
	client, err := minio.New(m.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(m.AccessKey, m.SecretKey, ""),
		Secure: m.Secure,
	})
	if err != nil {
		return err
	}
	_, err = client.FPutObject(ctx, request.Bucket, request.ObjectKey, request.FilePath, minio.PutObjectOptions{
		ContentType: request.ContentType,
	})
	return err
}

func (m MinioStore) Download(ctx context.Context, bucket string, objectKey string, targetPath string) error {
	client, err := minio.New(m.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(m.AccessKey, m.SecretKey, ""),
		Secure: m.Secure,
	})
	if err != nil {
		return err
	}
	return client.FGetObject(ctx, bucket, objectKey, targetPath, minio.GetObjectOptions{})
}
```

Run:

```powershell
cd sandbox-service
go get github.com/minio/minio-go/v7
```

- [ ] **Step 7: Wire session API**

Modify `sandbox-service/internal/host/api.go` to accept `SessionManager` and expose:

```go
func NewRouterWithManager(manager *SessionManager) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", health)
	mux.HandleFunc("POST /internal/sandbox/sessions", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ConversationID string `json:"conversationId"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ConversationID == "" {
			http.Error(w, "请求参数不正确", http.StatusBadRequest)
			return
		}
		session, err := manager.GetOrCreate(req.ConversationID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusTooManyRequests)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]string{
			"sandboxSessionId": session.ID,
			"status": "ACTIVE",
		})
	})
	return mux
}
```

- [ ] **Step 8: Restore historical artifacts on session creation**

Extend `POST /internal/sandbox/sessions` request body:

```go
type RestoreArtifact struct {
	RelativePath  string `json:"relativePath"`
	StorageBucket string `json:"storageBucket"`
	StorageKey    string `json:"storageKey"`
	Sha256        string `json:"sha256"`
}
```

After `manager.GetOrCreate`, download every restore artifact into:

```text
/workspace/artifacts/{relativePath}
```

Use `objectstore.Downloader.Download(...)` and reject any `relativePath` that escapes `/workspace/artifacts`.

- [ ] **Step 9: Add exec stream proxy route**

In `sandbox-service/internal/host/api.go`, add `POST /internal/sandbox/sessions/{sessionId}/exec/stream`. It must:

```go
// 1. 校验 sessionId 存在。
// 2. 再次执行 CommandPolicy。
// 3. 使用 http.Client 请求 session.VM.AgentURL()+"/exec/stream"。
// 4. 将 guest-agent 的 SSE 行原样转发给 Agent Hub。
// 5. 命令完成后调用 guest-agent /artifacts。
// 6. 对新增或变化产物调用 objectstore.Uploader.Upload。
// 7. 每个上传成功的产物发送 artifact_synced SSE 事件。
```

Use this route-level test shape in `api_test.go`:

```go
func TestCreateSessionReturnsSessionId(t *testing.T) {
	manager := NewSessionManager(NewFakeVMFactory(), 5, time.Minute)
	router := NewRouterWithManager(manager)
	body := strings.NewReader(`{"conversationId":"88"}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", body)
	res := httptest.NewRecorder()
	router.ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("code = %d body=%s", res.Code, res.Body.String())
	}
	if !strings.Contains(res.Body.String(), "sandboxSessionId") {
		t.Fatalf("body = %s", res.Body.String())
	}
}
```

- [ ] **Step 10: Run tests**

Run:

```powershell
cd sandbox-service
go test ./...
```

Expected: PASS.

- [ ] **Step 11: Commit**

```powershell
git add sandbox-service
git commit -m "新增沙箱内部 API 与产物同步"
```

---

### Task 6: 后端 sandbox 模块、迁移和错误码

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-hub-sandbox/pom.xml`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/entity/SandboxSession.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/entity/SandboxExecution.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/entity/SandboxArtifact.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/mapper/SandboxSessionMapper.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/mapper/SandboxExecutionMapper.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/mapper/SandboxArtifactMapper.java`
- Modify: `backend/agent-hub-server/pom.xml`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V7__init_sandbox_tool_loop.sql`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxMigrationSmokeTest.java`
- Modify: `backend/agent-hub-common/src/main/java/com/agenthub/common/api/ErrorCode.java`

- [ ] **Step 1: Write failing migration smoke test**

Create `backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxMigrationSmokeTest.java`:

```java
package com.agenthub.server.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sandbox_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class SandboxMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateSandboxTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('sandbox_session', 'sandbox_execution', 'sandbox_artifact')
                ORDER BY table_name
                """, String.class);

        assertThat(tableNames).containsExactly("sandbox_artifact", "sandbox_execution", "sandbox_session");
    }
}
```

- [ ] **Step 2: Run migration test to verify failure**

Run:

```powershell
cd backend
mvn -pl agent-hub-server -Dtest=SandboxMigrationSmokeTest test
```

Expected: FAIL，因为迁移尚不存在。

- [ ] **Step 3: Add Maven module**

Modify `backend/pom.xml` and add:

```xml
<module>agent-hub-sandbox</module>
```

Create `backend/agent-hub-sandbox/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.agenthub</groupId>
        <artifactId>backend</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>agent-hub-sandbox</artifactId>
    <name>agent-hub-sandbox</name>
    <dependencies>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-storage</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-core</artifactId>
            <version>3.5.16</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-tx</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Modify `backend/agent-hub-server/pom.xml` and add dependency:

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-sandbox</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: Add migration**

Create `backend/agent-hub-server/src/main/resources/db/migration/V7__init_sandbox_tool_loop.sql`:

```sql
CREATE TABLE IF NOT EXISTS sandbox_session (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sandbox_session_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    image VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_sandbox_session_conversation
    ON sandbox_session (conversation_id);

CREATE TABLE IF NOT EXISTS sandbox_execution (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sandbox_session_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    command TEXT,
    status VARCHAR(48) NOT NULL,
    exit_code INTEGER,
    stdout_excerpt TEXT,
    stderr_excerpt TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_code VARCHAR(64),
    error_message VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_sandbox_execution_conversation
    ON sandbox_execution (conversation_id, started_at DESC);

CREATE TABLE IF NOT EXISTS sandbox_artifact (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    execution_id BIGINT NOT NULL,
    sandbox_session_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    storage_bucket VARCHAR(128) NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    content_type VARCHAR(128),
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sandbox_artifact_conversation
    ON sandbox_artifact (conversation_id, relative_path, version DESC);
```

- [ ] **Step 5: Add error codes with Chinese messages**

Modify `backend/agent-hub-common/src/main/java/com/agenthub/common/api/ErrorCode.java`:

```java
SESSION_NOT_FOUND(460, "沙箱会话不存在或已过期"),
SESSION_CAPACITY_EXCEEDED(461, "当前沙箱会话数量已达到上限"),
VM_START_FAILED(462, "沙箱虚拟机启动失败"),
COMMAND_DENIED(463, "命令未通过安全校验"),
COMMAND_TIMEOUT(464, "命令执行超时"),
COMMAND_FAILED(465, "命令执行失败"),
FILE_TOO_LARGE(466, "文件大小超过限制"),
PATH_DENIED(467, "路径不在允许访问范围内"),
ARTIFACT_SYNC_FAILED(468, "产物同步失败"),
TOOL_CALL_INVALID(469, "模型返回的工具调用参数无效"),
TOOL_LOOP_LIMIT_EXCEEDED(470, "工具调用轮次超过上限"),
```

Keep existing `SUCCESS` through `INTERNAL_ERROR`, and ensure every new constant has a Chinese message.

- [ ] **Step 6: Add entities and mappers**

Create MyBatis-Plus entities using the existing style from `ChatAttachment`. Required annotations:

```java
@TableName("sandbox_session")
public class SandboxSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String sandboxSessionId;
    private String status;
    private String image;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActiveAt;
    private OffsetDateTime closedAt;
    // getters and setters
}
```

Create mapper:

```java
package com.agenthub.sandbox.mapper;

import com.agenthub.sandbox.entity.SandboxSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface SandboxSessionMapper extends BaseMapper<SandboxSession> {
}
```

Repeat the same pattern for `SandboxExecution` and `SandboxArtifact` with fields from the migration.

- [ ] **Step 7: Run tests**

Run:

```powershell
cd backend
mvn -pl agent-hub-server -Dtest=SandboxMigrationSmokeTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/pom.xml backend/agent-hub-sandbox backend/agent-hub-server/pom.xml backend/agent-hub-server/src/main/resources/db/migration/V7__init_sandbox_tool_loop.sql backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxMigrationSmokeTest.java backend/agent-hub-common/src/main/java/com/agenthub/common/api/ErrorCode.java
git commit -m "新增沙箱工具执行数据模型"
```

---

### Task 7: 后端 sandbox client、命令策略和工具执行服务

**Files:**
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/config/SandboxProperties.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/policy/CommandPolicy.java`
- Create: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/policy/CommandPolicyTest.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/SandboxClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/HttpSandboxClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/*.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/service/SandboxToolService.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/service/impl/SandboxToolServiceImpl.java`
- Create: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/service/SandboxToolServiceImplTest.java`

- [ ] **Step 1: Write failing Java command policy tests**

Create `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/policy/CommandPolicyTest.java`:

```java
package com.agenthub.sandbox.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {

    private final CommandPolicy policy = new CommandPolicy(200, 60);

    @Test
    void shouldAllowCommonCommand() {
        assertThatCode(() -> policy.validate("python3 main.py", "/workspace", 30))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDangerousKeyword() {
        assertThatThrownBy(() -> policy.validate("sudo whoami", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectDangerousPath() {
        assertThatThrownBy(() -> policy.validate("cat /etc/passwd", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }
}
```

- [ ] **Step 2: Implement Java CommandPolicy**

Create `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/policy/CommandPolicy.java`:

```java
package com.agenthub.sandbox.policy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.util.List;
import java.util.Locale;

public class CommandPolicy {

    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "shutdown", "reboot", "poweroff", "halt", "mount", "umount",
            "modprobe", "insmod", "sysctl", "sudo", "su", "systemctl", "service"
    );
    private static final List<String> DANGEROUS_PATHS = List.of(
            "rm -rf /", "rm -rf /*", "/etc", "/proc", "/sys", "/dev", "/root"
    );

    private final int maxCommandLength;
    private final int maxTimeoutSeconds;

    public CommandPolicy(int maxCommandLength, int maxTimeoutSeconds) {
        this.maxCommandLength = maxCommandLength;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public void validate(String command, String workingDir, int timeoutSeconds) {
        if (command == null || command.isBlank()) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令不能为空");
        }
        if (command.length() > maxCommandLength) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令长度超过限制");
        }
        if (workingDir == null || !workingDir.replace('\\', '/').startsWith("/workspace")) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "工作目录必须位于 /workspace");
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > maxTimeoutSeconds) {
            throw new BusinessException(ErrorCode.COMMAND_TIMEOUT, "命令超时时间超过限制");
        }
        String normalized = " " + command.toLowerCase(Locale.ROOT) + " ";
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (normalized.contains(" " + keyword + " ")) {
                throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令包含禁止的操作");
            }
        }
        for (String path : DANGEROUS_PATHS) {
            if (normalized.contains(path)) {
                throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令包含禁止访问的路径");
            }
        }
    }
}
```

- [ ] **Step 3: Add properties and client interface**

Create `SandboxProperties`:

```java
package com.agenthub.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-hub.sandbox")
public record SandboxProperties(
        String baseUrl,
        String internalToken,
        int commandMaxLength,
        int execTimeoutSeconds
) {
}
```

Create `SandboxClient`:

```java
package com.agenthub.sandbox.client;

import com.agenthub.sandbox.client.dto.CreateSandboxSessionRequest;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionResponse;
import com.agenthub.sandbox.client.dto.ExecSandboxRequest;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import java.util.function.Consumer;

public interface SandboxClient {

    CreateSandboxSessionResponse createSession(CreateSandboxSessionRequest request);

    void streamExec(String sandboxSessionId, ExecSandboxRequest request, Consumer<SandboxEvent> eventConsumer);
}
```

Create DTO records:

```java
package com.agenthub.sandbox.client.dto;

public record ExecSandboxRequest(
        String executionId,
        String command,
        String workingDir,
        int timeoutSeconds
) {
}
```

Create `SandboxEvent`:

```java
package com.agenthub.sandbox.client.dto;

public record SandboxEvent(
        String type,
        String executionId,
        String content,
        Integer exitCode,
        Long durationMs,
        String errorCode,
        String message
) {
}
```

Create `SandboxFileItem`:

```java
package com.agenthub.sandbox.client.dto;

public record SandboxFileItem(
        String name,
        String path,
        Long size,
        boolean directory
) {
}
```

Create `SandboxArtifactItem`:

```java
package com.agenthub.sandbox.client.dto;

public record SandboxArtifactItem(
        Long artifactId,
        String fileName,
        String relativePath,
        String storageKey,
        String contentType,
        Long sizeBytes,
        String sha256,
        Integer version
) {
}
```

- [ ] **Step 4: Implement HTTP client SSE parser**

Create `HttpSandboxClient` using `java.net.http.HttpClient`. Required behavior:

```java
// POST /internal/sandbox/sessions with Authorization header.
// POST /internal/sandbox/sessions/{sessionId}/exec/stream with Authorization header.
// Parse SSE event/data blocks.
// Convert every block to SandboxEvent and pass it to eventConsumer.
```

Use this parsing method signature:

```java
List<SandboxEvent> parseSseBlocks(List<String> lines)
```

Add a unit test that feeds:

```text
event: stdout_delta
data: {"content":"hello"}

event: completed
data: {"executionId":"exec_1","exitCode":0}
```

Expected parsed events: `stdout_delta` then `completed`.

- [ ] **Step 5: Write service test**

Create `SandboxToolServiceImplTest` with a fake client:

```java
@Test
void shouldValidateCommandBeforeCallingSandboxClient() {
    SandboxClient client = mock(SandboxClient.class);
    SandboxToolServiceImpl service = new SandboxToolServiceImpl(
            client,
            new CommandPolicy(200, 60),
            mock(SandboxSessionMapper.class),
            mock(SandboxExecutionMapper.class),
            mock(SandboxArtifactMapper.class)
    );

    assertThatThrownBy(() -> service.exec(7L, 100L, "sbx_1", "sudo whoami", "/workspace", 30, event -> {}))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.COMMAND_DENIED);

    verify(client, never()).streamExec(any(), any(), any());
}
```

- [ ] **Step 6: Implement service**

Create `SandboxToolService`:

```java
package com.agenthub.sandbox.service;

import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import java.util.function.Consumer;
import java.util.List;

public interface SandboxToolService {

    void exec(Long userId, Long conversationId, String sandboxSessionId, String command,
              String workingDir, int timeoutSeconds, Consumer<SandboxEvent> eventConsumer);

    List<SandboxFileItem> listFiles(Long userId, Long conversationId, String sandboxSessionId,
                                    String path, boolean recursive);

    String readFile(Long userId, Long conversationId, String sandboxSessionId,
                    String path, int maxBytes);

    void writeFile(Long userId, Long conversationId, String sandboxSessionId,
                   String path, String content, boolean append);

    void uploadFile(Long userId, Long conversationId, String sandboxSessionId,
                    Long attachmentId, String targetPath);

    List<SandboxArtifactItem> artifacts(Long userId, Long conversationId, boolean latestOnly);
}
```

Implement `SandboxToolServiceImpl` to:

```java
// 1. 调 CommandPolicy.validate。
// 2. 新增 sandbox_execution RUNNING 记录。
// 3. 调 SandboxClient.streamExec。
// 4. 根据 completed/error 事件更新 execution 状态。
// 5. 将事件传给 eventConsumer。
// 6. 对 list/read/write/upload/artifacts 统一校验 /workspace 路径和用户附件权限。
```

- [ ] **Step 7: Run tests**

Run:

```powershell
cd backend
mvn -pl agent-hub-sandbox test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/agent-hub-sandbox
git commit -m "新增后端沙箱客户端和工具服务"
```

---

### Task 8: 通用附件导入支持

**Files:**
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/AttachmentService.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/impl/AttachmentServiceImpl.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentImportSource.java`
- Modify: `backend/agent-hub-storage/src/test/java/com/agenthub/storage/attachment/AttachmentServiceImplTest.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/controller/AttachmentController.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/controller/request/CreateImageUploadUrlRequest.java`

- [ ] **Step 1: Write failing attachment import test**

Add to `AttachmentServiceImplTest`:

```java
@Test
void shouldResolveAttachmentImportSourceForOwner() {
    ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
    ObjectStorageClient storageClient = mock(ObjectStorageClient.class);
    AttachmentServiceImpl service = service(mapper, storageClient);
    ChatAttachment attachment = readyAttachment(10L, 7L, "inputs/data.csv", "data.csv", "text/csv");
    when(mapper.selectById(10L)).thenReturn(attachment);
    when(storageClient.presignGet("inputs/data.csv", Duration.ofMinutes(10)))
            .thenReturn("http://minio-internal/data.csv");

    AttachmentImportSource source = service.requireImportSource(7L, 10L);

    assertThat(source.attachmentId()).isEqualTo(10L);
    assertThat(source.filename()).isEqualTo("data.csv");
    assertThat(source.downloadUrl()).isEqualTo("http://minio-internal/data.csv");
}
```

- [ ] **Step 2: Extend service interface**

Modify `AttachmentService`:

```java
AttachmentImportSource requireImportSource(Long userId, Long attachmentId);
```

Create DTO:

```java
package com.agenthub.storage.attachment.dto;

public record AttachmentImportSource(
        Long attachmentId,
        String filename,
        String contentType,
        Long fileSize,
        String downloadUrl,
        String objectKey
) {
}
```

- [ ] **Step 3: Implement import source**

In `AttachmentServiceImpl`, implement:

```java
public AttachmentImportSource requireImportSource(Long userId, Long attachmentId) {
    ChatAttachment attachment = attachmentMapper.selectById(attachmentId);
    if (attachment == null || !userId.equals(attachment.getUserId()) || !"READY".equals(attachment.getStatus())) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "附件不存在");
    }
    String downloadUrl = objectStorageClient.presignGet(attachment.getObjectKey(), Duration.ofMinutes(10));
    return new AttachmentImportSource(
            attachment.getId(),
            attachment.getOriginalFilename(),
            attachment.getContentType(),
            attachment.getFileSize(),
            downloadUrl,
            attachment.getObjectKey()
    );
}
```

- [ ] **Step 4: Allow non-image upload metadata**

Rename request class only if necessary during implementation; keep existing image upload route working. Add a new route:

```text
POST /api/chat/attachments/upload-url
```

Request body:

```json
{
  "filename": "data.csv",
  "contentType": "text/csv",
  "fileSize": 1024
}
```

Response reuses `AttachmentUploadUrl`.

- [ ] **Step 5: Run tests**

Run:

```powershell
cd backend
mvn -pl agent-hub-storage test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/agent-hub-storage
git commit -m "支持沙箱导入通用附件"
```

---

### Task 9: LLM Tool Calling 抽象与 OpenAI Compatible 适配

**Files:**
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmStreamRequest.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmStreamChunk.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmStreamChunkType.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/tool/LlmToolDefinition.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/tool/LlmToolCall.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/tool/LlmToolResult.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmMessage.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiCompatibleClient.java`
- Modify: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/OpenAiCompatibleClientTest.java`

- [ ] **Step 1: Write failing OpenAI tool call parser test**

Add to `OpenAiCompatibleClientTest`:

```java
@Test
void shouldParseOpenAiToolCallDelta() {
    OpenAiCompatibleClient client = new OpenAiCompatibleClient();

    List<LlmStreamChunk> chunks = client.parseDataLine("""
            {"choices":[{"delta":{"tool_calls":[{"id":"call_1","type":"function","function":{"name":"exec","arguments":"{\\"command\\":\\"python3 main.py\\"}"}}]}}]}
            """);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().type()).isEqualTo(LlmStreamChunkType.TOOL_CALL);
    assertThat(chunks.getFirst().toolCall().id()).isEqualTo("call_1");
    assertThat(chunks.getFirst().toolCall().name()).isEqualTo("exec");
    assertThat(chunks.getFirst().toolCall().arguments()).containsEntry("command", "python3 main.py");
}
```

- [ ] **Step 2: Add tool records**

Create `LlmToolCall`:

```java
package com.agenthub.ai.llm.tool;

import java.util.Map;

public record LlmToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {
}
```

Create `LlmToolDefinition`:

```java
package com.agenthub.ai.llm.tool;

import java.util.Map;

public record LlmToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {
}
```

Create `LlmToolResult`:

```java
package com.agenthub.ai.llm.tool;

public record LlmToolResult(
        String toolCallId,
        String name,
        String content
) {
}
```

- [ ] **Step 3: Extend stream chunk**

Modify `LlmStreamChunkType`:

```java
TOOL_CALL
```

Modify `LlmStreamChunk`:

```java
public record LlmStreamChunk(
        LlmStreamChunkType type,
        String content,
        String errorMessage,
        LlmToolCall toolCall
) {
    public LlmStreamChunk(LlmStreamChunkType type, String content, String errorMessage) {
        this(type, content, errorMessage, null);
    }

    public static LlmStreamChunk toolCall(LlmToolCall toolCall) {
        return new LlmStreamChunk(LlmStreamChunkType.TOOL_CALL, null, null, toolCall);
    }
}
```

- [ ] **Step 4: Extend request and message**

Modify `LlmStreamRequest`:

```java
public record LlmStreamRequest(
        String baseUrl,
        String apiKey,
        String apiVersion,
        String model,
        List<LlmMessage> messages,
        Integer maxOutputTokens,
        boolean reasoningRequested,
        List<LlmToolDefinition> tools
) {
    public LlmStreamRequest(String baseUrl, String apiKey, String apiVersion, String model,
                            List<LlmMessage> messages, Integer maxOutputTokens, boolean reasoningRequested) {
        this(baseUrl, apiKey, apiVersion, model, messages, maxOutputTokens, reasoningRequested, List.of());
    }
}
```

Modify `LlmMessage` to support tool result messages:

```java
public record LlmMessage(String role, String content, List<LlmContentPart> parts, String toolCallId, String toolName) {
    public LlmMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    public LlmMessage(String role, String content, List<LlmContentPart> parts) {
        this(role, content, parts, null, null);
    }

    public static LlmMessage tool(String toolCallId, String toolName, String content) {
        return new LlmMessage("tool", content, null, toolCallId, toolName);
    }
}
```

- [ ] **Step 5: Add OpenAI request tools and parser**

In `OpenAiCompatibleClient.buildRequestBody`, add:

```java
if (request.tools() != null && !request.tools().isEmpty()) {
    body.put("tools", request.tools().stream().map(this::toOpenAiTool).toList());
    body.put("tool_choice", "auto");
}
```

Add converter:

```java
private Map<String, Object> toOpenAiTool(LlmToolDefinition tool) {
    return Map.of(
            "type", "function",
            "function", Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters()
            )
    );
}
```

In `toMessages`, when `message.role()` is `tool`, include:

```java
item.put("tool_call_id", message.toolCallId());
```

In `parseDataLine`, parse `delta.tool_calls` into `LlmStreamChunk.toolCall(...)`. Use Jackson to parse `function.arguments` into `Map<String, Object>`; if parsing fails, emit an empty argument map and keep the raw error in tests for `TOOL_CALL_INVALID`.

- [ ] **Step 6: Run tests**

Run:

```powershell
cd backend
mvn -pl agent-hub-ai -Dtest=OpenAiCompatibleClientTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/agent-hub-ai
git commit -m "支持 OpenAI Compatible 工具调用解析"
```

---

### Task 10: Anthropic Tool Calling 适配

**Files:**
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/anthropic/AnthropicMessagesClient.java`
- Modify: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/AnthropicMessagesClientTest.java`

- [ ] **Step 1: Write failing Anthropic tool use parser test**

Add to `AnthropicMessagesClientTest`:

```java
@Test
void shouldParseAnthropicToolUseBlock() {
    AnthropicMessagesClient client = new AnthropicMessagesClient();

    List<LlmStreamChunk> start = client.parseEvent("content_block_start", """
            {"index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"exec","input":{"command":"python3 main.py"}}}
            """);

    assertThat(start).hasSize(1);
    assertThat(start.getFirst().type()).isEqualTo(LlmStreamChunkType.TOOL_CALL);
    assertThat(start.getFirst().toolCall().id()).isEqualTo("toolu_1");
    assertThat(start.getFirst().toolCall().name()).isEqualTo("exec");
}
```

- [ ] **Step 2: Add Anthropic tools to request body**

In `buildRequestBody`, add:

```java
if (request.tools() != null && !request.tools().isEmpty()) {
    body.put("tools", request.tools().stream().map(this::toAnthropicTool).toList());
}
```

Add converter:

```java
private Map<String, Object> toAnthropicTool(LlmToolDefinition tool) {
    return Map.of(
            "name", tool.name(),
            "description", tool.description(),
            "input_schema", tool.parameters()
    );
}
```

- [ ] **Step 3: Parse tool_use and tool_result**

In `parseEvent`, handle `content_block_start`:

```java
if ("content_block_start".equals(eventName)) {
    JsonNode block = root.path("content_block");
    if ("tool_use".equals(block.path("type").asText())) {
        Map<String, Object> args = OBJECT_MAPPER.convertValue(block.path("input"), new TypeReference<>() {});
        return List.of(LlmStreamChunk.toolCall(new LlmToolCall(
                block.path("id").asText(),
                block.path("name").asText(),
                args
        )));
    }
}
```

Update `toMessages` so tool result messages become Anthropic content blocks:

```java
Map<String, Object> item = new LinkedHashMap<>();
item.put("role", "user");
item.put("content", List.of(Map.of(
        "type", "tool_result",
        "tool_use_id", message.toolCallId(),
        "content", message.content()
)));
```

- [ ] **Step 4: Run tests**

Run:

```powershell
cd backend
mvn -pl agent-hub-ai -Dtest=AnthropicMessagesClientTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/agent-hub-ai
git commit -m "支持 Anthropic 工具调用解析"
```

---

### Task 11: Agent Runtime Tool Loop 与聊天 SSE 接入

**Files:**
- Modify: `backend/agent-hub-chat/pom.xml`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/tool/ToolDefinitionFactory.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/tool/ToolLoopRunner.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/tool/ToolLoopResult.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/tool/ToolExecutionResult.java`
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/tool/ToolLoopRunnerTest.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: Add sandbox dependency**

Modify `backend/agent-hub-chat/pom.xml`:

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-sandbox</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: Write failing ToolLoopRunner test**

Create `ToolLoopRunnerTest`:

```java
package com.agenthub.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.tool.LlmToolCall;
import com.agenthub.sandbox.service.SandboxToolService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolLoopRunnerTest {

    @Test
    void shouldExecuteToolAndContinueModel() {
        RecordingLlmClient llm = new RecordingLlmClient(List.of(
                List.of(LlmStreamChunk.toolCall(new LlmToolCall("call_1", "exec", Map.of("command", "python3 main.py")))),
                List.of(LlmStreamChunk.answer("完成"), LlmStreamChunk.done())
        ));
        SandboxToolService sandboxToolService = mock(SandboxToolService.class);
        ToolLoopRunner runner = new ToolLoopRunner(sandboxToolService, new ToolDefinitionFactory(), 3);

        ToolLoopResult result = runner.run(7L, 100L, "sbx_1", runtimeConfig(), List.of(new LlmMessage("user", "运行脚本")), llm, event -> {});

        assertThat(result.answer()).isEqualTo("完成");
        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().get(1).messages()).anyMatch(message -> "tool".equals(message.role()));
    }
}
```

Add these helpers in the same test file:

```java
private AiModelRuntimeConfig runtimeConfig() {
    return new AiModelRuntimeConfig(
            10L, 20L, "openai", "OpenAI", "LLM", "OPENAI_COMPATIBLE",
            "https://api.example.com", "sk-cipher", "BEARER", "v1",
            "{}", "{}", "gpt-test", "测试模型",
            true, true, true, null, 128000, 4096, true, true
    );
}

private static class RecordingLlmClient implements LlmClient {
    private final List<List<LlmStreamChunk>> attempts;
    private final List<LlmStreamRequest> requests = new ArrayList<>();

    private RecordingLlmClient(List<List<LlmStreamChunk>> attempts) {
        this.attempts = attempts;
    }

    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        requests.add(request);
        attempts.get(Math.min(requests.size() - 1, attempts.size() - 1)).forEach(chunkConsumer);
    }

    private List<LlmStreamRequest> requests() {
        return requests;
    }
}
```

- [ ] **Step 3: Add tool definitions**

Create `ToolDefinitionFactory` returning six tools:

```java
public List<LlmToolDefinition> sandboxTools() {
    return List.of(
            execTool(),
            simpleTool("list_files", "列出工作区文件"),
            simpleTool("read_file", "读取工作区文本文件"),
            simpleTool("write_file", "写入工作区文本文件"),
            simpleTool("upload_file", "把用户已上传文件导入工作区"),
            simpleTool("artifacts", "查询已同步产物")
    );
}
```

`execTool()` parameters must include `command`、`workingDir`、`timeoutSeconds` JSON schema.

- [ ] **Step 4: Implement ToolLoopRunner**

Create `ToolLoopResult`:

```java
package com.agenthub.chat.tool;

public record ToolLoopResult(
        String answer,
        String reasoningContent
) {
}
```

Create `ToolExecutionResult`:

```java
package com.agenthub.chat.tool;

public record ToolExecutionResult(
        String content
) {
}
```

`ToolLoopRunner` algorithm:

```java
for (int round = 0; round < maxRounds; round++) {
    LlmStreamRequest request = new LlmStreamRequest(..., messages, ..., toolDefinitionFactory.sandboxTools());
    List<LlmToolCall> toolCalls = new ArrayList<>();
    client.stream(request, chunk -> {
        if (chunk.type() == LlmStreamChunkType.TOOL_CALL) {
            toolCalls.add(chunk.toolCall());
            return;
        }
        // answer/reasoning chunks are passed through to chat SSE
    });
    if (toolCalls.isEmpty()) {
        return finalAnswer;
    }
    for (LlmToolCall call : toolCalls) {
        ToolExecutionResult toolResult = executeTool(call);
        messages.add(LlmMessage.tool(call.id(), call.name(), toolResult.content()));
    }
}
throw new BusinessException(ErrorCode.TOOL_LOOP_LIMIT_EXCEEDED, "工具调用轮次超过上限");
```

Tool execution:

- `exec` calls `SandboxToolService.exec` and streams events.
- `list_files`、`read_file`、`write_file`、`upload_file`、`artifacts` 分别调用 `SandboxToolService` 中同名语义的方法。
- Invalid tool name throws `TOOL_CALL_INVALID` with Chinese message.

- [ ] **Step 5: Add SSE event types**

Modify `SseEventType`:

```java
TOOL_STARTED("tool_started"),
TOOL_STDOUT_DELTA("tool_stdout_delta"),
TOOL_STDERR_DELTA("tool_stderr_delta"),
TOOL_ARTIFACT("tool_artifact"),
TOOL_COMPLETED("tool_completed"),
TOOL_ERROR("tool_error"),
```

Modify `SseEventData` permits and add records:

```java
record ToolStarted(String executionId, String toolName, String summary) implements SseEventData {}
record ToolDelta(String executionId, String content) implements SseEventData {}
record ToolArtifact(String executionId, Long artifactId, String fileName, String relativePath, Long sizeBytes) implements SseEventData {}
record ToolCompleted(String executionId, String status, Integer exitCode) implements SseEventData {}
record ToolError(String executionId, String code, String message) implements SseEventData {}
```

- [ ] **Step 6: Integrate ChatServiceImpl**

Modify `ChatServiceImpl` constructor to accept optional `ToolLoopRunner`. In `streamLlm`, when tools are enabled for the selected Agent, call `ToolLoopRunner.run(...)` instead of single-pass `client.stream(...)`.

Keep existing behavior when no tool calls are returned:

```java
if (toolLoopRunner == null) {
    streamOnce(...);
    return;
}
```

Tool events must be sent with existing `send(emitter, SseEventType.TOOL_..., data)` helper.

- [ ] **Step 7: Add ChatService test for tool SSE**

Add to `ChatServiceImplTest`:

```java
@Test
void shouldForwardToolEventsBeforeFinalAnswer() {
    RecordingSseEventSender sender = new RecordingSseEventSender();
    // llm first emits TOOL_CALL exec, second emits ANSWER_DELTA and DONE
    // sandbox fake emits started/stdout_delta/completed
    service.stream(7L, 100L, 10L, "data-analysis", "运行分析", false, List.of(), List.of());

    assertThat(sender.eventTypes()).contains(
            SseEventType.TOOL_STARTED,
            SseEventType.TOOL_STDOUT_DELTA,
            SseEventType.TOOL_COMPLETED,
            SseEventType.ANSWER_DELTA,
            SseEventType.MESSAGE_COMPLETED
    );
}
```

- [ ] **Step 8: Run tests**

Run:

```powershell
cd backend
mvn -pl agent-hub-chat,agent-hub-sse -am test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add backend/agent-hub-chat backend/agent-hub-sse
git commit -m "接入 Agent Tool Loop 和工具 SSE"
```

---

### Task 12: 用户端工具事件展示

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Create: `user-web/src/views/chat-tool-events.helpers.ts`
- Create: `user-web/tests/chat-tool-events.spec.ts`
- Modify: `user-web/src/views/ChatView.vue`

- [ ] **Step 1: Write failing helper tests**

Create `user-web/tests/chat-tool-events.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { applyToolEvent, createToolRun } from '../src/views/chat-tool-events.helpers'

describe('chat tool events', () => {
  it('appends stdout and completes tool run', () => {
    const run = createToolRun({ executionId: 'exec_1', toolName: 'exec', summary: '执行命令' })
    applyToolEvent(run, { type: 'stdout', content: 'hello\n' })
    applyToolEvent(run, { type: 'completed', status: 'COMPLETED', exitCode: 0 })

    expect(run.stdout).toBe('hello\n')
    expect(run.status).toBe('COMPLETED')
    expect(run.exitCode).toBe(0)
  })
})
```

- [ ] **Step 2: Implement helper**

Create `user-web/src/views/chat-tool-events.helpers.ts`:

```ts
export interface ToolRun {
  executionId: string
  toolName: string
  summary: string
  stdout: string
  stderr: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  exitCode?: number | null
  artifacts: ToolArtifact[]
}

export interface ToolArtifact {
  artifactId: number
  fileName: string
  relativePath: string
  sizeBytes: number
}

export function createToolRun(input: { executionId: string; toolName: string; summary: string }): ToolRun {
  return {
    executionId: input.executionId,
    toolName: input.toolName,
    summary: input.summary,
    stdout: '',
    stderr: '',
    status: 'RUNNING',
    exitCode: null,
    artifacts: []
  }
}

export function applyToolEvent(run: ToolRun, event: { type: string; content?: string; status?: string; exitCode?: number; artifact?: ToolArtifact }) {
  if (event.type === 'stdout') {
    run.stdout += event.content ?? ''
  } else if (event.type === 'stderr') {
    run.stderr += event.content ?? ''
  } else if (event.type === 'artifact' && event.artifact) {
    run.artifacts.push(event.artifact)
  } else if (event.type === 'completed') {
    run.status = 'COMPLETED'
    run.exitCode = event.exitCode ?? null
  } else if (event.type === 'error') {
    run.status = 'FAILED'
  }
}
```

- [ ] **Step 3: Extend chat API SSE handlers**

Modify `ChatStreamHandlers` in `user-web/src/api/chat.ts`:

```ts
onToolStarted?: (data: { executionId: string; toolName: string; summary: string }) => void
onToolStdoutDelta?: (data: { executionId: string; content: string }) => void
onToolStderrDelta?: (data: { executionId: string; content: string }) => void
onToolArtifact?: (data: { executionId: string; artifactId: number; fileName: string; relativePath: string; sizeBytes: number }) => void
onToolCompleted?: (data: { executionId: string; status: string; exitCode?: number | null }) => void
onToolError?: (data: { executionId: string; code: string; message: string }) => void
```

Add cases in `dispatchSseEvent`:

```ts
case 'tool_started':
  dispatchJson(event.data, handlers.onToolStarted)
  break
case 'tool_stdout_delta':
  dispatchJson(event.data, handlers.onToolStdoutDelta)
  break
case 'tool_stderr_delta':
  dispatchJson(event.data, handlers.onToolStderrDelta)
  break
case 'tool_artifact':
  dispatchJson(event.data, handlers.onToolArtifact)
  break
case 'tool_completed':
  dispatchJson(event.data, handlers.onToolCompleted)
  break
case 'tool_error':
  dispatchJson(event.data, handlers.onToolError)
  break
```

- [ ] **Step 4: Add generic file upload API**

Modify `user-web/src/api/chat.ts`:

```ts
export async function createAttachmentUploadUrl(filename: string, contentType: string, fileSize: number) {
  const response = await http.post<ApiResponse<ImageUploadUrlRecord>>('/chat/attachments/upload-url', {
    filename,
    contentType,
    fileSize
  })
  return response.data.data
}
```

Keep `createImageUploadUrl` for image preview behavior. Generic files are sent as normal attachments; the model imports those attachment IDs through `upload_file`.

- [ ] **Step 5: Display tool runs in ChatView**

In `ChatView.vue`, add tool runs to assistant message state. Render a compact block above assistant markdown:

```vue
<div v-if="messageItem.toolRuns?.length" class="tool-run-list">
  <section v-for="run in messageItem.toolRuns" :key="run.executionId" class="tool-run">
    <header>
      <strong>{{ run.summary }}</strong>
      <span>{{ run.status }}</span>
    </header>
    <pre v-if="run.stdout || run.stderr">{{ run.stdout }}{{ run.stderr }}</pre>
    <div v-if="run.artifacts.length" class="tool-artifacts">
      <button v-for="artifact in run.artifacts" :key="artifact.artifactId" type="button">
        {{ artifact.fileName }}
      </button>
    </div>
  </section>
</div>
```

Add restrained CSS:

```css
.tool-run-list {
  display: grid;
  gap: 8px;
  margin-bottom: 12px;
}

.tool-run {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.tool-run pre {
  max-height: 180px;
  overflow: auto;
  margin: 0;
  padding: 8px;
  border-radius: 6px;
  background: #0f172a;
  color: #e5e7eb;
  font-size: 12px;
}
```

- [ ] **Step 6: Add generic file selector to ChatView**

In `ChatView.vue`, add a second hidden file input:

```vue
<input
  ref="fileInputRef"
  type="file"
  multiple
  hidden
  @change="handleFileSelected"
/>
```

Update upload menu options to include:

```ts
{
  label: '上传文件',
  key: 'file'
}
```

When `file` is selected, trigger `fileInputRef.click()`. `handleFileSelected` must call `createAttachmentUploadUrl` and `confirmImageUpload` for each selected file, then add the returned attachment ID to the next chat request `attachmentIds` list. Image preview stays on the existing image path; generic files render as filename chips near the composer.

- [ ] **Step 7: Run tests and type check**

Run:

```powershell
cd user-web
npm run type-check
npm test -- chat-tool-events.spec.ts
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add user-web/src/api/chat.ts user-web/src/views/ChatView.vue user-web/src/views/chat-tool-events.helpers.ts user-web/tests/chat-tool-events.spec.ts
git commit -m "展示聊天工具执行事件"
```

---

### Task 13: 端到端验证与部署文档

**Files:**
- Create: `sandbox-service/README.md`
- Modify: `docker/README.md`
- Create: `docs/superpowers/plans/2026-06-07-firecracker-sandbox-tool-loop-verification.md`

- [ ] **Step 1: Write sandbox-service README**

Create `sandbox-service/README.md`:

```markdown
# sandbox-service

`sandbox-service` 是 Agent Hub 的内部 Firecracker microVM 沙箱服务。

## 默认配置

- `SANDBOX_INTERNAL_TOKEN`
- `SANDBOX_WARM_POOL_SIZE=2`
- `SANDBOX_MAX_ACTIVE_SESSIONS=5`
- `SANDBOX_IDLE_TIMEOUT=5m`
- `SANDBOX_VM_CPU=1`
- `SANDBOX_VM_MEMORY_MB=1024`
- `SANDBOX_VM_DISK_GB=5`

## 本地测试

```powershell
go test ./...
```

## 服务器验证

服务器需要已安装 Firecracker，并具备 `/dev/kvm` 访问能力。
```

- [ ] **Step 2: Add backend config documentation**

Modify `docker/README.md` and add:

```markdown
## 沙箱服务配置

Agent Hub 后端通过以下环境变量访问内部沙箱服务：

- `AGENT_HUB_SANDBOX_BASE_URL`
- `AGENT_HUB_SANDBOX_INTERNAL_TOKEN`
- `AGENT_HUB_SANDBOX_COMMAND_MAX_LENGTH`
- `AGENT_HUB_SANDBOX_EXEC_TIMEOUT_SECONDS`
```

- [ ] **Step 3: Create verification checklist**

Create `docs/superpowers/plans/2026-06-07-firecracker-sandbox-tool-loop-verification.md`:

```markdown
# Firecracker 沙箱与 Tool Loop 验证清单

- `cd sandbox-service && go test ./...`
- `cd backend && mvn -pl agent-hub-sandbox test`
- `cd backend && mvn -pl agent-hub-ai test`
- `cd backend && mvn -pl agent-hub-chat,agent-hub-sse -am test`
- `cd backend && mvn -pl agent-hub-server -Dtest=SandboxMigrationSmokeTest test`
- `cd user-web && npm run type-check`
- `cd user-web && npm test -- chat-tool-events.spec.ts`
```

- [ ] **Step 4: Run full verification**

Run:

```powershell
cd sandbox-service
go test ./...

cd ..\backend
mvn test

cd ..\user-web
npm run type-check
npm run build
```

Expected: all commands PASS.

- [ ] **Step 5: Commit**

```powershell
git add sandbox-service/README.md docker/README.md docs/superpowers/plans/2026-06-07-firecracker-sandbox-tool-loop-verification.md
git commit -m "补充沙箱工具闭环部署验证文档"
```
