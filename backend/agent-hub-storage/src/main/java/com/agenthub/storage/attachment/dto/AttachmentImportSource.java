package com.agenthub.storage.attachment.dto;

public record AttachmentImportSource(
        Long attachmentId,
        String filename,
        String contentType,
        Long fileSize,
        String downloadUrl,
        String objectKey
) {
}
