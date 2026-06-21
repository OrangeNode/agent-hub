package agent

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
)

func TestFileServiceWriteReadAndList(t *testing.T) {
	service := NewFileService(t.TempDir())

	if err := service.WriteFile("notes/a.txt", []byte("hello"), false); err != nil {
		t.Fatalf("WriteFile error = %v", err)
	}
	if err := service.WriteFile("notes/a.txt", []byte(" world"), true); err != nil {
		t.Fatalf("append WriteFile error = %v", err)
	}

	content, err := service.ReadFile("notes/a.txt", 100)
	if err != nil {
		t.Fatalf("ReadFile error = %v", err)
	}
	if string(content.Content) != "hello world" {
		t.Fatalf("content = %q, want hello world", string(content.Content))
	}

	files, err := service.ListFiles("notes", false, 20)
	if err != nil {
		t.Fatalf("ListFiles error = %v", err)
	}
	if len(files) != 1 || files[0].RelativePath != "notes/a.txt" {
		t.Fatalf("files = %#v, want notes/a.txt", files)
	}
}

func TestFileServiceReadTruncates(t *testing.T) {
	service := NewFileService(t.TempDir())
	if err := service.WriteFile("big.txt", []byte("hello"), false); err != nil {
		t.Fatalf("WriteFile error = %v", err)
	}

	content, err := service.ReadFile("big.txt", 3)
	if err != nil {
		t.Fatalf("ReadFile error = %v", err)
	}
	if string(content.Content) != "hel" {
		t.Fatalf("content = %q, want hel", string(content.Content))
	}
	if !content.Truncated {
		t.Fatal("Truncated = false, want true")
	}
}

func TestFileServiceWriteTempWorkFile(t *testing.T) {
	service := NewFileService(t.TempDir())
	target := "/tmp/agent-hub-file-service-test/Program.cs"
	t.Cleanup(func() {
		_ = os.RemoveAll(filepath.Join(os.TempDir(), "agent-hub-file-service-test"))
	})

	if err := service.WriteFile(target, []byte("class Program {}"), false); err != nil {
		t.Fatalf("WriteFile temp work error = %v", err)
	}
	data, err := os.ReadFile(filepath.Join(os.TempDir(), "agent-hub-file-service-test", "Program.cs"))
	if err != nil {
		t.Fatalf("ReadFile temp work error = %v", err)
	}
	if string(data) != "class Program {}" {
		t.Fatalf("content = %q, want class Program {}", string(data))
	}
}

func TestFileServiceReadAndListTempWorkFile(t *testing.T) {
	service := NewFileService(t.TempDir())
	target := "/tmp/agent-hub-file-service-test-read/data.txt"
	t.Cleanup(func() {
		_ = os.RemoveAll(filepath.Join(os.TempDir(), "agent-hub-file-service-test-read"))
	})

	if err := service.WriteFile(target, []byte("tmp-ok"), false); err != nil {
		t.Fatalf("WriteFile temp work error = %v", err)
	}
	content, err := service.ReadFile(target, 100)
	if err != nil {
		t.Fatalf("ReadFile temp work error = %v", err)
	}
	if string(content.Content) != "tmp-ok" {
		t.Fatalf("content = %q, want tmp-ok", string(content.Content))
	}
	files, err := service.ListFiles("/tmp/agent-hub-file-service-test-read", false, 20)
	if err != nil {
		t.Fatalf("ListFiles temp work error = %v", err)
	}
	if len(files) != 1 || files[0].RelativePath != "/tmp/agent-hub-file-service-test-read/data.txt" {
		t.Fatalf("files = %#v, want temp data file", files)
	}
}

func TestFileServiceRejectsPathOutsideWorkspaceAndTempWrite(t *testing.T) {
	service := NewFileService(t.TempDir())
	outside := writablePathOutsideWorkspaceAndTemp(t, service)

	if err := service.WriteFile(outside, []byte("bad"), false); err == nil {
		t.Fatal("expected WriteFile to reject path outside workspace and temp")
	}
}

func writablePathOutsideWorkspaceAndTemp(t *testing.T, service *FileService) string {
	t.Helper()

	uniqueName := "agent-hub-outside-" + filepath.Base(t.TempDir())
	candidates := []string{
		filepath.Join(filepath.Dir(service.canonicalTempRoot), uniqueName),
		filepath.Join(filepath.Dir(service.canonicalRoot), uniqueName),
	}
	if filepath.Separator == '/' {
		candidates = append([]string{filepath.Join("/var/tmp", uniqueName)}, candidates...)
	}

	for _, candidate := range candidates {
		candidate = filepath.Clean(candidate)
		if isInsideBase(service.canonicalRoot, candidate) || isInsideBase(service.canonicalTempRoot, candidate) {
			continue
		}
		if err := os.MkdirAll(candidate, 0o755); err != nil {
			continue
		}
		t.Cleanup(func() {
			_ = os.RemoveAll(candidate)
		})
		return filepath.Join(candidate, "Program.cs")
	}

	t.Fatal("no writable directory outside workspace and temp root")
	return ""
}

