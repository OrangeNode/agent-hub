package com.agenthub.sandbox.admin.dto;

import java.util.List;

public record SandboxOverview(
        boolean serviceHealthy,
        int activeSessions,
        long dbOpenSessions,
        long runningExecutions,
        long recentFailedExecutions,
        int firecrackerProcesses,
        int tapDevices,
        long runDirBytes,
        int orphanResources,
        List<SandboxRisk> risks,
        List<SandboxRuntimeSession> runtimeSessions
) {
    public SandboxOverview {
        risks = risks == null ? List.of() : List.copyOf(risks);
        runtimeSessions = runtimeSessions == null ? List.of() : List.copyOf(runtimeSessions);
    }
}
