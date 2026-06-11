package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxWarmPoolSnapshot(
        int targetSize,
        int size,
        int starting,
        List<SandboxVmMetadata> vms
) {
    public SandboxWarmPoolSnapshot {
        vms = vms == null ? List.of() : List.copyOf(vms);
    }
}
