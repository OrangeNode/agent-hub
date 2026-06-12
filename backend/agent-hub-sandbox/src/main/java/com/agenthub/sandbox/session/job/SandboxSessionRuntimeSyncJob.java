package com.agenthub.sandbox.session.job;

import com.agenthub.sandbox.admin.service.SandboxAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SandboxSessionRuntimeSyncJob {

    private static final Logger log = LoggerFactory.getLogger(SandboxSessionRuntimeSyncJob.class);

    private final SandboxAdminService adminService;

    public SandboxSessionRuntimeSyncJob(SandboxAdminService adminService) {
        this.adminService = adminService;
    }

    @Scheduled(
            initialDelayString = "${agent-hub.sandbox.session-runtime-sync-initial-delay-ms:60000}",
            fixedDelayString = "${agent-hub.sandbox.session-runtime-sync-delay-ms:60000}"
    )
    public void syncMissingRuntimeSessions() {
        try {
            adminService.closeMissingRuntimeSessions();
        } catch (RuntimeException exception) {
            log.warn("同步缺失运行态的沙箱会话失败", exception);
        }
    }
}
