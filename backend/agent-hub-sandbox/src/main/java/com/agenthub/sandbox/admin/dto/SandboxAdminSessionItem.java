package com.agenthub.sandbox.admin.dto;

import java.time.OffsetDateTime;

public record SandboxAdminSessionItem(
        Long id,
        Long conversationId,
        String sandboxSessionId,
        String dbStatus,
        String runtimeStatus,
        String vmId,
        String runDir,
        String tapName,
        Integer firecrackerPid,
        long idleSeconds,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt,
        OffsetDateTime closedAt
) {
}
