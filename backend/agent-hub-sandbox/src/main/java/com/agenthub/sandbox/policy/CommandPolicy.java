package com.agenthub.sandbox.policy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CommandPolicy {

    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "shutdown", "reboot", "poweroff", "halt", "mount", "umount",
            "modprobe", "insmod", "sysctl", "sudo", "su", "systemctl", "service"
    );
    private static final List<String> DANGEROUS_PATHS = List.of(
            "rm -rf /", "rm -rf /*", "/etc", "/proc", "/sys", "/dev", "/root"
    );
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "python", "python3", "node", "npm", "cat", "ls", "pwd", "find",
            "grep", "sed", "awk", "head", "tail", "wc", "sort", "uniq",
            "mkdir", "cp", "mv", "touch", "echo", "zip", "unzip", "tar"
    );
    private static final Set<String> SHELL_COMMANDS = Set.of("sh", "bash", "zsh", "fish", "powershell", "cmd");

    private final int maxCommandLength;
    private final int maxTimeoutSeconds;

    public CommandPolicy(int maxCommandLength, int maxTimeoutSeconds) {
        this.maxCommandLength = maxCommandLength;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public void validate(String command, String workingDir, int timeoutSeconds) {
        if (command == null || command.isBlank()) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令不能为空");
        }
        if (command.length() > maxCommandLength) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令长度超过限制");
        }
        validateWorkspacePath(workingDir, "工作目录必须位于 /workspace");
        if (timeoutSeconds <= 0 || timeoutSeconds > maxTimeoutSeconds) {
            throw new BusinessException(ErrorCode.COMMAND_TIMEOUT, "命令超时时间超过限制");
        }
        rejectShellSyntax(command);
        String[] parts = command.strip().split("\\s+");
        String entrypoint = parts.length == 0 ? "" : parts[0];
        if (entrypoint.contains("/") || entrypoint.contains("\\") || SHELL_COMMANDS.contains(entrypoint)
                || !ALLOWED_COMMANDS.contains(entrypoint)) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
        }
        validateArgumentPaths(parts);
        String normalized = " " + command.toLowerCase(Locale.ROOT) + " ";
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (normalized.contains(" " + keyword + " ")) {
                throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令包含禁止的操作");
            }
        }
        for (String path : DANGEROUS_PATHS) {
            if (normalized.contains(path)) {
                throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令包含禁止访问的路径");
            }
        }
    }

    private void validateArgumentPaths(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            for (String candidate : pathCandidates(parts[i])) {
                if (hasParentTraversal(candidate) || isAbsoluteOutsideWorkspace(candidate)) {
                    throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
                }
            }
        }
    }

    private List<String> pathCandidates(String value) {
        String normalized = value.replace('\\', '/');
        int equalsIndex = normalized.indexOf('=');
        if (equalsIndex >= 0 && equalsIndex < normalized.length() - 1) {
            return List.of(normalized, normalized.substring(equalsIndex + 1));
        }
        return List.of(normalized);
    }

    private boolean hasParentTraversal(String value) {
        return Arrays.stream(value.split("/"))
                .anyMatch(".."::equals);
    }

    private boolean isAbsoluteOutsideWorkspace(String value) {
        return value.startsWith("/") && !value.equals("/workspace") && !value.startsWith("/workspace/");
    }

    private void rejectShellSyntax(String command) {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
        }
        for (String token : Arrays.asList(";", "&&", "||", "|", "`", "$(", ">", "<", "\"", "'")) {
            if (command.contains(token)) {
                throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
            }
        }
    }

    public String validateWorkspacePath(String path, String message) {
        String normalized = normalizeWorkspacePath(path);
        if (!normalized.equals("/workspace") && !normalized.startsWith("/workspace/")) {
            throw new BusinessException(ErrorCode.PATH_DENIED, message);
        }
        return normalized;
    }

    private String normalizeWorkspacePath(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "路径不能为空");
        }
        String replaced = value.trim().replace('\\', '/');
        Path normalized = Path.of(replaced).normalize();
        return normalized.toString().replace('\\', '/');
    }
}
