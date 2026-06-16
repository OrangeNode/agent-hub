package host

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"agent-hub/sandbox-service/internal/objectstore"
)

func TestNewRouterHealthReturnsUp(t *testing.T) {
	res := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)

	NewRouter().ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("code = %d", res.Code)
	}

	var body map[string]string
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if body["status"] != "UP" {
		t.Fatalf("status = %q", body["status"])
	}
}

func TestCreateSessionReturnsSessionID(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 2, 5*time.Minute)
	body := bytes.NewBufferString(`{"conversationId":"conversation-1"}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", body)
	rec := httptest.NewRecorder()

	NewRouterWithManager(manager).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var response struct {
		SandboxSessionID string `json:"sandboxSessionId"`
		Status           string `json:"status"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&response); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if response.SandboxSessionID == "" {
		t.Fatal("expected sandboxSessionId")
	}
	if response.Status != "ready" {
		t.Fatalf("status = %q", response.Status)
	}
	if strings.Contains(rec.Body.String(), "VM") || strings.Contains(rec.Body.String(), "agentURL") {
		t.Fatalf("response exposes internal handle: %s", rec.Body.String())
	}
}

func TestCreateSessionReusesConversation(t *testing.T) {
	factory := &fakeVMFactory{}
	manager := NewSessionManager(factory, 2, 5*time.Minute)
	router := NewRouterWithManager(manager)

	first := postJSON(t, router, "/internal/sandbox/sessions", `{"conversationId":"conversation-1"}`)
	second := postJSON(t, router, "/internal/sandbox/sessions", `{"conversationId":"conversation-1"}`)

	var firstBody, secondBody map[string]string
	if err := json.NewDecoder(first.Body).Decode(&firstBody); err != nil {
		t.Fatalf("Decode(first) error = %v", err)
	}
	if err := json.NewDecoder(second.Body).Decode(&secondBody); err != nil {
		t.Fatalf("Decode(second) error = %v", err)
	}
	if firstBody["sandboxSessionId"] != secondBody["sandboxSessionId"] {
		t.Fatalf("session changed: first=%q second=%q", firstBody["sandboxSessionId"], secondBody["sandboxSessionId"])
	}
	if factory.StartCount() != 1 {
		t.Fatalf("starts = %d, want 1", factory.StartCount())
	}
}

