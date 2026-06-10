package com.agenthub.chat.artifact.dto;

public record ChatArtifactDownload(
        String fileName,
        String contentType,
        byte[] content
) {
}
