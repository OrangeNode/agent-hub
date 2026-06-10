package com.agenthub.chat.artifact.dto;

public record ChatArtifactContent(
        Long artifactId,
        String fileName,
        String relativePath,
        String contentType,
        Long sizeBytes,
        boolean previewable,
        boolean truncated,
        String content
) {
}
