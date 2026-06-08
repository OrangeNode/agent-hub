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
	root          string
	canonicalRoot string
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
	canonicalRoot := filepath.Clean(absRoot)
	if evaluatedRoot, err := filepath.EvalSymlinks(canonicalRoot); err == nil {
		canonicalRoot = filepath.Clean(evaluatedRoot)
	}
	return &FileService{root: filepath.Clean(absRoot), canonicalRoot: canonicalRoot}
}

func (s *FileService) WriteFile(path string, content []byte, appendMode bool) error {
	target, err := s.resolveLexical(path)
	if err != nil {
		return err
	}
	parent := filepath.Dir(target)
	if err := s.ensureExistingAncestorInside(parent); err != nil {
		return err
	}
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return err
	}
	if _, err := s.resolveExisting(parent); err != nil {
		return err
	}
	if _, err := os.Lstat(target); err == nil {
		if _, err := s.resolveExisting(target); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
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
	target, err := s.resolveExisting(path)
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
	target, err := s.resolveExisting(path)
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
			if _, err := s.resolveExisting(current); err != nil {
				return err
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
		if _, err := s.resolveExisting(filepath.Join(target, entry.Name())); err != nil {
			return nil, err
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
	target, err := s.resolveExisting(artifactDir)
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
		if _, err := s.resolveExisting(current); err != nil {
			return err
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

func (s *FileService) resolveLexical(input string) (string, error) {
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
	cleanTarget := filepath.Clean(absTarget)
	if !isInsideBase(s.root, cleanTarget) && !isInsideBase(s.canonicalRoot, cleanTarget) {
		return "", errors.New("路径不能超出工作目录")
	}
	return cleanTarget, nil
}

func (s *FileService) resolveExisting(input string) (string, error) {
	target, err := s.resolveLexical(input)
	if err != nil {
		return "", err
	}
	canonicalTarget, err := filepath.EvalSymlinks(target)
	if err != nil {
		return "", err
	}
	return s.ensureCanonicalInside(canonicalTarget)
}

func (s *FileService) ensureExistingAncestorInside(path string) error {
	current := filepath.Clean(path)
	for {
		if _, err := os.Lstat(current); err == nil {
			_, err := s.resolveExisting(current)
			return err
		} else if !os.IsNotExist(err) {
			return err
		}

		parent := filepath.Dir(current)
		if parent == current {
			return errors.New("工作目录不存在")
		}
		current = parent
	}
}

func (s *FileService) ensureCanonicalInside(path string) (string, error) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	cleanPath := filepath.Clean(absPath)
	rel, err := filepath.Rel(s.canonicalRoot, cleanPath)
	if err != nil {
		return "", err
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return "", errors.New("路径真实目标不能超出工作目录")
	}
	return cleanPath, nil
}

func isInsideBase(base string, target string) bool {
	rel, err := filepath.Rel(base, target)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func (s *FileService) fileInfo(path string, info os.FileInfo) FileInfo {
	relative, err := filepath.Rel(s.canonicalRoot, path)
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
