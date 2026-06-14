# Skill Package Sandbox Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建文件化 Skill 包管理能力，支持管理员上传单 Skill zip，sandbox-service 校验与运行时同步，Agent 绑定 Skill 后聊天自动注入 `SKILL.md` 并同步资源文件。

**Architecture:** Go `sandbox-service` 负责 zip 下载、流式解包、安全校验和会话同步；Java 新增 `agent-hub-skill` 模块负责对象存储、元数据、异步校验任务、Agent 绑定和运行时上下文；admin-web 增加 Skill 库页面和 Agent Skill 绑定入口。ChatService 只依赖 `SkillRuntimeService` 获取提示词注入内容和同步清单，避免把 Skill 业务散落到聊天主流程。

**Tech Stack:** Go 标准库 `archive/zip`、`net/http`、`httptest`；Java 21、Spring Boot 4、MyBatis Plus、Flyway、JUnit 5、Mockito、AssertJ、H2；Vue 3、TypeScript、Naive UI、Vitest。

---

## Scope Check

本计划覆盖一个端到端功能，但按可独立验证的任务拆分：

- Task 1-2：sandbox-service 的 Skill zip 校验和同步接口。
- Task 3：Java sandbox client 对新接口的封装。
- Task 4-6：`agent-hub-skill` 模块、数据库迁移、上传和异步校验。
- Task 7：Agent Skill 绑定和聊天运行时注入。
- Task 8-9：admin-web 类型、API、路由和页面。
- Task 10：端到端验证与收尾。

每个任务完成后都有可运行测试和提交点。不要跨任务提前实现后续 UI 或聊天集成。

## File Map

### sandbox-service 新增或修改

- Create: `sandbox-service/internal/skill/package.go`：Skill zip 校验、根目录识别、文件清单、同步解包。
- Test: `sandbox-service/internal/skill/package_test.go`：zip slip、缺失 `SKILL.md`、大包限制、同步幂等。
- Modify: `sandbox-service/internal/host/api.go`：内部 host API 增加 Skill 校验和同步路由转发。
- Test: `sandbox-service/internal/host/api_test.go`：host API 新路由请求转发。
- Modify: `sandbox-service/internal/agent/server.go`：agent 侧增加 Skill 同步接口。
- Test: `sandbox-service/internal/agent/server_test.go`：agent 侧解包到 `/workspace/skills/{skillKey}`。

### Java 后端新增或修改

