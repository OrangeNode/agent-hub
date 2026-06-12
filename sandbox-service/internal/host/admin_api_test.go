package host

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"agent-hub/sandbox-service/internal/auth"
)

func TestAdminStatusRequiresInternalToken(t *testing.T) {
	runDir := mustCreateRunDir(t)
	handler := adminTestHandler(&AdminServer{
		Manager: NewSessionManager(&fakeVMFactory{}, 1, time.Minute),
		Scanner: ResourceScanner{
			RunDir:        runDir,
			ProcessLister: emptyProcessLister,
			TapLister:     emptyTapLister,
		},
		StartedAt: time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC),
	}, "secret")

	req := httptest.NewRequest(http.MethodGet, "/internal/admin/sandbox/status", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status without token = %d", rec.Code)
	}

	req = httptest.NewRequest(http.MethodGet, "/internal/admin/sandbox/status", nil)
	req.Header.Set("Authorization", "Bearer secret")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status with token = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestAdminStatusReturnsRuntimeAndResourceSummary(t *testing.T) {
	runDir := mustCreateRunDir(t)
	liveDir := filepath.Join(runDir, "fc-live")
	orphanDir := filepath.Join(runDir, "fc-orphan")
	if err := os.MkdirAll(liveDir, 0o755); err != nil {
		t.Fatalf("mkdir liveDir: %v", err)
	}
	if err := os.MkdirAll(orphanDir, 0o755); err != nil {
		t.Fatalf("mkdir orphanDir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(orphanDir, "rootfs.ext4"), []byte("rootfs"), 0o644); err != nil {
		t.Fatalf("write orphan rootfs: %v", err)
	}

	manager := NewSessionManager(&fakeVMFactory{vms: []*fakeVM{{
		id:     "fc-live",
		runDir: liveDir,
		pid:    1001,
	}}}, 2, time.Minute)
	if _, err := manager.GetOrCreate("conversation-1"); err != nil {
		t.Fatalf("GetOrCreate() error = %v", err)
	}
	warmPool := NewWarmPool(&fakeVMFactory{}, 1)
	if err := warmPool.Release(&fakeVM{id: "fc-warm", runDir: filepath.Join(runDir, "fc-warm"), pid: 1002}); err != nil {
		t.Fatalf("Release() error = %v", err)
	}

	handler := adminTestHandler(&AdminServer{
		Manager:  manager,
		WarmPool: warmPool,
		Scanner: ResourceScanner{
			RunDir: runDir,
			ProcessLister: func() ([]ProcessInfo, error) {
				return []ProcessInfo{{PID: 1001, Args: "firecracker --api-sock " + filepath.Join(liveDir, "firecracker.socket")}}, nil
			},
			TapLister: emptyTapLister,
		},
		StartedAt: time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC),
	}, "secret")

	req := httptest.NewRequest(http.MethodGet, "/internal/admin/sandbox/status", nil)
	req.Header.Set("Authorization", "Bearer secret")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var body AdminStatusResponse
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if !body.ServiceHealthy {
		t.Fatal("ServiceHealthy = false")
	}
	if body.ActiveSessions != 1 {
		t.Fatalf("ActiveSessions = %d", body.ActiveSessions)
	}
	if body.WarmPool.Size != 1 {
		t.Fatalf("WarmPool.Size = %d", body.WarmPool.Size)
	}
	if body.ResourceSummary.OrphanResources != 1 {
		t.Fatalf("OrphanResources = %d, resources = %#v", body.ResourceSummary.OrphanResources, body.Resources.Orphans)
	}
	if body.ResourceSummary.RunDirBytes == 0 {
		t.Fatalf("RunDirBytes = %d", body.ResourceSummary.RunDirBytes)
	}
}