func TestCreateSessionReturnsCapacityError(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 1, 5*time.Minute)
	router := NewRouterWithManager(manager)
	_ = postJSON(t, router, "/internal/sandbox/sessions", `{"conversationId":"conversation-1"}`)

	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", bytes.NewBufferString(`{"conversationId":"conversation-2"}`))
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "当前沙箱会话数量已达到上限") {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestExecStreamProxiesGuestSSEAndSyncsArtifacts(t *testing.T) {
	guestCalled := false
	artifactSHA := sha256Hex("ok")
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/exec/stream":
			guestCalled = true
			w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
			_, _ = w.Write([]byte("event: stdout_delta\n"))
			_, _ = w.Write([]byte(`data: {"content":"ok"}` + "\n\n"))
			_, _ = w.Write([]byte("event: completed\n"))
			_, _ = w.Write([]byte(`data: {"exitCode":0}` + "\n\n"))
		case "/artifacts":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"artifacts": []map[string]any{
					{"relativePath": "reports/out.txt", "sha256": artifactSHA, "size": 2},
				},
			})
		case "/files/content":
			if r.URL.Query().Get("path") != "/workspace/artifacts/reports/out.txt" {
				t.Fatalf("file path = %q", r.URL.Query().Get("path"))
			}
			if r.URL.Query().Get("maxBytes") != fmt.Sprint(maxArtifactBytes+1) {
				t.Fatalf("maxBytes = %q", r.URL.Query().Get("maxBytes"))
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"content": "ok", "truncated": false})
		default:
			t.Fatalf("unexpected guest path %s", r.URL.Path)
		}
	}))
	defer guest.Close()

	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	uploader := &recordingUploader{}
	router := NewRouterWithManager(manager, WithUploader(uploader), WithArtifactSyncer(NewArtifactSyncer("bucket")))
	body := bytes.NewBufferString(`{"command":"cat reports/out.txt","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions/"+session.ID+"/exec/stream", body)
	rec := httptest.NewRecorder()

	router.ServeHTTP(rec, req)

	if !guestCalled {
		t.Fatal("expected guest exec to be called")
	}
	response := rec.Body.String()
	if !strings.Contains(response, "event: stdout_delta") || !strings.Contains(response, "event: completed") {
		t.Fatalf("expected proxied stdout and completed events, got %s", response)
	}
	if !strings.Contains(response, "event: artifact_synced") {
		t.Fatalf("expected artifact_synced event, got %s", response)
	}
	if len(uploader.uploads) != 1 {
		t.Fatalf("uploads = %d, want 1", len(uploader.uploads))
	}
	if uploader.uploads[0].Bucket != "bucket" || uploader.uploads[0].Key != "sandbox-artifacts/conversation-1/"+artifactSHA+"/reports/out.txt" {
		t.Fatalf("upload target = %#v", uploader.uploads[0])
	}
	if uploader.uploads[0].Content != "ok" {
		t.Fatalf("uploaded content = %q", uploader.uploads[0].Content)
	}
}

func TestExecStreamRetriesArtifactUploadUntilMarkedSynced(t *testing.T) {
	artifactSHA := sha256Hex("ok")
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/exec/stream":
			w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
			_, _ = w.Write([]byte("event: completed\n"))
			_, _ = w.Write([]byte(`data: {"exitCode":0}` + "\n\n"))
		case "/artifacts":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"artifacts": []map[string]any{
					{"relativePath": "reports/out.txt", "sha256": artifactSHA, "size": 2},
				},
			})
		case "/files/content":
			_ = json.NewEncoder(w).Encode(map[string]any{"content": "ok", "truncated": false})
		default:
			t.Fatalf("unexpected guest path %s", r.URL.Path)
		}
	}))
	defer guest.Close()

	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	uploader := &recordingUploader{failuresRemaining: 1}
	router := NewRouterWithManager(manager, WithUploader(uploader), WithArtifactSyncer(NewArtifactSyncer("bucket")))

	first := execStream(t, router, session.ID)
	if !strings.Contains(first.Body.String(), "产物同步失败") {
		t.Fatalf("first response = %s", first.Body.String())
	}
	if uploader.Attempts() != 1 {
		t.Fatalf("first attempts = %d, want 1", uploader.Attempts())
	}

	second := execStream(t, router, session.ID)
	if !strings.Contains(second.Body.String(), "event: artifact_synced") {
		t.Fatalf("second response = %s", second.Body.String())
	}
	if uploader.Attempts() != 2 {
		t.Fatalf("second attempts = %d, want 2", uploader.Attempts())
	}

	third := execStream(t, router, session.ID)
	if strings.Contains(third.Body.String(), "event: artifact_synced") {
		t.Fatalf("third response should not sync unchanged artifact: %s", third.Body.String())
	}
	if uploader.Attempts() != 2 {
		t.Fatalf("third attempts = %d, want 2", uploader.Attempts())
	}
}

func TestExecStreamRejectsArtifactChangedDuringSync(t *testing.T) {
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/exec/stream":
			w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
			_, _ = w.Write([]byte("event: completed\n"))
			_, _ = w.Write([]byte(`data: {"exitCode":0}` + "\n\n"))
		case "/artifacts":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"artifacts": []map[string]any{
					{"relativePath": "reports/out.txt", "sha256": sha256Hex("old"), "size": 3},
				},
			})
		case "/files/content":
			_ = json.NewEncoder(w).Encode(map[string]any{"content": "new"})
		default:
			t.Fatalf("unexpected guest path %s", r.URL.Path)
		}
	}))
	defer guest.Close()

	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	uploader := &recordingUploader{}
	router := NewRouterWithManager(manager, WithUploader(uploader), WithArtifactSyncer(NewArtifactSyncer("bucket")))

	rec := execStream(t, router, session.ID)

	if !strings.Contains(rec.Body.String(), "产物同步失败") {
		t.Fatalf("response = %s", rec.Body.String())
	}
	if len(uploader.uploads) != 0 {
		t.Fatalf("uploads = %d, want 0", len(uploader.uploads))
	}
}

func TestExecStreamDoesNotSyncArtifactsWhenGuestDoesNotCompleteSuccessfully(t *testing.T) {
	cases := []struct {
		name string
		sse  string
	}{
		{
			name: "guest error",
			sse:  "event: error\n" + `data: {"message":"failed"}` + "\n\n",
		},
		{
			name: "non zero completed",
			sse:  "event: completed\n" + `data: {"exitCode":1}` + "\n\n",
		},
		{
			name: "timed out completed",
			sse:  "event: completed\n" + `data: {"exitCode":0,"timedOut":true}` + "\n\n",
		},
		{
			name: "completed with error",
			sse:  "event: completed\n" + `data: {"exitCode":0,"error":"boom"}` + "\n\n",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			artifactCalled := false
			guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/exec/stream":
					w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
					_, _ = w.Write([]byte(tc.sse))
				case "/artifacts", "/files/content":
					artifactCalled = true
				default:
					t.Fatalf("unexpected guest path %s", r.URL.Path)
				}
			}))
			defer guest.Close()

			manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
			session, err := manager.GetOrCreate("conversation-1")
			if err != nil {
				t.Fatalf("GetOrCreate() error = %v", err)
			}
			uploader := &recordingUploader{}
			router := NewRouterWithManager(manager, WithUploader(uploader), WithArtifactSyncer(NewArtifactSyncer("bucket")))

			rec := execStream(t, router, session.ID)

			if artifactCalled {
				t.Fatalf("artifact endpoints should not be called, response = %s", rec.Body.String())
			}
			if len(uploader.uploads) != 0 {
				t.Fatalf("uploads = %d, want 0", len(uploader.uploads))
			}
			if !strings.Contains(rec.Body.String(), strings.TrimSpace(tc.sse)) {
				t.Fatalf("expected guest event to be forwarded, got %s", rec.Body.String())
			}
		})
	}
}

func TestProxyExecStreamFlushesStdoutBeforeCompleted(t *testing.T) {
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
		_, _ = w.Write([]byte("event: stdout_delta\n"))
		_, _ = w.Write([]byte(`data: {"content":"ok"}` + "\n\n"))
		_, _ = w.Write([]byte("event: completed\n"))
		_, _ = w.Write([]byte(`data: {"exitCode":0}` + "\n\n"))
	}))
	defer guest.Close()

	server := &apiServer{client: guest.Client()}
	rec := &flushRecorder{header: http.Header{}}
	req := httptest.NewRequest(http.MethodPost, "/exec", nil)

	completedOK, err := server.proxyExecStream(req, rec, Session{AgentURL: guest.URL}, execRequest{
		Command:        "echo ok",
		WorkingDir:     "/workspace",
		TimeoutSeconds: 5,
	})

	if err != nil {
		t.Fatalf("proxyExecStream() error = %v", err)
	}
	if !completedOK {
		t.Fatal("completedOK = false, want true")
	}
	if len(rec.flushes) == 0 {
		t.Fatal("expected at least one flush")
	}
	if !strings.Contains(rec.flushes[0], "event: stdout_delta") {
		t.Fatalf("first flush = %q", rec.flushes[0])
	}
	if strings.Contains(rec.flushes[0], "event: completed") {
		t.Fatalf("stdout should flush before completed, first flush = %q", rec.flushes[0])
	}
}

func TestExecStreamProxyErrorDoesNotExposeGuestURL(t *testing.T) {
	agentURL := "http://guest.internal:7070"
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: agentURL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	client := &http.Client{Transport: failingRoundTripper{}}
	router := NewRouterWithManager(manager, WithHTTPClient(client), WithUploader(&recordingUploader{}))

	rec := execStream(t, router, session.ID)
	body := rec.Body.String()

	if !strings.Contains(body, "沙箱命令执行代理失败") {
		t.Fatalf("body = %s", body)
	}
	if strings.Contains(body, agentURL) || strings.Contains(body, "guest.internal") {
		t.Fatalf("response exposes guest URL: %s", body)
	}
}

func TestExecStreamRejectsTruncatedArtifactContent(t *testing.T) {
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/exec/stream":
			w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
			_, _ = w.Write([]byte("event: completed\n"))
			_, _ = w.Write([]byte(`data: {"exitCode":0}` + "\n\n"))
		case "/artifacts":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"artifacts": []map[string]any{
					{"relativePath": "reports/out.txt", "sha256": "sha-1", "size": maxArtifactBytes + 1},
				},
			})
		case "/files/content":
			_ = json.NewEncoder(w).Encode(map[string]any{"content": "partial", "truncated": true})
		default:
			t.Fatalf("unexpected guest path %s", r.URL.Path)
		}
	}))
	defer guest.Close()

	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	uploader := &recordingUploader{}
	router := NewRouterWithManager(manager, WithUploader(uploader), WithArtifactSyncer(NewArtifactSyncer("bucket")))

	rec := execStream(t, router, session.ID)

	if !strings.Contains(rec.Body.String(), "产物同步失败") {
		t.Fatalf("response = %s", rec.Body.String())
	}
	if len(uploader.uploads) != 0 {
		t.Fatalf("uploads = %d, want 0", len(uploader.uploads))
	}
}

func TestExecStreamRejectsDangerousCommandWithoutCallingGuest(t *testing.T) {
	called := false
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
	}))
	defer guest.Close()
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	body := bytes.NewBufferString(`{"command":"sudo whoami","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions/"+session.ID+"/exec/stream", body)
	rec := httptest.NewRecorder()

	NewRouterWithManager(manager).ServeHTTP(rec, req)

	if called {
		t.Fatal("guest should not be called when policy rejects command")
	}
	if !strings.Contains(rec.Body.String(), "event: error") {
		t.Fatalf("expected error SSE, got %s", rec.Body.String())
	}
}

