package com.agenthub.chat.artifact.dto;

public record ChatArtifactItem(
        Long artifactId,
        String fileName,
        String relativePath,
        String contentType,
        Long sizeBytes,
        String sha256,
        Integer version
) {
}
