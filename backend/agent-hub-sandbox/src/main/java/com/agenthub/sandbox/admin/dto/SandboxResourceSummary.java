package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxResourceSummary(
        int firecrackerProcesses,
        int tapDevices,
        int runDirs,
        long runDirBytes,
        int orphanResources
) {
}
