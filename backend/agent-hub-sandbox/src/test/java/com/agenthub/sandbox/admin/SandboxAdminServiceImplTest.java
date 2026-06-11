package com.agenthub.sandbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(sessionMapper.selectAdminSessions(null, null, null)).thenReturn(List.of(dbSession("sbx-db")));
        when(executionMapper.selectAdminExecutions(null, null, null)).thenReturn(List.of());

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