- Modify: `backend/pom.xml`：加入 `agent-hub-skill` module。
- Create: `backend/agent-hub-skill/pom.xml`：Skill 模块 Maven 配置。
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/**`：实体、Mapper、DTO、Service、Controller、sandbox client DTO。
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/**`：服务与运行时测试。
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/SandboxClient.java`：新增 Skill 校验和同步方法。
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/HttpSandboxClient.java`：调用 sandbox-service 内部接口。
- Test: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/client/HttpSandboxClientTest.java`：解析校验和同步响应。
- Modify: `backend/agent-hub-server/pom.xml`：依赖 `agent-hub-skill`。
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_package.sql`：Skill 表、菜单和权限。
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/skill/SkillMigrationSmokeTest.java`：表、约束、权限、Mapper 装配。
- Modify: `backend/agent-hub-chat/pom.xml`：依赖 `agent-hub-skill`。
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`：注入 SkillRuntimeService，聊天前拼接 Skill prompt 并同步 sandbox。
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`：运行时注入和同步失败。

### admin-web 新增或修改

- Create: `admin-web/src/types/skill.ts`：Skill 类型定义。
- Create: `admin-web/src/api/skill.ts`：Skill API。
- Modify: `admin-web/src/router/route-registry.ts`：新增 `/agent/skills` 路由。
- Create: `admin-web/src/views/SkillsView.vue`：Skill 库页面。
- Modify: `admin-web/src/views/AgentsView.vue`：Agent Skill 绑定入口。
- Test: `admin-web/tests/skills.spec.ts`：源码级路由、API、页面行为断言。
- Test: `admin-web/tests/agent-skill-binding.spec.ts`：Agent 页面包含 Skill 绑定 API 与 UI 入口。

---

### Task 1: sandbox-service Skill zip 校验核心

**Files:**
- Create: `sandbox-service/internal/skill/package.go`
- Test: `sandbox-service/internal/skill/package_test.go`

- [ ] **Step 1: 写失败测试覆盖有效 zip 和缺失 SKILL.md**

Create `sandbox-service/internal/skill/package_test.go`:

```go
package skill

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestValidateZipAcceptsSingleSkillAtRoot(t *testing.T) {
	zipPath := writeZip(t, map[string]string{
		"SKILL.md":           "# 公文写作\n\n先确认文种和受众。",
		"examples/input.txt": "通知写作样例",
	})
	result, err := ValidateZip(ValidateZipRequest{
		ZipPath: zipPath,
		Limits:  DefaultLimits(),
	})

	if err != nil {
		t.Fatalf("ValidateZip returned error: %v", err)
	}
	if !result.Success {
		t.Fatalf("ValidateZip success = false, errors = %#v", result.Errors)
	}
	if result.RootDir != "" {
		t.Fatalf("RootDir = %q, want empty root", result.RootDir)
	}
	if result.SkillMDPath != "SKILL.md" {
		t.Fatalf("SkillMDPath = %q", result.SkillMDPath)
	}
	if result.FileCount != 2 {
		t.Fatalf("FileCount = %d", result.FileCount)
	}
}

func TestValidateZipAcceptsSingleTopDirectory(t *testing.T) {
	zipPath := writeZip(t, map[string]string{
		"writing-skill/SKILL.md":           "# 写作 Skill",
		"writing-skill/assets/template.md": "模板",
	})
	result, err := ValidateZip(ValidateZipRequest{
		ZipPath: zipPath,
		Limits:  DefaultLimits(),
	})

	if err != nil {
		t.Fatalf("ValidateZip returned error: %v", err)
	}
	if result.RootDir != "writing-skill" {
		t.Fatalf("RootDir = %q", result.RootDir)
	}
	if result.SkillMDPath != "SKILL.md" {
		t.Fatalf("SkillMDPath = %q", result.SkillMDPath)
	}
}

func TestValidateZipRejectsMissingSkillMD(t *testing.T) {
	zipPath := writeZip(t, map[string]string{
		"README.md": "说明",
	})
	result, err := ValidateZip(ValidateZipRequest{
		ZipPath: zipPath,
		Limits:  DefaultLimits(),
	})

	if err != nil {
		t.Fatalf("ValidateZip returned unexpected error: %v", err)
	}
	if result.Success {
		t.Fatalf("ValidateZip success = true, want false")
	}
	if result.Errors[0].Code != "MISSING_SKILL_MD" {
		t.Fatalf("error code = %q", result.Errors[0].Code)
	}
}

func writeZip(t *testing.T, files map[string]string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "skill.zip")
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, content := range files {
		entry, err := writer.Create(name)
		if err != nil {
			t.Fatalf("Create zip entry %s: %v", name, err)
		}
		if _, err := entry.Write([]byte(content)); err != nil {
			t.Fatalf("Write zip entry %s: %v", name, err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("Close zip: %v", err)
	}
	if err := os.WriteFile(path, buffer.Bytes(), 0o644); err != nil {
		t.Fatalf("Write zip file: %v", err)
	}
	return path
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
go test ./internal/skill
```

Workdir: `sandbox-service`

Expected: FAIL，原因是 `sandbox-service/internal/skill` 包不存在。

- [ ] **Step 3: 实现最小校验结构和成功路径**

Create `sandbox-service/internal/skill/package.go`:

```go
package skill

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type Limits struct {
	MaxZipBytes          int64 `json:"maxZipBytes"`
	MaxUncompressedBytes int64 `json:"maxUncompressedBytes"`
	MaxFileCount         int   `json:"maxFileCount"`
	MaxDepth             int   `json:"maxDepth"`
	MaxSingleFileBytes   int64 `json:"maxSingleFileBytes"`
	MaxSkillMDBytes      int64 `json:"maxSkillMdBytes"`
}

type ValidateZipRequest struct {
	ZipPath string `json:"-"`
	Limits  Limits `json:"limits"`
}

type ValidationError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type FileManifest struct {
	RelativePath string `json:"relativePath"`
	SizeBytes    int64  `json:"sizeBytes"`
	SHA256       string `json:"sha256,omitempty"`
	Directory    bool   `json:"directory"`
}

type ValidateZipResult struct {
	Success                bool              `json:"success"`
	RootDir                string            `json:"rootDir"`
	SkillMDPath            string            `json:"skillMdPath"`
	SkillMDContent         string            `json:"skillMdContent"`
	ZipSHA256              string            `json:"zipSha256"`
	TotalUncompressedBytes int64             `json:"totalUncompressedBytes"`
	FileCount              int               `json:"fileCount"`
	Files                  []FileManifest    `json:"files"`
	Errors                 []ValidationError `json:"errors"`
}

func DefaultLimits() Limits {
	return Limits{
		MaxZipBytes:          500 * 1024 * 1024,
		MaxUncompressedBytes: 1024 * 1024 * 1024,
		MaxFileCount:         3000,
		MaxDepth:             16,
		MaxSingleFileBytes:   200 * 1024 * 1024,
		MaxSkillMDBytes:      512 * 1024,
	}
}

func DownloadZip(ctx context.Context, downloadURL string, limits Limits) (string, func(), error) {
	if strings.TrimSpace(downloadURL) == "" {
		return "", func() {}, errors.New("downloadUrl 不能为空")
	}
	limits = withDefaultLimits(limits)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return "", func() {}, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", func() {}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", func() {}, errors.New("下载 Skill 包失败")
	}
	tmpFile, err := os.CreateTemp("", "agent-hub-skill-*.zip")
	if err != nil {
		return "", func() {}, err
	}
	cleanup := func() {
		_ = os.Remove(tmpFile.Name())
	}
	_, copyErr := io.Copy(tmpFile, io.LimitReader(resp.Body, limits.MaxZipBytes+1))
	closeErr := tmpFile.Close()
	if copyErr != nil {
		cleanup()
		return "", func() {}, copyErr
	}
	if closeErr != nil {
		cleanup()
		return "", func() {}, closeErr
	}
	info, err := os.Stat(tmpFile.Name())
	if err != nil {
		cleanup()
		return "", func() {}, err
	}
	if info.Size() > limits.MaxZipBytes {
		cleanup()
		return "", func() {}, errors.New("Skill 包超过 500MB 限制")
	}
	return tmpFile.Name(), cleanup, nil
}

func ValidateZip(req ValidateZipRequest) (ValidateZipResult, error) {
	limits := withDefaultLimits(req.Limits)
	info, err := os.Stat(req.ZipPath)
	if err != nil {
		return ValidateZipResult{}, err
	}
	if info.Size() > limits.MaxZipBytes {
		return failed("ZIP_TOO_LARGE", "Skill 包超过 500MB 限制"), nil
	}
	reader, err := zip.OpenReader(req.ZipPath)
	if err != nil {
		return failed("INVALID_ZIP", "Skill 包不是有效的 zip 文件"), nil
	}
	defer reader.Close()

	rootDir, rootOK := detectRoot(reader.File)
	if !rootOK {
		return failed("INVALID_ROOT", "Skill 包目录结构不符合规范"), nil
	}

	result := ValidateZipResult{
		Success:   true,
		RootDir:   rootDir,
		ZipSHA256: sha256File(req.ZipPath),
		Files:     make([]FileManifest, 0, len(reader.File)),
	}
	for _, file := range reader.File {
		relative, include, validationErr := normalizeEntry(file.Name, rootDir, limits)
		if validationErr != nil {
			return failed(validationErr.Code, validationErr.Message), nil
		}
		if !include {
			continue
		}
		if file.FileInfo().Mode()&os.ModeSymlink != 0 {
			return failed("UNSAFE_PATH", "Skill 包包含不安全路径"), nil
		}
		if file.FileInfo().Mode().Type() != 0 && !file.FileInfo().IsDir() {
			return failed("UNSAFE_FILE_TYPE", "Skill 包包含不支持的文件类型"), nil
		}
		result.FileCount++
		if result.FileCount > limits.MaxFileCount {
			return failed("TOO_MANY_FILES", "Skill 包文件数量超过限制"), nil
		}
		size := int64(file.UncompressedSize64)
		if size > limits.MaxSingleFileBytes {
			return failed("SINGLE_FILE_TOO_LARGE", "Skill 包内单文件超过 200MB 限制"), nil
		}
		result.TotalUncompressedBytes += size
		if result.TotalUncompressedBytes > limits.MaxUncompressedBytes {
			return failed("UNCOMPRESSED_TOO_LARGE", "Skill 包解压后总大小超过 1GB 限制"), nil
		}
		manifest := FileManifest{
			RelativePath: relative,
			SizeBytes:    size,
			Directory:    file.FileInfo().IsDir(),
		}
		if !manifest.Directory {
			hash, content, err := readEntry(file, relative == "SKILL.md", limits.MaxSkillMDBytes)
			if err != nil {
				var validationErr ValidationError
				if errors.As(err, &validationErr) {
					return failed(validationErr.Code, validationErr.Message), nil
				}
				return ValidateZipResult{}, err
			}
			manifest.SHA256 = hash
			if relative == "SKILL.md" {
				result.SkillMDPath = "SKILL.md"
				result.SkillMDContent = content
			}
		}
		result.Files = append(result.Files, manifest)
	}
	if result.SkillMDPath == "" {
		return failed("MISSING_SKILL_MD", "Skill 包缺少 SKILL.md"), nil
	}
	sort.Slice(result.Files, func(i, j int) bool {
		return result.Files[i].RelativePath < result.Files[j].RelativePath
	})
	return result, nil
}

func detectRoot(files []*zip.File) (string, bool) {
	topLevel := map[string]bool{}
	hasRootSkill := false
	for _, file := range files {
		name := filepath.ToSlash(strings.Trim(file.Name, "/"))
		if name == "" {
			continue
		}
		parts := strings.Split(name, "/")
		if len(parts) == 1 && parts[0] == "SKILL.md" {
			hasRootSkill = true
		}
		topLevel[parts[0]] = true
	}
	if hasRootSkill {
		return "", true
	}
	if len(topLevel) != 1 {
		return "", false
	}
	for name := range topLevel {
		return name, true
	}
	return "", false
}

func normalizeEntry(name string, rootDir string, limits Limits) (string, bool, *ValidationError) {
	normalized := filepath.ToSlash(strings.TrimSpace(name))
	if normalized == "" || strings.HasPrefix(normalized, "/") {
		return "", false, &ValidationError{Code: "UNSAFE_PATH", Message: "Skill 包包含不安全路径"}
	}
	clean := filepath.ToSlash(filepath.Clean(normalized))
	if clean == "." || strings.HasPrefix(clean, "../") || clean == ".." || strings.Contains(clean, "/../") {
		return "", false, &ValidationError{Code: "UNSAFE_PATH", Message: "Skill 包包含不安全路径"}
	}
	if rootDir != "" {
		prefix := rootDir + "/"
		if clean == rootDir {
			return "", false, nil
		}
		if !strings.HasPrefix(clean, prefix) {
			return "", false, &ValidationError{Code: "INVALID_ROOT", Message: "Skill 包目录结构不符合规范"}
		}
		clean = strings.TrimPrefix(clean, prefix)
	}
	if clean == "" || clean == "." {
		return "", false, nil
	}
	if depth(clean) > limits.MaxDepth {
		return "", false, &ValidationError{Code: "TOO_DEEP", Message: "Skill 包目录深度超过限制"}
	}
	return clean, true, nil
}

func readEntry(file *zip.File, skillMD bool, maxSkillMDBytes int64) (string, string, error) {
	reader, err := file.Open()
	if err != nil {
		return "", "", err
	}
	defer reader.Close()
	hash := sha256.New()
	var contentBuilder strings.Builder
	if skillMD {
		limited := io.LimitReader(reader, maxSkillMDBytes+1)
		data, err := io.ReadAll(limited)
		if err != nil {
			return "", "", err
		}
		if int64(len(data)) > maxSkillMDBytes {
			return "", "", ValidationError{Code: "SKILL_MD_TOO_LARGE", Message: "SKILL.md 超过 512KB 限制"}
		}
		hash.Write(data)
		contentBuilder.WriteString(string(data))
		return hex.EncodeToString(hash.Sum(nil)), contentBuilder.String(), nil
	}
	if _, err := io.Copy(hash, reader); err != nil {
		return "", "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), "", nil
}

func failed(code string, message string) ValidateZipResult {
	return ValidateZipResult{
		Success: false,
		Errors:  []ValidationError{{Code: code, Message: message}},
	}
}

func withDefaultLimits(limits Limits) Limits {
	defaults := DefaultLimits()
	if limits.MaxZipBytes <= 0 {
		limits.MaxZipBytes = defaults.MaxZipBytes
	}
	if limits.MaxUncompressedBytes <= 0 {
		limits.MaxUncompressedBytes = defaults.MaxUncompressedBytes
	}
	if limits.MaxFileCount <= 0 {
		limits.MaxFileCount = defaults.MaxFileCount
	}
	if limits.MaxDepth <= 0 {
		limits.MaxDepth = defaults.MaxDepth
	}
	if limits.MaxSingleFileBytes <= 0 {
		limits.MaxSingleFileBytes = defaults.MaxSingleFileBytes
	}
	if limits.MaxSkillMDBytes <= 0 {
		limits.MaxSkillMDBytes = defaults.MaxSkillMDBytes
	}
	return limits
}

func depth(path string) int {
	if path == "" {
		return 0
	}
	return len(strings.Split(path, "/"))
}

func sha256File(path string) string {
	file, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return ""
	}
	return hex.EncodeToString(hash.Sum(nil))
}

func (e ValidationError) Error() string {
	return e.Message
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
go test ./internal/skill
```

Workdir: `sandbox-service`

Expected: PASS。

- [ ] **Step 5: 增加安全和限制测试**

Append to `sandbox-service/internal/skill/package_test.go`:

```go
func TestValidateZipRejectsZipSlip(t *testing.T) {
	zipPath := writeZip(t, map[string]string{
		"../evil.txt": "bad",
		"SKILL.md":    "# ok",
	})
	result, err := ValidateZip(ValidateZipRequest{ZipPath: zipPath, Limits: DefaultLimits()})

	if err != nil {
		t.Fatalf("ValidateZip returned unexpected error: %v", err)
	}
	if result.Success {
		t.Fatalf("ValidateZip success = true, want false")
	}
	if result.Errors[0].Code != "UNSAFE_PATH" {
		t.Fatalf("error code = %q", result.Errors[0].Code)
	}
}

func TestValidateZipRejectsTooManyFiles(t *testing.T) {
	zipPath := writeZip(t, map[string]string{
		"SKILL.md": "ok",
		"a.txt":    "a",
		"b.txt":    "b",
	})
	limits := DefaultLimits()
	limits.MaxFileCount = 2
	result, err := ValidateZip(ValidateZipRequest{ZipPath: zipPath, Limits: limits})

	if err != nil {
		t.Fatalf("ValidateZip returned unexpected error: %v", err)
	}
	if result.Success {
		t.Fatalf("ValidateZip success = true, want false")
	}
	if result.Errors[0].Code != "TOO_MANY_FILES" {
		t.Fatalf("error code = %q", result.Errors[0].Code)
	}
}

func TestValidateZipRejectsLargeSkillMD(t *testing.T) {
	zipPath := writeZip(t, map[string]string{
		"SKILL.md": "123456",
	})
	limits := DefaultLimits()
	limits.MaxSkillMDBytes = 5
	result, err := ValidateZip(ValidateZipRequest{ZipPath: zipPath, Limits: limits})

	if err != nil {
		t.Fatalf("ValidateZip returned unexpected error: %v", err)
	}
	if result.Success {
		t.Fatalf("ValidateZip success = true, want false")
	}
	if result.Errors[0].Code != "SKILL_MD_TOO_LARGE" {
		t.Fatalf("error code = %q", result.Errors[0].Code)
	}
}
```

- [ ] **Step 6: 运行 sandbox-service Skill 测试**

Run:

```powershell
go test ./internal/skill
```

Workdir: `sandbox-service`

Expected: PASS。

- [ ] **Step 7: 提交**

Run:

```powershell
git add sandbox-service/internal/skill
git commit -m "新增 Skill 包校验核心"
```

---

### Task 2: sandbox-service Skill 内部 HTTP 接口与同步

**Files:**
- Modify: `sandbox-service/internal/skill/package.go`
- Modify: `sandbox-service/internal/agent/files.go`
- Modify: `sandbox-service/internal/agent/server.go`
- Test: `sandbox-service/internal/agent/server_test.go`
- Modify: `sandbox-service/internal/host/api.go`
- Test: `sandbox-service/internal/host/api_test.go`

- [ ] **Step 1: 写 agent 侧 HTTP 测试**

Append to `sandbox-service/internal/agent/server_test.go`:

```go
func TestSkillValidateEndpointRejectsMissingSkillMD(t *testing.T) {
	root := t.TempDir()
	zipPath := writeAgentTestZip(t, map[string]string{
		"README.md": "说明",
	})
	server := NewServer(nil, NewFileService(root))
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/skills/validate-zip", strings.NewReader(`{"zipPath":"`+filepath.ToSlash(zipPath)+`"}`))

	server.Router().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "MISSING_SKILL_MD") {
		t.Fatalf("body = %s", recorder.Body.String())
	}
}

func TestSkillSyncEndpointExtractsSkillToWorkspace(t *testing.T) {
	root := t.TempDir()
	zipPath := writeAgentTestZip(t, map[string]string{
		"my-skill/SKILL.md":           "# 写作",
		"my-skill/assets/template.md": "模板",
	})
	server := NewServer(nil, NewFileService(root))
	body := `{"skills":[{"skillKey":"writing","versionId":7,"zipPath":"` + filepath.ToSlash(zipPath) + `","zipSha256":"abc"}],"targetRoot":"/workspace/skills"}`
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/skills/sync-to-session", strings.NewReader(body))

	server.Router().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	content, err := os.ReadFile(filepath.Join(root, "skills", "writing", "SKILL.md"))
	if err != nil {
		t.Fatalf("read synced SKILL.md: %v", err)
	}
	if !strings.Contains(string(content), "# 写作") {
		t.Fatalf("content = %s", string(content))
	}
}
```

Also add helper imports and helper function in the same file if missing:

```go
import (
	"archive/zip"
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeAgentTestZip(t *testing.T, files map[string]string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "skill.zip")
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, content := range files {
		entry, err := writer.Create(name)
		if err != nil {
			t.Fatalf("create entry %s: %v", name, err)
		}
		if _, err := entry.Write([]byte(content)); err != nil {
			t.Fatalf("write entry %s: %v", name, err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close zip: %v", err)
	}
	if err := os.WriteFile(path, buffer.Bytes(), 0o644); err != nil {
		t.Fatalf("write zip: %v", err)
	}
	return path
}
```

- [ ] **Step 2: 运行 agent 测试确认失败**

Run:

```powershell
go test ./internal/agent
```

Workdir: `sandbox-service`

Expected: FAIL，原因是 `/skills/validate-zip` 和 `/skills/sync-to-session` 路由不存在。

- [ ] **Step 3: 实现同步解包方法**

Append to `sandbox-service/internal/skill/package.go`:

```go
type SyncSkill struct {
	SkillKey    string `json:"skillKey"`
	VersionID   int64  `json:"versionId"`
	ZipPath     string `json:"zipPath"`
	DownloadURL string `json:"downloadUrl"`
	ZIPSHA256   string `json:"zipSha256"`
}

type SyncRequest struct {
	SandboxSessionID string      `json:"sandboxSessionId"`
	Skills           []SyncSkill `json:"skills"`
	TargetRoot       string      `json:"targetRoot"`
	Limits           Limits      `json:"limits"`
}

type SyncResult struct {
	Success bool              `json:"success"`
	Synced  []string          `json:"synced"`
	Skipped []string          `json:"skipped"`
	Errors  []ValidationError `json:"errors"`
}

func SyncToWorkspace(req SyncRequest, workspaceRoot string) (SyncResult, error) {
	limits := withDefaultLimits(req.Limits)
	targetRoot := strings.TrimSpace(req.TargetRoot)
	if targetRoot == "" {
		targetRoot = "/workspace/skills"
	}
	if targetRoot != "/workspace/skills" {
		return SyncResult{Success: false, Errors: []ValidationError{{Code: "INVALID_TARGET", Message: "Skill 只能同步到 /workspace/skills"}}}, nil
	}
	result := SyncResult{Success: true}
	for _, item := range req.Skills {
		key := safeSkillKey(item.SkillKey)
		if key == "" {
			return SyncResult{Success: false, Errors: []ValidationError{{Code: "INVALID_SKILL_KEY", Message: "Skill 标识不正确"}}}, nil
		}
		target := filepath.Join(workspaceRoot, "skills", key)
		marker := filepath.Join(target, ".agenthub-version")
		markerValue := itemMarker(item)
		if data, err := os.ReadFile(marker); err == nil && string(data) == markerValue {
			result.Skipped = append(result.Skipped, key)
			continue
		}
		zipPath := item.ZipPath
		cleanup := func() {}
		if strings.TrimSpace(zipPath) == "" {
			downloadedPath, cleanupFn, err := DownloadZip(context.Background(), item.DownloadURL, limits)
			if err != nil {
				return SyncResult{}, err
			}
			zipPath = downloadedPath
			cleanup = cleanupFn
		}
		defer cleanup()
		if err := os.RemoveAll(target); err != nil {
			return SyncResult{}, err
		}
		if err := os.MkdirAll(target, 0o755); err != nil {
			return SyncResult{}, err
		}
		validation, err := ValidateZip(ValidateZipRequest{ZipPath: zipPath, Limits: limits})
		if err != nil {
			return SyncResult{}, err
		}
		if !validation.Success {
			return SyncResult{Success: false, Errors: validation.Errors}, nil
		}
		if err := extractZip(zipPath, validation.RootDir, target, limits); err != nil {
			return SyncResult{}, err
		}
		if err := os.WriteFile(marker, []byte(markerValue), 0o644); err != nil {
			return SyncResult{}, err
		}
		result.Synced = append(result.Synced, key)
	}
	return result, nil
}

func extractZip(zipPath string, rootDir string, target string, limits Limits) error {
	reader, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer reader.Close()
	for _, file := range reader.File {
		relative, include, validationErr := normalizeEntry(file.Name, rootDir, limits)
		if validationErr != nil {
			return *validationErr
		}
		if !include {
			continue
		}
		destination := filepath.Join(target, filepath.FromSlash(relative))
		if !isInside(target, destination) {
			return ValidationError{Code: "UNSAFE_PATH", Message: "Skill 包包含不安全路径"}
		}
		if file.FileInfo().IsDir() {
			if err := os.MkdirAll(destination, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
			return err
		}
		reader, err := file.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
		if err != nil {
			reader.Close()
			return err
		}
		_, copyErr := io.Copy(out, reader)
		closeErr := out.Close()
		reader.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func safeSkillKey(value string) string {
	key := strings.TrimSpace(value)
	if key == "" {
		return ""
	}
	for _, ch := range key {
		if (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' {
			continue
		}
		return ""
	}
	return key
}

func itemMarker(item SyncSkill) string {
	return strconv.FormatInt(item.VersionID, 10) + "\n" + item.ZIPSHA256
}

func isInside(base string, target string) bool {
	rel, err := filepath.Rel(base, target)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}
```

Add `strconv` to imports in `package.go`.

- [ ] **Step 4: 实现 agent 侧路由**

Add a tiny accessor to `sandbox-service/internal/agent/files.go`:

```go
func (s *FileService) Root() string {
	return s.root
}
```

Modify `sandbox-service/internal/agent/server.go` imports:

```go
import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"strconv"
	"sync"

	"agent-hub/sandbox-service/internal/policy"
	"agent-hub/sandbox-service/internal/skill"
)
```

Add routes in `Router()`:

```go
mux.HandleFunc("POST /skills/validate-zip", s.handleSkillValidateZip)
mux.HandleFunc("POST /skills/sync-to-session", s.handleSkillSyncToSession)
```

Add handlers:

```go
func (s *Server) handleSkillValidateZip(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ZipPath string       `json:"zipPath"`
		Limits  skill.Limits `json:"limits"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	result, err := skill.ValidateZip(skill.ValidateZipRequest{
		ZipPath: req.ZipPath,
		Limits:  req.Limits,
	})
	if err != nil {
		writeError(w, http.StatusBadRequest, "校验 Skill 包失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleSkillSyncToSession(w http.ResponseWriter, r *http.Request) {
	var req skill.SyncRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	workspaceRoot := "/workspace"
	if s.files != nil {
		workspaceRoot = s.files.Root()
	}
	result, err := skill.SyncToWorkspace(req, filepath.Clean(workspaceRoot))
	if err != nil {
		writeError(w, http.StatusBadRequest, "同步 Skill 失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, result)
}
```

- [ ] **Step 5: 运行 agent 测试**

Run:

```powershell
go test ./internal/agent
```

Workdir: `sandbox-service`

Expected: PASS。

- [ ] **Step 6: 补 host API 转发测试**

Append route assertions to `sandbox-service/internal/host/api_test.go`:

```go
func TestAdminSkillRoutesAreRegistered(t *testing.T) {
	manager := NewSessionManager(SessionManagerOptions{})
	api := NewAPI(manager)
	router := api.Router()

	request := httptest.NewRequest(http.MethodPost, "/internal/skills/validate-zip", strings.NewReader(`{"downloadUrl":"http://example.test/skill.zip"}`))
	recorder := httptest.NewRecorder()

	router.ServeHTTP(recorder, request)

	if recorder.Code == http.StatusNotFound {
		t.Fatalf("validate route returned 404")
	}
}
```

- [ ] **Step 7: 实现 host API 内部路由**

Modify `sandbox-service/internal/host/api.go` to register:

```go
mux.HandleFunc("POST /internal/skills/validate-zip", api.handleSkillValidateZip)
mux.HandleFunc("POST /internal/skills/sync-to-session", api.handleSkillSyncToSession)
```

Add handlers that decode JSON, validate the zip through the host process, and proxy runtime sync to the target guest agent:

```go
func (s *apiServer) handleSkillValidateZip(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DownloadURL string       `json:"downloadUrl"`
		Limits      skill.Limits `json:"limits"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求体不是有效的 JSON"})
		return
	}
	zipPath, cleanup, err := skill.DownloadZip(r.Context(), req.DownloadURL, req.Limits)
	if err != nil {
		writeError(w, http.StatusBadRequest, "下载 Skill 包失败: "+err.Error())
		return
	}
	defer cleanup()
	result, err := skill.ValidateZip(skill.ValidateZipRequest{
		ZipPath: zipPath,
		Limits:  req.Limits,
	})
	if err != nil {
		writeError(w, http.StatusBadRequest, "校验 Skill 包失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *apiServer) handleSkillSyncToSession(w http.ResponseWriter, r *http.Request) {
	var req skill.SyncRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	session, ok := s.beginSessionUse(w, req.SandboxSessionID)
	if !ok {
		return
	}
	defer s.manager.EndUse(session.ID)
	body, err := json.Marshal(req)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "同步 Skill 失败")
		return
	}
	s.proxyJSON(w, r, http.MethodPost, joinAgentURL(session.AgentURL, "/skills/sync-to-session"), bytes.NewReader(body))
}
```

Import `agent-hub/sandbox-service/internal/skill` and `strings` if missing.

- [ ] **Step 8: 运行 sandbox-service 全量测试**

Run:

```powershell
go test ./...
```

Workdir: `sandbox-service`

Expected: PASS。

- [ ] **Step 9: 提交**

Run:

```powershell
git add sandbox-service
git commit -m "新增 Skill 包沙箱接口"
```

---

### Task 3: Java sandbox client 支持 Skill 校验和同步

**Files:**
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/SandboxClient.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/HttpSandboxClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillValidationRequest.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillValidationResult.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillSyncRequest.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillSyncResult.java`
- Test: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/client/HttpSandboxClientTest.java`

- [ ] **Step 1: 写 HttpSandboxClient 失败测试**

Append to `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/client/HttpSandboxClientTest.java`:

```java
@Test
void shouldParseSkillValidationResult() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "success": true,
                          "rootDir": "writing-skill",
                          "skillMdPath": "SKILL.md",
                          "skillMdContent": "# 写作",
                          "zipSha256": "abc",
                          "totalUncompressedBytes": 128,
                          "fileCount": 1,
                          "files": [
                            {"relativePath":"SKILL.md","sizeBytes":8,"sha256":"def","directory":false}
                          ],
                          "errors": []
                        }
                        """));
        server.start();
        HttpSandboxClient client = new HttpSandboxClient(properties(server), HttpClient.newHttpClient());

        SkillValidationResult result = client.validateSkillZip(new SkillValidationRequest(
                1L,
                "http://minio.test/skill.zip",
                SkillValidationRequest.Limits.defaults()
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.rootDir()).isEqualTo("writing-skill");
        assertThat(result.files()).hasSize(1);
    }
}

@Test
void shouldSendSkillSyncRequest() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"success":true,"synced":["writing"],"skipped":[],"errors":[]}
                        """));
        server.start();
        HttpSandboxClient client = new HttpSandboxClient(properties(server), HttpClient.newHttpClient());

        SkillSyncResult result = client.syncSkills(new SkillSyncRequest(
                "sbx_1",
                List.of(new SkillSyncRequest.Skill("writing", 7L, "http://minio.test/skill.zip", "abc")),
                "/workspace/skills"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.synced()).containsExactly("writing");
        assertThat(server.takeRequest().getPath()).isEqualTo("/internal/skills/sync-to-session");
    }
}
```

If `MockWebServer` is not already imported in that test file, add imports matching existing usage in the same file.

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-sandbox -Dtest=HttpSandboxClientTest test
```

Workdir: `backend`

Expected: FAIL，原因是 Skill DTO 和 client 方法不存在。

- [ ] **Step 3: 新增 DTO records**

Create `SkillValidationRequest.java`:

```java
package com.agenthub.sandbox.client.dto;

public record SkillValidationRequest(
        Long importId,
        String downloadUrl,
        Limits limits
) {
    public record Limits(
            long maxZipBytes,
            long maxUncompressedBytes,
            int maxFileCount,
            int maxDepth,
            long maxSingleFileBytes,
            long maxSkillMdBytes
    ) {
        public static Limits defaults() {
            return new Limits(
                    500L * 1024 * 1024,
                    1024L * 1024 * 1024,
                    3000,
                    16,
                    200L * 1024 * 1024,
                    512L * 1024
            );
        }
    }
}
```

Create `SkillValidationResult.java`:

```java
package com.agenthub.sandbox.client.dto;

import java.util.List;

public record SkillValidationResult(
        boolean success,
        String rootDir,
        String skillMdPath,
        String skillMdContent,
        String zipSha256,
        long totalUncompressedBytes,
        int fileCount,
        List<FileItem> files,
        List<ValidationError> errors
) {
    public record FileItem(
            String relativePath,
            long sizeBytes,
            String sha256,
            boolean directory
    ) {
    }

    public record ValidationError(
            String code,
            String message
    ) {
    }
}
```

Create `SkillSyncRequest.java`:

```java
package com.agenthub.sandbox.client.dto;

import java.util.List;

public record SkillSyncRequest(
        String sandboxSessionId,
        List<Skill> skills,
        String targetRoot
) {
    public record Skill(
            String skillKey,
            Long versionId,
            String downloadUrl,
            String zipSha256
    ) {
    }
}
```

Create `SkillSyncResult.java`:

```java
package com.agenthub.sandbox.client.dto;

import java.util.List;

public record SkillSyncResult(
        boolean success,
        List<String> synced,
        List<String> skipped,
        List<SkillValidationResult.ValidationError> errors
) {
}
```

- [ ] **Step 4: 扩展 SandboxClient**

Modify `SandboxClient.java`:

```java
SkillValidationResult validateSkillZip(SkillValidationRequest request);

SkillSyncResult syncSkills(SkillSyncRequest request);
```

Add imports:

```java
import com.agenthub.sandbox.client.dto.SkillSyncRequest;
import com.agenthub.sandbox.client.dto.SkillSyncResult;
import com.agenthub.sandbox.client.dto.SkillValidationRequest;
import com.agenthub.sandbox.client.dto.SkillValidationResult;
```

- [ ] **Step 5: 实现 HttpSandboxClient 方法**

Add to `HttpSandboxClient.java`:

```java
@Override
public SkillValidationResult validateSkillZip(SkillValidationRequest request) {
    try {
        HttpRequest httpRequest = requestBuilder("/internal/skills/validate-zip")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(request)))
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "Skill 包校验失败");
        return OBJECT_MAPPER.readValue(response.body(), SkillValidationResult.class);
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Skill 包校验被中断");
    } catch (IOException exception) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Skill 包校验失败");
    }
}

@Override
public SkillSyncResult syncSkills(SkillSyncRequest request) {
    try {
        HttpRequest httpRequest = requestBuilder("/internal/skills/sync-to-session")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(request)))
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "同步 Skill 到沙箱失败");
        return OBJECT_MAPPER.readValue(response.body(), SkillSyncResult.class);
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 到沙箱被中断");
    } catch (IOException exception) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 到沙箱失败");
    }
}
```

- [ ] **Step 6: 运行测试**

Run:

```powershell
mvn -pl agent-hub-sandbox -Dtest=HttpSandboxClientTest test
```

Workdir: `backend`

Expected: PASS。

- [ ] **Step 7: 提交**

Run:

```powershell
git add backend/agent-hub-sandbox
git commit -m "扩展沙箱 Skill 客户端"
```

---

### Task 4: 新增 agent-hub-skill 模块和数据库迁移

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-hub-skill/pom.xml`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/entity/SkillPackage.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/entity/SkillVersion.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/entity/SkillFileManifest.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/entity/AgentSkillBinding.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/mapper/SkillPackageMapper.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/mapper/SkillVersionMapper.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/mapper/SkillFileManifestMapper.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/mapper/AgentSkillBindingMapper.java`
- Modify: `backend/agent-hub-server/pom.xml`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_package.sql`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/skill/SkillMigrationSmokeTest.java`

- [ ] **Step 1: 写迁移 smoke test**

Create `SkillMigrationSmokeTest.java`:

```java
package com.agenthub.server.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.skill.binding.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.packageinfo.mapper.SkillFileManifestMapper;
import com.agenthub.skill.packageinfo.mapper.SkillPackageMapper;
import com.agenthub.skill.packageinfo.mapper.SkillVersionMapper;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:skill_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class SkillMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SkillPackageMapper packageMapper;

    @Autowired
    private SkillVersionMapper versionMapper;

    @Autowired
    private SkillFileManifestMapper fileMapper;

    @Autowired
    private AgentSkillBindingMapper bindingMapper;

    @Test
    void shouldCreateSkillTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('skill_package', 'skill_version', 'skill_file_manifest', 'agent_skill_binding')
                ORDER BY table_name
                """, String.class);

        assertThat(tableNames).containsExactly(
                "agent_skill_binding",
                "skill_file_manifest",
                "skill_package",
                "skill_version"
        );
    }

    @Test
    void shouldRegisterSkillMappers() {
        assertThat(packageMapper).isNotNull();
        assertThat(versionMapper).isNotNull();
        assertThat(fileMapper).isNotNull();
        assertThat(bindingMapper).isNotNull();
    }

    @Test
    void shouldRegisterSkillMenuAndPermissions() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer menuCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE name = 'skillLibrary'
                    AND path = '/agent/skills'
                    AND permission = 'skill:package:list'
                """, Integer.class);

        assertThat(menuCount).isEqualTo(1);

        List<String> permissions = jdbcTemplate.queryForList("""
                SELECT sm.permission
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.permission IN (
                        'skill:package:list',
                        'skill:package:create',
                        'skill:package:update',
                        'skill:package:upload',
                        'skill:package:enable',
                        'skill:version:activate',
                        'skill:version:read',
                        'agent:skill:update'
                    )
                ORDER BY sm.permission
                """, String.class);

        assertThat(permissions).containsExactly(
                "agent:skill:update",
                "skill:package:create",
                "skill:package:enable",
                "skill:package:list",
                "skill:package:update",
                "skill:package:upload",
                "skill:version:activate",
                "skill:version:read"
        );
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-server -Dtest=SkillMigrationSmokeTest test
```

Workdir: `backend`

Expected: FAIL，原因是 `agent-hub-skill` 模块、Mapper 和迁移不存在。

- [ ] **Step 3: 新增 Maven module**

Modify `backend/pom.xml` modules, insert after `agent-hub-agent`:

```xml
<module>agent-hub-skill</module>
```

Create `backend/agent-hub-skill/pom.xml`:

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

    <artifactId>agent-hub-skill</artifactId>
    <name>agent-hub-skill</name>

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
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-agent</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-sandbox</artifactId>
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
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Modify `backend/agent-hub-server/pom.xml`, add:

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-skill</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: 新增实体和 Mapper**

Create entity classes with `@TableName` and `@TableId(type = IdType.AUTO)`. Use JavaBean getters and setters like existing modules.

Required fields:

`SkillPackage`:

```java
private Long id;
private String skillKey;
private String name;
private String description;
private Boolean enabled;
private Long currentVersionId;
private Long createdBy;
private OffsetDateTime createdAt;
private Long updatedBy;
private OffsetDateTime updatedAt;
```

`SkillVersion`:

```java
private Long id;
private Long packageId;
private Integer versionNo;
private String originalFilename;
private String bucketName;
private String objectKey;
private String zipSha256;
private String status;
private String rootDir;
private String skillMdPath;
private String skillMdContent;
private Integer fileCount;
private Long zipSizeBytes;
private Long uncompressedSizeBytes;
private String errorMessage;
private OffsetDateTime validationStartedAt;
private OffsetDateTime validationCompletedAt;
private Long createdBy;
private OffsetDateTime createdAt;
private Long updatedBy;
private OffsetDateTime updatedAt;
```

`SkillFileManifest`:

```java
private Long id;
private Long versionId;
private String relativePath;
private Boolean directory;
private Long sizeBytes;
private String sha256;
private OffsetDateTime createdAt;
```

`AgentSkillBinding`:

```java
private Long id;
private Long agentId;
private Long skillId;
private Long versionId;
private Boolean enabled;
private Integer sort;
private Long createdBy;
private OffsetDateTime createdAt;
private Long updatedBy;
private OffsetDateTime updatedAt;
```

Create Mapper interfaces extending `BaseMapper<T>`:

```java
@Mapper
public interface SkillPackageMapper extends BaseMapper<SkillPackage> {
}
```

Repeat for version, file manifest and binding.

- [ ] **Step 5: 新增 migration**

Create `V12__init_skill_package.sql` using the SQL from the design spec, plus indexes:

```sql
CREATE INDEX idx_skill_package_enabled_sort ON skill_package (enabled, id);
CREATE INDEX idx_skill_version_package_status ON skill_version (package_id, status, version_no);
CREATE INDEX idx_skill_file_version_path ON skill_file_manifest (version_id, relative_path);
CREATE INDEX idx_agent_skill_agent_sort ON agent_skill_binding (agent_id, enabled, sort, id);
```

Insert menu under existing `agent` directory:

```sql
INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT parent.id, 'skillLibrary', 'Skill 库', '/agent/skills', 'agent/skills/index', 'FolderOpenOutlined', 'skill:package:list', 'MENU', TRUE, 2, 1, 1
FROM sys_menu parent
WHERE parent.parent_id = 0
    AND parent.name = 'agent'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu child
        WHERE child.parent_id = parent.id
            AND child.name = 'skillLibrary'
    );
```

Add child button permissions with `menu_type='BUTTON'` and permissions from the smoke test. Grant all new menus to ADMIN via `sys_role_menu`.

- [ ] **Step 6: 运行迁移测试**

Run:

```powershell
mvn -pl agent-hub-server -am -Dtest=SkillMigrationSmokeTest test
```

Workdir: `backend`

Expected: PASS。

- [ ] **Step 7: 提交**

Run:

```powershell
git add backend/pom.xml backend/agent-hub-skill backend/agent-hub-server/pom.xml backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_package.sql backend/agent-hub-server/src/test/java/com/agenthub/server/skill/SkillMigrationSmokeTest.java
git commit -m "新增 Skill 包数据模型"
```

---

### Task 5: Skill 上传、确认上传和异步校验服务

**Files:**
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/dto/SkillUploadUrl.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/dto/SkillPackageItem.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/dto/SkillVersionItem.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/dto/SkillFileItem.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/command/CreateSkillUploadCommand.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/service/SkillPackageService.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/service/impl/SkillPackageServiceImpl.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/controller/SkillPackageController.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/packageinfo/SkillPackageServiceImplTest.java`

- [ ] **Step 1: 写上传服务失败测试**

Create `SkillPackageServiceImplTest.java`:

```java
package com.agenthub.skill.packageinfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.skill.packageinfo.command.CreateSkillUploadCommand;
import com.agenthub.skill.packageinfo.dto.SkillUploadUrl;
import com.agenthub.skill.packageinfo.entity.SkillPackage;
import com.agenthub.skill.packageinfo.entity.SkillVersion;
import com.agenthub.skill.packageinfo.mapper.SkillFileManifestMapper;
import com.agenthub.skill.packageinfo.mapper.SkillPackageMapper;
import com.agenthub.skill.packageinfo.mapper.SkillVersionMapper;
import com.agenthub.skill.packageinfo.service.impl.SkillPackageServiceImpl;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillPackageServiceImplTest {

    @Test
    void shouldCreateUploadUrlForZip() {
        SkillPackageMapper packageMapper = mock(SkillPackageMapper.class);
        SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setBucket("agent-hub");
        when(objectStorageClient.presignPut(any(), any(), any(Duration.class))).thenReturn("http://upload.test");
        when(packageMapper.insert(any(SkillPackage.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SkillPackage.class).setId(10L);
            return 1;
        });
        when(versionMapper.insert(any(SkillVersion.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SkillVersion.class).setId(20L);
            return 1;
        });
        SkillPackageServiceImpl service = service(packageMapper, versionMapper, mock(SkillFileManifestMapper.class),
                objectStorageClient, storageProperties);

        SkillUploadUrl uploadUrl = service.createUploadUrl(1L, new CreateSkillUploadCommand(
                "official-writing-style",
                "公文写作规范",
                "公文模板和规则",
                "writing.zip",
                "application/zip",
                1024L
        ));

        assertThat(uploadUrl.packageId()).isEqualTo(10L);
        assertThat(uploadUrl.versionId()).isEqualTo(20L);
        assertThat(uploadUrl.uploadUrl()).isEqualTo("http://upload.test");
    }

    @Test
    void shouldRejectNonZipUpload() {
        SkillPackageServiceImpl service = service(mock(SkillPackageMapper.class), mock(SkillVersionMapper.class),
                mock(SkillFileManifestMapper.class), mock(ObjectStorageClient.class), new StorageProperties());

        assertThatThrownBy(() -> service.createUploadUrl(1L, new CreateSkillUploadCommand(
                "bad",
                "错误包",
                "",
                "bad.txt",
                "text/plain",
                100L
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅支持上传 zip 格式的 Skill 包")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldConfirmUploadAndMarkValidating() {
        SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        SkillVersion version = new SkillVersion();
        version.setId(20L);
        version.setPackageId(10L);
        version.setObjectKey("skill-packages/1/writing.zip");
        version.setStatus("UPLOADED");
        when(versionMapper.selectById(20L)).thenReturn(version);
        when(objectStorageClient.stat("skill-packages/1/writing.zip"))
                .thenReturn(new ObjectMetadata(4096L, "application/zip"));
        ArgumentCaptor<SkillVersion> captor = ArgumentCaptor.forClass(SkillVersion.class);
        SkillPackageServiceImpl service = service(mock(SkillPackageMapper.class), versionMapper,
                mock(SkillFileManifestMapper.class), objectStorageClient, new StorageProperties());

        service.confirmUpload(1L, 10L, 20L);

        verify(versionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("VALIDATING");
        assertThat(captor.getValue().getZipSizeBytes()).isEqualTo(4096L);
    }

    private SkillPackageServiceImpl service(
            SkillPackageMapper packageMapper,
            SkillVersionMapper versionMapper,
            SkillFileManifestMapper fileMapper,
            ObjectStorageClient objectStorageClient,
            StorageProperties storageProperties
    ) {
        return new SkillPackageServiceImpl(packageMapper, versionMapper, fileMapper,
                objectStorageClient, storageProperties, Runnable::run);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-skill -Dtest=SkillPackageServiceImplTest test
```

Workdir: `backend`

Expected: FAIL，原因是 DTO、command、service 不存在。

- [ ] **Step 3: 新增 DTO 和 command**

Create `CreateSkillUploadCommand.java`:

```java
package com.agenthub.skill.packageinfo.command;

public record CreateSkillUploadCommand(
        String skillKey,
        String name,
        String description,
        String filename,
        String contentType,
        Long fileSize
) {
}
```

Create `SkillUploadUrl.java`:

```java
package com.agenthub.skill.packageinfo.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record SkillUploadUrl(
        Long packageId,
        Long versionId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        OffsetDateTime expiresAt
) {
}
```

Create list/detail DTOs:

```java
public record SkillPackageItem(
        Long packageId,
        String skillKey,
        String name,
        String description,
        Boolean enabled,
        Long currentVersionId,
        String currentVersionStatus
) {
}
```

```java
public record SkillVersionItem(
        Long versionId,
        Long packageId,
        Integer versionNo,
        String status,
        String rootDir,
        String skillMdPath,
        String skillMdContent,
        Integer fileCount,
        Long zipSizeBytes,
        Long uncompressedSizeBytes,
        String errorMessage
) {
}
```

```java
public record SkillFileItem(
        Long fileId,
        Long versionId,
        String relativePath,
        Boolean directory,
        Long sizeBytes,
        String sha256
) {
}
```

- [ ] **Step 4: 实现 SkillPackageService 接口**

Create `SkillPackageService.java`:

```java
package com.agenthub.skill.packageinfo.service;

import com.agenthub.skill.packageinfo.command.CreateSkillUploadCommand;
import com.agenthub.skill.packageinfo.dto.SkillFileItem;
import com.agenthub.skill.packageinfo.dto.SkillPackageItem;
import com.agenthub.skill.packageinfo.dto.SkillUploadUrl;
import com.agenthub.skill.packageinfo.dto.SkillVersionItem;
import java.util.List;

public interface SkillPackageService {

    SkillUploadUrl createUploadUrl(Long userId, CreateSkillUploadCommand command);

    void confirmUpload(Long userId, Long packageId, Long versionId);

    List<SkillPackageItem> listPackages();

    List<SkillVersionItem> listVersions(Long packageId);

    SkillVersionItem requireVersion(Long versionId);

    List<SkillFileItem> listFiles(Long versionId);

    SkillPackageItem activateVersion(Long packageId, Long versionId);

    SkillPackageItem updateEnabled(Long packageId, boolean enabled);
}
```

- [ ] **Step 5: 实现 SkillPackageServiceImpl 最小上传流**

Create `SkillPackageServiceImpl.java` with constants:

```java
private static final String STATUS_UPLOADED = "UPLOADED";
private static final String STATUS_VALIDATING = "VALIDATING";
private static final long MAX_ZIP_BYTES = 500L * 1024 * 1024;
private static final Duration UPLOAD_TTL = Duration.ofMinutes(30);
```

Implement methods used by tests:

```java
@Override
@Transactional
public SkillUploadUrl createUploadUrl(Long userId, CreateSkillUploadCommand command) {
    requireZip(command.filename(), command.contentType(), command.fileSize());
    OffsetDateTime now = OffsetDateTime.now();
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setSkillKey(command.skillKey().strip());
    skillPackage.setName(command.name().strip());
    skillPackage.setDescription(command.description());
    skillPackage.setEnabled(true);
    skillPackage.setCreatedBy(userId);
    skillPackage.setUpdatedBy(userId);
    skillPackage.setCreatedAt(now);
    skillPackage.setUpdatedAt(now);
    packageMapper.insert(skillPackage);

    String objectKey = "skill-packages/" + skillPackage.getId() + "/" + UUID.randomUUID() + ".zip";
    SkillVersion version = new SkillVersion();
    version.setPackageId(skillPackage.getId());
    version.setVersionNo(1);
    version.setOriginalFilename(command.filename());
    version.setBucketName(storageProperties.getBucket());
    version.setObjectKey(objectKey);
    version.setStatus(STATUS_UPLOADED);
    version.setZipSizeBytes(command.fileSize());
    version.setFileCount(0);
    version.setUncompressedSizeBytes(0L);
    version.setCreatedBy(userId);
    version.setUpdatedBy(userId);
    version.setCreatedAt(now);
    version.setUpdatedAt(now);
    versionMapper.insert(version);

    String uploadUrl = objectStorageClient.presignPut(objectKey, "application/zip", UPLOAD_TTL);
    return new SkillUploadUrl(version.getPackageId(), version.getId(), uploadUrl, "PUT",
            Map.of("Content-Type", "application/zip"), now.plus(UPLOAD_TTL));
}
```

Implement `confirmUpload`:

```java
@Override
@Transactional
public void confirmUpload(Long userId, Long packageId, Long versionId) {
    SkillVersion version = requireVersionEntity(versionId);
    if (!packageId.equals(version.getPackageId()) || !STATUS_UPLOADED.equals(version.getStatus())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包状态不允许确认上传");
    }
    ObjectMetadata metadata = objectStorageClient.stat(version.getObjectKey());
    if (metadata.size() <= 0 || metadata.size() > MAX_ZIP_BYTES) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包大小超过 500MB 限制");
    }
    SkillVersion updated = new SkillVersion();
    updated.setId(versionId);
    updated.setStatus(STATUS_VALIDATING);
    updated.setZipSizeBytes(metadata.size());
    updated.setValidationStartedAt(OffsetDateTime.now());
    updated.setUpdatedBy(userId);
    updated.setUpdatedAt(OffsetDateTime.now());
    versionMapper.updateById(updated);
    validationExecutor.execute(() -> validateVersion(versionId));
}
```

`validateVersion` can initially reload version and no-op; Task 6 will connect sandbox validation:

```java
void validateVersion(Long versionId) {
    SkillVersion version = versionMapper.selectById(versionId);
    if (version == null) {
        return;
    }
}
```

Implement `requireZip`:

```java
private void requireZip(String filename, String contentType, Long fileSize) {
    String normalizedName = filename == null ? "" : filename.strip().toLowerCase(Locale.ROOT);
    String normalizedContentType = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
    if (!normalizedName.endsWith(".zip")
            || !Set.of("application/zip", "application/x-zip-compressed", "application/octet-stream")
                    .contains(normalizedContentType)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持上传 zip 格式的 Skill 包");
    }
    if (fileSize == null || fileSize <= 0 || fileSize > MAX_ZIP_BYTES) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包大小超过 500MB 限制");
    }
}
```

- [ ] **Step 6: 运行上传服务测试**

Run:

```powershell
mvn -pl agent-hub-skill -Dtest=SkillPackageServiceImplTest test
```

Workdir: `backend`

Expected: PASS。

- [ ] **Step 7: 实现查询、文件清单和版本激活方法**

Add mapper helper methods:

```java
@Select("""
        SELECT sp.id AS packageId,
               sp.skill_key AS skillKey,
               sp.name AS name,
               sp.description AS description,
               sp.enabled AS enabled,
               sp.current_version_id AS currentVersionId,
               sv.status AS currentVersionStatus
        FROM skill_package sp
        LEFT JOIN skill_version sv ON sv.id = sp.current_version_id
        ORDER BY sp.id DESC
        """)
List<SkillPackageItem> selectItems();
```

Add to `SkillVersionMapper`:

```java
@Select("""
        SELECT id AS versionId,
               package_id AS packageId,
               version_no AS versionNo,
               status,
               root_dir AS rootDir,
               skill_md_path AS skillMdPath,
               skill_md_content AS skillMdContent,
               file_count AS fileCount,
               zip_size_bytes AS zipSizeBytes,
               uncompressed_size_bytes AS uncompressedSizeBytes,
               error_message AS errorMessage
        FROM skill_version
        WHERE package_id = #{packageId}
        ORDER BY version_no DESC
        """)
List<SkillVersionItem> selectItemsByPackageId(Long packageId);
```

Add to `SkillFileManifestMapper`:

```java
@Select("""
        SELECT id AS fileId,
               version_id AS versionId,
               relative_path AS relativePath,
               directory,
               size_bytes AS sizeBytes,
               sha256
        FROM skill_file_manifest
        WHERE version_id = #{versionId}
        ORDER BY directory DESC, relative_path ASC
        """)
List<SkillFileItem> selectItemsByVersionId(Long versionId);
```

Implement service methods:

```java
@Override
public List<SkillPackageItem> listPackages() {
    return packageMapper.selectItems();
}

@Override
public List<SkillVersionItem> listVersions(Long packageId) {
    return versionMapper.selectItemsByPackageId(packageId);
}

@Override
public SkillVersionItem requireVersion(Long versionId) {
    SkillVersion version = requireVersionEntity(versionId);
    return new SkillVersionItem(version.getId(), version.getPackageId(), version.getVersionNo(), version.getStatus(),
            version.getRootDir(), version.getSkillMdPath(), version.getSkillMdContent(), version.getFileCount(),
            version.getZipSizeBytes(), version.getUncompressedSizeBytes(), version.getErrorMessage());
}

@Override
public List<SkillFileItem> listFiles(Long versionId) {
    requireVersionEntity(versionId);
    return fileMapper.selectItemsByVersionId(versionId);
}

@Override
@Transactional
public SkillPackageItem activateVersion(Long packageId, Long versionId) {
    SkillVersion version = requireVersionEntity(versionId);
    if (!packageId.equals(version.getPackageId()) || !"READY".equals(version.getStatus())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "只能激活 READY 状态的 Skill 版本");
    }
    SkillPackage updated = new SkillPackage();
    updated.setId(packageId);
    updated.setCurrentVersionId(versionId);
    updated.setUpdatedAt(OffsetDateTime.now());
    packageMapper.updateById(updated);
    return packageMapper.selectItems().stream()
            .filter(item -> packageId.equals(item.packageId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在"));
}

@Override
@Transactional
public SkillPackageItem updateEnabled(Long packageId, boolean enabled) {
    SkillPackage updated = new SkillPackage();
    updated.setId(packageId);
    updated.setEnabled(enabled);
    updated.setUpdatedAt(OffsetDateTime.now());
    packageMapper.updateById(updated);
    return packageMapper.selectItems().stream()
            .filter(item -> packageId.equals(item.packageId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在"));
}
```

- [ ] **Step 8: 实现 Controller 请求 records**

Create controller request records:

```java
public record CreateSkillUploadUrlRequest(
        @NotBlank String skillKey,
        @NotBlank String name,
        String description,
        @NotBlank String filename,
        @NotBlank String contentType,
        @NotNull Long fileSize
) {
}
```

Create `SkillPackageController.java`:

```java
@RestController
@RequestMapping("/api/skills")
public class SkillPackageController {

    private final SkillPackageService skillPackageService;

    public SkillPackageController(SkillPackageService skillPackageService) {
        this.skillPackageService = skillPackageService;
    }

    @GetMapping("/packages")
    public ApiResponse<List<SkillPackageItem>> listPackages() {
        return ApiResponse.success(skillPackageService.listPackages());
    }

    @PostMapping("/packages/upload-url")
    public ApiResponse<SkillUploadUrl> createUploadUrl(
            Authentication authentication,
            @Valid @RequestBody CreateSkillUploadUrlRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(skillPackageService.createUploadUrl(loginUser.userId(),
                new CreateSkillUploadCommand(request.skillKey(), request.name(), request.description(),
                        request.filename(), request.contentType(), request.fileSize())));
    }

    @PostMapping("/packages/{packageId}/confirm-upload")
    public ApiResponse<Void> confirmUpload(
            Authentication authentication,
            @PathVariable Long packageId,
            @RequestParam Long versionId
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        skillPackageService.confirmUpload(loginUser.userId(), packageId, versionId);
        return ApiResponse.success();
    }

    @GetMapping("/packages/{packageId}/versions")
    public ApiResponse<List<SkillVersionItem>> listVersions(@PathVariable Long packageId) {
        return ApiResponse.success(skillPackageService.listVersions(packageId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<SkillVersionItem> version(@PathVariable Long versionId) {
        return ApiResponse.success(skillPackageService.requireVersion(versionId));
    }

    @GetMapping("/versions/{versionId}/files")
    public ApiResponse<List<SkillFileItem>> files(@PathVariable Long versionId) {
        return ApiResponse.success(skillPackageService.listFiles(versionId));
    }

    @PutMapping("/packages/{packageId}/enabled")
    public ApiResponse<SkillPackageItem> updateEnabled(@PathVariable Long packageId, @RequestBody Map<String, Boolean> body) {
        return ApiResponse.success(skillPackageService.updateEnabled(packageId, Boolean.TRUE.equals(body.get("enabled"))));
    }

    @PostMapping("/packages/{packageId}/versions/{versionId}/activate")
    public ApiResponse<SkillPackageItem> activate(@PathVariable Long packageId, @PathVariable Long versionId) {
        return ApiResponse.success(skillPackageService.activateVersion(packageId, versionId));
    }
}
```

- [ ] **Step 9: 提交**

Run:

```powershell
git add backend/agent-hub-skill
git commit -m "新增 Skill 上传服务"
```

---

### Task 6: Skill sandbox 校验结果落库

**Files:**
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/packageinfo/service/impl/SkillPackageServiceImpl.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/packageinfo/SkillPackageServiceImplTest.java`

- [ ] **Step 1: 添加校验成功和失败测试**

Append tests:

```java
@Test
void shouldPersistValidationSuccess() {
    SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
    SkillFileManifestMapper fileMapper = mock(SkillFileManifestMapper.class);
    ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
    SandboxClient sandboxClient = mock(SandboxClient.class);
    SkillVersion version = new SkillVersion();
    version.setId(20L);
    version.setObjectKey("skill-packages/1/writing.zip");
    version.setBucketName("agent-hub");
    when(versionMapper.selectById(20L)).thenReturn(version);
    when(objectStorageClient.presignGet("skill-packages/1/writing.zip", Duration.ofMinutes(30)))
            .thenReturn("http://download.test");
    when(sandboxClient.validateSkillZip(any())).thenReturn(new SkillValidationResult(
            true,
            "writing",
            "SKILL.md",
            "# 写作",
            "abc",
            1024L,
            1,
            List.of(new SkillValidationResult.FileItem("SKILL.md", 8L, "def", false)),
            List.of()
    ));
    SkillPackageServiceImpl service = serviceWithSandbox(versionMapper, fileMapper, objectStorageClient, sandboxClient);

    service.validateVersion(20L);

    ArgumentCaptor<SkillVersion> versionCaptor = ArgumentCaptor.forClass(SkillVersion.class);
    verify(versionMapper).updateById(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getStatus()).isEqualTo("READY");
    assertThat(versionCaptor.getValue().getSkillMdContent()).isEqualTo("# 写作");
    verify(fileMapper).insert(any(SkillFileManifest.class));
}

@Test
void shouldPersistValidationFailure() {
    SkillVersionMapper versionMapper = mock(SkillVersionMapper.class);
    SkillVersion version = new SkillVersion();
    version.setId(20L);
    version.setObjectKey("skill-packages/1/writing.zip");
    when(versionMapper.selectById(20L)).thenReturn(version);
    ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
    when(objectStorageClient.presignGet("skill-packages/1/writing.zip", Duration.ofMinutes(30)))
            .thenReturn("http://download.test");
    SandboxClient sandboxClient = mock(SandboxClient.class);
    when(sandboxClient.validateSkillZip(any())).thenReturn(new SkillValidationResult(
            false,
            null,
            null,
            null,
            null,
            0L,
            0,
            List.of(),
            List.of(new SkillValidationResult.ValidationError("MISSING_SKILL_MD", "Skill 包缺少 SKILL.md"))
    ));
    SkillPackageServiceImpl service = serviceWithSandbox(versionMapper, mock(SkillFileManifestMapper.class),
            objectStorageClient, sandboxClient);

    service.validateVersion(20L);

    ArgumentCaptor<SkillVersion> versionCaptor = ArgumentCaptor.forClass(SkillVersion.class);
    verify(versionMapper).updateById(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getStatus()).isEqualTo("FAILED");
    assertThat(versionCaptor.getValue().getErrorMessage()).contains("Skill 包缺少 SKILL.md");
}
```

Add helper:

```java
private SkillPackageServiceImpl serviceWithSandbox(
        SkillVersionMapper versionMapper,
        SkillFileManifestMapper fileMapper,
        ObjectStorageClient objectStorageClient,
        SandboxClient sandboxClient
) {
    return new SkillPackageServiceImpl(mock(SkillPackageMapper.class), versionMapper, fileMapper,
            objectStorageClient, new StorageProperties(), Runnable::run, sandboxClient);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-skill -Dtest=SkillPackageServiceImplTest test
```

Workdir: `backend`

Expected: FAIL，原因是构造器没有 `SandboxClient`，`validateVersion` 不落库。

- [ ] **Step 3: 注入 SandboxClient 并实现 validateVersion**

Modify constructor to accept `SandboxClient sandboxClient`; keep a test-only constructor that delegates to full constructor.

Implement:

```java
public void validateVersion(Long versionId) {
    SkillVersion version = versionMapper.selectById(versionId);
    if (version == null) {
        return;
    }
    try {
        String downloadUrl = objectStorageClient.presignGet(version.getObjectKey(), Duration.ofMinutes(30));
        SkillValidationResult result = sandboxClient.validateSkillZip(new SkillValidationRequest(
                versionId,
                downloadUrl,
                SkillValidationRequest.Limits.defaults()
        ));
        if (!result.success()) {
            markFailed(versionId, firstErrorMessage(result.errors()));
            return;
        }
        persistValidationSuccess(versionId, result);
    } catch (RuntimeException exception) {
        markFailed(versionId, "沙箱校验服务不可用: " + exception.getMessage());
    }
}
```

Implement helpers:

```java
private void persistValidationSuccess(Long versionId, SkillValidationResult result) {
    SkillVersion updated = new SkillVersion();
    updated.setId(versionId);
    updated.setStatus("READY");
    updated.setRootDir(result.rootDir());
    updated.setSkillMdPath(result.skillMdPath());
    updated.setSkillMdContent(result.skillMdContent());
    updated.setZipSha256(result.zipSha256());
    updated.setFileCount(result.fileCount());
    updated.setUncompressedSizeBytes(result.totalUncompressedBytes());
    updated.setValidationCompletedAt(OffsetDateTime.now());
    updated.setErrorMessage(null);
    updated.setUpdatedAt(OffsetDateTime.now());
    versionMapper.updateById(updated);

    for (SkillValidationResult.FileItem item : result.files()) {
        SkillFileManifest file = new SkillFileManifest();
        file.setVersionId(versionId);
        file.setRelativePath(item.relativePath());
        file.setDirectory(item.directory());
        file.setSizeBytes(item.sizeBytes());
        file.setSha256(item.sha256());
        file.setCreatedAt(OffsetDateTime.now());
        fileMapper.insert(file);
    }
}

private void markFailed(Long versionId, String message) {
    SkillVersion updated = new SkillVersion();
    updated.setId(versionId);
    updated.setStatus("FAILED");
    updated.setErrorMessage(message == null || message.isBlank() ? "Skill 包校验失败" : message);
    updated.setValidationCompletedAt(OffsetDateTime.now());
    updated.setUpdatedAt(OffsetDateTime.now());
    versionMapper.updateById(updated);
}

private String firstErrorMessage(List<SkillValidationResult.ValidationError> errors) {
    if (errors == null || errors.isEmpty()) {
        return "Skill 包校验失败";
    }
    return errors.getFirst().message();
}
```

- [ ] **Step 4: 运行测试**

Run:

```powershell
mvn -pl agent-hub-skill -Dtest=SkillPackageServiceImplTest test
```

Workdir: `backend`

Expected: PASS。

- [ ] **Step 5: 提交**

Run:

```powershell
git add backend/agent-hub-skill
git commit -m "接入 Skill 沙箱校验"
```

---

### Task 7: Agent Skill 绑定与 ChatService 运行时注入

**Files:**
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/dto/AgentSkillBindingItem.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/command/UpdateAgentSkillBindingsCommand.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/service/AgentSkillBindingService.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/service/impl/AgentSkillBindingServiceImpl.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/controller/AgentSkillBindingController.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/binding/controller/request/UpdateAgentSkillBindingsRequest.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/runtime/SkillRuntimeService.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/runtime/SkillRuntimeContext.java`
- Modify: `backend/agent-hub-chat/pom.xml`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/runtime/SkillRuntimeServiceTest.java`
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: 写 SkillRuntimeService 注入长度测试**

Create `SkillRuntimeServiceTest.java`:

```java
package com.agenthub.skill.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.skill.binding.mapper.AgentSkillBindingMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRuntimeServiceTest {

    @Test
    void shouldBuildPromptInjectionForEnabledBindings() {
        AgentSkillBindingMapper mapper = mock(AgentSkillBindingMapper.class);
        when(mapper.selectRuntimeSkills(1L)).thenReturn(List.of(new SkillRuntimeContext.Skill(
                "official-writing-style",
                "公文写作规范",
                7L,
                "# 公文写作\n\n先确认文种。",
                "abc"
        )));
        SkillRuntimeService service = new SkillRuntimeService(mapper);

        SkillRuntimeContext context = service.buildRuntimeContext(1L);

        assertThat(context.promptInjection()).contains("[Skill: official-writing-style]");
        assertThat(context.promptInjection()).contains("/workspace/skills/official-writing-style");
        assertThat(context.skills()).hasSize(1);
    }

    @Test
    void shouldTruncateSingleSkillContent() {
        AgentSkillBindingMapper mapper = mock(AgentSkillBindingMapper.class);
        when(mapper.selectRuntimeSkills(1L)).thenReturn(List.of(new SkillRuntimeContext.Skill(
                "large-skill",
                "大 Skill",
                8L,
                "a".repeat(70 * 1024),
                "def"
        )));
        SkillRuntimeService service = new SkillRuntimeService(mapper);

        SkillRuntimeContext context = service.buildRuntimeContext(1L);

        assertThat(context.promptInjection()).contains("完整内容和资源文件位于 /workspace/skills/large-skill");
        assertThat(context.promptInjection().length()).isLessThan(70 * 1024);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-skill -Dtest=SkillRuntimeServiceTest test
```

Workdir: `backend`

Expected: FAIL，原因是 runtime 类型和 mapper 方法不存在。

- [ ] **Step 3: 实现 runtime DTO 和 service**

Create `SkillRuntimeContext.java`:

```java
package com.agenthub.skill.runtime;

import java.util.List;

public record SkillRuntimeContext(
        String promptInjection,
        List<Skill> skills
) {
    public record Skill(
            String skillKey,
            String name,
            Long versionId,
            String skillMdContent,
            String zipSha256
    ) {
    }
}
```

Add to `AgentSkillBindingMapper.java`:

```java
@Select("""
        SELECT sp.skill_key AS skillKey,
               sp.name AS name,
               sv.id AS versionId,
               sv.skill_md_content AS skillMdContent,
               sv.zip_sha256 AS zipSha256
        FROM agent_skill_binding b
        JOIN skill_package sp ON sp.id = b.skill_id
        JOIN skill_version sv ON sv.id = b.version_id
        WHERE b.agent_id = #{agentId}
            AND b.enabled = TRUE
            AND sp.enabled = TRUE
            AND sv.status = 'READY'
        ORDER BY b.sort ASC, b.id ASC
        """)
List<SkillRuntimeContext.Skill> selectRuntimeSkills(Long agentId);
```

Create `SkillRuntimeService.java`:

```java
package com.agenthub.skill.runtime;

import com.agenthub.skill.binding.mapper.AgentSkillBindingMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SkillRuntimeService {

    private static final int SINGLE_SKILL_LIMIT = 64 * 1024;
    private static final int TOTAL_LIMIT = 128 * 1024;

    private final AgentSkillBindingMapper bindingMapper;

    public SkillRuntimeService(AgentSkillBindingMapper bindingMapper) {
        this.bindingMapper = bindingMapper;
    }

    public SkillRuntimeContext buildRuntimeContext(Long agentId) {
        List<SkillRuntimeContext.Skill> skills = bindingMapper.selectRuntimeSkills(agentId);
        if (skills == null || skills.isEmpty()) {
            return new SkillRuntimeContext("", List.of());
        }
        StringBuilder prompt = new StringBuilder("""
                已绑定以下 Skill。每个 Skill 的完整文件位于 /workspace/skills/{skillKey}。
                如需读取示例、模板、脚本或其他资源，请使用 sandbox 工具访问对应目录。
                """.strip());
        List<SkillRuntimeContext.Skill> included = new ArrayList<>();
        for (SkillRuntimeContext.Skill skill : skills) {
            String block = skillBlock(skill);
            if (prompt.length() + block.length() > TOTAL_LIMIT) {
                break;
            }
            prompt.append("\n\n").append(block);
            included.add(skill);
        }
        return new SkillRuntimeContext(prompt.toString(), List.copyOf(included));
    }

    private String skillBlock(SkillRuntimeContext.Skill skill) {
        String content = skill.skillMdContent() == null ? "" : skill.skillMdContent();
        boolean truncated = content.length() > SINGLE_SKILL_LIMIT;
        if (truncated) {
            content = content.substring(0, SINGLE_SKILL_LIMIT)
                    + "\n\n内容已截断，完整内容和资源文件位于 /workspace/skills/" + skill.skillKey();
        }
        return """
                [Skill: %s]
                名称：%s
                路径：/workspace/skills/%s
                SKILL.md:
                %s
                """.formatted(skill.skillKey(), skill.name(), skill.skillKey(), content).strip();
    }
}
```

- [ ] **Step 4: 运行 runtime 测试**

Run:

```powershell
mvn -pl agent-hub-skill -Dtest=SkillRuntimeServiceTest test
```

Workdir: `backend`

Expected: PASS。

- [ ] **Step 5: 实现 Agent Skill 绑定服务和接口**

Create `AgentSkillBindingItem.java`:

```java
package com.agenthub.skill.binding.dto;

public record AgentSkillBindingItem(
        Long bindingId,
        Long agentId,
        Long skillId,
        String skillKey,
        String skillName,
        Long versionId,
        Integer versionNo,
        Boolean enabled,
        Integer sort
) {
}
```

Create `UpdateAgentSkillBindingsCommand.java`:

```java
package com.agenthub.skill.binding.command;

import java.util.List;

public record UpdateAgentSkillBindingsCommand(
        List<Item> bindings
) {
    public record Item(
            Long skillId,
            Long versionId,
            Boolean enabled,
            Integer sort
    ) {
    }
}
```

Add joined query to `AgentSkillBindingMapper.java`:

```java
@Select("""
        SELECT b.id AS bindingId,
               b.agent_id AS agentId,
               sp.id AS skillId,
               sp.skill_key AS skillKey,
               sp.name AS skillName,
               sv.id AS versionId,
               sv.version_no AS versionNo,
               b.enabled AS enabled,
               b.sort AS sort
        FROM agent_skill_binding b
        JOIN skill_package sp ON sp.id = b.skill_id
        JOIN skill_version sv ON sv.id = b.version_id
        WHERE b.agent_id = #{agentId}
        ORDER BY b.sort ASC, b.id ASC
        """)
List<AgentSkillBindingItem> selectItemsByAgentId(Long agentId);
```

Create `AgentSkillBindingService.java`:

```java
package com.agenthub.skill.binding.service;

import com.agenthub.skill.binding.command.UpdateAgentSkillBindingsCommand;
import com.agenthub.skill.binding.dto.AgentSkillBindingItem;
import java.util.List;

public interface AgentSkillBindingService {

    List<AgentSkillBindingItem> list(Long agentId);

    List<AgentSkillBindingItem> update(Long userId, Long agentId, UpdateAgentSkillBindingsCommand command);
}
```

Create `AgentSkillBindingServiceImpl.java`:

```java
package com.agenthub.skill.binding.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.skill.binding.command.UpdateAgentSkillBindingsCommand;
import com.agenthub.skill.binding.dto.AgentSkillBindingItem;
import com.agenthub.skill.binding.entity.AgentSkillBinding;
import com.agenthub.skill.binding.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.binding.service.AgentSkillBindingService;
import com.agenthub.skill.packageinfo.entity.SkillVersion;
import com.agenthub.skill.packageinfo.mapper.SkillVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSkillBindingServiceImpl implements AgentSkillBindingService {

    private final AgentSkillBindingMapper bindingMapper;
    private final SkillVersionMapper versionMapper;

    public AgentSkillBindingServiceImpl(AgentSkillBindingMapper bindingMapper, SkillVersionMapper versionMapper) {
        this.bindingMapper = bindingMapper;
        this.versionMapper = versionMapper;
    }

    @Override
    public List<AgentSkillBindingItem> list(Long agentId) {
        return bindingMapper.selectItemsByAgentId(agentId);
    }

    @Override
    @Transactional
    public List<AgentSkillBindingItem> update(Long userId, Long agentId, UpdateAgentSkillBindingsCommand command) {
        List<UpdateAgentSkillBindingsCommand.Item> items =
                command == null || command.bindings() == null ? List.of() : command.bindings();
        validateItems(items);
        bindingMapper.delete(new LambdaQueryWrapper<AgentSkillBinding>().eq(AgentSkillBinding::getAgentId, agentId));
        OffsetDateTime now = OffsetDateTime.now();
        for (UpdateAgentSkillBindingsCommand.Item item : items) {
            SkillVersion version = versionMapper.selectById(item.versionId());
            if (version == null || !"READY".equals(version.getStatus()) || !item.skillId().equals(version.getPackageId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "只能绑定 READY 状态的 Skill 版本");
            }
            AgentSkillBinding binding = new AgentSkillBinding();
            binding.setAgentId(agentId);
            binding.setSkillId(item.skillId());
            binding.setVersionId(item.versionId());
            binding.setEnabled(item.enabled() == null || item.enabled());
            binding.setSort(item.sort() == null ? 0 : item.sort());
            binding.setCreatedBy(userId);
            binding.setUpdatedBy(userId);
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            bindingMapper.insert(binding);
        }
        return list(agentId);
    }

    private void validateItems(List<UpdateAgentSkillBindingsCommand.Item> items) {
        Set<Long> skillIds = new HashSet<>();
        for (UpdateAgentSkillBindingsCommand.Item item : items) {
            if (item.skillId() == null || item.versionId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 绑定参数不完整");
            }
            if (!skillIds.add(item.skillId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "同一个 Agent 不能重复绑定同一个 Skill");
            }
        }
    }
}
```

Create request record:

```java
package com.agenthub.skill.binding.controller.request;

import java.util.List;

public record UpdateAgentSkillBindingsRequest(
        List<Item> bindings
) {
    public record Item(
            Long skillId,
            Long versionId,
            Boolean enabled,
            Integer sort
    ) {
    }
}
```

Create controller:

```java
package com.agenthub.skill.binding.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.skill.binding.command.UpdateAgentSkillBindingsCommand;
import com.agenthub.skill.binding.controller.request.UpdateAgentSkillBindingsRequest;
import com.agenthub.skill.binding.dto.AgentSkillBindingItem;
import com.agenthub.skill.binding.service.AgentSkillBindingService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillBindingController {

    private final AgentSkillBindingService bindingService;

    public AgentSkillBindingController(AgentSkillBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    public ApiResponse<List<AgentSkillBindingItem>> list(@PathVariable Long agentId) {
        return ApiResponse.success(bindingService.list(agentId));
    }

    @PutMapping
    public ApiResponse<List<AgentSkillBindingItem>> update(
            Authentication authentication,
            @PathVariable Long agentId,
            @RequestBody UpdateAgentSkillBindingsRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        List<UpdateAgentSkillBindingsCommand.Item> bindings = request.bindings() == null
                ? List.of()
                : request.bindings().stream()
                        .map(item -> new UpdateAgentSkillBindingsCommand.Item(
                                item.skillId(), item.versionId(), item.enabled(), item.sort()))
                        .toList();
        return ApiResponse.success(bindingService.update(loginUser.userId(), agentId,
                new UpdateAgentSkillBindingsCommand(bindings)));
    }
}
```

- [ ] **Step 6: 增加 agent-hub-chat 依赖**

Modify `backend/agent-hub-chat/pom.xml`:

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-skill</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 7: 修改 ChatService 构造器和 prepareChat**

Add field:

```java
private final SkillRuntimeService skillRuntimeService;
```

Constructor should accept `SkillRuntimeService skillRuntimeService`; existing test constructors can pass `null`.

In `prepareChat`, before `contextBuilder.build`:

```java
SkillRuntimeContext skillRuntime = resolveSkillRuntime(agentRuntime);
String mergedSystemPrompt = mergeSystemPrompt(systemPrompt(agentRuntime), skillRuntime.promptInjection());
ConversationContext context = contextBuilder.build(userId, resolvedConversationId, runtimeConfig, normalizedPrompt,
        images, mergedSystemPrompt, currentMessageCreatedAt);
```

Add helpers:

```java
private SkillRuntimeContext resolveSkillRuntime(AgentRuntimeConfig agentRuntime) {
    if (skillRuntimeService == null || agentRuntime == null) {
        return new SkillRuntimeContext("", List.of());
    }
    return skillRuntimeService.buildRuntimeContext(agentRuntime.agentId());
}

private String mergeSystemPrompt(String agentPrompt, String skillPrompt) {
    if (!hasText(skillPrompt)) {
        return agentPrompt;
    }
    if (!hasText(agentPrompt)) {
        return skillPrompt;
    }
    return agentPrompt.strip() + "\n\n" + skillPrompt.strip();
}
```

Extend `PreparedChat` to include `SkillRuntimeContext skillRuntime`.

Before `streamLlm` starts, pass `preparedChat.skillRuntime()` into `streamLlm`.

In `streamLlm`, before `listTools(toolContext)`:

```java
syncSkillsToSandbox(userId, conversationId, toolContext, skillRuntime);
```

Implement:

```java
private void syncSkillsToSandbox(
        Long userId,
        Long conversationId,
        ToolExecutionContext toolContext,
        SkillRuntimeContext skillRuntime
) {
    if (skillRuntimeService == null || skillRuntime == null || skillRuntime.skills().isEmpty()) {
        return;
    }
    try {
        skillRuntimeService.syncToSandbox(userId, conversationId, skillRuntime);
    } catch (RuntimeException exception) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "Skill 同步到沙箱失败：" + defaultString(exception.getMessage()));
    }
}
```

Add `syncToSandbox` method to `SkillRuntimeService` in the same task:

```java
public void syncToSandbox(Long userId, Long conversationId, SkillRuntimeContext context) {
    if (context == null || context.skills().isEmpty()) {
        return;
    }
    String sandboxSessionId = sandboxSessionService.ensureSession(userId, conversationId);
    List<SkillSyncRequest.Skill> syncSkills = context.skills().stream()
            .map(skill -> new SkillSyncRequest.Skill(skill.skillKey(), skill.versionId(),
                    objectStorageClient.presignGet(objectKeyForVersion(skill.versionId()), Duration.ofMinutes(10)),
                    skill.zipSha256()))
            .toList();
    SkillSyncResult result = sandboxClient.syncSkills(new SkillSyncRequest(sandboxSessionId, syncSkills, "/workspace/skills"));
    if (!result.success()) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, firstSyncError(result));
    }
}
```

To support `objectKeyForVersion`, add a mapper query in `SkillVersionMapper`:

```java
@Select("SELECT object_key FROM skill_version WHERE id = #{versionId}")
String selectObjectKeyById(Long versionId);
```

- [ ] **Step 8: 补 ChatService 单测**

Append to `ChatServiceImplTest.java`:

```java
@Test
void shouldAppendSkillPromptToSystemPrompt() {
    SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
    when(skillRuntimeService.buildRuntimeContext(1L)).thenReturn(new SkillRuntimeContext(
            "[Skill: writing]\nSKILL.md:\n按公文规范写作。",
            List.of()
    ));
    RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.done()));
    ChatServiceImpl service = serviceWithSkillRuntime(skillRuntimeService, llmClient);
    when(agentService.requireEnabledRuntimeConfig("official-writing")).thenReturn(new AgentRuntimeConfig(
            1L,
            "official-writing",
            "公文写作 Agent",
            "你是公文写作 Agent。",
            10L
    ));

    service.stream(7L, null, 10L, "official-writing", "写一份通知", false, List.of(), List.of());

    assertThat(llmClient.lastRequest().messages().getFirst().content())
            .contains("你是公文写作 Agent。")
            .contains("[Skill: writing]");
}
```

Use the existing helper style in this test file to create `serviceWithSkillRuntime`. Keep the helper local to the test file and pass existing mocks for unrelated dependencies.

- [ ] **Step 9: 运行目标测试**

Run:

```powershell
mvn -pl agent-hub-chat,agent-hub-skill -am -Dtest=ChatServiceImplTest,SkillRuntimeServiceTest test
```

Workdir: `backend`

Expected: PASS。

- [ ] **Step 10: 提交**

Run:

```powershell
git add backend/agent-hub-skill backend/agent-hub-chat
git commit -m "接入 Agent Skill 运行时"
```

---

### Task 8: admin-web Skill 类型、API 和路由

**Files:**
- Create: `admin-web/src/types/skill.ts`
- Create: `admin-web/src/api/skill.ts`
- Modify: `admin-web/src/router/route-registry.ts`
- Create: `admin-web/src/views/SkillsView.vue`
- Test: `admin-web/tests/skills.spec.ts`

- [ ] **Step 1: 写前端源码测试**

Create `admin-web/tests/skills.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/skill.ts?raw'
import routeSource from '../src/router/route-registry.ts?raw'
import typeSource from '../src/types/skill.ts?raw'
import viewSource from '../src/views/SkillsView.vue?raw'

describe('Skill 库管理', () => {
  it('定义 Skill 包、版本和文件类型', () => {
    expect(typeSource).toContain('export interface SkillPackageRecord')
    expect(typeSource).toContain('skillKey: string')
    expect(typeSource).toContain('export interface SkillVersionRecord')
    expect(typeSource).toContain('skillMdContent: string')
    expect(typeSource).toContain('export interface SkillFileRecord')
    expect(typeSource).toContain('relativePath: string')
  })

  it('提供 Skill 管理 API', () => {
    expect(apiSource).toContain("'/skills/packages'")
    expect(apiSource).toContain('createSkillUploadUrl')
    expect(apiSource).toContain('confirmSkillUpload')
    expect(apiSource).toContain('fetchSkillVersionFiles')
    expect(apiSource).toContain('activateSkillVersion')
  })

  it('注册 Skill 库路由', () => {
    expect(routeSource).toContain("path: '/agent/skills'")
    expect(routeSource).toContain("name: 'agent-skills'")
    expect(routeSource).toContain("permission: 'skill:package:list'")
    expect(routeSource).toContain("component: () => import('../views/SkillsView.vue')")
  })

  it('页面支持上传、轮询状态和文件清单', () => {
    expect(viewSource).toContain('n-upload')
    expect(viewSource).toContain('createSkillUploadUrl')
    expect(viewSource).toContain('confirmSkillUpload')
    expect(viewSource).toContain('fetchSkillVersionFiles')
    expect(viewSource).toContain('SKILL.md')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
npm test -- skills.spec.ts
```

Workdir: `admin-web`

Expected: FAIL，原因是类型、API、路由和页面不存在。

- [ ] **Step 3: 新增 Skill 类型**

Create `admin-web/src/types/skill.ts`:

```ts
export interface SkillPackageRecord {
  packageId: number
  skillKey: string
  name: string
  description?: string | null
  enabled: boolean
  currentVersionId?: number | null
  currentVersionStatus?: string | null
}

export interface SkillVersionRecord {
  versionId: number
  packageId: number
  versionNo: number
  status: string
  rootDir?: string | null
  skillMdPath?: string | null
  skillMdContent: string
  fileCount: number
  zipSizeBytes: number
  uncompressedSizeBytes: number
  errorMessage?: string | null
}

export interface SkillFileRecord {
  fileId: number
  versionId: number
  relativePath: string
  directory: boolean
  sizeBytes: number
  sha256?: string | null
}

export interface SkillUploadPayload {
  skillKey: string
  name: string
  description?: string
  filename: string
  contentType: string
  fileSize: number
}

export interface SkillUploadUrl {
  packageId: number
  versionId: number
  uploadUrl: string
  method: string
  headers: Record<string, string>
  expiresAt: string
}
```

- [ ] **Step 4: 新增 Skill API**

Create `admin-web/src/api/skill.ts`:

```ts
import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  SkillFileRecord,
  SkillPackageRecord,
  SkillUploadPayload,
  SkillUploadUrl,
  SkillVersionRecord
} from '../types/skill'

export async function fetchSkillPackages() {
  const response = await http.get<ApiResponse<SkillPackageRecord[]>>('/skills/packages')
  return response.data.data
}

export async function createSkillUploadUrl(payload: SkillUploadPayload) {
  const response = await http.post<ApiResponse<SkillUploadUrl>>('/skills/packages/upload-url', payload)
  return response.data.data
}

export async function confirmSkillUpload(packageId: number, versionId: number) {
  await http.post(`/skills/packages/${packageId}/confirm-upload`, undefined, { params: { versionId } })
}

export async function fetchSkillVersions(packageId: number) {
  const response = await http.get<ApiResponse<SkillVersionRecord[]>>(`/skills/packages/${packageId}/versions`)
  return response.data.data
}

export async function fetchSkillVersion(versionId: number) {
  const response = await http.get<ApiResponse<SkillVersionRecord>>(`/skills/versions/${versionId}`)
  return response.data.data
}

export async function fetchSkillVersionFiles(versionId: number) {
  const response = await http.get<ApiResponse<SkillFileRecord[]>>(`/skills/versions/${versionId}/files`)
  return response.data.data
}

export async function updateSkillEnabled(packageId: number, enabled: boolean) {
  const response = await http.put<ApiResponse<SkillPackageRecord>>(`/skills/packages/${packageId}/enabled`, { enabled })
  return response.data.data
}

export async function activateSkillVersion(packageId: number, versionId: number) {
  const response = await http.post<ApiResponse<SkillPackageRecord>>(
    `/skills/packages/${packageId}/versions/${versionId}/activate`
  )
  return response.data.data
}
```

- [ ] **Step 5: 注册路由**

Modify `route-registry.ts`, add after Agent list:

```ts
{
  path: '/agent/skills',
  name: 'agent-skills',
  title: 'Skill 库',
  icon: 'folder',
  permission: 'skill:package:list',
  component: () => import('../views/SkillsView.vue')
}
```

- [ ] **Step 6: 新增页面骨架**

Create `SkillsView.vue` with upload and table skeleton:

```vue
<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>Skill 库</h2>
        <p>上传 zip 格式 Skill 包，查看校验状态、SKILL.md 和文件清单。</p>
      </div>
      <n-upload :show-file-list="false" accept=".zip" :custom-request="handleUpload">
        <n-button type="primary">上传 Skill 包</n-button>
      </n-upload>
    </div>
    <n-card :bordered="false" size="small">
      <n-data-table :columns="columns" :data="packages" :loading="loading" :pagination="false" />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { NButton, NCard, NDataTable, NTag, NUpload, useMessage } from 'naive-ui'
import {
  confirmSkillUpload,
  createSkillUploadUrl,
  fetchSkillPackages,
  fetchSkillVersionFiles
} from '../api/skill'
import type { SkillPackageRecord } from '../types/skill'

const message = useMessage()
const loading = ref(false)
const packages = ref<SkillPackageRecord[]>([])

const columns: DataTableColumns<SkillPackageRecord> = [
  { title: '名称', key: 'name', width: 180 },
  { title: '标识', key: 'skillKey', width: 180 },
  {
    title: '状态',
    key: 'currentVersionStatus',
    width: 120,
    render: (row) => h(NTag, { bordered: false }, { default: () => row.currentVersionStatus ?? '未导入' })
  },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row) =>
      h(NButton, { size: 'small', tertiary: true, onClick: () => openFiles(row) }, { default: () => '文件清单' })
  }
]

async function loadData() {
  loading.value = true
  try {
    packages.value = await fetchSkillPackages()
  } finally {
    loading.value = false
  }
}

async function handleUpload(options: UploadCustomRequestOptions) {
  const file = options.file.file
  if (!file) {
    return
  }
  const upload = await createSkillUploadUrl({
    skillKey: file.name.replace(/\.zip$/i, '').replace(/[^a-zA-Z0-9_-]/g, '-'),
    name: file.name.replace(/\.zip$/i, ''),
    description: '',
    filename: file.name,
    contentType: file.type || 'application/zip',
    fileSize: file.size
  })
  await fetch(upload.uploadUrl, { method: upload.method, headers: upload.headers, body: file })
  await confirmSkillUpload(upload.packageId, upload.versionId)
  message.success('Skill 包已上传，正在校验')
  await loadData()
}

async function openFiles(row: SkillPackageRecord) {
  if (!row.currentVersionId) {
    message.warning('当前 Skill 尚无可查看版本')
    return
  }
  await fetchSkillVersionFiles(row.currentVersionId)
  message.success('文件清单已加载')
}

onMounted(() => {
  void loadData()
})
</script>

<style scoped>
.page {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page-header h2 {
  margin: 0 0 6px;
}

.page-header p {
  margin: 0;
  color: #64748b;
}
</style>
```

- [ ] **Step 7: 运行前端测试**

Run:

```powershell
npm test -- skills.spec.ts
```

Workdir: `admin-web`

Expected: PASS。

- [ ] **Step 8: 提交**

Run:

```powershell
git add admin-web/src/types/skill.ts admin-web/src/api/skill.ts admin-web/src/router/route-registry.ts admin-web/src/views/SkillsView.vue admin-web/tests/skills.spec.ts
git commit -m "新增 Skill 库前端入口"
```

---

### Task 9: admin-web Agent Skill 绑定入口

**Files:**
- Modify: `admin-web/src/types/skill.ts`
- Modify: `admin-web/src/api/skill.ts`
- Modify: `admin-web/src/views/AgentsView.vue`
- Test: `admin-web/tests/agent-skill-binding.spec.ts`

- [ ] **Step 1: 写 Agent Skill 绑定源码测试**

Create `agent-skill-binding.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/skill.ts?raw'
import typeSource from '../src/types/skill.ts?raw'
import viewSource from '../src/views/AgentsView.vue?raw'

describe('Agent Skill 绑定', () => {
  it('定义 Agent Skill 绑定类型', () => {
    expect(typeSource).toContain('export interface AgentSkillBindingRecord')
    expect(typeSource).toContain('versionId: number')
    expect(typeSource).toContain('sort: number')
  })

  it('提供 Agent Skill 绑定 API', () => {
    expect(apiSource).toContain('fetchAgentSkillBindings')
    expect(apiSource).toContain('updateAgentSkillBindings')
    expect(apiSource).toContain('/agents/${agentId}/skills')
  })

  it('Agent 页面包含 Skill 绑定入口', () => {
    expect(viewSource).toContain('Skill 绑定')
    expect(viewSource).toContain('fetchAgentSkillBindings')
    expect(viewSource).toContain('updateAgentSkillBindings')
    expect(viewSource).toContain('fetchSkillPackages')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
npm test -- agent-skill-binding.spec.ts
```

Workdir: `admin-web`

Expected: FAIL。

- [ ] **Step 3: 扩展类型和 API**

Append to `skill.ts`:

```ts
export interface AgentSkillBindingRecord {
  bindingId?: number
  agentId: number
  skillId: number
  skillKey: string
  skillName: string
  versionId: number
  versionNo: number
  enabled: boolean
  sort: number
}

export interface AgentSkillBindingPayload {
  skillId: number
  versionId: number
  enabled: boolean
  sort: number
}
```

Append to `skill.ts` API:

```ts
export async function fetchAgentSkillBindings(agentId: number) {
  const response = await http.get<ApiResponse<AgentSkillBindingRecord[]>>(`/agents/${agentId}/skills`)
  return response.data.data
}

export async function updateAgentSkillBindings(agentId: number, bindings: AgentSkillBindingPayload[]) {
  const response = await http.put<ApiResponse<AgentSkillBindingRecord[]>>(`/agents/${agentId}/skills`, { bindings })
  return response.data.data
}
```

- [ ] **Step 4: 增强 AgentsView**

Modify imports:

```ts
import {
  fetchAgentSkillBindings,
  fetchSkillPackages,
  updateAgentSkillBindings
} from '../api/skill'
import type { AgentSkillBindingPayload, AgentSkillBindingRecord, SkillPackageRecord } from '../types/skill'
```

Add state:

```ts
const showSkillModal = ref(false)
const skillBindingAgent = ref<AgentRecord | null>(null)
const skillPackages = ref<SkillPackageRecord[]>([])
const skillBindings = ref<AgentSkillBindingPayload[]>([])
```

Add operation button in `columns`:

```ts
h(NButton, { size: 'small', tertiary: true, onClick: () => openSkillBindings(row) }, { default: () => 'Skill 绑定' })
```

Add modal:

```vue
<n-modal
  v-model:show="showSkillModal"
  preset="card"
  title="Skill 绑定"
  class="form-modal"
  :style="{ width: 'min(760px, calc(100vw - 32px))' }"
>
  <n-space vertical>
    <n-alert type="info" :bordered="false">只会在聊天运行时注入已启用且 READY 的 Skill 版本。</n-alert>
    <n-button secondary @click="addSkillBinding">新增绑定</n-button>
    <n-data-table :columns="skillBindingColumns" :data="skillBindings" :pagination="false" />
  </n-space>
  <template #footer>
    <n-space justify="end">
      <n-button @click="showSkillModal = false">取消</n-button>
      <n-button type="primary" @click="saveSkillBindings">保存</n-button>
    </n-space>
  </template>
</n-modal>
```

Add methods:

```ts
const skillOptions = computed(() =>
  skillPackages.value
    .filter((item) => item.currentVersionId && item.currentVersionStatus === 'READY')
    .map((item) => ({
      label: `${item.name}（${item.skillKey}）`,
      value: item.packageId
    }))
)

const skillBindingColumns: DataTableColumns<AgentSkillBindingPayload> = [
  {
    title: 'Skill',
    key: 'skillId',
    render: (row) =>
      h(NSelect, {
        value: row.skillId,
        options: skillOptions.value,
        onUpdateValue: (value: number) => updateBindingSkill(row, value)
      })
  },
  {
    title: '启用',
    key: 'enabled',
    width: 90,
    render: (row) =>
      h(NSwitch, {
        value: row.enabled,
        onUpdateValue: (value: boolean) => {
          row.enabled = value
        }
      })
  },
  {
    title: '排序',
    key: 'sort',
    width: 120,
    render: (row) =>
      h(NInputNumber, {
        value: row.sort,
        min: 0,
        onUpdateValue: (value: number | null) => {
          row.sort = value ?? 0
        }
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: (row) => h(NButton, { size: 'small', tertiary: true, type: 'error', onClick: () => removeSkillBinding(row) }, { default: () => '移除' })
  }
]

async function openSkillBindings(agent: AgentRecord) {
  skillBindingAgent.value = agent
  const [packages, bindings] = await Promise.all([
    fetchSkillPackages(),
    fetchAgentSkillBindings(agent.agentId)
  ])
  skillPackages.value = packages
  skillBindings.value = bindings.map((item: AgentSkillBindingRecord) => ({
    skillId: item.skillId,
    versionId: item.versionId,
    enabled: item.enabled,
    sort: item.sort
  }))
  showSkillModal.value = true
}

function addSkillBinding() {
  const first = skillPackages.value.find((item) => item.currentVersionId && item.currentVersionStatus === 'READY')
  if (!first || !first.currentVersionId) {
    message.warning('暂无 READY 状态的 Skill 版本')
    return
  }
  skillBindings.value.push({
    skillId: first.packageId,
    versionId: first.currentVersionId,
    enabled: true,
    sort: skillBindings.value.length + 1
  })
}

function updateBindingSkill(row: AgentSkillBindingPayload, skillId: number) {
  const selected = skillPackages.value.find((item) => item.packageId === skillId)
  if (!selected?.currentVersionId) {
    return
  }
  row.skillId = selected.packageId
  row.versionId = selected.currentVersionId
}

function removeSkillBinding(row: AgentSkillBindingPayload) {
  skillBindings.value = skillBindings.value.filter((item) => item !== row)
}

async function saveSkillBindings() {
  if (!skillBindingAgent.value) {
    return
  }
  await updateAgentSkillBindings(skillBindingAgent.value.agentId, skillBindings.value)
  message.success('Skill 绑定已保存')
  showSkillModal.value = false
}
```

- [ ] **Step 5: 运行测试**

Run:

```powershell
npm test -- agent-skill-binding.spec.ts
```

Workdir: `admin-web`

Expected: PASS。

- [ ] **Step 6: 提交**

Run:

```powershell
git add admin-web/src/types/skill.ts admin-web/src/api/skill.ts admin-web/src/views/AgentsView.vue admin-web/tests/agent-skill-binding.spec.ts
git commit -m "新增 Agent Skill 绑定入口"
```

---

### Task 10: 最终验证和收尾

**Files:**
- No planned edits. If verification exposes failures, edit only the failing module and commit focused fixes.

- [ ] **Step 1: 运行 sandbox-service 全量测试**

Run:

```powershell
go test ./...
```

Workdir: `sandbox-service`

Expected: PASS。

- [ ] **Step 2: 运行后端目标测试**

Run:

```powershell
mvn -pl agent-hub-skill,agent-hub-sandbox,agent-hub-chat,agent-hub-server -am test
```

Workdir: `backend`

Expected: BUILD SUCCESS。

- [ ] **Step 3: 运行管理端测试**

Run:

```powershell
npm test
```

Workdir: `admin-web`

Expected: all test files pass。

- [ ] **Step 4: 运行管理端类型检查**

Run:

```powershell
npm run type-check
```

Workdir: `admin-web`

Expected: exit 0。

- [ ] **Step 5: 检查工作区**

Run:

```powershell
git status --short
```

Workdir: repository root.

Expected: no output. If output exists, inspect with `git diff --stat` and commit intentional changes.

- [ ] **Step 6: 汇总**

Report:

```text
- sandbox-service Skill zip 校验和同步接口完成
- agent-hub-skill 模块、迁移、上传和校验完成
- Agent Skill 绑定和 ChatService 运行时注入完成
- admin-web Skill 库和 Agent 绑定入口完成
- 验证命令和结果
```
