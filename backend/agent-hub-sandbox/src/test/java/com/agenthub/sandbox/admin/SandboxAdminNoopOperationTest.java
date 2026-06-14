package com.agenthub.sandbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.sandbox.admin.client.SandboxAdminClient;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.dto.SandboxResourceSummary;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import com.agenthub.sandbox.admin.dto.SandboxWarmPoolSnapshot;
import com.agenthub.sandbox.admin.service.impl.SandboxAdminServiceImpl;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.execution.mapper.SandboxExecutionMapper;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxAdminNoopOperationTest {

    @Test
    void shouldTreatEmptyReapIdleResultAsSuccessfulNoop() {
        SandboxAdminClient client = mock(SandboxAdminClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxAdminServiceImpl service = new SandboxAdminServiceImpl(
                client,
                sessionMapper,
                executionMapper,
                mock(SandboxArtifactMapper.class)
        );
        when(client.reapIdle()).thenReturn(new SandboxControlResult(false, List.of(), List.of()));
        when(client.status()).thenReturn(runtimeStatus());
        when(sessionMapper.markOpenSessionsMissingRuntimeClosed(eq(List.of()), any(OffsetDateTime.class))).thenReturn(0);

        SandboxControlResult result = service.reapIdle();

        assertThat(result.success()).isTrue();
        assertThat(result.affected()).containsExactly("无空闲运行时需要回收");
        assertThat(result.errors()).isEmpty();
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
}
