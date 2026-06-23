package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxWarmPoolSnapshot(
        int targetSize,
        int size,
        int starting,
        OffsetDateTime lastFillAt,
        OffsetDateTime lastSuccessAt,
        String lastFillError,
        OffsetDateTime nextRetryAt,
        long hotAcquireCount,
        long coldStartCount,
        List<SandboxVmMetadata> vms
) {
    public SandboxWarmPoolSnapshot(int targetSize, int size, int starting, List<SandboxVmMetadata> vms) {
        this(targetSize, size, starting, null, null, null, null, 0L, 0L, vms);
    }

    public SandboxWarmPoolSnapshot {
        lastFillError = lastFillError == null ? "" : lastFillError;
        vms = vms == null ? List.of() : List.copyOf(vms);
    }
}
