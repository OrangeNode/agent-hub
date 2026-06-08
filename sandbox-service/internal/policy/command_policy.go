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

	normalized := " " + strings.ToLower(command) + " "
	for _, keyword := range []string{
		"shutdown", "reboot", "poweroff", "halt", "mount", "umount",
		"modprobe", "insmod", "sysctl", "sudo", "su", "systemctl", "service",
	} {
		if strings.Contains(normalized, " "+keyword+" ") {
			return errors.New("命令包含禁止的操作")
		}
	}
	for _, pattern := range []string{"rm -rf /", "rm -rf /*", "/etc", "/proc", "/sys", "/dev", "/root"} {
		if strings.Contains(normalized, pattern) {
			return errors.New("命令包含禁止访问的路径")
		}
	}
	return nil
}

func isWorkspacePath(value string) bool {
	cleaned := path.Clean(cleanSlash(value))
	return cleaned == "/workspace" || strings.HasPrefix(cleaned, "/workspace/")
}

func cleanSlash(value string) string {
	return strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
}
