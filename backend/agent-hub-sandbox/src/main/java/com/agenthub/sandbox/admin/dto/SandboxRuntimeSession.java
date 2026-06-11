package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxRuntimeSession(
        String sandboxSessionId,
        String conversationId,
        String vmId,
        String agentUrl,
        OffsetDateTime lastActiveAt,
        int activeUses,
        long idleSeconds,
        long idleTimeoutSeconds,
        String runDir,
        String tapName,
        Integer firecrackerPid,
        String runtimeStatus
) {
}
