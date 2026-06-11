package com.agenthub.sandbox.admin.dto;

import java.time.OffsetDateTime;

public record SandboxAdminArtifactItem(
        Long id,
        Long sandboxSessionId,
        Long conversationId,
        Long executionId,
        String relativePath,
        String fileName,
        Long sizeBytes,
        String contentType,
        Integer version,
        OffsetDateTime createdAt
) {
}
