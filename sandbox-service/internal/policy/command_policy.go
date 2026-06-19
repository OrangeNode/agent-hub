package policy

import (
	"errors"
	"path"
	"strings"
)

const (
	WorkspaceRoot = "/workspace"
	TempRoot      = "/tmp"
)

var allowedExecutionRoots = []string{WorkspaceRoot, TempRoot}

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
		return errors.New("工作目录必须位于 " + AllowedExecutionRootsDescription())
	}
	if req.TimeoutSeconds <= 0 || req.TimeoutSeconds > p.maxTimeout {
		return errors.New("命令超时时间超过限制")
	}
	if strings.ContainsRune(command, '\x00') {
		return errors.New("命令未通过安全校验")
	}

	compactCommand := removeQuotes(strings.ToLower(cleanSlash(command)))
	if hasDangerousKeyword(compactCommand) {
		return errors.New("命令包含禁止的操作")
	}
	for _, token := range splitCommandPathTokens(compactCommand) {
		if hasDangerousPath(token) {
			return errors.New("命令包含禁止访问的路径")
		}
	}

	argv, err := parseSimpleCommand(command)
	if err != nil {
		return err
	}
	if hasPathSeparator(argv[0]) {
		return errors.New("命令入口不允许包含路径")
	}
	if !allowedCommandNames[argv[0]] && !isAllowedCommandLookup(argv) {
		return errors.New("命令不在允许列表中")
	}
	return nil
}

var allowedCommandNames = map[string]bool{
	"sh":          true,
	"bash":        true,
	"python":      true,
	"python3":     true,
	"node":        true,
	"npm":         true,
	"npx":         true,
	"pip":         true,
	"pip3":        true,
	"go":          true,
	"java":        true,
	"javac":       true,
	"mvn":         true,
	"gradle":      true,
	"ls":          true,
	"cat":         true,
	"cd":          true,
	"pwd":         true,
	"find":        true,
	"grep":        true,
	"sed":         true,
	"awk":         true,
	"head":        true,
	"tail":        true,
	"wc":          true,
	"sort":        true,
	"uniq":        true,
	"printf":      true,
	"mkdir":       true,
	"cp":          true,
	"mv":          true,
	"rm":          true,
	"touch":       true,
	"chmod":       true,
	"echo":        true,
	"zip":         true,
	"unzip":       true,
	"tar":         true,
	"dotnet":      true,
	"pandoc":      true,
	"soffice":     true,
	"libreoffice": true,
	"which":       true,
	"test":        true,
	"file":        true,
	"stat":        true,
}

func parseSimpleCommand(command string) ([]string, error) {
	argv := strings.Fields(command)
	if len(argv) == 0 {
		return nil, errors.New("命令不能为空")
	}
	return argv, nil
}

func isAllowedCommandLookup(argv []string) bool {
	if len(argv) < 3 || argv[0] != "command" {
		return false
	}
	if argv[1] != "-v" && argv[1] != "-V" {
		return false
	}
	for _, name := range argv[2:] {
		name = strings.Trim(name, `"'`)
		if name == "" || strings.HasPrefix(name, "-") || hasPathSeparator(name) || !isCommandLookupName(name) {
			return false
		}
	}
	return true
}

func isCommandLookupName(value string) bool {
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			continue
		}
		switch r {
		case '.', '_', '+', '-':
			continue
		default:
			return false
		}
	}
	return true
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

func hasDangerousKeyword(command string) bool {
	for _, token := range strings.FieldsFunc(command, func(r rune) bool {
		return !isKeywordRune(r)
	}) {
		if dangerousKeywords[token] {
			return true
		}
	}
	return false
}

var dangerousKeywords = map[string]bool{
	"shutdown":  true,
	"reboot":    true,
	"poweroff":  true,
	"halt":      true,
	"mount":     true,
	"umount":    true,
	"modprobe":  true,
	"insmod":    true,
	"sysctl":    true,
	"sudo":      true,
	"su":        true,
	"systemctl": true,
	"service":   true,
}

func splitCommandPathTokens(command string) []string {
	return strings.FieldsFunc(command, func(r rune) bool {
		switch r {
		case ' ', '\t', '\n', '\r', ';', '|', '&', '<', '>', '"', '\'', '`', '$', '(', ')', '{', '}', '[', ']', ',':
			return true
		default:
			return false
		}
	})
}

func removeQuotes(value string) string {
	value = strings.ReplaceAll(value, "'", "")
	return strings.ReplaceAll(value, "\"", "")
}

func isKeywordRune(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-'
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
	if IsAllowedExecutionPath(cleaned) {
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
	return IsAllowedExecutionPath(cleaned)
}

func IsAllowedExecutionPath(value string) bool {
	for _, root := range allowedExecutionRoots {
		if value == root || strings.HasPrefix(value, root+"/") {
			return true
		}
	}
	return false
}

func AllowedExecutionRootsDescription() string {
	return strings.Join(allowedExecutionRoots, " 或 ")
}

func cleanSlash(value string) string {
	return strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
}
