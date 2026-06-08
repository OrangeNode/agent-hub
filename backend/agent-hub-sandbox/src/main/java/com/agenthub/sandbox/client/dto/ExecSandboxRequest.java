package com.agenthub.sandbox.client.dto;

public record ExecSandboxRequest(
        String executionId,
        String command,
        String workingDir,
        int timeoutSeconds
) {
}
