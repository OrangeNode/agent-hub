package policy

import (
	"errors"
	"path"
	"strings"
)

type CommandRequest struct {
	Command        string
	WorkingDir     string
	TimeoutSeconds int
}

type CommandPolicy struct {
	maxCommandLength int
	maxTimeout       int
}

func NewCommandPolicy(maxCommandLength int, maxTimeout int) CommandPolicy {
	return CommandPolicy{maxCommandLength: maxCommandLength, maxTimeout: maxTimeout}
}

func (p CommandPolicy) Validate(req CommandRequest) error {
	command := strings.TrimSpace(req.Command)
	if command == "" {
		return errors.New("命令不能为空")
	}
	if len(command) > p.maxCommandLength {
		return errors.New("命令长度超过限制")
	}
	if !isWorkspacePath(req.WorkingDir) {
		return errors.New("工作目录必须位于 /workspace")
	}
	if req.TimeoutSeconds <= 0 || req.TimeoutSeconds > p.maxTimeout {
		return errors.New("命令超时时间超过限制")
	}

	argv, err := parseSimpleCommand(command)
	if err != nil {
		return err
	}
	if hasPathSeparator(argv[0]) {
		return errors.New("命令入口不允许包含路径")
	}
	if !allowedCommandNames[argv[0]] {
		return errors.New("命令不在允许列表中")
	}
	for _, arg := range argv[1:] {
		if hasDangerousPath(arg) {
			return errors.New("命令包含禁止访问的路径")
		}
	}
	return nil
}

var allowedCommandNames = map[string]bool{
	"python":  true,
	"python3": true,
	"node":    true,
	"npm":     true,
	"npx":     true,
	"pip":     true,
	"pip3":    true,
	"go":      true,
	"java":    true,
	"javac":   true,
	"mvn":     true,
	"gradle":  true,
	"ls":      true,
	"cat":     true,
	"pwd":     true,
	"find":    true,
	"grep":    true,
	"sed":     true,
	"awk":     true,
	"mkdir":   true,
	"cp":      true,
	"mv":      true,
	"rm":      true,
}

func parseSimpleCommand(command string) ([]string, error) {
	for _, r := range command {
		if isForbiddenCommandChar(r) {
			return nil, errors.New("命令包含禁止的 shell 字符")
		}
	}
	argv := strings.Fields(command)
	if len(argv) == 0 {
		return nil, errors.New("命令不能为空")
	}
	return argv, nil
}

func isForbiddenCommandChar(r rune) bool {
	switch r {
	case '\'', '"', '`', ';', '&', '|', '<', '>', '$', '(', ')', '{', '}', '[', ']', '*', '?', '!', '#', '\n', '\r':
		return true
	default:
		return false
	}
}

func hasDangerousPath(value string) bool {
	cleanedValue := cleanSlash(strings.Trim(value, `"'`))
	if hasRelativeTraversalPath(cleanedValue) {
		return true
	}
	for i, r := range cleanedValue {
		if r != '/' {
			continue
		}
		if i != 0 && !isEmbeddedPathSeparator(cleanedValue[i-1]) && !isShortFlagAttachedPathStart(cleanedValue, i) {
			continue
		}
		if isForbiddenAbsolutePath(cleanedValue[i:]) {
			return true
		}
	}
	return false
}

func hasRelativeTraversalPath(value string) bool {
	if hasDotDotPathSegment(value) {
		return true
	}
	for i, r := range value {
		if r != '=' && r != ':' {
			continue
		}
		if i+1 < len(value) && hasDotDotPathSegment(value[i+1:]) {
			return true
		}
	}
	if len(value) > 2 && value[0] == '-' && isAsciiLetter(value[1]) {
		return hasDotDotPathSegment(value[2:])
	}
	return false
}

func hasDotDotPathSegment(value string) bool {
	for _, segment := range strings.Split(value, "/") {
		if segment == ".." {
			return true
		}
	}
	return false
}

func isForbiddenAbsolutePath(value string) bool {
	cleaned := path.Clean(value)
	if cleaned == "/" {
		return true
	}
	if cleaned == "/workspace" || strings.HasPrefix(cleaned, "/workspace/") {
		return false
	}
	return strings.HasPrefix(cleaned, "/")
}

func isEmbeddedPathSeparator(r byte) bool {
	switch r {
	case '=', ':':
		return true
	default:
		return false
	}
}

func isShortFlagAttachedPathStart(value string, slashIndex int) bool {
	return slashIndex >= 2 && value[slashIndex-2] == '-' && isAsciiLetter(value[slashIndex-1])
}

func isAsciiLetter(value byte) bool {
	return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
}

func hasPathSeparator(value string) bool {
	return strings.Contains(value, "/") || strings.Contains(value, "\\")
}

func isWorkspacePath(value string) bool {
	cleaned := path.Clean(cleanSlash(value))
	return cleaned == "/workspace" || strings.HasPrefix(cleaned, "/workspace/")
}

func cleanSlash(value string) string {
	return strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
}
