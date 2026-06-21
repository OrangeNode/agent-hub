package agent

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestServerHealth(t *testing.T) {
	server := NewServer(NewExecutor(1024), NewFileService(t.TempDir()))
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `"status":"UP"`) {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestServerExecStreamRejectsDangerousCommand(t *testing.T) {
	executor := &recordingExecutor{}
	server := NewServer(executor, NewFileService(t.TempDir()))
	body := bytes.NewBufferString(`{"command":"sudo whoami","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/exec/stream", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	response := rec.Body.String()
	if !strings.Contains(response, "event: error") {
		t.Fatalf("expected error SSE, got %s", response)
	}
	if strings.Contains(response, "event: started") {
		t.Fatalf("started event should not be emitted on policy rejection, got %s", response)
	}
	if executor.called {
		t.Fatal("executor should not be called when policy rejects the command")
	}
}

func TestServerExecStreamRejectsSymlinkWorkspaceArgument(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o644); err != nil {
		t.Fatalf("WriteFile outside error = %v", err)
	}
	createSymlinkOrSkip(t, outside, filepath.Join(root, "link"))
	executor := &recordingExecutor{}
	server := NewServer(executor, NewFileService(root))
	body := bytes.NewBufferString(`{"command":"cat /workspace/link/secret.txt","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/exec/stream", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	response := rec.Body.String()
	if !strings.Contains(response, "event: error") {
		t.Fatalf("expected error SSE, got %s", response)
	}
	if executor.called {
		t.Fatal("executor should not be called when exec path validation rejects the command")
	}
}

func TestServerExecStreamRejectsSymlinkWorkingDir(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o644); err != nil {
		t.Fatalf("WriteFile outside error = %v", err)
	}
	createSymlinkOrSkip(t, outside, filepath.Join(root, "link"))
	executor := &recordingExecutor{}
	server := NewServer(executor, NewFileService(root))
	body := bytes.NewBufferString(`{"command":"cat secret.txt","workingDir":"/workspace/link","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/exec/stream", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	response := rec.Body.String()
	if !strings.Contains(response, "event: error") {
		t.Fatalf("expected error SSE, got %s", response)
	}
	if executor.called {
		t.Fatal("executor should not be called when workingDir validation rejects the command")
	}
}

func TestServerExecStreamAllowsTmpWorkingDirAndArguments(t *testing.T) {
	tmpDocxWork := filepath.Join(os.TempDir(), "docx-work")
	if _, err := os.Stat(tmpDocxWork); os.IsNotExist(err) {
		if err := os.MkdirAll(tmpDocxWork, 0o755); err != nil {
			t.Fatalf("MkdirAll tmp docx work error = %v", err)
		}
		t.Cleanup(func() {
			_ = os.RemoveAll(tmpDocxWork)
		})
	} else if err != nil {
		t.Fatalf("Stat tmp docx work error = %v", err)
	}
	executor := &recordingExecutor{}
	server := NewServer(executor, NewFileService(t.TempDir()))
	tests := []struct {
		name       string
		body       string
		workingDir string
	}{
		{
			name:       "tmp argument",
			body:       `{"command":"cp /workspace/artifacts/Program.cs /tmp/docx-work/Program.cs","workingDir":"/workspace","timeoutSeconds":5}`,
			workingDir: "/workspace",
		},
		{
			name:       "tmp working dir",
			body:       `{"command":"pwd","workingDir":"/tmp/docx-work","timeoutSeconds":5}`,
			workingDir: "/tmp/docx-work",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			executor.called = false
			executor.req = ExecRequest{}
			req := httptest.NewRequest(http.MethodPost, "/exec/stream", bytes.NewBufferString(tt.body))
			rec := httptest.NewRecorder()

			server.Router().ServeHTTP(rec, req)

			response := rec.Body.String()
			if !strings.Contains(response, "event: started") {
				t.Fatalf("expected started SSE, got %s", response)
			}
			if strings.Contains(response, "event: error") {
				t.Fatalf("unexpected error SSE, got %s", response)
			}
			if !executor.called {
				t.Fatal("executor should be called when /tmp exec paths pass validation")
			}
			if executor.req.WorkingDir != tt.workingDir {
				t.Fatalf("workingDir = %q, want %q", executor.req.WorkingDir, tt.workingDir)
			}
		})
	}
}

func TestServerExecStreamRejectsSymlinkFollowingOptions(t *testing.T) {
	for _, command := range []string{"find -L /workspace", "find /workspace -follow"} {
		executor := &recordingExecutor{}
		server := NewServer(executor, NewFileService(t.TempDir()))
		body := bytes.NewBufferString(`{"command":"` + command + `","workingDir":"/workspace","timeoutSeconds":5}`)
		req := httptest.NewRequest(http.MethodPost, "/exec/stream", body)
		rec := httptest.NewRecorder()

		server.Router().ServeHTTP(rec, req)

		response := rec.Body.String()
		if !strings.Contains(response, "event: error") {
			t.Fatalf("expected error SSE for %q, got %s", command, response)
		}
		if !strings.Contains(response, "符号链接") {
			t.Fatalf("expected symlink error for %q, got %s", command, response)
		}
		if executor.called {
			t.Fatalf("executor should not be called for %q", command)
		}
	}
}

func TestServerExecStreamAllowsWorkspaceContainingUnusedSymlink(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "file.txt"), []byte("safe"), 0o644); err != nil {
		t.Fatalf("WriteFile file error = %v", err)
	}
	createSymlinkOrSkip(t, filepath.Join(root, "file.txt"), filepath.Join(root, "file-link"))
	executor := &recordingExecutor{}
	server := NewServer(executor, NewFileService(root))
	body := bytes.NewBufferString(`{"command":"cat /workspace/file.txt","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/exec/stream", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	response := rec.Body.String()
	if !strings.Contains(response, "event: started") {
		t.Fatalf("expected started SSE, got %s", response)
	}
	if strings.Contains(response, "event: error") {
		t.Fatalf("unexpected error SSE, got %s", response)
	}
	if !executor.called {
		t.Fatal("executor should be called when symlink is not part of command path")
	}
}

func TestServerExecStreamRejectsExplicitSymlinkPath(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "file.txt"), []byte("safe"), 0o644); err != nil {
		t.Fatalf("WriteFile file error = %v", err)
	}
	createSymlinkOrSkip(t, filepath.Join(root, "file.txt"), filepath.Join(root, "file-link"))
	executor := &recordingExecutor{}
	server := NewServer(executor, NewFileService(root))
	body := bytes.NewBufferString(`{"command":"cat /workspace/file-link","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/exec/stream", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	response := rec.Body.String()
	if !strings.Contains(response, "event: error") {
		t.Fatalf("expected error SSE, got %s", response)
	}
	if !strings.Contains(response, "符号链接") {
		t.Fatalf("expected symlink error, got %s", response)
	}
	if executor.called {
		t.Fatal("executor should not be called when command references symlink")
	}
}

func TestServerFileAPIWriteReadAndList(t *testing.T) {
	server := NewServer(NewExecutor(1024), NewFileService(t.TempDir()))
	writeBody, _ := json.Marshal(map[string]any{
		"path":    "docs/a.txt",
		"content": "hello",
	})
	writeReq := httptest.NewRequest(http.MethodPost, "/files/content", bytes.NewReader(writeBody))
	writeRec := httptest.NewRecorder()

	server.Router().ServeHTTP(writeRec, writeReq)
	if writeRec.Code != http.StatusOK {
		t.Fatalf("write status = %d, body = %s", writeRec.Code, writeRec.Body.String())
	}

	readReq := httptest.NewRequest(http.MethodGet, "/files/content?path=docs/a.txt&maxBytes=20", nil)
	readRec := httptest.NewRecorder()
	server.Router().ServeHTTP(readRec, readReq)
	if readRec.Code != http.StatusOK || !strings.Contains(readRec.Body.String(), `"content":"hello"`) {
		t.Fatalf("read status = %d, body = %s", readRec.Code, readRec.Body.String())
	}

	listReq := httptest.NewRequest(http.MethodGet, "/files?path=docs&recursive=false&maxFiles=20", nil)
	listRec := httptest.NewRecorder()
	server.Router().ServeHTTP(listRec, listReq)
	if listRec.Code != http.StatusOK || !strings.Contains(listRec.Body.String(), `"relativePath":"docs/a.txt"`) {
		t.Fatalf("list status = %d, body = %s", listRec.Code, listRec.Body.String())
	}
}

func TestServerFileAPIWritesBase64Content(t *testing.T) {
	root := t.TempDir()
	server := NewServer(NewExecutor(1024), NewFileService(root))
	content := []byte{0x50, 0x4b, 0x03, 0x04, 0xff, 0x00, 0x80}
	writeBody, _ := json.Marshal(map[string]any{
		"path":          "input/slide.png",
		"contentBase64": base64.StdEncoding.EncodeToString(content),
	})
	writeReq := httptest.NewRequest(http.MethodPost, "/files/content", bytes.NewReader(writeBody))
	writeRec := httptest.NewRecorder()

	server.Router().ServeHTTP(writeRec, writeReq)

	if writeRec.Code != http.StatusOK {
		t.Fatalf("write status = %d, body = %s", writeRec.Code, writeRec.Body.String())
	}
	actual, err := os.ReadFile(filepath.Join(root, "input", "slide.png"))
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	if !bytes.Equal(actual, content) {
		t.Fatalf("content = %v, want %v", actual, content)
	}
}

func TestServerReadFileUsesDefaultLimit(t *testing.T) {
	server := NewServer(NewExecutor(1024), NewFileService(t.TempDir()))
	content := strings.Repeat("a", defaultReadMaxBytes+1)
	writeBody, _ := json.Marshal(map[string]any{
		"path":    "big.txt",
		"content": content,
	})
	writeReq := httptest.NewRequest(http.MethodPost, "/files/content", bytes.NewReader(writeBody))
	writeRec := httptest.NewRecorder()
	server.Router().ServeHTTP(writeRec, writeReq)
	if writeRec.Code != http.StatusOK {
		t.Fatalf("write status = %d, body = %s", writeRec.Code, writeRec.Body.String())
	}

	readReq := httptest.NewRequest(http.MethodGet, "/files/content?path=big.txt", nil)
	readRec := httptest.NewRecorder()
	server.Router().ServeHTTP(readRec, readReq)
	if readRec.Code != http.StatusOK {
		t.Fatalf("read status = %d, body = %s", readRec.Code, readRec.Body.String())
	}
	if !strings.Contains(readRec.Body.String(), `"truncated":true`) {
		t.Fatalf("expected default read to be truncated, body = %s", readRec.Body.String())
	}
}

func TestServerReadFileIncludesBase64Content(t *testing.T) {
	root := t.TempDir()
	content := []byte{0x50, 0x4b, 0x03, 0x04, 0xff, 0x00, 0x80}
	if err := os.WriteFile(filepath.Join(root, "binary.docx"), content, 0o644); err != nil {
		t.Fatalf("WriteFile binary error = %v", err)
	}
	server := NewServer(NewExecutor(1024), NewFileService(root))
	req := httptest.NewRequest(http.MethodGet, "/files/content?path=binary.docx&maxBytes=20", nil)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var response struct {
		Content       string `json:"content"`
		ContentBase64 string `json:"contentBase64"`
		Truncated     bool   `json:"truncated"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&response); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if response.ContentBase64 != base64.StdEncoding.EncodeToString(content) {
		t.Fatalf("contentBase64 = %q", response.ContentBase64)
	}
}

type recordingExecutor struct {
	called bool
	req    ExecRequest
}

func (e *recordingExecutor) Exec(ctx context.Context, req ExecRequest, emit func(ExecEvent)) ExecResult {
	e.called = true
	e.req = req
	return ExecResult{ExitCode: 0}
}
