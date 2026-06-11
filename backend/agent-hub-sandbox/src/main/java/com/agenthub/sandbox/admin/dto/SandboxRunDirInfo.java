package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxRunDirInfo(
        String vmId,
        String path,
        long sizeBytes,
        OffsetDateTime createdAt,
        boolean hasSocket,
        boolean bound,
        String resourceStatus
) {
}
