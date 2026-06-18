package com.agenthub.sandbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.admin.client.SandboxAdminClient;
import com.agenthub.sandbox.admin.dto.SandboxControlRequest;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxOverview;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.dto.SandboxResourceSummary;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import com.agenthub.sandbox.admin.dto.SandboxWarmPoolSnapshot;
import com.agenthub.sandbox.admin.service.impl.SandboxAdminServiceImpl;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.execution.mapper.SandboxExecutionMapper;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxAdminServiceImplTest {

    @Test
    void shouldReportWarnRiskWhenDbReadySessionMissingRuntime() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxAdminServiceImpl service = newService(client, sessionMapper, executionMapper);
        when(client.status()).thenReturn(runtimeStatus());
        when(sessionMapper.selectAdminOpenSessions(5)).thenReturn(List.of(dbSession("sbx-db")));
        when(sessionMapper.countOpenSessions()).thenReturn(1L);
        when(executionMapper.countAdminExecutionsByStatus("RUNNING")).thenReturn(0L);
        when(executionMapper.countAdminExecutionsByStatuses(List.of("FAILED", "TIMEOUT"))).thenReturn(0L);

        SandboxOverview overview = service.overview();

        assertThat(overview.dbOpenSessions()).isEqualTo(1);
        assertThat(overview.risks())
                .anySatisfy(risk -> {
                    assertThat(risk.level()).isEqualTo("WARN");
                    assertThat(risk.code()).isEqualTo("DB_SESSION_MISSING_RUNTIME");
                    assertThat(risk.targetId()).isEqualTo("sbx-db");
                });
    }

    @Test
    void shouldDefaultAdminListsToLatestFiveRows() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        SandboxAdminServiceImpl service = new SandboxAdminServiceImpl(client, sessionMapper, executionMapper, artifactMapper);
        when(client.status()).thenReturn(runtimeStatus());
        when(sessionMapper.selectAdminSessions(null, null, null, 5, 0)).thenReturn(List.of());
        when(executionMapper.selectAdminExecutions(null, null, null, 5, 0)).thenReturn(List.of());
        when(artifactMapper.selectAdminArtifacts(null, null, 5, 0)).thenReturn(List.of());
        when(sessionMapper.countAdminSessions(null, null, null)).thenReturn(11L);
        when(executionMapper.countAdminExecutions(null, null, null)).thenReturn(12L);
        when(artifactMapper.countAdminArtifacts(null, null)).thenReturn(13L);

        var sessionPage = service.sessions(null, null, null, null, null);
        var executionPage = service.executions(null, null, null, null, null);
        var artifactPage = service.artifacts(null, null, null, null);

        verify(sessionMapper).selectAdminSessions(null, null, null, 5, 0);
        verify(executionMapper).selectAdminExecutions(null, null, null, 5, 0);
        verify(artifactMapper).selectAdminArtifacts(null, null, 5, 0);
        assertThat(sessionPage.total()).isEqualTo(11);
        assertThat(executionPage.total()).isEqualTo(12);
        assertThat(artifactPage.total()).isEqualTo(13);
        assertThat(sessionPage.limit()).isEqualTo(5);
        assertThat(executionPage.offset()).isZero();
    }

    @Test
    void shouldMarkMissingRuntimeSessionsClosedWithoutCallingRuntimeClose() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxAdminServiceImpl service = newService(client, sessionMapper, executionMapper);
        when(client.status()).thenReturn(runtimeStatus());
        when(sessionMapper.markOpenSessionsMissingRuntimeClosed(eq(List.of()), any(OffsetDateTime.class))).thenReturn(3);

        SandboxControlResult result = service.closeMissingRuntimeSessions();

        assertThat(result.success()).isTrue();
        assertThat(result.affected()).containsExactly("已关闭 3 条缺失运行态的数据库会话");
        verify(client, never()).closeSession(any());
        verify(sessionMapper).markOpenSessionsMissingRuntimeClosed(eq(List.of()), any(OffsetDateTime.class));
    }

    @Test
    void shouldSyncDbClosedAfterReapingIdleRuntimeSessions() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxAdminServiceImpl service = newService(client, sessionMapper, executionMapper);
        SandboxControlResult reapResult = new SandboxControlResult(true, List.of("sbx-expired"), List.of());
        when(client.reapIdle()).thenReturn(reapResult);
        when(client.status()).thenReturn(runtimeStatus());
        when(sessionMapper.markOpenSessionsMissingRuntimeClosed(eq(List.of()), any(OffsetDateTime.class))).thenReturn(1);

        SandboxControlResult result = service.reapIdle();

        assertThat(result).isEqualTo(reapResult);
        verify(client).reapIdle();
        verify(sessionMapper).markOpenSessionsMissingRuntimeClosed(eq(List.of()), any(OffsetDateTime.class));
    }

    @Test
    void shouldCloseSessionAndMarkDbClosed() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxAdminServiceImpl service = newService(client, sessionMapper, executionMapper);
        SandboxControlResult result = new SandboxControlResult(true, List.of("sbx-1"), List.of());
        when(client.closeSession("sbx-1")).thenReturn(result);

        SandboxControlResult actual = service.closeSession("sbx-1");

        assertThat(actual).isEqualTo(result);
        verify(sessionMapper).markClosedBySandboxSessionId(eq("sbx-1"), any(OffsetDateTime.class));
    }

    @Test
    void shouldRejectKillVmWhenConfirmTextDoesNotMatchVmId() {
        SandboxAdminServiceImpl service = newService(
                mock(SandboxAdminClient.class),
                mock(SandboxSessionMapper.class),
                mock(SandboxExecutionMapper.class)
        );

        assertThatThrownBy(() -> service.killVm("vm-1", new SandboxControlRequest("释放资源", "wrong", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("确认文本必须等于 VM ID");
    }

    @Test
    void shouldClampTailLogLinesToMax500() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxAdminServiceImpl service = newService(
                client,
                mock(SandboxSessionMapper.class),
                mock(SandboxExecutionMapper.class)
        );
        when(client.tailLogs(500)).thenReturn("line");

        String logs = service.tailLogs(1000);

        assertThat(logs).isEqualTo("line");
        verify(client).tailLogs(500);
    }

    @Test
    void shouldForwardDryRunToDangerousRuntimeOperations() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxAdminServiceImpl service = newService(
                client,
                mock(SandboxSessionMapper.class),
                mock(SandboxExecutionMapper.class)
        );
        SandboxControlResult result = new SandboxControlResult(true, List.of(), List.of());
        when(client.killVm("vm-1", true)).thenReturn(result);
        when(client.clearRunDir(true)).thenReturn(result);
        when(client.rebuildWarmPool(true)).thenReturn(result);

        service.killVm("vm-1", new SandboxControlRequest("释放资源", "vm-1", true));
        service.clearRunDir(new SandboxControlRequest("释放磁盘", "清空运行目录", true));
        service.rebuildWarmPool(new SandboxControlRequest("恢复预热", "重建预热池", true));

        verify(client).killVm("vm-1", true);
        verify(client).clearRunDir(true);
        verify(client).rebuildWarmPool(true);
    }

    private SandboxAdminServiceImpl newService(
            SandboxAdminClient client,
            SandboxSessionMapper sessionMapper,
            SandboxExecutionMapper executionMapper
    ) {
        return new SandboxAdminServiceImpl(
                client,
                sessionMapper,
                executionMapper,
                mock(SandboxArtifactMapper.class)
        );
    }

    private SandboxRuntimeStatus runtimeStatus() {
        return new SandboxRuntimeStatus(
                true,
                null,
                10L,
                0,
                List.of(),
                new SandboxWarmPoolSnapshot(5, 0, 0, List.of()),
                new SandboxResourceSnapshot(null, "/run", 0L, List.of(), List.of(), List.of(), List.of()),
                new SandboxResourceSummary(0, 0, 0, 0L, 0)
        );
    }

    private SandboxSession dbSession(String sandboxSessionId) {
        SandboxSession session = new SandboxSession();
        session.setId(1L);
        session.setConversationId(100L);
        session.setSandboxSessionId(sandboxSessionId);
        session.setStatus("READY");
        return session;
    }
}
