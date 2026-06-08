package com.agenthub.storage.attachment.service;

import com.agenthub.storage.attachment.dto.AttachmentItem;
import com.agenthub.storage.attachment.dto.AttachmentImportSource;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import java.util.List;

public interface AttachmentService {

    AttachmentUploadUrl createImageUploadUrl(Long userId, String filename, String contentType, long fileSize);

    AttachmentUploadUrl createAttachmentUploadUrl(Long userId, String filename, String contentType, long fileSize);

    void confirmUpload(Long userId, Long attachmentId);

    AttachmentImportSource requireImportSource(Long userId, Long attachmentId);

    List<AttachmentReference> resolveImagesForChat(
            Long userId,
            Long conversationId,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds,
            int imageTokenEstimate
    );

    void bindToMessage(Long userId, Long conversationId, Long messageId, List<Long> attachmentIds);

    List<AttachmentItem> listMessageAttachments(Long userId, List<Long> messageIds);
}
