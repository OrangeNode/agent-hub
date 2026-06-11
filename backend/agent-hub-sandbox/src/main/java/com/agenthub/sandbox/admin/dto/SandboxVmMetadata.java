package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxVmMetadata(
        String vmId,
        String agentUrl,
        String runDir,
        String tapName,
        Integer pid
) {
}
