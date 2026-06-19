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
	"sync"

	"agent-hub/sandbox-service/internal/policy"
)

const maxWorkspaceSymlinkScanEntries = 10000

type FileService struct {
	mu                sync.Mutex
	root              string
	canonicalRoot     string
	canonicalTempRoot string
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
	canonicalRoot := canonicalizeExistingRoot(root)
	canonicalTempRoot := canonicalizeExistingRoot(os.TempDir())
	return &FileService{
		root:              canonicalRoot,
		canonicalRoot:     canonicalRoot,
		canonicalTempRoot: canonicalTempRoot,
	}
}

func canonicalizeExistingRoot(root string) string {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		absRoot = filepath.Clean(root)
	}
	canonicalRoot := filepath.Clean(absRoot)
	if evaluatedRoot, err := filepath.EvalSymlinks(canonicalRoot); err == nil {
		canonicalRoot = filepath.Clean(evaluatedRoot)
	}
	return canonicalRoot
}

func (s *FileService) WriteFile(path string, content []byte, appendMode bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	target, targetRoot, outsideMessage, err := s.resolveWritablePath(path)
	if err != nil {
		return err
	}
	parent := filepath.Dir(target)
	if _, err := s.ensureNoSymlinkInExistingComponentsWithin(parent, targetRoot, false, outsideMessage); err != nil {
		return err
	}
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return err
	}
	if _, err := s.ensureNoSymlinkInExistingComponentsWithin(parent, targetRoot, true, outsideMessage); err != nil {
		return err
	}
	if _, err := os.Lstat(target); err == nil {
		if _, err := s.ensureNoSymlinkInExistingComponentsWithin(target, targetRoot, true, outsideMessage); err != nil {
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
	s.mu.Lock()
	defer s.mu.Unlock()

	target, targetRoot, outsideMessage, err := s.resolveReadablePath(path)
	if err != nil {
		return FileContent{}, err
	}
	if _, err := s.ensureNoSymlinkInExistingComponentsWithin(target, targetRoot, true, outsideMessage); err != nil {
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
	s.mu.Lock()
	defer s.mu.Unlock()

	target, targetRoot, outsideMessage, err := s.resolveReadablePath(path)
	if err != nil {
		return nil, err
	}
	if _, err := s.ensureNoSymlinkInExistingComponentsWithin(target, targetRoot, true, outsideMessage); err != nil {
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
			if _, err := s.ensureNoSymlinkInExistingComponentsWithin(current, targetRoot, true, outsideMessage); err != nil {
				return err
			}
			info, err := entry.Info()
			if err != nil {
				return err
			}
			files = append(files, s.fileInfo(current, info, targetRoot))
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
		if _, err := s.ensureNoSymlinkInExistingComponentsWithin(filepath.Join(target, entry.Name()), targetRoot, true, outsideMessage); err != nil {
			return nil, err
		}
		info, err := entry.Info()
		if err != nil {
			return nil, err
		}
		files = append(files, s.fileInfo(filepath.Join(target, entry.Name()), info, targetRoot))
	}
	return files, nil
}

func (s *FileService) ScanArtifacts(artifactDir string) ([]ArtifactInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	target, err := s.resolveExistingNoSymlink(artifactDir)
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
		if _, err := s.ensureNoSymlinkInExistingComponents(current, true); err != nil {
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

func (s *FileService) ValidateExecRequest(req ExecRequest) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	workingDir, workingRoot, err := s.resolveGuestExecutionPath(req.WorkingDir)
	if err != nil {
		return err
	}
	if _, err := s.ensureNoSymlinkInExistingComponentsWithin(workingDir, workingRoot, true, "路径不能超出允许目录"); err != nil {
		return errors.New("工作目录不安全: " + err.Error())
	}

	argv := strings.Fields(req.Command)
	if len(argv) == 0 {
		return errors.New("命令不能为空")
	}
	for _, arg := range argv[1:] {
		if isSymlinkFollowingOption(arg) {
			return errors.New("命令不允许跟随符号链接")
		}
	}
	if err := s.ensureWorkspaceHasNoSymlinkLocked(maxWorkspaceSymlinkScanEntries); err != nil {
		return err
	}
	for _, arg := range argv[1:] {
		for _, candidate := range execPathCandidates(arg) {
			target, targetRoot, err := s.resolveExecCandidate(candidate, req.WorkingDir)
			if err != nil {
				return errors.New("命令参数路径不安全: " + err.Error())
			}
			if err := s.ensureExecPathAncestorSafe(target, targetRoot); err != nil {
				return errors.New("命令参数路径不安全: " + err.Error())
			}
		}
	}
	return nil
}

func (s *FileService) EnsureWorkspaceHasNoSymlink() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	return s.ensureWorkspaceHasNoSymlinkLocked(maxWorkspaceSymlinkScanEntries)
}

func (s *FileService) ResolveSkillTarget(skillKey string, targetDir string) (string, string, error) {
	if !validSkillKey(skillKey) {
		return "", "", errors.New("Skill key 不合法")
	}
	expectedGuestPath := "/workspace/skills/" + skillKey
	cleanedTarget := filepath.ToSlash(filepath.Clean(strings.TrimSpace(targetDir)))
	if cleanedTarget != expectedGuestPath {
		return "", "", errors.New("Skill 只能同步到 /workspace/skills/{skillKey}")
	}
	targetHostPath, err := s.resolveGuestWorkspacePath(expectedGuestPath)
	if err != nil {
		return "", "", err
	}
	archiveHostPath, err := s.resolveGuestWorkspacePath("/workspace/skills/.archives/" + skillKey + ".zip")
	if err != nil {
		return "", "", err
	}
	return targetHostPath, archiveHostPath, nil
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

func (s *FileService) resolveWritablePath(input string) (string, string, string, error) {
	cleaned := filepath.ToSlash(filepath.Clean(strings.TrimSpace(input)))
	if cleaned == "." || cleaned == "" {
		cleaned = policy.WorkspaceRoot
	}
	if cleaned == policy.TempRoot || strings.HasPrefix(cleaned, policy.TempRoot+"/") {
		relative := strings.TrimPrefix(cleaned, policy.TempRoot)
		relative = strings.TrimPrefix(relative, "/")
		target, err := resolveWithinBase(s.canonicalTempRoot, relative, "路径只能写入 /tmp")
		return target, s.canonicalTempRoot, "路径只能写入 /tmp", err
	}
	target, err := s.resolveLexical(input)
	return target, s.canonicalRoot, "路径不能超出工作目录", err
}

func (s *FileService) resolveReadablePath(input string) (string, string, string, error) {
	cleaned := filepath.ToSlash(filepath.Clean(strings.TrimSpace(input)))
	if cleaned == "." || cleaned == "" {
		cleaned = policy.WorkspaceRoot
	}
	if cleaned == policy.TempRoot || strings.HasPrefix(cleaned, policy.TempRoot+"/") {
		relative := strings.TrimPrefix(cleaned, policy.TempRoot)
		relative = strings.TrimPrefix(relative, "/")
		target, err := resolveWithinBase(s.canonicalTempRoot, relative, "路径只能位于 /workspace 或 /tmp")
		return target, s.canonicalTempRoot, "路径只能位于 /workspace 或 /tmp", err
	}
	target, err := s.resolveLexical(input)
	return target, s.canonicalRoot, "路径不能超出工作目录", err
}

func (s *FileService) resolveExistingNoSymlink(input string) (string, error) {
	target, err := s.resolveLexical(input)
	if err != nil {
		return "", err
	}
	return s.ensureNoSymlinkInExistingComponents(target, true)
}

func (s *FileService) resolveGuestWorkspacePath(input string) (string, error) {
	cleaned := filepath.ToSlash(filepath.Clean(strings.TrimSpace(input)))
	if cleaned == "." || cleaned == "" {
		cleaned = policy.WorkspaceRoot
	}
	if cleaned != policy.WorkspaceRoot && !strings.HasPrefix(cleaned, policy.WorkspaceRoot+"/") {
		return "", errors.New("路径必须位于 " + policy.WorkspaceRoot)
	}
	relative := strings.TrimPrefix(cleaned, policy.WorkspaceRoot)
	relative = strings.TrimPrefix(relative, "/")
	return s.resolveLexical(relative)
}

func (s *FileService) resolveGuestExecutionPath(input string) (string, string, error) {
	cleaned := filepath.ToSlash(filepath.Clean(strings.TrimSpace(input)))
	if cleaned == "." || cleaned == "" {
		cleaned = policy.WorkspaceRoot
	}
	if cleaned == policy.WorkspaceRoot || strings.HasPrefix(cleaned, policy.WorkspaceRoot+"/") {
		hostPath, err := s.resolveGuestWorkspacePath(cleaned)
		if err != nil {
			return "", "", err
		}
		return hostPath, s.canonicalRoot, nil
	}
	if cleaned == policy.TempRoot || strings.HasPrefix(cleaned, policy.TempRoot+"/") {
		relative := strings.TrimPrefix(cleaned, policy.TempRoot)
		relative = strings.TrimPrefix(relative, "/")
		hostPath, err := resolveWithinBase(s.canonicalTempRoot, relative, "路径必须位于 "+policy.AllowedExecutionRootsDescription())
		if err != nil {
			return "", "", err
		}
		return hostPath, s.canonicalTempRoot, nil
	}
	return "", "", errors.New("路径必须位于 " + policy.AllowedExecutionRootsDescription())
}

func resolveWithinBase(base string, relative string, outsideMessage string) (string, error) {
	target := base
	if relative != "" {
		target = filepath.Join(base, filepath.FromSlash(relative))
	}
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return "", err
	}
	cleanTarget := filepath.Clean(absTarget)
	if !isInsideBase(base, cleanTarget) {
		return "", errors.New(outsideMessage)
	}
	return cleanTarget, nil
}

func (s *FileService) resolveExecCandidate(candidate string, workingDir string) (string, string, error) {
	cleaned := filepath.ToSlash(filepath.Clean(strings.TrimSpace(candidate)))
	if cleaned == "." || cleaned == "" {
		return s.resolveGuestExecutionPath(workingDir)
	}
	if strings.HasPrefix(cleaned, "/") {
		return s.resolveGuestExecutionPath(cleaned)
	}
	workingHostPath, workingRoot, err := s.resolveGuestExecutionPath(workingDir)
	if err != nil {
		return "", "", err
	}
	target := filepath.Clean(filepath.Join(workingHostPath, filepath.FromSlash(cleaned)))
	if !isInsideBase(workingRoot, target) {
		return "", "", errors.New("路径不能超出允许目录")
	}
	return target, workingRoot, nil
}

func (s *FileService) ensureExecPathAncestorSafe(path string, canonicalRoot string) error {
	if _, err := s.ensureNoSymlinkInExistingComponentsWithin(path, canonicalRoot, true, "路径不能超出允许目录"); err == nil {
		return nil
	} else if !os.IsNotExist(err) {
		return err
	}
	_, err := s.ensureNoSymlinkInExistingComponentsWithin(path, canonicalRoot, false, "路径不能超出允许目录")
	return err
}

func (s *FileService) ensureNoSymlinkInExistingComponents(path string, requireTarget bool) (string, error) {
	return s.ensureNoSymlinkInExistingComponentsWithin(path, s.canonicalRoot, requireTarget, "路径不能超出工作目录")
}

func (s *FileService) ensureNoSymlinkInExistingComponentsWithin(path string, canonicalRoot string, requireTarget bool, outsideMessage string) (string, error) {
	absPath, err := filepath.Abs(filepath.Clean(path))
	if err != nil {
		return "", err
	}
	cleanPath := filepath.Clean(absPath)
	if !isInsideBase(canonicalRoot, cleanPath) {
		return "", errors.New(outsideMessage)
	}

	rel, err := filepath.Rel(canonicalRoot, cleanPath)
	if err != nil {
		return "", err
	}
	current := canonicalRoot
	if rel == "." {
		if _, err := os.Lstat(current); err != nil {
			return "", err
		}
		return current, nil
	}
	parts := strings.Split(rel, string(filepath.Separator))
	for index, part := range parts {
		current = filepath.Join(current, part)
		info, err := os.Lstat(current)
		if err != nil {
			if os.IsNotExist(err) && (!requireTarget || index < len(parts)-1) {
				return current, nil
			}
			return "", err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("路径不能包含符号链接")
		}
	}
	return current, nil
}

func (s *FileService) ensureWorkspaceHasNoSymlinkLocked(maxEntries int) error {
	checked := 0
	return filepath.WalkDir(s.canonicalRoot, func(current string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		checked++
		if checked > maxEntries {
			return errors.New("工作目录条目过多，无法完成符号链接安全扫描")
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return errors.New("工作目录包含符号链接，不能执行命令")
		}
		return nil
	})
}

func isInsideBase(base string, target string) bool {
	rel, err := filepath.Rel(base, target)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func isSymlinkFollowingOption(arg string) bool {
	switch arg {
	case "-L", "-H", "-follow", "--dereference", "--copy-links":
		return true
	default:
		return strings.HasPrefix(arg, "--dereference=") || strings.HasPrefix(arg, "--copy-links=")
	}
}

func execPathCandidates(arg string) []string {
	candidates := make([]string, 0, 2)
	if strings.HasPrefix(arg, "-") {
		if index := strings.IndexAny(arg, "=:"); index >= 0 && index+1 < len(arg) {
			value := arg[index+1:]
			if isPathLike(value) {
				candidates = append(candidates, value)
			}
		}
		if len(arg) > 2 && isASCIILetter(arg[1]) && (arg[2] == '/' || arg[2] == '\\' || arg[2] == '.') {
			candidates = append(candidates, arg[2:])
		}
		return candidates
	}
	if index := strings.IndexAny(arg, "=:"); index >= 0 && index+1 < len(arg) {
		value := arg[index+1:]
		if isPathLike(value) {
			candidates = append(candidates, value)
		}
	}
	if isPathLike(arg) || !strings.HasPrefix(arg, "-") {
		candidates = append(candidates, arg)
	}
	return candidates
}

func isPathLike(value string) bool {
	return strings.HasPrefix(value, "/") ||
		strings.HasPrefix(value, ".") ||
		strings.Contains(value, "/") ||
		strings.Contains(value, "\\")
}

func isASCIILetter(value byte) bool {
	return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
}

func validSkillKey(value string) bool {
	if value == "" {
		return false
	}
	for i := 0; i < len(value); i++ {
		ch := value[i]
		if (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' {
			continue
		}
		return false
	}
	return true
}

func (s *FileService) fileInfo(path string, info os.FileInfo, base string) FileInfo {
	relative, err := filepath.Rel(base, path)
	if err != nil {
		relative = info.Name()
	}
	relative = filepath.ToSlash(relative)
	if base == s.canonicalTempRoot {
		relative = policy.TempRoot + "/" + strings.TrimPrefix(relative, "./")
	}
	return FileInfo{
		RelativePath: relative,
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
