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
	if !allowedCommandNames[strings.ToLower(path.Base(cleanSlash(argv[0])))] {
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
	cleaned := path.Clean(cleanSlash(strings.Trim(value, `"'`)))
	if cleaned == "/" {
		return true
	}
	if cleaned == "/workspace" || strings.HasPrefix(cleaned, "/workspace/") {
		return false
	}
	if strings.HasPrefix(cleaned, "/") {
		return true
	}
	return false
}

func isWorkspacePath(value string) bool {
	cleaned := path.Clean(cleanSlash(value))
	return cleaned == "/workspace" || strings.HasPrefix(cleaned, "/workspace/")
}

func cleanSlash(value string) string {
	return strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
}
