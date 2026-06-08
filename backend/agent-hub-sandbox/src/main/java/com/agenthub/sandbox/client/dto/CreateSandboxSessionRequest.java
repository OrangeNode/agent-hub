package com.agenthub.sandbox.client.dto;

import java.util.List;

public record CreateSandboxSessionRequest(
        String conversationId,
        List<SandboxArtifactItem> artifacts
) {
}
