package com.agenthub.sandbox.client.dto;

public record CreateSandboxSessionResponse(
        String sandboxSessionId,
        String status
) {
}
