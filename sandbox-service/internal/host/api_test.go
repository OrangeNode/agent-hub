package host

import (
	"bytes"
	"encoding/json"
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
					{"relativePath": "reports/out.txt", "sha256": "sha-1", "size": 2},
				},
			})
		case "/files/content":
			if r.URL.Query().Get("path") != "/workspace/artifacts/reports/out.txt" {
				t.Fatalf("file path = %q", r.URL.Query().Get("path"))
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
	if uploader.uploads[0].Bucket != "bucket" || uploader.uploads[0].Key != "sandbox-artifacts/conversation-1/sha-1/reports/out.txt" {
		t.Fatalf("upload target = %#v", uploader.uploads[0])
	}
	if uploader.uploads[0].Content != "ok" {
		t.Fatalf("uploaded content = %q", uploader.uploads[0].Content)
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
	body := bytes.NewBufferString(`{"command":"bash -c ls","workingDir":"/workspace","timeoutSeconds":5}`)
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

type recordingUploader struct {
	uploads []uploadRecord
}

type uploadRecord struct {
	Bucket  string
	Key     string
	Content string
}

func (u *recordingUploader) Upload(req objectstore.UploadRequest) error {
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

type recordingDownloader struct {
	called bool
}

func (d *recordingDownloader) Download(req objectstore.DownloadRequest) (io.ReadCloser, error) {
	d.called = true
	return io.NopCloser(strings.NewReader("content")), nil
}
