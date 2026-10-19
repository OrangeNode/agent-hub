package com.agenthub.sandbox.tool;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class BuiltinSandboxToolProvider implements ToolProvider {

    private static final String DEFAULT_WORKING_DIR = "/workspace";
    private static final int DEFAULT_EXEC_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_READ_MAX_BYTES = 32768;
    private static final String DEFAULT_OUTPUT_PATH = "/workspace/artifacts/output.txt";
    private static final String DEFAULT_WORK_FILE_PATH = "/workspace/work/output.txt";
    private static final String DEFAULT_UPLOAD_PATH = "/workspace/input/upload.bin";
    private static final int WRITE_FILE_PREVIEW_MAX_LENGTH = 4000;
    private static final Set<String> SUPPORTED_TOOL_NAMES = Set.of(
            "sandbox.exec",
            "sandbox.read_file",
            "sandbox.write_file",
            "sandbox.write_workspace_file",
            "sandbox.list_files",
            "sandbox.upload_file"
    );

    private static final List<LlmToolDefinition> TOOLS = List.of(
            new LlmToolDefinition(
                    "sandbox.exec",
                    "在工作区执行白名单命令",
                    objectParameters(
                            properties(
                                    property("command", "string"),
                                    property("working_dir", "string"),
                                    property("timeout_seconds", "integer")
                            ),
                            List.of("command")
                    )
            ),
            new LlmToolDefinition(
                    "sandbox.read_file",
                    "读取 /workspace 内文件内容",
                    objectParameters(
                            properties(
                                    property("path", "string"),
                                    property("max_bytes", "integer")
                            ),
                            List.of("path")
                    )
            ),
            new LlmToolDefinition(
                    "sandbox.write_file",
                    "写入交付给用户的输出文件，路径必须位于 /workspace/artifacts",
                    objectParameters(
                            properties(
                                    property("path", "string"),
                                    property("content", "string"),
                                    property("append", "boolean")
                            ),
                            List.of("path", "content")
                    )
            ),
            new LlmToolDefinition(
                    "sandbox.write_workspace_file",
                    "写入中间工作文件，不作为交付文件登记；路径必须位于 /workspace/work 或 /tmp，最终文档请用 sandbox.exec 生成到 /workspace/artifacts",
                    objectParameters(
                            properties(
                                    property("path", "string"),
                                    property("content", "string"),
                                    property("append", "boolean")
                            ),
                            List.of("path", "content")
                    )
            ),
            new LlmToolDefinition(
                    "sandbox.list_files",
                    "列出 /workspace 内文件",
                    objectParameters(
                            properties(
                                    property("path", "string"),
                                    property("recursive", "boolean")
                            ),
                            List.of()
                    )
            ),
            new LlmToolDefinition(
                    "sandbox.upload_file",
                    "把用户上传文件导入 /workspace/input",
                    objectParameters(
                            properties(
                                    property("attachment_id", "integer"),
                                    property("target_path", "string")
                            ),
                            List.of("attachment_id", "target_path")
                    )
            )
    );

    private final SandboxSessionService sandboxSessionService;
    private final SandboxToolService sandboxToolService;

    public BuiltinSandboxToolProvider(
            SandboxSessionService sandboxSessionService,
            SandboxToolService sandboxToolService
    ) {
        this.sandboxSessionService = sandboxSessionService;
        this.sandboxToolService = sandboxToolService;
    }

    @Override
    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        return TOOLS;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && SUPPORTED_TOOL_NAMES.contains(toolName);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        String toolName = request.toolName();
        if (!supports(toolName)) {
            return ToolExecutionResult.failed("不支持的工具: " + toolName);
        }
        try {
            String sandboxSessionId = sandboxSessionService.ensureSession(context.userId(), context.conversationId());
            if ("sandbox.exec".equals(toolName)) {
                return executeCommandResult(request, context, sandboxSessionId);
            }
            String result = switch (toolName) {
                case "sandbox.read_file" -> readFile(request, context, sandboxSessionId);
                case "sandbox.write_file" -> writeFile(request, context, sandboxSessionId);
                case "sandbox.write_workspace_file" -> writeWorkspaceFile(request, context, sandboxSessionId);
                case "sandbox.list_files" -> listFiles(request, context, sandboxSessionId);
                case "sandbox.upload_file" -> uploadFile(request, context, sandboxSessionId);
                default -> throw new IllegalStateException("不支持的工具: " + toolName);
            };
            return ToolExecutionResult.completed(result);
        } catch (Exception exception) {
            return ToolExecutionResult.failed(defaultString(exception.getMessage()));
        }
    }

    private ToolExecutionResult executeCommandResult(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            String sandboxSessionId
    ) {
        String toolName = request.toolName();
        String toolCallId = request.toolCallId();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        ExecOutcome outcome = new ExecOutcome();
        sandboxToolService.exec(
                context.userId(),
                context.conversationId(),
                sandboxSessionId,
                stringArg(request, "command", ""),
                stringArg(request, "working_dir", DEFAULT_WORKING_DIR),
                intArg(request, "timeout_seconds", DEFAULT_EXEC_TIMEOUT_SECONDS),
                event -> handleSandboxEvent(context, toolCallId, toolName, stdout, stderr, outcome, event)
        );
        String content = formatExecOutput(stdout, stderr);
        if (outcome.timedOut) {
            return ToolExecutionResult.failed("命令执行超时\n" + content);
        }
        if (outcome.exitCode != null && outcome.exitCode != 0) {
            return ToolExecutionResult.failed("命令执行失败(exitCode=" + outcome.exitCode + ")\n" + content);
        }
        if (outcome.errorMessage != null && !outcome.errorMessage.isBlank()) {
            return ToolExecutionResult.failed("命令执行失败: " + outcome.errorMessage + "\n" + content);
        }
        return ToolExecutionResult.completed(content);
    }

    private String readFile(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            String sandboxSessionId
    ) {
        return sandboxToolService.readFile(
                context.userId(),
                context.conversationId(),
                sandboxSessionId,
                stringArg(request, "path", DEFAULT_WORKING_DIR),
                intArg(request, "max_bytes", DEFAULT_READ_MAX_BYTES)
        );
    }

    private String writeFile(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            String sandboxSessionId
    ) {
        String path = stringArg(request, "path", DEFAULT_OUTPUT_PATH);
        String content = stringArg(request, "content", "");
        context.eventSink().output(request.toolCallId(), request.toolName(), "stdout",
                formatWriteFilePreview(path, content));
        sandboxToolService.writeFile(
                context.userId(),
                context.conversationId(),
                sandboxSessionId,
                path,
                content,
                booleanArg(request, "append", false)
        );
        return "文件写入完成: " + path;
    }

    private String writeWorkspaceFile(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            String sandboxSessionId
    ) {
        String path = stringArg(request, "path", DEFAULT_WORK_FILE_PATH);
        String content = stringArg(request, "content", "");
        context.eventSink().output(request.toolCallId(), request.toolName(), "stdout",
                formatWriteFilePreview(path, content));
        sandboxToolService.writeWorkspaceFile(
                context.userId(),
                context.conversationId(),
                sandboxSessionId,
                path,
                content,
                booleanArg(request, "append", false)
        );
        return "工作文件写入完成: " + path;
    }

    private String listFiles(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            String sandboxSessionId
    ) {
        List<SandboxFileItem> files = sandboxToolService.listFiles(
                context.userId(),
                context.conversationId(),
                sandboxSessionId,
                stringArg(request, "path", DEFAULT_WORKING_DIR),
                booleanArg(request, "recursive", false)
        );
        return formatFiles(files);
    }

    private String uploadFile(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            String sandboxSessionId
    ) {
        sandboxToolService.uploadFile(
                context.userId(),
                context.conversationId(),
                sandboxSessionId,
                longArg(request, "attachment_id", null),
                stringArg(request, "target_path", DEFAULT_UPLOAD_PATH)
        );
        return "上传文件已放入工作区";
    }

    private void handleSandboxEvent(
            ToolExecutionContext context,
            String toolCallId,
            String toolName,
            StringBuilder stdout,
            StringBuilder stderr,
            ExecOutcome outcome,
            SandboxEvent event
    ) {
        if (event == null) {
            return;
        }
        String content = defaultString(event.content());
        if ("stdout_delta".equals(event.type())) {
            stdout.append(content);
            context.eventSink().output(toolCallId, toolName, "stdout", content);
        } else if ("stderr_delta".equals(event.type())) {
            stderr.append(content);
            context.eventSink().output(toolCallId, toolName, "stderr", content);
        } else if ("completed".equals(event.type())) {
            outcome.exitCode = event.exitCode();
            outcome.timedOut = event.timedOut();
        } else if ("error".equals(event.type())) {
            outcome.errorMessage = defaultString(event.message());
            if (outcome.errorMessage.isBlank()) {
                outcome.errorMessage = content;
            }
        }
    }

    private static String formatExecOutput(StringBuilder stdout, StringBuilder stderr) {
        return "stdout:\n" + stdout + "\nstderr:\n" + stderr;
    }

    private static class ExecOutcome {
        private Integer exitCode;
        private boolean timedOut;
        private String errorMessage;
    }

    private static String formatFiles(List<SandboxFileItem> files) {
        if (files == null || files.isEmpty()) {
            return "没有文件";
        }
        return files.stream()
                .map(file -> (file.directory() ? "dir " : "file ") + file.path())
                .collect(Collectors.joining("\n"));
    }

    private static String formatWriteFilePreview(String path, String content) {
        String value = defaultString(content);
        String excerpt = value.length() > WRITE_FILE_PREVIEW_MAX_LENGTH
                ? value.substring(0, WRITE_FILE_PREVIEW_MAX_LENGTH) + "\n...（内容已截断）"
                : value;
        return "写入文件: " + path + "\n" + excerpt;
    }

    private static String stringArg(ToolExecutionRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int intArg(ToolExecutionRequest request, String name, int defaultValue) {
        Object value = request.arguments().get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static Long longArg(ToolExecutionRequest request, String name, Long defaultValue) {
        Object value = request.arguments().get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static boolean booleanArg(ToolExecutionRequest request, String name, boolean defaultValue) {
        Object value = request.arguments().get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return defaultValue;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    @SafeVarargs
    private static Map<String, Object> properties(Map.Entry<String, Map<String, Object>>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private static Map.Entry<String, Map<String, Object>> property(String name, String type) {
        return Map.entry(name, Map.of("type", type));
    }

    private static Map<String, Object> objectParameters(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required
        );
    }
}
