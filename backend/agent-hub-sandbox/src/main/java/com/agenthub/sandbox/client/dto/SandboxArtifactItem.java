package com.agenthub.sandbox.client.dto;

public record SandboxArtifactItem(
        Long artifactId,
        String fileName,
        String relativePath,
        String storageBucket,
        String storageKey,
        String contentType,
        Long sizeBytes,
        String sha256,
        Integer version
) {
}
