package com.agenthub.sandbox.admin.dto;

import java.time.OffsetDateTime;

public record SandboxAdminExecutionItem(
        Long id,
        Long sandboxSessionId,
        Long conversationId,
        String toolName,
        String command,
        String workingDir,
        String status,
        Integer exitCode,
        String errorMessage,
        Long durationMs,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
