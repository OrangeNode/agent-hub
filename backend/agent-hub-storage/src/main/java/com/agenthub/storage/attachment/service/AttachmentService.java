package com.agenthub.storage.attachment.service;

import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;

public interface AttachmentService {

    AttachmentUploadUrl createImageUploadUrl(Long userId, String filename, String contentType, long fileSize);

    void confirmUpload(Long userId, Long attachmentId);
}
