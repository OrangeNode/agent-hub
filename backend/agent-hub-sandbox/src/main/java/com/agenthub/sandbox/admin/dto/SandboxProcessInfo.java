package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxProcessInfo(
        int pid,
        String args,
        String apiSocket,
        String vmId,
        String runDir,
        boolean bound,
        String resourceStatus
) {
}
