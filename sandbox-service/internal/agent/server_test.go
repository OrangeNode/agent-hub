package agent

import (
	"bytes"
	"context"
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
	body := bytes.NewBufferString(`{"command":"bash -c ls","workingDir":"/workspace","timeoutSeconds":5}`)
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

type recordingExecutor struct {
	called bool
}

func (e *recordingExecutor) Exec(ctx context.Context, req ExecRequest, emit func(ExecEvent)) ExecResult {
	e.called = true
	return ExecResult{ExitCode: 0}
}
