package com.agenthub.storage.attachment.dto;

public record AttachmentItem(
        Long attachmentId,
        String filename,
        String contentType,
        String status,
        boolean expired,
        String previewUrl,
        String message,
        Long messageId
) {
}
