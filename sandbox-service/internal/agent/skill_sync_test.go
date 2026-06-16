package agent

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestServerSyncSkillZipDownloadsAndExtracts(t *testing.T) {
	root := t.TempDir()
	zipBytes := testZip(t, map[string]string{
		"SKILL.md":               "# Demo",
		"references/style.md":    "style",
		"scripts/render_docx.py": "print('ok')",
	})
	zipServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(zipBytes)
	}))
	defer zipServer.Close()

	server := NewServer(NewExecutor(1024), NewFileService(root))
	body := bytes.NewBufferString(`{"skills":[{"skillId":6,"skillKey":"demo-skill","name":"Demo","zipDownloadUrl":"` +
		zipServer.URL + `","zipSha256":"` + sha256Hex(zipBytes) + `","targetDir":"/workspace/skills/demo-skill"}]}`)
	req := httptest.NewRequest(http.MethodPost, "/skills/sync-zip", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	assertFileContent(t, filepath.Join(root, "skills", "demo-skill", "references", "style.md"), "style")
	assertFileContent(t, filepath.Join(root, "skills", ".archives", "demo-skill.zip"), string(zipBytes))
}

func TestServerSyncSkillZipRejectsUnsafeEntries(t *testing.T) {
	for _, tc := range []struct {
		name  string
		entry string
	}{
		{name: "zip slip", entry: "../evil.txt"},
		{name: "absolute path", entry: "/evil.txt"},
		{name: "windows drive", entry: "C:/evil.txt"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			zipBytes := testZip(t, map[string]string{tc.entry: "bad"})
			server := NewServer(NewExecutor(1024), NewFileService(root))
			rec := postSkillZipSync(t, server, zipBytes, sha256Hex(zipBytes), "/workspace/skills/demo-skill")

			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
			}
			if _, err := os.Stat(filepath.Join(root, "evil.txt")); !os.IsNotExist(err) {
				t.Fatalf("evil file should not exist, stat error = %v", err)
			}
		})
	}
}

func TestServerSyncSkillZipRejectsInvalidTargetDir(t *testing.T) {
	root := t.TempDir()
	zipBytes := testZip(t, map[string]string{"SKILL.md": "# Demo"})
	server := NewServer(NewExecutor(1024), NewFileService(root))
	rec := postSkillZipSync(t, server, zipBytes, sha256Hex(zipBytes), "/workspace/skills/other-skill")

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
}

func TestServerSyncSkillZipRejectsSHA256Mismatch(t *testing.T) {
	root := t.TempDir()
	zipBytes := testZip(t, map[string]string{"SKILL.md": "# Demo"})
	server := NewServer(NewExecutor(1024), NewFileService(root))
	rec := postSkillZipSync(t, server, zipBytes, strings.Repeat("0", 64), "/workspace/skills/demo-skill")

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if _, err := os.Stat(filepath.Join(root, "skills", "demo-skill", "SKILL.md")); !os.IsNotExist(err) {
		t.Fatalf("skill file should not be extracted after sha mismatch, stat error = %v", err)
	}
}

func TestServerSyncSkillZipRejectsDownloadedZipTooLarge(t *testing.T) {
	root := t.TempDir()
	zipServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.CopyN(w, zeroReader{}, maxSkillZipBytes+1)
	}))
	defer zipServer.Close()

	server := NewServer(NewExecutor(1024), NewFileService(root))
	body := bytes.NewBufferString(`{"skills":[{"skillId":6,"skillKey":"demo-skill","name":"Demo","zipDownloadUrl":"` +
		zipServer.URL + `","zipSha256":"` + strings.Repeat("0", 64) + `","targetDir":"/workspace/skills/demo-skill"}]}`)
	req := httptest.NewRequest(http.MethodPost, "/skills/sync-zip", body)
	rec := httptest.NewRecorder()

	server.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "超过大小限制") {
		t.Fatalf("expected size limit error, body = %s", rec.Body.String())
	}
	if _, err := os.Stat(filepath.Join(root, "skills", ".archives", "demo-skill.zip")); !os.IsNotExist(err) {
		t.Fatalf("archive should not be written after oversized download, stat error = %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "skills", "demo-skill")); !os.IsNotExist(err) {
		t.Fatalf("target dir should not be created after oversized download, stat error = %v", err)
	}
}

func TestServerSyncSkillZipRejectsTooManyFilesOrExtractedBytes(t *testing.T) {
	t.Run("too many files", func(t *testing.T) {
		root := t.TempDir()
		zipBytes := testZipManyFiles(t, 2001)
		server := NewServer(NewExecutor(1024), NewFileService(root))
		rec := postSkillZipSync(t, server, zipBytes, sha256Hex(zipBytes), "/workspace/skills/demo-skill")

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
		}
	})

	t.Run("too many extracted bytes", func(t *testing.T) {
		root := t.TempDir()
		zipBytes := testZipLargeFile(t, "large.bin", 100*1024*1024+1)
		server := NewServer(NewExecutor(1024), NewFileService(root))
		rec := postSkillZipSync(t, server, zipBytes, sha256Hex(zipBytes), "/workspace/skills/demo-skill")

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
		}
	})
}

func postSkillZipSync(t *testing.T, server *Server, zipBytes []byte, sha string, targetDir string) *httptest.ResponseRecorder {
	t.Helper()
	zipServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(zipBytes)
	}))
	t.Cleanup(zipServer.Close)

	body := bytes.NewBufferString(`{"skills":[{"skillId":6,"skillKey":"demo-skill","name":"Demo","zipDownloadUrl":"` +
		zipServer.URL + `","zipSha256":"` + sha + `","targetDir":"` + targetDir + `"}]}`)
	req := httptest.NewRequest(http.MethodPost, "/skills/sync-zip", body)
	rec := httptest.NewRecorder()
	server.Router().ServeHTTP(rec, req)
	return rec
}

func testZip(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, content := range files {
		file, err := writer.Create(name)
		if err != nil {
			t.Fatalf("Create zip entry error = %v", err)
		}
		if _, err := file.Write([]byte(content)); err != nil {
			t.Fatalf("Write zip entry error = %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("Close zip error = %v", err)
	}
	return buffer.Bytes()
}

func testZipManyFiles(t *testing.T, count int) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for i := 0; i < count; i++ {
		file, err := writer.Create("files/file-" + hex.EncodeToString([]byte{byte(i >> 8), byte(i)}) + ".txt")
		if err != nil {
			t.Fatalf("Create zip entry error = %v", err)
		}
		if _, err := file.Write([]byte("x")); err != nil {
			t.Fatalf("Write zip entry error = %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("Close zip error = %v", err)
	}
	return buffer.Bytes()
}

func testZipLargeFile(t *testing.T, name string, size int64) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	file, err := writer.Create(name)
	if err != nil {
		t.Fatalf("Create zip entry error = %v", err)
	}
	if _, err := io.Copy(file, io.LimitReader(zeroReader{}, size)); err != nil {
		t.Fatalf("Write zip entry error = %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("Close zip error = %v", err)
	}
	return buffer.Bytes()
}

func sha256Hex(content []byte) string {
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

func assertFileContent(t *testing.T, path string, want string) {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile(%s) error = %v", path, err)
	}
	if string(content) != want {
		t.Fatalf("content of %s = %q, want %q", path, string(content), want)
	}
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 0
	}
	return len(p), nil
}