func TestListFilesProxiesWorkspacePathToGuest(t *testing.T) {
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/files" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if r.URL.Query().Get("path") != "/workspace/input" {
			t.Fatalf("path = %q", r.URL.Query().Get("path"))
		}
		if r.URL.Query().Get("recursive") != "true" {
			t.Fatalf("recursive = %q", r.URL.Query().Get("recursive"))
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"files": []map[string]any{
				{"name": "data.csv", "path": "/workspace/input/data.csv", "size": 10, "directory": false},
			},
		})
	}))
	defer guest.Close()
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	router := NewRouterWithManager(manager)

	req := httptest.NewRequest(http.MethodGet, "/internal/sandbox/sessions/"+session.ID+"/files?path=/workspace/input&recursive=true", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "data.csv") {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestReadFileRejectsPathEscapeBeforeCallingGuest(t *testing.T) {
	called := false
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
	}))
	defer guest.Close()
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	router := NewRouterWithManager(manager)

	req := httptest.NewRequest(http.MethodGet, "/internal/sandbox/sessions/"+session.ID+"/files/content?path=/workspace/../etc/passwd&maxBytes=100", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if called {
		t.Fatal("guest should not be called")
	}
}

func TestWriteFileProxiesAppendModeToGuest(t *testing.T) {
	var written struct {
		Path       string `json:"path"`
		Content    string `json:"content"`
		AppendMode bool   `json:"appendMode"`
	}
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/files/content" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&written); err != nil {
			t.Fatalf("Decode() error = %v", err)
		}
		_ = json.NewEncoder(w).Encode(map[string]bool{"ok": true})
	}))
	defer guest.Close()
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	router := NewRouterWithManager(manager)

	body := bytes.NewBufferString(`{"path":"/workspace/notes.txt","content":"hello","appendMode":true}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions/"+session.ID+"/files/content", body)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if written.Path != "/workspace/notes.txt" || written.Content != "hello" || !written.AppendMode {
		t.Fatalf("written = %#v", written)
	}
}

func TestSyncSkillZipProxiesBodyToGuest(t *testing.T) {
	requestBody := `{"skills":[{"skillId":6,"skillKey":"docx","targetDir":"/workspace/skills/docx"}]}`
	var guestBody string
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/skills/sync-zip" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		data, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("ReadAll() error = %v", err)
		}
		guestBody = string(data)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_, _ = w.Write([]byte(`{"ok":true,"skills":[{"skillId":6,"skillKey":"docx","targetDir":"/workspace/skills/docx"}]}`))
	}))
	defer guest.Close()
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	router := NewRouterWithManager(manager)

	req := httptest.NewRequest(http.MethodPost,
		"/internal/sandbox/sessions/"+session.ID+"/skills/sync-zip",
		strings.NewReader(requestBody))
	rec := httptest.NewRecorder()

	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if guestBody != requestBody {
		t.Fatalf("guest body = %s, want %s", guestBody, requestBody)
	}
	if !strings.Contains(rec.Body.String(), `"ok":true`) {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestSyncSkillZipReturnsNotFoundWhenSessionMissing(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 1, 5*time.Minute)
	router := NewRouterWithManager(manager)
	req := httptest.NewRequest(http.MethodPost,
		"/internal/sandbox/sessions/sbx_missing/skills/sync-zip",
		strings.NewReader(`{"skills":[]}`))
	rec := httptest.NewRecorder()

	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("Content-Type = %q", rec.Header().Get("Content-Type"))
	}
}

func TestWriteFileSyncsArtifactsAfterSuccessfulArtifactWrite(t *testing.T) {
	artifactSHA := sha256Hex("hello")
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/files/content":
			if r.Method == http.MethodPost {
				var written struct {
					Path    string `json:"path"`
					Content string `json:"content"`
				}
				if err := json.NewDecoder(r.Body).Decode(&written); err != nil {
					t.Fatalf("Decode(write) error = %v", err)
				}
				if written.Path != "/workspace/artifacts/output.txt" || written.Content != "hello" {
					t.Fatalf("written = %#v", written)
				}
				_ = json.NewEncoder(w).Encode(map[string]bool{"ok": true})
				return
			}
			if r.Method == http.MethodGet {
				if r.URL.Query().Get("path") != "/workspace/artifacts/output.txt" {
					t.Fatalf("read path = %q", r.URL.Query().Get("path"))
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"content": "hello", "truncated": false})
				return
			}
			t.Fatalf("unexpected method for /files/content: %s", r.Method)
		case "/artifacts":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"artifacts": []map[string]any{
					{"relativePath": "output.txt", "sha256": artifactSHA, "size": 5},
				},
			})
		default:
			t.Fatalf("unexpected guest path %s", r.URL.Path)
		}
	}))
	defer guest.Close()
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	uploader := &recordingUploader{}
	router := NewRouterWithManager(manager, WithUploader(uploader), WithArtifactSyncer(NewArtifactSyncer("bucket")))

	body := bytes.NewBufferString(`{"path":"/workspace/artifacts/output.txt","content":"hello"}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions/"+session.ID+"/files/content", body)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"relativePath":"output.txt"`) {
		t.Fatalf("response should include synced artifacts, got %s", rec.Body.String())
	}
	if len(uploader.uploads) != 1 {
		t.Fatalf("uploads = %d, want 1", len(uploader.uploads))
	}
	if uploader.uploads[0].Bucket != "bucket" || uploader.uploads[0].Content != "hello" {
		t.Fatalf("upload = %#v", uploader.uploads[0])
	}
}

