package com.agenthub.sandbox.admin.service.impl;

import com.agenthub.common.api.ErrorCode;
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
            ));
        }
        List<SandboxSession> dbSessions = sessionMapper.selectAdminSessions(null, null, null);
        List<SandboxExecution> executions = executionMapper.selectAdminExecutions(null, null, null);
        List<SandboxRisk> risks = buildRisks(runtime, dbSessions);
        long dbOpenSessions = dbSessions.stream().filter(this::isOpenSession).count();
        long runningExecutions = executions.stream().filter(item -> sameStatus(item.getStatus(), "RUNNING")).count();
        long failedExecutions = executions.stream()
                .filter(item -> sameStatus(item.getStatus(), "FAILED") || sameStatus(item.getStatus(), "TIMEOUT"))
                .count();
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
                risks
        );
    }

    @Override
    public List<SandboxAdminSessionItem> sessions(String status, Long conversationId, String sandboxSessionId) {
        Map<String, SandboxRuntimeSession> runtimeBySessionId = new HashMap<>();
        for (SandboxRuntimeSession session : client.status().sessions()) {
            runtimeBySessionId.put(session.sandboxSessionId(), session);
        }
        return sessionMapper.selectAdminSessions(status, conversationId, sandboxSessionId).stream()
                .map(session -> toSessionItem(session, runtimeBySessionId.get(session.getSandboxSessionId())))
                .toList();
    }

    @Override
    public List<SandboxAdminExecutionItem> executions(Long conversationId, Long sandboxSessionDbId, String status) {
        return executionMapper.selectAdminExecutions(conversationId, sandboxSessionDbId, status).stream()
                .map(this::toExecutionItem)
                .toList();
    }

    @Override
    public List<SandboxAdminArtifactItem> artifacts(Long conversationId, Long sandboxSessionDbId) {
        return artifactMapper.selectAdminArtifacts(conversationId, sandboxSessionDbId).stream()
                .map(this::toArtifactItem)
                .toList();
    }

    @Override
    public SandboxResourceSnapshot resources() {
        return client.resources();
    }

    @Override
    public SandboxControlResult reapIdle() {
        return client.reapIdle();
    }

    @Override
    public SandboxControlResult closeSession(String sessionId) {
        SandboxControlResult result = client.closeSession(sessionId);
        sessionMapper.markClosedBySandboxSessionId(sessionId, OffsetDateTime.now());
        return result;
    }

    @Override
    public SandboxControlResult killVm(String vmId, SandboxControlRequest request) {
        requireReason(request);
        if (request == null || !vmId.equals(request.confirmText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "确认文本必须等于 VM ID");
        }
        return client.killVm(vmId);
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
        return client.clearRunDir();
    }

    @Override
    public SandboxControlResult rebuildWarmPool(SandboxControlRequest request) {
        requireReason(request);
        requireConfirmText(request, CONFIRM_REBUILD_WARM_POOL);
        return client.rebuildWarmPool();
    }

    @Override
    public String tailLogs(int lines) {
        return client.tailLogs(normalizeLogLines(lines));
    }

    private List<SandboxRisk> buildRisks(SandboxRuntimeStatus runtime, List<SandboxSession> dbSessions) {
        Set<String> runtimeSessionIds = new HashSet<>();
        java.util.ArrayList<SandboxRisk> risks = new java.util.ArrayList<>();
        for (SandboxRuntimeSession session : runtime.sessions()) {
            runtimeSessionIds.add(session.sandboxSessionId());
            if (sameStatus(session.runtimeStatus(), "IDLE")) {
                risks.add(new SandboxRisk("WARN", "IDLE_TIMEOUT", "运行时已空闲超时",
                        "运行态 session 已达到 idle timeout，可执行空闲回收", "SESSION", session.sandboxSessionId(), "执行回收空闲运行时"));
            }
        }
        for (SandboxSession session : dbSessions) {
            if (isOpenSession(session) && !runtimeSessionIds.contains(session.getSandboxSessionId())) {
                risks.add(new SandboxRisk("WARN", "DB_SESSION_MISSING_RUNTIME", "数据库会话缺少运行态",
                        "数据库中存在未关闭会话，但 Go 运行态没有对应 session", "SESSION", session.getSandboxSessionId(), "关闭数据库会话或检查运行态同步"));
            }
        }
        for (SandboxOrphanResource orphan : runtime.resources().orphans()) {
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
}