func TestFileServiceScanArtifacts(t *testing.T) {
	root := t.TempDir()
	service := NewFileService(root)
	if err := os.MkdirAll(filepath.Join(root, "artifacts"), 0o755); err != nil {
		t.Fatalf("MkdirAll error = %v", err)
	}
	if err := service.WriteFile("artifacts/report.txt", []byte("report"), false); err != nil {
		t.Fatalf("WriteFile error = %v", err)
	}

	artifacts, err := service.ScanArtifacts("artifacts")
	if err != nil {
		t.Fatalf("ScanArtifacts error = %v", err)
	}
	if len(artifacts) != 1 {
		t.Fatalf("len(artifacts) = %d, want 1", len(artifacts))
	}
	sum := sha256.Sum256([]byte("report"))
	if artifacts[0].RelativePath != "report.txt" || artifacts[0].Size != 6 || artifacts[0].SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("artifact = %#v", artifacts[0])
	}
}

func TestFileServiceRejectsPathEscape(t *testing.T) {
	service := NewFileService(t.TempDir())

	if err := service.WriteFile("../escape.txt", []byte("bad"), false); err == nil {
		t.Fatal("expected WriteFile to reject escaped path")
	}
	if _, err := service.ReadFile("../escape.txt", 100); err == nil {
		t.Fatal("expected ReadFile to reject escaped path")
	}
	if _, err := service.ListFiles("../", false, 20); err == nil {
		t.Fatal("expected ListFiles to reject escaped path")
	}
	if _, err := service.ScanArtifacts("../"); err == nil {
		t.Fatal("expected ScanArtifacts to reject escaped path")
	}
}

func TestFileServiceRejectsSymlinkEscapeForReadListAndScan(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o644); err != nil {
		t.Fatalf("WriteFile outside error = %v", err)
	}
	createSymlinkOrSkip(t, outside, filepath.Join(root, "link"))

	service := NewFileService(root)
	if _, err := service.ReadFile("link/secret.txt", 100); err == nil {
		t.Fatal("expected ReadFile to reject symlink escape")
	}
	if _, err := service.ListFiles("link", false, 20); err == nil {
		t.Fatal("expected ListFiles to reject symlink escape")
	}
	if _, err := service.ScanArtifacts("link"); err == nil {
		t.Fatal("expected ScanArtifacts to reject symlink escape")
	}
}

func TestFileServiceRejectsSymlinkEscapeForWrite(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	createSymlinkOrSkip(t, outside, filepath.Join(root, "link"))

	service := NewFileService(root)
	if err := service.WriteFile("link/created.txt", []byte("bad"), false); err == nil {
		t.Fatal("expected WriteFile to reject symlink parent escape")
	}
	if _, err := os.Stat(filepath.Join(outside, "created.txt")); err == nil {
		t.Fatal("outside file should not be created through symlink")
	}

	if err := os.WriteFile(filepath.Join(outside, "target.txt"), []byte("old"), 0o644); err != nil {
		t.Fatalf("WriteFile outside target error = %v", err)
	}
	createSymlinkOrSkip(t, filepath.Join(outside, "target.txt"), filepath.Join(root, "file-link"))
	if err := service.WriteFile("file-link", []byte("bad"), false); err == nil {
		t.Fatal("expected WriteFile to reject existing symlink target escape")
	}
}

func TestFileServiceRejectsSymlinkInsideRoot(t *testing.T) {
	root := t.TempDir()
	realDir := filepath.Join(root, "real")
	if err := os.MkdirAll(realDir, 0o755); err != nil {
		t.Fatalf("MkdirAll realDir error = %v", err)
	}
	if err := os.WriteFile(filepath.Join(realDir, "data.txt"), []byte("data"), 0o644); err != nil {
		t.Fatalf("WriteFile data error = %v", err)
	}
	createSymlinkOrSkip(t, realDir, filepath.Join(root, "link"))

	service := NewFileService(root)
	if _, err := service.ReadFile("link/data.txt", 100); err == nil {
		t.Fatal("expected ReadFile to reject symlink inside root")
	}
	if _, err := service.ListFiles("link", false, 20); err == nil {
		t.Fatal("expected ListFiles to reject symlink inside root")
	}
}

func createSymlinkOrSkip(t *testing.T, oldname string, newname string) {
	t.Helper()
	if err := os.Symlink(oldname, newname); err != nil {
		t.Skipf("当前环境无法创建 symlink: %v", err)
	}
}