func TestSessionHealthClosesRuntimeSessionWhenGuestUnavailable(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: "http://guest.internal:7070"}}}, 1, 5*time.Minute)
	session, err := manager.GetOrCreate("conversation-1")
	if err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	client := &http.Client{Transport: failingRoundTripper{}}
	router := NewRouterWithManager(manager, WithHTTPClient(client))

	req := httptest.NewRequest(http.MethodGet, "/internal/sandbox/sessions/"+session.ID+"/health", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if manager.HasSession(session.ID) {
		t.Fatal("unhealthy runtime session should be closed")
	}
}

func TestCreateSessionRejectsEscapingRestoreArtifact(t *testing.T) {
	manager := NewSessionManager(&fakeVMFactory{}, 1, 5*time.Minute)
	body := bytes.NewBufferString(`{"conversationId":"conversation-1","artifacts":[{"relativePath":"../secret.txt","bucket":"bucket","key":"k"}]}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", body)
	rec := httptest.NewRecorder()

	NewRouterWithManager(manager, WithDownloader(&recordingDownloader{})).ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "产物路径不能超出工作目录") {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestCreateSessionRestoresArtifactWithStorageFields(t *testing.T) {
	content := "restored content"
	sum := sha256.Sum256([]byte(content))
	var written struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/files/content" {
			t.Fatalf("unexpected guest request %s %s", r.Method, r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&written); err != nil {
			t.Fatalf("Decode(guest write) error = %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer guest.Close()

	downloader := &recordingDownloader{content: content}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	body := bytes.NewBufferString(fmt.Sprintf(
		`{"conversationId":"conversation-1","artifacts":[{"relativePath":"reports/out.txt","storageBucket":"bucket-a","storageKey":"objects/out.txt","sha256":"%x"}]}`,
		sum,
	))
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", body)
	rec := httptest.NewRecorder()

	NewRouterWithManager(manager, WithDownloader(downloader)).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if len(downloader.downloads) != 1 {
		t.Fatalf("downloads = %d, want 1", len(downloader.downloads))
	}
	if downloader.downloads[0].Bucket != "bucket-a" || downloader.downloads[0].Key != "objects/out.txt" {
		t.Fatalf("download target = %#v", downloader.downloads[0])
	}
	if written.Path != "/workspace/artifacts/reports/out.txt" {
		t.Fatalf("written path = %q", written.Path)
	}
	if written.Content != content {
		t.Fatalf("written content = %q", written.Content)
	}
}

func TestCreateSessionRejectsRestoreArtifactWithMismatchedSHA256(t *testing.T) {
	guestCalled := false
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		guestCalled = true
	}))
	defer guest.Close()

	downloader := &recordingDownloader{content: "restored content"}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	body := bytes.NewBufferString(
		`{"conversationId":"conversation-1","artifacts":[{"relativePath":"reports/out.txt","storageBucket":"bucket-a","storageKey":"objects/out.txt","sha256":"0000"}]}`,
	)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", body)
	rec := httptest.NewRecorder()

	NewRouterWithManager(manager, WithDownloader(downloader)).ServeHTTP(rec, req)

	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if guestCalled {
		t.Fatal("guest should not be called when sha256 mismatches")
	}
	if !strings.Contains(rec.Body.String(), "sha256") {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestCreateSessionRejectsOversizedRestoreArtifact(t *testing.T) {
	guestCalled := false
	guest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		guestCalled = true
	}))
	defer guest.Close()

	downloader := &recordingDownloader{content: strings.Repeat("a", maxArtifactBytes+1)}
	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{id: "vm-1", url: guest.URL}}}, 1, 5*time.Minute)
	body := bytes.NewBufferString(
		`{"conversationId":"conversation-1","artifacts":[{"relativePath":"reports/out.txt","storageBucket":"bucket-a","storageKey":"objects/out.txt"}]}`,
	)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions", body)
	rec := httptest.NewRecorder()

	NewRouterWithManager(manager, WithDownloader(downloader)).ServeHTTP(rec, req)

	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if guestCalled {
		t.Fatal("guest should not be called when restore artifact is too large")
	}
	if !strings.Contains(rec.Body.String(), "恢复产物超过大小上限") {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func execStream(t *testing.T, router http.Handler, sessionID string) *httptest.ResponseRecorder {
	t.Helper()
	body := bytes.NewBufferString(`{"command":"cat reports/out.txt","workingDir":"/workspace","timeoutSeconds":5}`)
	req := httptest.NewRequest(http.MethodPost, "/internal/sandbox/sessions/"+sessionID+"/exec/stream", body)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

func postJSON(t *testing.T, router http.Handler, path string, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body))
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("POST %s status = %d, body = %s", path, rec.Code, rec.Body.String())
	}
	return rec
}

func sha256Hex(content string) string {
	sum := sha256.Sum256([]byte(content))
	return fmt.Sprintf("%x", sum)
}

type recordingUploader struct {
	uploads           []uploadRecord
	failuresRemaining int
	attempts          int
}

type uploadRecord struct {
	Bucket  string
	Key     string
	Content string
}

func (u *recordingUploader) Upload(req objectstore.UploadRequest) error {
	u.attempts++
	if u.failuresRemaining > 0 {
		u.failuresRemaining--
		return errors.New("upload failed")
	}
	data, err := io.ReadAll(req.Body)
	if err != nil {
		return err
	}
	u.uploads = append(u.uploads, uploadRecord{
		Bucket:  req.Bucket,
		Key:     req.Key,
		Content: string(data),
	})
	return nil
}

func (u *recordingUploader) Attempts() int {
	return u.attempts
}

type recordingDownloader struct {
	called    bool
	content   string
	downloads []objectstore.DownloadRequest
}

func (d *recordingDownloader) Download(req objectstore.DownloadRequest) (io.ReadCloser, error) {
	d.called = true
	d.downloads = append(d.downloads, req)
	content := d.content
	if content == "" {
		content = "content"
	}
	return io.NopCloser(strings.NewReader(content)), nil
}

type flushRecorder struct {
	header  http.Header
	body    bytes.Buffer
	flushes []string
	status  int
}

func (r *flushRecorder) Header() http.Header {
	return r.header
}

func (r *flushRecorder) Write(data []byte) (int, error) {
	return r.body.Write(data)
}

func (r *flushRecorder) WriteHeader(status int) {
	r.status = status
}

func (r *flushRecorder) Flush() {
	r.flushes = append(r.flushes, r.body.String())
}

type failingRoundTripper struct{}

func (f failingRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	return nil, fmt.Errorf("connect %s failed", req.URL.String())
}
