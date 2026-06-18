package com.agenthub.sandbox.policy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class CommandPolicy {

    private static final String WORKSPACE_ROOT = "/workspace";
    private static final String INPUT_DIR = "/workspace/input";
    private static final String ARTIFACTS_DIR = "/workspace/artifacts";
    private static final String OUTPUT_DIR = "/workspace/output";
    private static final Pattern COMMAND_TOKEN_SEPARATOR = Pattern.compile("[\\s;|&<>\"'`$(){}\\[\\],]+");
    private static final Pattern COMMAND_LOOKUP_NAME = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "shutdown", "reboot", "poweroff", "halt", "mount", "umount",
            "modprobe", "insmod", "sysctl", "sudo", "su", "systemctl", "service"
    );
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "sh", "bash", "python", "python3", "node", "npm", "npx", "pip", "pip3",
            "go", "java", "javac", "mvn", "gradle", "cat", "ls", "pwd", "find",
            "grep", "sed", "awk", "head", "tail", "wc", "sort", "uniq", "printf",
            "mkdir", "cp", "mv", "rm", "touch", "chmod", "echo", "zip", "unzip", "tar",
            "dotnet", "pandoc", "soffice", "libreoffice", "which", "test", "file", "stat"
    );

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
        rejectDangerousContent(command);
        String[] parts = command.strip().split("\\s+");
        String entrypoint = parts.length == 0 ? "" : parts[0];
        if (entrypoint.contains("/") || entrypoint.contains("\\")
                || (!ALLOWED_COMMANDS.contains(entrypoint) && !isAllowedCommandLookup(parts))) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
        }
    }

    private boolean isAllowedCommandLookup(String[] parts) {
        if (parts.length < 3 || !"command".equals(parts[0])) {
            return false;
        }
        if (!"-v".equals(parts[1]) && !"-V".equals(parts[1])) {
            return false;
        }
        for (int index = 2; index < parts.length; index++) {
            String name = removeQuotes(parts[index]);
            if (name.isBlank() || name.startsWith("-") || name.contains("/") || name.contains("\\")
                    || !COMMAND_LOOKUP_NAME.matcher(name).matches()) {
                return false;
            }
        }
        return true;
    }

    private void rejectDangerousContent(String command) {
        if (command.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
        }
        String normalized = command.replace('\\', '/');
        String compact = removeQuotes(normalized).toLowerCase(Locale.ROOT);
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (containsWord(compact, keyword)) {
                throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令包含禁止的操作");
            }
        }
        validateArgumentPaths(COMMAND_TOKEN_SEPARATOR.split(compact));
    }

    private void validateArgumentPaths(String[] parts) {
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            for (String candidate : pathCandidates(part)) {
                if (hasParentTraversal(candidate) || isAbsoluteOutsideWorkspace(candidate)) {
                    throw new BusinessException(ErrorCode.COMMAND_DENIED, "命令未通过安全校验");
                }
            }
        }
    }

    private List<String> pathCandidates(String value) {
        String normalized = value.replace('\\', '/');
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        int equalsIndex = normalized.indexOf('=');
        if (equalsIndex >= 0 && equalsIndex < normalized.length() - 1) {
            candidates.add(normalized.substring(equalsIndex + 1));
        }
        int colonIndex = normalized.indexOf(':');
        if (colonIndex >= 0 && colonIndex < normalized.length() - 1) {
            candidates.add(normalized.substring(colonIndex + 1));
        }
        if (normalized.length() > 2 && normalized.charAt(0) == '-' && isAsciiLetter(normalized.charAt(1))) {
            candidates.add(normalized.substring(2));
        }
        return candidates;
    }

    private boolean hasParentTraversal(String value) {
        return Arrays.stream(value.split("/"))
                .anyMatch(".."::equals);
    }

    private boolean isAbsoluteOutsideWorkspace(String value) {
        return value.startsWith("/") && !value.equals("/workspace") && !value.startsWith("/workspace/");
    }

    private boolean containsWord(String value, String word) {
        return Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(word) + "(?![a-z0-9_-])")
                .matcher(value)
                .find();
    }

    private String removeQuotes(String value) {
        return value.replace("'", "").replace("\"", "");
    }

    private boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    public String validateWorkspacePath(String path, String message) {
        String normalized = normalizeWorkspacePath(path, WORKSPACE_ROOT);
        if (!isUnder(normalized, WORKSPACE_ROOT)) {
            throw new BusinessException(ErrorCode.PATH_DENIED, message);
        }
        return normalized;
    }

    public String validateOutputPath(String path) {
        String normalized = normalizeWorkspacePath(path, ARTIFACTS_DIR);
        if (isUnder(normalized, OUTPUT_DIR)) {
            normalized = ARTIFACTS_DIR + normalized.substring(OUTPUT_DIR.length());
        } else if (isUnder(normalized, WORKSPACE_ROOT) && !isUnder(normalized, ARTIFACTS_DIR)) {
            String relative = normalized.equals(WORKSPACE_ROOT)
                    ? "output.txt"
                    : normalized.substring(WORKSPACE_ROOT.length() + 1);
            normalized = ARTIFACTS_DIR + "/" + relative;
        }
        if (!isUnder(normalized, ARTIFACTS_DIR)) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "输出文件只能写入 /workspace/artifacts");
        }
        return normalized;
    }

    public String validateInputPath(String path) {
        String normalized = normalizeWorkspacePath(path, INPUT_DIR);
        if (!isUnder(normalized, INPUT_DIR)) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "上传文件只能写入 /workspace/input");
        }
        return normalized;
    }

    private String normalizeWorkspacePath(String value, String defaultDirectory) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "路径不能为空");
        }
        String replaced = value.trim().replace('\\', '/');
        if (replaced.matches("^[A-Za-z]:.*")) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "路径不在允许访问范围内");
        }
        if (Arrays.stream(replaced.split("/")).anyMatch(".."::equals)) {
            throw new BusinessException(ErrorCode.PATH_DENIED, "路径不在允许访问范围内");
        }
        Path normalized = Path.of(replaced).normalize();
        String path = normalized.toString().replace('\\', '/');
        if (".".equals(path)) {
            path = "";
        }
        if (path.equals("workspace") || path.startsWith("workspace/")) {
            path = "/" + path;
        } else if (path.equals("artifacts") || path.startsWith("artifacts/")) {
            path = WORKSPACE_ROOT + "/" + path;
        } else if (path.equals("input") || path.startsWith("input/")) {
            path = WORKSPACE_ROOT + "/" + path;
        } else if (path.equals("output") || path.startsWith("output/")) {
            path = WORKSPACE_ROOT + "/" + path;
        } else if (!path.startsWith("/")) {
            path = defaultDirectory + "/" + path;
        }
        return Path.of(path).normalize().toString().replace('\\', '/');
    }

    private boolean isUnder(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }
}
