package com.agenthub.storage.attachment.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import com.agenthub.storage.attachment.service.AttachmentService;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AttachmentServiceImpl implements AttachmentService {

    private static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final String IMAGE_TYPE = "IMAGE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String UNSUPPORTED_IMAGE_MESSAGE = "仅支持 jpg、jpeg、png、webp、gif 图片";
    private static final String IMAGE_TOO_LARGE_MESSAGE = "图片不能超过 20MB";
    private static final String UPLOAD_FAILED_MESSAGE = "图片上传失败，请重试";

    private final ChatAttachmentMapper chatAttachmentMapper;
    private final ObjectStorageClient objectStorageClient;
    private final StorageProperties storageProperties;

    public AttachmentServiceImpl(
            ChatAttachmentMapper chatAttachmentMapper,
            ObjectStorageClient objectStorageClient,
            StorageProperties storageProperties
    ) {
        this.chatAttachmentMapper = chatAttachmentMapper;
        this.objectStorageClient = objectStorageClient;
        this.storageProperties = storageProperties;
    }

    @Override
    public AttachmentUploadUrl createImageUploadUrl(Long userId, String filename, String contentType, long fileSize) {
        String normalizedContentType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        String fileExt = extractFileExt(filename);
        validateImage(normalizedContentType, fileExt, fileSize);

        OffsetDateTime now = OffsetDateTime.now();
        String objectKey = buildObjectKey(userId, fileExt, now);
        ChatAttachment attachment = new ChatAttachment();
        attachment.setUserId(userId);
        attachment.setObjectKey(objectKey);
        attachment.setBucketName(storageProperties.getBucket());
        attachment.setOriginalFilename(filename);
        attachment.setContentType(normalizedContentType);
        attachment.setFileSize(fileSize);
        attachment.setFileExt(fileExt);
        attachment.setAttachmentType(IMAGE_TYPE);
        attachment.setStatus(STATUS_PENDING);
        attachment.setExpiresAt(now.plusDays(storageProperties.getImageExpireDays()));
        attachment.setCreatedAt(now);
        attachment.setUpdatedAt(now);
        chatAttachmentMapper.insert(attachment);

        Duration ttl = Duration.ofMinutes(storageProperties.getPresignedUploadTtlMinutes());
        String uploadUrl = objectStorageClient.presignPut(objectKey, normalizedContentType, ttl);
        return new AttachmentUploadUrl(
                attachment.getId(),
                uploadUrl,
                "PUT",
                Map.of("Content-Type", normalizedContentType),
                now.plus(ttl)
        );
    }

    @Override
    public void confirmUpload(Long userId, Long attachmentId) {
        ChatAttachment attachment = chatAttachmentMapper.selectById(attachmentId);
        if (attachment == null || !userId.equals(attachment.getUserId()) || !STATUS_PENDING.equals(attachment.getStatus())) {
            throw uploadFailedException();
        }

        ObjectMetadata metadata;
        try {
            metadata = objectStorageClient.stat(attachment.getObjectKey());
        } catch (RuntimeException exception) {
            throw uploadFailedException();
        }
        if (metadata.size() != attachment.getFileSize() || !attachment.getContentType().equals(metadata.contentType())) {
            throw uploadFailedException();
        }

        OffsetDateTime now = OffsetDateTime.now();
        ChatAttachment updatedAttachment = new ChatAttachment();
        updatedAttachment.setId(attachment.getId());
        updatedAttachment.setStatus(STATUS_READY);
        updatedAttachment.setConfirmedAt(now);
        updatedAttachment.setUpdatedAt(now);
        chatAttachmentMapper.updateById(updatedAttachment);
    }

    private void validateImage(String contentType, String fileExt, long fileSize) {
        if (!SUPPORTED_CONTENT_TYPES.contains(contentType) || !SUPPORTED_EXTENSIONS.contains(fileExt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, UNSUPPORTED_IMAGE_MESSAGE);
        }
        if (fileSize > MAX_IMAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, IMAGE_TOO_LARGE_MESSAGE);
        }
    }

    private String extractFileExt(String filename) {
        if (filename == null) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex < 0 || lastDotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String buildObjectKey(Long userId, String fileExt, OffsetDateTime now) {
        return "users/" + userId + "/chat-images/" + now.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID() + "." + fileExt;
    }

    private BusinessException uploadFailedException() {
        return new BusinessException(ErrorCode.BAD_REQUEST, UPLOAD_FAILED_MESSAGE);
    }
}
