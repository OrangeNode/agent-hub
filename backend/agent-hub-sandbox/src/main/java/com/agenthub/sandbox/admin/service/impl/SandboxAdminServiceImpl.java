package com.agenthub.sandbox.admin.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.admin.client.SandboxAdminClient;
import com.agenthub.sandbox.admin.dto.SandboxAdminArtifactItem;
import com.agenthub.sandbox.admin.dto.SandboxAdminExecutionItem;
import com.agenthub.sandbox.admin.dto.SandboxAdminSessionItem;
import com.agenthub.sandbox.admin.dto.SandboxControlRequest;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxOrphanResource;
import com.agenthub.sandbox.admin.dto.SandboxOverview;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.dto.SandboxRisk;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeSession;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import com.agenthub.sandbox.artifact.entity.SandboxArtifact;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.execution.entity.SandboxExecution;
import com.agenthub.sandbox.execution.mapper.SandboxExecutionMapper;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SandboxAdminServiceImpl implements com.agenthub.sandbox.admin.service.SandboxAdminService {

    private static final String CONFIRM_CLEANUP_ORPHANS = "清理残留资源";
    private static final String CONFIRM_CLEAR_RUN_DIR = "清空运行目录";
    private static final String CONFIRM_REBUILD_WARM_POOL = "重建预热池";
    private static final int DEFAULT_LOG_LINES = 100;
    private static final int MAX_LOG_LINES = 500;
    private static final int DEFAULT_LIST_LIMIT = 5;
    private static final int MAX_LIST_LIMIT = 50;

    private final SandboxAdminClient client;
    private final SandboxSessionMapper sessionMapper;
    private final SandboxExecutionMapper executionMapper;
    private final SandboxArtifactMapper artifactMapper;

    public SandboxAdminServiceImpl(
            SandboxAdminClient client,
            SandboxSessionMapper sessionMapper,
            SandboxExecutionMapper executionMapper,
            SandboxArtifactMapper artifactMapper
    ) {
        this.client = client;
        this.sessionMapper = sessionMapper;
        this.executionMapper = executionMapper;
        this.artifactMapper = artifactMapper;
    }

    @Override
    public SandboxOverview overview() {
        SandboxRuntimeStatus runtime;
        try {
            runtime = client.status();
        } catch (RuntimeException exception) {
            return new SandboxOverview(false, 0, 0, 0, 0, 0, 0, 0, 0, List.of(
                    new SandboxRisk("CRITICAL", "SERVICE_UNAVAILABLE", "Sandbox 服务不可用",
                            "无法连接 sandbox-service: " + exception.getMessage(), "SERVICE", "sandbox-service", "检查服务进程和内部 token")
            ), List.of());
        }
        List<SandboxSession> dbSessions = sessionMapper.selectAdminOpenSessions(DEFAULT_LIST_LIMIT);
        List<SandboxRisk> risks = buildRisks(runtime, dbSessions, DEFAULT_LIST_LIMIT);
        long dbOpenSessions = sessionMapper.countOpenSessions();
        long runningExecutions = executionMapper.countAdminExecutionsByStatus("RUNNING");
        long failedExecutions = executionMapper.countAdminExecutionsByStatuses(List.of("FAILED", "TIMEOUT"));
        return new SandboxOverview(
                runtime.serviceHealthy(),
                runtime.activeSessions(),
                dbOpenSessions,
                runningExecutions,
                failedExecutions,
                runtime.resourceSummary().firecrackerProcesses(),
                runtime.resourceSummary().tapDevices(),
                runtime.resourceSummary().runDirBytes(),
                runtime.resourceSummary().orphanResources(),
                risks,
                limitItems(runtime.sessions(), DEFAULT_LIST_LIMIT)
        );
    }

    @Override
    public PageResult<SandboxAdminSessionItem> sessions(
            String status,
            Long conversationId,
            String sandboxSessionId,
            Integer limit,
            Integer offset
    ) {
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = normalizeOffset(offset);
        Map<String, SandboxRuntimeSession> runtimeBySessionId = new HashMap<>();
        for (SandboxRuntimeSession session : client.status().sessions()) {
            runtimeBySessionId.put(session.sandboxSessionId(), session);
        }
        List<SandboxAdminSessionItem> items = sessionMapper.selectAdminSessions(
                        status,
                        conversationId,
                        sandboxSessionId,
                        normalizedLimit,
                        normalizedOffset
                ).stream()
                .map(session -> toSessionItem(session, runtimeBySessionId.get(session.getSandboxSessionId())))
                .toList();
        long total = sessionMapper.countAdminSessions(status, conversationId, sandboxSessionId);
        return PageResult.of(items, total, normalizedLimit, normalizedOffset);
    }

    @Override
    public PageResult<SandboxAdminExecutionItem> executions(
            Long conversationId,
            Long sandboxSessionDbId,
            String status,
            Integer limit,
            Integer offset
    ) {
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = normalizeOffset(offset);
        List<SandboxAdminExecutionItem> items = executionMapper.selectAdminExecutions(
                        conversationId,
                        sandboxSessionDbId,
                        status,
                        normalizedLimit,
                        normalizedOffset
                ).stream()
                .map(this::toExecutionItem)
                .toList();
        long total = executionMapper.countAdminExecutions(conversationId, sandboxSessionDbId, status);
        return PageResult.of(items, total, normalizedLimit, normalizedOffset);
    }

    @Override
    public PageResult<SandboxAdminArtifactItem> artifacts(
            Long conversationId,
            Long sandboxSessionDbId,
            Integer limit,
            Integer offset
    ) {
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = normalizeOffset(offset);
        List<SandboxAdminArtifactItem> items = artifactMapper.selectAdminArtifacts(
                        conversationId,
                        sandboxSessionDbId,
                        normalizedLimit,
                        normalizedOffset
                ).stream()
                .map(this::toArtifactItem)
                .toList();
        long total = artifactMapper.countAdminArtifacts(conversationId, sandboxSessionDbId);
        return PageResult.of(items, total, normalizedLimit, normalizedOffset);
    }

    @Override
    public SandboxResourceSnapshot resources() {
        return client.resources();
    }

    @Override
    public SandboxControlResult reapIdle() {
        SandboxControlResult result = client.reapIdle();
        syncOpenSessionsWithRuntime();
        if (!result.success() && result.affected().isEmpty() && result.errors().isEmpty()) {
            return new SandboxControlResult(true, List.of("无空闲运行时需要回收"), List.of());
        }
        return result;
    }

    @Override
    public SandboxControlResult closeSession(String sessionId) {
        SandboxControlResult result = client.closeSession(sessionId);
        sessionMapper.markClosedBySandboxSessionId(sessionId, OffsetDateTime.now());
        return result;
    }

    @Override
    public SandboxControlResult closeMissingRuntimeSessions() {
        int closed = syncOpenSessionsWithRuntime();
        String message = "已关闭 " + closed + " 条缺失运行态的数据库会话";
        return new SandboxControlResult(true, List.of(message), List.of());
    }

    @Override
    public SandboxControlResult killVm(String vmId, SandboxControlRequest request) {
        requireReason(request);
        if (request == null || !vmId.equals(request.confirmText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "确认文本必须等于 VM ID");
        }
        return client.killVm(vmId, request.dryRun());
    }

    @Override
    public SandboxControlResult cleanupOrphans(SandboxControlRequest request) {
        requireReason(request);
        requireConfirmText(request, CONFIRM_CLEANUP_ORPHANS);
        return client.cleanupOrphans(request.dryRun());
    }

    @Override
    public SandboxControlResult clearRunDir(SandboxControlRequest request) {
        requireReason(request);
        requireConfirmText(request, CONFIRM_CLEAR_RUN_DIR);
        return client.clearRunDir(request.dryRun());
    }

    @Override
    public SandboxControlResult rebuildWarmPool(SandboxControlRequest request) {
        requireReason(request);
        requireConfirmText(request, CONFIRM_REBUILD_WARM_POOL);
        return client.rebuildWarmPool(request.dryRun());
    }

    @Override
    public String tailLogs(int lines) {
        return client.tailLogs(normalizeLogLines(lines));
    }

    private List<SandboxRisk> buildRisks(SandboxRuntimeStatus runtime, List<SandboxSession> dbSessions, int limit) {
        Set<String> runtimeSessionIds = new HashSet<>();
        java.util.ArrayList<SandboxRisk> risks = new java.util.ArrayList<>();
        for (SandboxRuntimeSession session : runtime.sessions()) {
            runtimeSessionIds.add(session.sandboxSessionId());
            if (risks.size() < limit && sameStatus(session.runtimeStatus(), "IDLE")) {
                risks.add(new SandboxRisk("WARN", "IDLE_TIMEOUT", "运行时已空闲超时",
                        "运行态 session 已达到 idle timeout，可执行空闲回收", "SESSION", session.sandboxSessionId(), "执行回收空闲运行时"));
            }
        }
        for (SandboxSession session : dbSessions) {
            if (risks.size() >= limit) {
                break;
            }
            if (isOpenSession(session) && !runtimeSessionIds.contains(session.getSandboxSessionId())) {
                risks.add(new SandboxRisk("WARN", "DB_SESSION_MISSING_RUNTIME", "数据库会话缺少运行态",
                        "数据库中存在未关闭会话，但 Go 运行态没有对应 session", "SESSION", session.getSandboxSessionId(), "关闭数据库会话或检查运行态同步"));
            }
        }
        for (SandboxOrphanResource orphan : runtime.resources().orphans()) {
            if (risks.size() >= limit) {
                break;
            }
            risks.add(new SandboxRisk("WARN", orphan.type(), "发现残留资源", orphan.reason(),
                    orphan.type(), orphan.id(), "确认后清理残留资源"));
        }
        return risks;
    }

    private SandboxAdminSessionItem toSessionItem(SandboxSession session, SandboxRuntimeSession runtime) {
        return new SandboxAdminSessionItem(
                session.getId(),
                session.getConversationId(),
                session.getSandboxSessionId(),
                session.getStatus(),
                runtime == null ? null : runtime.runtimeStatus(),
                runtime == null ? null : runtime.vmId(),
                runtime == null ? null : runtime.runDir(),
                runtime == null ? null : runtime.tapName(),
                runtime == null ? null : runtime.firecrackerPid(),
                runtime == null ? 0L : runtime.idleSeconds(),
                session.getLastActiveAt(),
                session.getCreatedAt(),
                session.getClosedAt()
        );
    }

    private SandboxAdminExecutionItem toExecutionItem(SandboxExecution execution) {
        return new SandboxAdminExecutionItem(
                execution.getId(),
                execution.getSandboxSessionId(),
                execution.getConversationId(),
                execution.getToolName(),
                execution.getCommand(),
                execution.getWorkingDir(),
                execution.getStatus(),
                execution.getExitCode(),
                execution.getErrorMessage(),
                execution.getDurationMs(),
                execution.getStartedAt(),
                execution.getCompletedAt()
        );
    }

    private SandboxAdminArtifactItem toArtifactItem(SandboxArtifact artifact) {
        return new SandboxAdminArtifactItem(
                artifact.getId(),
                artifact.getSandboxSessionId(),
                artifact.getConversationId(),
                artifact.getExecutionId(),
                artifact.getRelativePath(),
                artifact.getFileName(),
                artifact.getSizeBytes(),
                artifact.getContentType(),
                artifact.getVersion(),
                artifact.getCreatedAt()
        );
    }

    private boolean isOpenSession(SandboxSession session) {
        return sameStatus(session.getStatus(), "READY") || sameStatus(session.getStatus(), "RUNNING");
    }

    private int syncOpenSessionsWithRuntime() {
        SandboxRuntimeStatus runtime = client.status();
        List<String> runtimeSessionIds = runtime.sessions().stream()
                .map(SandboxRuntimeSession::sandboxSessionId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        return sessionMapper.markOpenSessionsMissingRuntimeClosed(runtimeSessionIds, OffsetDateTime.now());
    }

    private boolean sameStatus(String actual, String expected) {
        return actual != null && actual.trim().toUpperCase(Locale.ROOT).equals(expected);
    }

    private void requireReason(SandboxControlRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "操作原因不能为空");
        }
    }

    private void requireConfirmText(SandboxControlRequest request, String expected) {
        if (request == null || !expected.equals(request.confirmText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "确认文本必须等于：" + expected);
        }
    }

    private int normalizeLogLines(int lines) {
        if (lines <= 0) {
            return DEFAULT_LOG_LINES;
        }
        return Math.min(lines, MAX_LOG_LINES);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private int normalizeOffset(Integer offset) {
        if (offset == null || offset <= 0) {
            return 0;
        }
        return offset;
    }

    private <T> List<T> limitItems(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().limit(limit).toList();
    }
}