func TestCleanupOrphansDryRunDoesNotDelete(t *testing.T) {
	runDir := mustCreateRunDir(t)
	orphanDir := filepath.Join(runDir, "fc-orphan")
	if err := os.MkdirAll(orphanDir, 0o755); err != nil {
		t.Fatalf("mkdir orphanDir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(orphanDir, "rootfs.ext4"), []byte("rootfs"), 0o644); err != nil {
		t.Fatalf("write orphan rootfs: %v", err)
	}
	handler := adminTestHandler(&AdminServer{
		Manager: NewSessionManager(&fakeVMFactory{}, 1, time.Minute),
		Scanner: ResourceScanner{
			RunDir:        runDir,
			ProcessLister: emptyProcessLister,
			TapLister:     emptyTapLister,
		},
	}, "secret")

	req := httptest.NewRequest(http.MethodPost, "/internal/admin/sandbox/resources/cleanup-orphans", bytes.NewBufferString(`{"dryRun":true}`))
	req.Header.Set("Authorization", "Bearer secret")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var result ControlResult
	if err := json.NewDecoder(rec.Body).Decode(&result); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if !result.DryRun || !result.Success {
		t.Fatalf("result = %#v", result)
	}
	if !containsString(result.Affected, orphanDir) {
		t.Fatalf("affected = %#v, want %s", result.Affected, orphanDir)
	}
	if _, err := os.Stat(orphanDir); err != nil {
		t.Fatalf("orphanDir should still exist after dry-run: %v", err)
	}
}

func TestKillWarmPoolVMDryRunDoesNotRemoveVM(t *testing.T) {
	runDir := mustCreateRunDir(t)
	vm := &fakeVM{id: "fc-warm", runDir: filepath.Join(runDir, "fc-warm")}
	warmPool := NewWarmPool(&fakeVMFactory{}, 1)
	if err := warmPool.Release(vm); err != nil {
		t.Fatalf("Release() error = %v", err)
	}
	handler := adminTestHandler(&AdminServer{
		Manager:  NewSessionManager(&fakeVMFactory{}, 1, time.Minute),
		WarmPool: warmPool,
		Scanner: ResourceScanner{
			RunDir:        runDir,
			ProcessLister: emptyProcessLister,
			TapLister:     emptyTapLister,
		},
	}, "secret")

	req := httptest.NewRequest(http.MethodPost, "/internal/admin/sandbox/vms/fc-warm/kill", strings.NewReader(`{"dryRun":true}`))
	req.Header.Set("Authorization", "Bearer secret")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if warmPool.Size() != 1 {
		t.Fatalf("warm pool size = %d, want 1", warmPool.Size())
	}
	if vm.StopCount() != 0 {
		t.Fatalf("StopCount() = %d", vm.StopCount())
	}
	var result ControlResult
	if err := json.NewDecoder(rec.Body).Decode(&result); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if !result.DryRun || !result.Success || len(result.Items) != 1 || result.Items[0].Status != "DRY_RUN" {
		t.Fatalf("result = %#v", result)
	}
}

func TestRebuildWarmPoolDryRunDoesNotRebuild(t *testing.T) {
	warmPool := NewWarmPool(&fakeVMFactory{}, 1)
	handler := adminTestHandler(&AdminServer{
		Manager:  NewSessionManager(&fakeVMFactory{}, 1, time.Minute),
		WarmPool: warmPool,
		Scanner: ResourceScanner{
			RunDir:        mustCreateRunDir(t),
			ProcessLister: emptyProcessLister,
			TapLister:     emptyTapLister,
		},
	}, "secret")

	req := httptest.NewRequest(http.MethodPost, "/internal/admin/sandbox/warm-pool/rebuild", strings.NewReader(`{"dryRun":true}`))
	req.Header.Set("Authorization", "Bearer secret")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if warmPool.Size() != 0 {
		t.Fatalf("warm pool size = %d, want 0", warmPool.Size())
	}
	var result ControlResult
	if err := json.NewDecoder(rec.Body).Decode(&result); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if !result.DryRun || !result.Success || len(result.Items) != 1 || result.Items[0].Status != "DRY_RUN" {
		t.Fatalf("result = %#v", result)
	}
}

func TestClearRunDirRejectsUnsafePath(t *testing.T) {
	unsafeDir := filepath.Join(t.TempDir(), "sandbox")
	child := filepath.Join(unsafeDir, "fc-unsafe")
	if err := os.MkdirAll(child, 0o755); err != nil {
		t.Fatalf("mkdir child: %v", err)
	}
	handler := adminTestHandler(&AdminServer{
		Manager: NewSessionManager(&fakeVMFactory{}, 1, time.Minute),
		Scanner: ResourceScanner{
			RunDir:        unsafeDir,
			ProcessLister: emptyProcessLister,
			TapLister:     emptyTapLister,
		},
	}, "secret")

	req := httptest.NewRequest(http.MethodPost, "/internal/admin/sandbox/run-dir/clear", strings.NewReader(`{"dryRun":false}`))
	req.Header.Set("Authorization", "Bearer secret")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if _, err := os.Stat(child); err != nil {
		t.Fatalf("unsafe child should not be deleted: %v", err)
	}
}

func adminTestHandler(server *AdminServer, token string) http.Handler {
	mux := http.NewServeMux()
	server.Register(mux)
	return auth.RequireToken(token)(mux)
}

func mustCreateRunDir(t *testing.T) string {
	t.Helper()
	runDir := filepath.Join(t.TempDir(), "run")
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		t.Fatalf("mkdir runDir: %v", err)
	}
	return runDir
}

func emptyProcessLister() ([]ProcessInfo, error) {
	return nil, nil
}

func emptyTapLister() ([]TapInfo, error) {
	return nil, nil
}

func containsString(items []string, want string) bool {
	for _, item := range items {
		if item == want {
			return true
		}
	}
	return false
}
