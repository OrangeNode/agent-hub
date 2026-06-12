package com.agenthub.sandbox.session.job;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.service.SandboxAdminService;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxSessionRuntimeSyncJobTest {

    @Test
    void shouldCloseDbSessionsMissingRuntimeOnSchedule() {
        SandboxAdminService adminService = mock(SandboxAdminService.class);
        when(adminService.closeMissingRuntimeSessions())
                .thenReturn(new SandboxControlResult(true, List.of("已关闭 1 条缺失运行态的数据库会话"), List.of()));
        SandboxSessionRuntimeSyncJob job = new SandboxSessionRuntimeSyncJob(adminService);

        job.syncMissingRuntimeSessions();

        verify(adminService).closeMissingRuntimeSessions();
    }

    @Test
    void shouldKeepSchedulerAliveWhenRuntimeStatusQueryFails() {
        SandboxAdminService adminService = mock(SandboxAdminService.class);
        when(adminService.closeMissingRuntimeSessions()).thenThrow(new RuntimeException("sandbox-service unavailable"));
        SandboxSessionRuntimeSyncJob job = new SandboxSessionRuntimeSyncJob(adminService);

        assertThatNoException().isThrownBy(job::syncMissingRuntimeSessions);
    }
}
