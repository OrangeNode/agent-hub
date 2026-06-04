package com.agenthub.storage.attachment.dto;

public record AttachmentReference(
        Long attachmentId,
        String filename,
        String contentType,
        String readUrl,
        int tokenEstimate
) {
}
