package agent

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type FileService struct {
	root string
}

type FileContent struct {
	Content   []byte `json:"content"`
	Truncated bool   `json:"truncated"`
}

type FileInfo struct {
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
	IsDir        bool   `json:"isDir"`
}

type ArtifactInfo struct {
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
	SHA256       string `json:"sha256"`
}

func NewFileService(root string) *FileService {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		absRoot = filepath.Clean(root)
	}
	return &FileService{root: filepath.Clean(absRoot)}
}

func (s *FileService) WriteFile(path string, content []byte, appendMode bool) error {
	target, err := s.resolve(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	flag := os.O_CREATE | os.O_WRONLY
	if appendMode {
		flag |= os.O_APPEND
	} else {
		flag |= os.O_TRUNC
	}
	file, err := os.OpenFile(target, flag, 0o644)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = file.Write(content)
	return err
}

func (s *FileService) ReadFile(path string, maxBytes int64) (FileContent, error) {
	target, err := s.resolve(path)
	if err != nil {
		return FileContent{}, err
	}
	if maxBytes < 0 {
		return FileContent{}, errors.New("读取字节数不能小于 0")
	}
	file, err := os.Open(target)
	if err != nil {
		return FileContent{}, err
	}
	defer file.Close()

	if maxBytes == 0 {
		data, err := io.ReadAll(file)
		return FileContent{Content: data}, err
	}
	limited := io.LimitReader(file, maxBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return FileContent{}, err
	}
	if int64(len(data)) > maxBytes {
		return FileContent{Content: data[:maxBytes], Truncated: true}, nil
	}
	return FileContent{Content: data}, nil
}

func (s *FileService) ListFiles(path string, recursive bool, maxFiles int) ([]FileInfo, error) {
	target, err := s.resolve(path)
	if err != nil {
		return nil, err
	}
	if maxFiles <= 0 {
		maxFiles = 100
	}
	files := make([]FileInfo, 0)
	if recursive {
		err = filepath.WalkDir(target, func(current string, entry os.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if current == target {
				return nil
			}
			info, err := entry.Info()
			if err != nil {
				return err
			}
			files = append(files, s.fileInfo(current, info))
			if len(files) >= maxFiles {
				return filepath.SkipAll
			}
			return nil
		})
		if err != nil {
			return nil, err
		}
		return files, nil
	}

	entries, err := os.ReadDir(target)
	if err != nil {
		return nil, err
	}
	for _, entry := range entries {
		if len(files) >= maxFiles {
			break
		}
		info, err := entry.Info()
		if err != nil {
			return nil, err
		}
		files = append(files, s.fileInfo(filepath.Join(target, entry.Name()), info))
	}
	return files, nil
}

func (s *FileService) ScanArtifacts(artifactDir string) ([]ArtifactInfo, error) {
	target, err := s.resolve(artifactDir)
	if err != nil {
		return nil, err
	}
	artifacts := make([]ArtifactInfo, 0)
	err = filepath.WalkDir(target, func(current string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if current == target || entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		sum, err := sha256File(current)
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(target, current)
		if err != nil {
			return err
		}
		artifacts = append(artifacts, ArtifactInfo{
			RelativePath: filepath.ToSlash(relative),
			Size:         info.Size(),
			SHA256:       sum,
		})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Slice(artifacts, func(i, j int) bool {
		return artifacts[i].RelativePath < artifacts[j].RelativePath
	})
	return artifacts, nil
}

func (s *FileService) resolve(input string) (string, error) {
	cleanInput := filepath.Clean(strings.TrimSpace(input))
	if cleanInput == "." {
		cleanInput = ""
	}
	var target string
	if filepath.IsAbs(cleanInput) {
		target = cleanInput
	} else {
		target = filepath.Join(s.root, cleanInput)
	}
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return "", err
	}
	rel, err := filepath.Rel(s.root, filepath.Clean(absTarget))
	if err != nil {
		return "", err
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return "", errors.New("路径不能超出工作目录")
	}
	return filepath.Clean(absTarget), nil
}

func (s *FileService) fileInfo(path string, info os.FileInfo) FileInfo {
	relative, err := filepath.Rel(s.root, path)
	if err != nil {
		relative = info.Name()
	}
	return FileInfo{
		RelativePath: filepath.ToSlash(relative),
		Size:         info.Size(),
		IsDir:        info.IsDir(),
	}
}

func sha256File(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
