package com.agenthub.storage.attachment.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record AttachmentUploadUrl(
        Long attachmentId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        OffsetDateTime expiresAt
) {
}
