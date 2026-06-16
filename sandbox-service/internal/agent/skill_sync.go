package agent

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

const (
	maxSkillZipBytes          int64 = 50 * 1024 * 1024
	maxSkillZipFiles                = 2000
	maxSkillZipExtractedBytes int64 = 100 * 1024 * 1024
)

var skillZipHTTPClient = &http.Client{Timeout: 30 * time.Second}

type SkillZipSyncRequest struct {
	Skills []SkillZipSyncItem `json:"skills"`
}

type SkillZipSyncItem struct {
	SkillID        int64  `json:"skillId"`
	SkillKey       string `json:"skillKey"`
	Name           string `json:"name"`
	ZipDownloadURL string `json:"zipDownloadUrl"`
	ZipSHA256      string `json:"zipSha256"`
	TargetDir      string `json:"targetDir"`
}

type SkillZipSyncItemResult struct {
	SkillID     int64  `json:"skillId"`
	SkillKey    string `json:"skillKey"`
	TargetDir   string `json:"targetDir"`
	ArchivePath string `json:"archivePath"`
	FileCount   int    `json:"fileCount"`
	TotalBytes  int64  `json:"totalBytes"`
}

type skillZipEntry struct {
	file      *zip.File
	cleanName string
	isDir     bool
}

func (s *Server) handleSyncSkillZip(w http.ResponseWriter, r *http.Request) {
	var req SkillZipSyncRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	results, err := s.syncSkillZips(r.Context(), req)
	if err != nil {
		writeError(w, http.StatusBadRequest, "同步 Skill 包失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "skills": results})
}

func (s *Server) syncSkillZips(ctx context.Context, req SkillZipSyncRequest) ([]SkillZipSyncItemResult, error) {
	results := make([]SkillZipSyncItemResult, 0, len(req.Skills))
	for _, item := range req.Skills {
		result, err := s.syncSkillZip(ctx, item)
		if err != nil {
			if item.SkillKey == "" {
				return nil, err
			}
			return nil, fmt.Errorf("%s: %w", item.SkillKey, err)
		}
		results = append(results, result)
	}
	return results, nil
}

func (s *Server) syncSkillZip(ctx context.Context, item SkillZipSyncItem) (SkillZipSyncItemResult, error) {
	targetHostPath, archiveHostPath, err := s.files.ResolveSkillTarget(item.SkillKey, item.TargetDir)
	if err != nil {
		return SkillZipSyncItemResult{}, err
	}
	zipBytes, err := downloadSkillZip(ctx, item.ZipDownloadURL)
	if err != nil {
		return SkillZipSyncItemResult{}, err
	}
	if err := verifySkillZipSHA256(zipBytes, item.ZipSHA256); err != nil {
		return SkillZipSyncItemResult{}, err
	}
	reader, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		return SkillZipSyncItemResult{}, errors.New("zip 包格式不合法")
	}
	entries, err := validateSkillZipEntries(reader)
	if err != nil {
		return SkillZipSyncItemResult{}, err
	}

	fileCount, totalBytes, err := s.files.replaceSkillZip(targetHostPath, archiveHostPath, zipBytes, entries)
	if err != nil {
		return SkillZipSyncItemResult{}, err
	}
	return SkillZipSyncItemResult{
		SkillID:     item.SkillID,
		SkillKey:    item.SkillKey,
		TargetDir:   "/workspace/skills/" + item.SkillKey,
		ArchivePath: "/workspace/skills/.archives/" + item.SkillKey + ".zip",
		FileCount:   fileCount,
		TotalBytes:  totalBytes,
	}, nil
}

func downloadSkillZip(ctx context.Context, rawURL string) ([]byte, error) {
	rawURL = strings.TrimSpace(rawURL)
	if rawURL == "" {
		return nil, errors.New("zip 下载地址不能为空")
	}
	parsed, err := url.ParseRequestURI(rawURL)
	if err != nil {
		return nil, errors.New("zip 下载地址不合法")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return nil, errors.New("zip 下载地址只支持 http 或 https")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	response, err := skillZipHTTPClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return nil, fmt.Errorf("zip 下载失败，HTTP 状态码 %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, maxSkillZipBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > maxSkillZipBytes {
		return nil, errors.New("zip 包超过大小限制")
	}
	return data, nil
}

func verifySkillZipSHA256(data []byte, expectedSHA256 string) error {
	expected := strings.ToLower(strings.TrimSpace(expectedSHA256))
	if len(expected) != sha256.Size*2 {
		return errors.New("zipSha256 格式不合法")
	}
	if _, err := hex.DecodeString(expected); err != nil {
		return errors.New("zipSha256 格式不合法")
	}
	sum := sha256.Sum256(data)
	if hex.EncodeToString(sum[:]) != expected {
		return errors.New("zip sha256 不匹配")
	}
	return nil
}

func validateSkillZipEntries(reader *zip.Reader) ([]skillZipEntry, error) {
	if len(reader.File) > maxSkillZipFiles {
		return nil, errors.New("zip 包文件数超过限制")
	}
	entries := make([]skillZipEntry, 0, len(reader.File))
	var totalBytes uint64
	for _, zipFile := range reader.File {
		cleanName, err := safeZipEntryPath(zipFile.Name)
		if err != nil {
			return nil, err
		}
		if zipFile.FileInfo().Mode()&os.ModeSymlink != 0 {
			return nil, errors.New("zip 包不能包含符号链接")
		}
		isDir := zipFile.FileInfo().IsDir()
		if !isDir {
			totalBytes += zipFile.UncompressedSize64
			if totalBytes > uint64(maxSkillZipExtractedBytes) {
				return nil, errors.New("zip 包解压总大小超过限制")
			}
		}
		entries = append(entries, skillZipEntry{
			file:      zipFile,
			cleanName: cleanName,
			isDir:     isDir,
		})
	}
	return entries, nil
}

func (s *FileService) replaceSkillZip(targetHostPath string, archiveHostPath string, zipBytes []byte, entries []skillZipEntry) (int, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.prepareSkillArchivePath(archiveHostPath); err != nil {
		return 0, 0, err
	}
	if err := os.WriteFile(archiveHostPath, zipBytes, 0o644); err != nil {
		return 0, 0, err
	}
	if err := s.prepareCleanSkillTarget(targetHostPath); err != nil {
		return 0, 0, err
	}
	return s.extractSkillZipEntries(targetHostPath, entries)
}

func (s *FileService) prepareSkillArchivePath(archiveHostPath string) error {
	archiveParent := filepath.Dir(archiveHostPath)
	if _, err := s.ensureNoSymlinkInExistingComponents(archiveParent, false); err != nil {
		return err
	}
	if err := os.MkdirAll(archiveParent, 0o755); err != nil {
		return err
	}
	if _, err := s.ensureNoSymlinkInExistingComponents(archiveParent, true); err != nil {
		return err
	}
	if _, err := os.Lstat(archiveHostPath); err == nil {
		_, err = s.ensureNoSymlinkInExistingComponents(archiveHostPath, true)
		return err
	} else if !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (s *FileService) prepareCleanSkillTarget(targetHostPath string) error {
	targetParent := filepath.Dir(targetHostPath)
	if _, err := s.ensureNoSymlinkInExistingComponents(targetParent, false); err != nil {
		return err
	}
	if err := os.MkdirAll(targetParent, 0o755); err != nil {
		return err
	}
	if _, err := s.ensureNoSymlinkInExistingComponents(targetParent, true); err != nil {
		return err
	}
	if _, err := os.Lstat(targetHostPath); err == nil {
		if _, err := s.ensureNoSymlinkInExistingComponents(targetHostPath, true); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	if err := os.RemoveAll(targetHostPath); err != nil {
		return err
	}
	if err := os.MkdirAll(targetHostPath, 0o755); err != nil {
		return err
	}
	_, err := s.ensureNoSymlinkInExistingComponents(targetHostPath, true)
	return err
}

func (s *FileService) extractSkillZipEntries(targetHostPath string, entries []skillZipEntry) (int, int64, error) {
	fileCount := 0
	var totalBytes int64
	for _, entry := range entries {
		targetPath := filepath.Clean(filepath.Join(targetHostPath, filepath.FromSlash(entry.cleanName)))
		if !isInsideBase(targetHostPath, targetPath) {
			return 0, 0, errors.New("zip 包含不安全路径")
		}
		if entry.isDir {
			if err := os.MkdirAll(targetPath, 0o755); err != nil {
				return 0, 0, err
			}
			if _, err := s.ensureNoSymlinkInExistingComponents(targetPath, true); err != nil {
				return 0, 0, err
			}
			continue
		}

		copied, err := s.extractSkillZipFile(targetPath, entry.file, maxSkillZipExtractedBytes-totalBytes)
		if err != nil {
			return 0, 0, err
		}
		fileCount++
		totalBytes += copied
	}
	return fileCount, totalBytes, nil
}

func (s *FileService) extractSkillZipFile(targetPath string, zipFile *zip.File, remainingBytes int64) (int64, error) {
	if remainingBytes < 0 {
		return 0, errors.New("zip 包解压总大小超过限制")
	}
	parent := filepath.Dir(targetPath)
	if _, err := s.ensureNoSymlinkInExistingComponents(parent, false); err != nil {
		return 0, err
	}
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return 0, err
	}
	if _, err := s.ensureNoSymlinkInExistingComponents(parent, true); err != nil {
		return 0, err
	}
	if info, err := os.Lstat(targetPath); err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return 0, errors.New("解压目标不能是符号链接")
		}
		if info.IsDir() {
			return 0, errors.New("zip 文件路径与目录冲突")
		}
	} else if !os.IsNotExist(err) {
		return 0, err
	}

	reader, err := zipFile.Open()
	if err != nil {
		return 0, err
	}
	defer reader.Close()

	output, err := os.OpenFile(targetPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return 0, err
	}
	copied, copyErr := io.Copy(output, io.LimitReader(reader, remainingBytes+1))
	closeErr := output.Close()
	if copyErr != nil {
		return copied, copyErr
	}
	if closeErr != nil {
		return copied, closeErr
	}
	if copied > remainingBytes {
		return copied, errors.New("zip 包解压总大小超过限制")
	}
	return copied, nil
}

func safeZipEntryPath(name string) (string, error) {
	cleaned := path.Clean(strings.TrimSpace(strings.ReplaceAll(name, "\\", "/")))
	if cleaned == "." || strings.HasPrefix(cleaned, "/") || strings.HasPrefix(cleaned, "../") ||
		cleaned == ".." || isWindowsDrivePath(cleaned) {
		return "", errors.New("zip 包含不安全路径")
	}
	return cleaned, nil
}

func isWindowsDrivePath(value string) bool {
	return len(value) >= 2 && isASCIILetter(value[0]) && value[1] == ':'
}
