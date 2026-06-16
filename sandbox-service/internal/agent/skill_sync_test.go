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

func TestServerSyncSkillZipRejectsInvalidSchemeAndHTTPError(t *testing.T) {
	for _, tc := range []struct {
		name        string
		url         string
		wantMessage string
	}{
		{name: "invalid scheme", url: "file:///tmp/demo-skill.zip", wantMessage: "只支持 http 或 https"},
		{name: "http 500", wantMessage: "HTTP 状态码 500"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			if tc.url == "" {
				zipServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					http.Error(w, "failed", http.StatusInternalServerError)
				}))
				t.Cleanup(zipServer.Close)
				tc.url = zipServer.URL
			}

			server := NewServer(NewExecutor(1024), NewFileService(root))
			rec := postSkillZipSyncURL(t, server, tc.url, strings.Repeat("0", 64), "/workspace/skills/demo-skill")

			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
			}
			if !strings.Contains(rec.Body.String(), tc.wantMessage) {
				t.Fatalf("expected error containing %q, body = %s", tc.wantMessage, rec.Body.String())
			}
			assertPathMissing(t, filepath.Join(root, "skills", ".archives", "demo-skill.zip"))
			assertPathMissing(t, filepath.Join(root, "skills", "demo-skill"))
		})
	}
}

func TestServerSyncSkillZipRejectsCrossHostRedirect(t *testing.T) {
	root := t.TempDir()
	zipBytes := testZip(t, map[string]string{"SKILL.md": "# Demo"})
	targetServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(zipBytes)
	}))
	defer targetServer.Close()
	redirectServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, targetServer.URL, http.StatusFound)
	}))
	defer redirectServer.Close()

	server := NewServer(NewExecutor(1024), NewFileService(root))
	rec := postSkillZipSyncURL(t, server, redirectServer.URL, sha256Hex(zipBytes), "/workspace/skills/demo-skill")

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "重定向") {
		t.Fatalf("expected redirect error, body = %s", rec.Body.String())
	}
	assertPathMissing(t, filepath.Join(root, "skills", ".archives", "demo-skill.zip"))
	assertPathMissing(t, filepath.Join(root, "skills", "demo-skill"))
}

func TestServerSyncSkillZipRejectsSkillSymlinkTargets(t *testing.T) {
	t.Run("skills dir symlink", func(t *testing.T) {
		root := t.TempDir()
		outside := t.TempDir()
		if err := os.WriteFile(filepath.Join(outside, "keep.txt"), []byte("keep"), 0o644); err != nil {
			t.Fatalf("WriteFile outside error = %v", err)
		}
		createSymlinkOrSkip(t, outside, filepath.Join(root, "skills"))

		rec := postValidDemoSkillZip(t, root)

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
		}
		assertFileContent(t, filepath.Join(outside, "keep.txt"), "keep")
		assertPathMissing(t, filepath.Join(outside, "demo-skill", "SKILL.md"))
	})

	t.Run("target dir symlink", func(t *testing.T) {
		root := t.TempDir()
		outside := t.TempDir()
		if err := os.MkdirAll(filepath.Join(root, "skills"), 0o755); err != nil {
			t.Fatalf("MkdirAll skills error = %v", err)
		}
		if err := os.WriteFile(filepath.Join(outside, "keep.txt"), []byte("keep"), 0o644); err != nil {
			t.Fatalf("WriteFile outside error = %v", err)
		}
		createSymlinkOrSkip(t, outside, filepath.Join(root, "skills", "demo-skill"))

		rec := postValidDemoSkillZip(t, root)

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
		}
		assertFileContent(t, filepath.Join(outside, "keep.txt"), "keep")
		assertPathMissing(t, filepath.Join(outside, "SKILL.md"))
		assertPathMissing(t, filepath.Join(root, "skills", ".archives", "demo-skill.zip"))
	})
}

func TestServerSyncSkillZipKeepsOldSkillWhenExtractFails(t *testing.T) {
	root := t.TempDir()
	oldSkillPath := filepath.Join(root, "skills", "demo-skill", "SKILL.md")
	if err := os.MkdirAll(filepath.Dir(oldSkillPath), 0o755); err != nil {
		t.Fatalf("MkdirAll old skill error = %v", err)
	}
	if err := os.WriteFile(oldSkillPath, []byte("old"), 0o644); err != nil {
		t.Fatalf("WriteFile old skill error = %v", err)
	}
	zipBytes := testCorruptedStoredZip(t, "references/new.md", "new content", "bad content")
	server := NewServer(NewExecutor(1024), NewFileService(root))
	rec := postSkillZipSync(t, server, zipBytes, sha256Hex(zipBytes), "/workspace/skills/demo-skill")

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	assertFileContent(t, oldSkillPath, "old")
	assertPathMissing(t, filepath.Join(root, "skills", "demo-skill", "references", "new.md"))
	assertPathMissing(t, filepath.Join(root, "skills", ".archives", "demo-skill.zip"))
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

func postSkillZipSyncURL(t *testing.T, server *Server, zipURL string, sha string, targetDir string) *httptest.ResponseRecorder {
	t.Helper()
	body := bytes.NewBufferString(`{"skills":[{"skillId":6,"skillKey":"demo-skill","name":"Demo","zipDownloadUrl":"` +
		zipURL + `","zipSha256":"` + sha + `","targetDir":"` + targetDir + `"}]}`)
	req := httptest.NewRequest(http.MethodPost, "/skills/sync-zip", body)
	rec := httptest.NewRecorder()
	server.Router().ServeHTTP(rec, req)
	return rec
}

func postValidDemoSkillZip(t *testing.T, root string) *httptest.ResponseRecorder {
	t.Helper()
	zipBytes := testZip(t, map[string]string{"SKILL.md": "# Demo"})
	server := NewServer(NewExecutor(1024), NewFileService(root))
	return postSkillZipSync(t, server, zipBytes, sha256Hex(zipBytes), "/workspace/skills/demo-skill")
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

func testCorruptedStoredZip(t *testing.T, name string, goodContent string, badContent string) []byte {
	t.Helper()
	if len(goodContent) != len(badContent) {
		t.Fatalf("test data lengths differ: %d != %d", len(goodContent), len(badContent))
	}
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	header := &zip.FileHeader{Name: name, Method: zip.Store}
	file, err := writer.CreateHeader(header)
	if err != nil {
		t.Fatalf("CreateHeader zip entry error = %v", err)
	}
	if _, err := file.Write([]byte(goodContent)); err != nil {
		t.Fatalf("Write zip entry error = %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("Close zip error = %v", err)
	}
	zipBytes := buffer.Bytes()
	index := bytes.Index(zipBytes, []byte(goodContent))
	if index < 0 {
		t.Fatalf("stored zip content %q not found", goodContent)
	}
	copy(zipBytes[index:index+len(badContent)], []byte(badContent))
	return zipBytes
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

func assertPathMissing(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatalf("%s should not exist, stat error = %v", path, err)
	}
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 0
	}
	return len(p), nil
}
