package com.agenthub.storage.attachment.job;

import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectStorageClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AttachmentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupJob.class);
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_DELETED = "DELETED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final ChatAttachmentMapper chatAttachmentMapper;
    private final ObjectStorageClient objectStorageClient;
    private final StorageProperties storageProperties;

    public AttachmentCleanupJob(
            ChatAttachmentMapper chatAttachmentMapper,
            ObjectStorageClient objectStorageClient,
            StorageProperties storageProperties
    ) {
        this.chatAttachmentMapper = chatAttachmentMapper;
        this.objectStorageClient = objectStorageClient;
        this.storageProperties = storageProperties;
    }

    @Scheduled(
            fixedDelayString = "${agent-hub.storage.cleanup-fixed-delay-ms:600000}",
            initialDelayString = "${agent-hub.storage.cleanup-initial-delay-ms:600000}"
    )
    public void cleanupTemporaryAttachments() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime pendingThreshold = now.minusMinutes(storageProperties.getPendingCleanupMinutes());
        OffsetDateTime unusedReadyThreshold = now.minusHours(storageProperties.getUnusedReadyCleanupHours());
        LambdaQueryWrapper<ChatAttachment> queryWrapper = new LambdaQueryWrapper<ChatAttachment>()
                .and(wrapper -> wrapper
                        .eq(ChatAttachment::getStatus, STATUS_PENDING)
                        .lt(ChatAttachment::getCreatedAt, pendingThreshold))
                .or(wrapper -> wrapper
                        .eq(ChatAttachment::getStatus, STATUS_READY)
                        .isNull(ChatAttachment::getMessageId)
                        .lt(ChatAttachment::getCreatedAt, unusedReadyThreshold));

        List<ChatAttachment> attachments = chatAttachmentMapper.selectList(queryWrapper);
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        for (ChatAttachment attachment : attachments) {
            deleteAndMarkDeleted(attachment);
        }
    }

    @Scheduled(
            fixedDelayString = "${agent-hub.storage.expire-mark-fixed-delay-ms:3600000}",
            initialDelayString = "${agent-hub.storage.expire-mark-initial-delay-ms:3600000}"
    )
    public void markExpiredAttachments() {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaQueryWrapper<ChatAttachment> queryWrapper = new LambdaQueryWrapper<ChatAttachment>()
                .eq(ChatAttachment::getStatus, STATUS_READY)
                .lt(ChatAttachment::getExpiresAt, now);

        List<ChatAttachment> attachments = chatAttachmentMapper.selectList(queryWrapper);
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        for (ChatAttachment attachment : attachments) {
            ChatAttachment updatedAttachment = new ChatAttachment();
            updatedAttachment.setId(attachment.getId());
            updatedAttachment.setStatus(STATUS_EXPIRED);
            updatedAttachment.setUpdatedAt(OffsetDateTime.now());
            chatAttachmentMapper.updateById(updatedAttachment);
        }
    }

    private void deleteAndMarkDeleted(ChatAttachment attachment) {
        try {
            objectStorageClient.delete(attachment.getObjectKey());
        } catch (RuntimeException ex) {
            log.warn("清理附件对象失败，attachmentId={}, objectKey={}",
                    attachment.getId(), attachment.getObjectKey(), ex);
            return;
        }

        try {
            OffsetDateTime now = OffsetDateTime.now();
            ChatAttachment updatedAttachment = new ChatAttachment();
            updatedAttachment.setId(attachment.getId());
            updatedAttachment.setStatus(STATUS_DELETED);
            updatedAttachment.setDeletedAt(now);
            updatedAttachment.setUpdatedAt(now);
            chatAttachmentMapper.updateById(updatedAttachment);
        } catch (RuntimeException ex) {
            log.warn("更新附件清理状态失败，attachmentId={}, objectKey={}",
                    attachment.getId(), attachment.getObjectKey(), ex);
        }
    }
}
