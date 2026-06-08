package com.agenthub.sandbox.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.artifact.entity.SandboxArtifact;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.ExecSandboxRequest;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.execution.entity.SandboxExecution;
import com.agenthub.sandbox.execution.mapper.SandboxExecutionMapper;
import com.agenthub.sandbox.policy.CommandPolicy;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agenthub.storage.attachment.dto.AttachmentImportSource;
import com.agenthub.storage.attachment.service.AttachmentService;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SandboxToolServiceImpl implements SandboxToolService {

    private static final String TOOL_EXEC = "exec";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final int EXCERPT_LIMIT = 4096;

    private final SandboxClient client;
    private final CommandPolicy commandPolicy;
    private final SandboxSessionMapper sessionMapper;
    private final SandboxExecutionMapper executionMapper;
    private final SandboxArtifactMapper artifactMapper;
    private final AttachmentService attachmentService;
    private final HttpClient httpClient;

    public SandboxToolServiceImpl(
            SandboxClient client,
            CommandPolicy commandPolicy,
            SandboxSessionMapper sessionMapper,
            SandboxExecutionMapper executionMapper,
            SandboxArtifactMapper artifactMapper
    ) {
        this(client, commandPolicy, sessionMapper, executionMapper, artifactMapper, null, HttpClient.newHttpClient());
    }

    @Autowired
    public SandboxToolServiceImpl(
            SandboxClient client,
            CommandPolicy commandPolicy,
            SandboxSessionMapper sessionMapper,
            SandboxExecutionMapper executionMapper,
            SandboxArtifactMapper artifactMapper,
            AttachmentService attachmentService
    ) {
        this(client, commandPolicy, sessionMapper, executionMapper, artifactMapper, attachmentService,
                HttpClient.newHttpClient());
    }

    public SandboxToolServiceImpl(
            SandboxClient client,
            CommandPolicy commandPolicy,
            SandboxSessionMapper sessionMapper,
            SandboxExecutionMapper executionMapper,
            SandboxArtifactMapper artifactMapper,
            AttachmentService attachmentService,
            HttpClient httpClient
    ) {
        this.client = client;
        this.commandPolicy = commandPolicy;
        this.sessionMapper = sessionMapper;
        this.executionMapper = executionMapper;
        this.artifactMapper = artifactMapper;
        this.attachmentService = attachmentService;
        this.httpClient = httpClient;
    }

    @Override
    public void exec(
            Long userId,
            Long conversationId,
            String sandboxSessionId,
            String command,
            String workingDir,
            int timeoutSeconds,
            Consumer<SandboxEvent> eventConsumer
    ) {
        commandPolicy.validate(command, workingDir, timeoutSeconds);
        SandboxSession session = requireOwnedSession(userId, conversationId, sandboxSessionId);
        SandboxExecution execution = newExecution(session.getId(), conversationId, command, workingDir, timeoutSeconds);
        executionMapper.insert(execution);
        ExecutionAccumulator accumulator = new ExecutionAccumulator(execution);
        try {
            client.streamExec(sandboxSessionId, new ExecSandboxRequest(
                    "exec_" + execution.getId(),
                    command,
                    workingDir,
                    timeoutSeconds
            ), event -> {
                accumulator.accept(event);
                if (eventConsumer != null) {
                    forwardEvent(eventConsumer, event);
                }
            });
            if (STATUS_RUNNING.equals(execution.getStatus())) {
                failExecution(execution, ErrorCode.COMMAND_FAILED, "命令执行未收到终态事件");
            }
        } catch (BusinessException exception) {
            failExecution(execution, exception.errorCode(), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            failExecution(execution, ErrorCode.COMMAND_FAILED, exception.getMessage());
            throw exception;
        }
    }

    @Override
    public List<SandboxFileItem> listFiles(Long userId, Long conversationId, String sandboxSessionId,
                                           String path, boolean recursive) {
        requireOwnedSession(userId, conversationId, sandboxSessionId);
        String normalizedPath = commandPolicy.validateWorkspacePath(path, "路径不在允许访问范围内");
        return client.listFiles(sandboxSessionId, normalizedPath, recursive);
    }

    @Override
    public String readFile(Long userId, Long conversationId, String sandboxSessionId, String path, int maxBytes) {
        requireOwnedSession(userId, conversationId, sandboxSessionId);
        String normalizedPath = commandPolicy.validateWorkspacePath(path, "路径不在允许访问范围内");
        if (maxBytes <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取大小上限必须大于 0");
        }
        return client.readFile(sandboxSessionId, normalizedPath, maxBytes);
    }

    @Override
    public void writeFile(Long userId, Long conversationId, String sandboxSessionId,
                          String path, String content, boolean append) {
        requireOwnedSession(userId, conversationId, sandboxSessionId);
        String normalizedPath = commandPolicy.validateWorkspacePath(path, "路径不在允许访问范围内");
        client.writeFile(sandboxSessionId, normalizedPath, content == null ? "" : content, append);
    }

    @Override
    public void uploadFile(Long userId, Long conversationId, String sandboxSessionId, Long attachmentId, String targetPath) {
        requireOwnedSession(userId, conversationId, sandboxSessionId);
        String normalizedPath = commandPolicy.validateWorkspacePath(targetPath, "附件导入路径不在允许访问范围内");
        if (attachmentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "attachmentId 不能为空");
        }
        if (attachmentService == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "附件导入服务不可用");
        }
        AttachmentImportSource source = attachmentService.requireImportSource(userId, attachmentId);
        client.writeFile(sandboxSessionId, normalizedPath, downloadText(source.downloadUrl()), false);
    }

    @Override
    public List<SandboxArtifactItem> artifacts(Long userId, Long conversationId, boolean latestOnly) {
        LambdaQueryWrapper<SandboxArtifact> wrapper = new LambdaQueryWrapper<SandboxArtifact>()
                .eq(SandboxArtifact::getConversationId, conversationId)
                .orderByAsc(SandboxArtifact::getRelativePath)
                .orderByDesc(SandboxArtifact::getVersion)
                .orderByDesc(SandboxArtifact::getId);
        List<SandboxArtifact> records = artifactMapper.selectList(wrapper);
        if (!latestOnly) {
            return records.stream().map(this::toItem).toList();
        }
        Map<String, SandboxArtifact> latestByPath = new LinkedHashMap<>();
        for (SandboxArtifact record : records) {
            latestByPath.putIfAbsent(record.getRelativePath(), record);
        }
        return latestByPath.values().stream().map(this::toItem).toList();
    }

    private SandboxSession requireOwnedSession(Long userId, Long conversationId, String sandboxSessionId) {
        SandboxSession session = sessionMapper.selectBySandboxSessionId(sandboxSessionId);
        if (session == null
                || !sameId(session.getUserId(), userId)
                || !sameId(session.getConversationId(), conversationId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    private boolean sameId(Long actual, Long expected) {
        return actual != null && actual.equals(expected);
    }

    private SandboxExecution newExecution(Long sandboxSessionDbId, Long conversationId, String command, String workingDir, int timeoutSeconds) {
        SandboxExecution execution = new SandboxExecution();
        execution.setSandboxSessionId(sandboxSessionDbId);
        execution.setConversationId(conversationId);
        execution.setToolName(TOOL_EXEC);
        execution.setCommand(command);
        execution.setWorkingDir(workingDir);
        execution.setTimeoutSeconds(timeoutSeconds);
        execution.setStatus(STATUS_RUNNING);
        execution.setStartedAt(OffsetDateTime.now());
        return execution;
    }

    private void failExecution(SandboxExecution execution, ErrorCode errorCode, String message) {
        if (!STATUS_RUNNING.equals(execution.getStatus())) {
            return;
        }
        execution.setStatus(errorCode == ErrorCode.COMMAND_TIMEOUT ? STATUS_TIMEOUT : STATUS_FAILED);
        execution.setErrorCode(errorCode.code());
        execution.setErrorMessage(message == null || message.isBlank() ? errorCode.message() : message);
        execution.setCompletedAt(OffsetDateTime.now());
        execution.setDurationMs(durationMs(execution));
        executionMapper.updateById(execution);
    }

    private long durationMs(SandboxExecution execution) {
        if (execution.getStartedAt() == null || execution.getCompletedAt() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis());
    }

    private SandboxArtifactItem toItem(SandboxArtifact artifact) {
        return new SandboxArtifactItem(
                artifact.getId(),
                artifact.getFileName(),
                artifact.getRelativePath(),
                artifact.getStorageBucket(),
                artifact.getStorageKey(),
                artifact.getContentType(),
                artifact.getSizeBytes(),
                artifact.getSha256(),
                artifact.getVersion()
        );
    }

    private void forwardEvent(Consumer<SandboxEvent> eventConsumer, SandboxEvent event) {
        eventConsumer.accept(event);
    }

    private String downloadText(String downloadUrl) {
        try {
            if (downloadUrl != null && downloadUrl.startsWith("data:")) {
                return readDataUrl(downloadUrl);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "附件下载失败，HTTP 状态码: " + response.statusCode());
            }
            return response.body();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "附件下载失败: " + exception.getMessage());
        }
    }

    private String readDataUrl(String dataUrl) {
        int commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0) {
            return "";
        }
        String meta = dataUrl.substring(0, commaIndex);
        String payload = dataUrl.substring(commaIndex + 1);
        if (meta.contains(";base64")) {
            return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        }
        return URLDecoder.decode(payload, StandardCharsets.UTF_8);
    }

    private class ExecutionAccumulator {

        private final SandboxExecution execution;
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();

        private ExecutionAccumulator(SandboxExecution execution) {
            this.execution = execution;
        }

        private void accept(SandboxEvent event) {
            if (event == null) {
                return;
            }
            if ("stdout_delta".equals(event.type())) {
                append(stdout, event.content());
                execution.setStdoutExcerpt(excerpt(stdout));
            } else if ("stderr_delta".equals(event.type())) {
                append(stderr, event.content());
                execution.setStderrExcerpt(excerpt(stderr));
            } else if ("completed".equals(event.type())) {
                complete(event);
            } else if ("error".equals(event.type())) {
                failExecution(execution, ErrorCode.COMMAND_FAILED,
                        event.message() == null ? "命令执行失败" : event.message());
            } else if ("artifact_synced".equals(event.type())) {
                persistArtifact(event.artifact());
            }
        }

        private void complete(SandboxEvent event) {
            execution.setExitCode(event.exitCode());
            execution.setDurationMs(event.durationMs());
            execution.setCompletedAt(OffsetDateTime.now());
            if (event.timedOut()) {
                execution.setStatus(STATUS_TIMEOUT);
                execution.setErrorCode(ErrorCode.COMMAND_TIMEOUT.code());
                execution.setErrorMessage(ErrorCode.COMMAND_TIMEOUT.message());
            } else if (event.exitCode() != null && event.exitCode() == 0) {
                execution.setStatus(STATUS_COMPLETED);
            } else {
                execution.setStatus(STATUS_FAILED);
                execution.setErrorCode(ErrorCode.COMMAND_FAILED.code());
                execution.setErrorMessage(ErrorCode.COMMAND_FAILED.message());
            }
            if (execution.getDurationMs() == null) {
                execution.setDurationMs(durationMs(execution));
            }
            executionMapper.updateById(execution);
        }

        private void persistArtifact(SandboxArtifactItem item) {
            if (item == null) {
                return;
            }
            SandboxArtifact artifact = new SandboxArtifact();
            artifact.setSandboxSessionId(execution.getSandboxSessionId());
            artifact.setExecutionId(execution.getId());
            artifact.setConversationId(execution.getConversationId());
            artifact.setRelativePath(item.relativePath());
            artifact.setFileName(item.fileName());
            artifact.setSizeBytes(item.sizeBytes() == null ? 0L : item.sizeBytes());
            artifact.setSha256(item.sha256());
            artifact.setContentType(item.contentType() == null ? "application/octet-stream" : item.contentType());
            artifact.setStorageBucket(item.storageBucket());
            artifact.setStorageKey(item.storageKey());
            artifact.setVersion(item.version() == null ? 1 : item.version());
            artifact.setCreatedAt(OffsetDateTime.now());
            artifactMapper.insert(artifact);
        }

        private void append(StringBuilder builder, String content) {
            if (content != null) {
                builder.append(content);
                if (builder.length() > EXCERPT_LIMIT) {
                    builder.delete(0, builder.length() - EXCERPT_LIMIT);
                }
            }
        }

        private String excerpt(StringBuilder builder) {
            if (builder.length() <= EXCERPT_LIMIT) {
                return builder.toString();
            }
            return builder.substring(builder.length() - EXCERPT_LIMIT);
        }
    }
}
