package com.agenthub.sandbox.client.dto;

public record SandboxEvent(
        String type,
        String executionId,
        String content,
        Integer exitCode,
        Long durationMs,
        String errorCode,
        String message,
        SandboxArtifactItem artifact,
        boolean timedOut
) {
}
