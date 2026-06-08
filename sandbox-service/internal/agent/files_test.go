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
