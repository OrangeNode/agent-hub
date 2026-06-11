package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxResourceSnapshot(
        OffsetDateTime scannedAt,
        String runDir,
        long runDirBytes,
        List<SandboxRunDirInfo> runDirs,
        List<SandboxProcessInfo> firecrackerProcesses,
        List<SandboxTapInfo> taps,
        List<SandboxOrphanResource> orphans
) {
    public SandboxResourceSnapshot {
        runDirs = runDirs == null ? List.of() : List.copyOf(runDirs);
        firecrackerProcesses = firecrackerProcesses == null ? List.of() : List.copyOf(firecrackerProcesses);
        taps = taps == null ? List.of() : List.copyOf(taps);
        orphans = orphans == null ? List.of() : List.copyOf(orphans);
    }
}
