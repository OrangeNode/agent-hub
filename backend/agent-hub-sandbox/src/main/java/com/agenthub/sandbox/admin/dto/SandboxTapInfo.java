package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxTapInfo(
        String name,
        String vmId,
        boolean bound,
        String resourceStatus
) {
}
