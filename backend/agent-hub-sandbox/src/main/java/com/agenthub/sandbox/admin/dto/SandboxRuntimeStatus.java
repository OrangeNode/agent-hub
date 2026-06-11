package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxRuntimeStatus(
        boolean serviceHealthy,
        OffsetDateTime startedAt,
        long uptimeSeconds,
        int activeSessions,
        List<SandboxRuntimeSession> sessions,
        SandboxWarmPoolSnapshot warmPool,
        SandboxResourceSnapshot resources,
        SandboxResourceSummary resourceSummary
) {
    public SandboxRuntimeStatus {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        warmPool = warmPool == null ? new SandboxWarmPoolSnapshot(0, 0, 0, List.of()) : warmPool;
        resources = resources == null
                ? new SandboxResourceSnapshot(null, null, 0L, List.of(), List.of(), List.of(), List.of())
                : resources;
        resourceSummary = resourceSummary == null ? new SandboxResourceSummary(0, 0, 0, 0L, 0) : resourceSummary;
    }
}
