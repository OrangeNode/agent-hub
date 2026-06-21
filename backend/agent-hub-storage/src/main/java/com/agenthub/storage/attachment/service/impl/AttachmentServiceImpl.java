package com.agenthub.storage.attachment.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentItem;
import com.agenthub.storage.attachment.dto.AttachmentImportSource;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import com.agenthub.storage.attachment.service.AttachmentService;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private static final String FILE_TYPE = "FILE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String UNSUPPORTED_IMAGE_MESSAGE = "仅支持 jpg、jpeg、png、webp、gif 图片";
    private static final String IMAGE_TOO_LARGE_MESSAGE = "图片不能超过 20MB";
    private static final String UPLOAD_FAILED_MESSAGE = "图片上传失败，请重试";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String IMAGE_UNAVAILABLE_MESSAGE = "图片不可用";
    private static final String IMAGE_EXPIRED_MESSAGE = "图片已过期";
    private static final String IMAGE_LIMIT_MESSAGE = "单条消息最多上传 8 张图片";
    private static final String ATTACHMENT_NOT_FOUND_MESSAGE = "附件不存在";
    private static final String INVALID_FILE_MESSAGE = "文件信息不正确";
    private static final int MAX_CHAT_IMAGES = 8;
    private static final int IMAGE_MAGIC_READ_LENGTH = 16;

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
        return createUploadUrl(userId, filename, normalizedContentType, fileSize, fileExt, IMAGE_TYPE, "chat-images");
    }

    @Override
    public AttachmentUploadUrl createAttachmentUploadUrl(Long userId, String filename, String contentType, long fileSize) {
        String normalizedContentType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        String fileExt = extractFileExt(filename);
        validateGenericFile(filename, normalizedContentType, fileSize);
        return createUploadUrl(userId, filename, normalizedContentType, fileSize, fileExt, FILE_TYPE, "chat-files");
    }

    private AttachmentUploadUrl createUploadUrl(Long userId, String filename, String contentType, long fileSize,
                                                String fileExt, String attachmentType, String directory) {
        OffsetDateTime now = OffsetDateTime.now();
        String objectKey = buildObjectKey(userId, directory, fileExt, now);
        ChatAttachment attachment = new ChatAttachment();
        attachment.setUserId(userId);
        attachment.setObjectKey(objectKey);
        attachment.setBucketName(storageProperties.getBucket());
        attachment.setOriginalFilename(filename);
        attachment.setContentType(contentType);
        attachment.setFileSize(fileSize);
        attachment.setFileExt(fileExt);
        attachment.setAttachmentType(attachmentType);
        attachment.setStatus(STATUS_PENDING);
        attachment.setExpiresAt(now.plusDays(storageProperties.getImageExpireDays()));
        attachment.setCreatedAt(now);
        attachment.setUpdatedAt(now);
        chatAttachmentMapper.insert(attachment);

        Duration ttl = Duration.ofMinutes(storageProperties.getPresignedUploadTtlMinutes());
        String uploadUrl = objectStorageClient.presignPut(objectKey, contentType, ttl);
        return new AttachmentUploadUrl(
                attachment.getId(),
                uploadUrl,
                "PUT",
                Map.of("Content-Type", contentType),
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
        } catch (BusinessException exception) {
            throwStorageInternalOrUploadFailed(exception);
            return;
        }
        if (metadata.size() != attachment.getFileSize() || !attachment.getContentType().equals(metadata.contentType())) {
            throw uploadFailedException();
        }
        if (IMAGE_TYPE.equals(attachment.getAttachmentType())) {
            if (!contentTypeMatchesExtension(attachment.getContentType(), attachment.getFileExt())) {
                throw uploadFailedException();
            }

            byte[] firstBytes;
            try {
                firstBytes = objectStorageClient.readFirstBytes(attachment.getObjectKey(), IMAGE_MAGIC_READ_LENGTH);
            } catch (BusinessException exception) {
                throwStorageInternalOrUploadFailed(exception);
                return;
            }
            if (!hasValidImageMagic(attachment.getContentType(), attachment.getFileExt(), firstBytes)) {
                throw uploadFailedException();
            }
        } else if (!FILE_TYPE.equals(attachment.getAttachmentType())) {
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

    @Override
    public AttachmentImportSource requireImportSource(Long userId, Long attachmentId) {
        ChatAttachment attachment = chatAttachmentMapper.selectById(attachmentId);
        if (attachment == null
                || !userId.equals(attachment.getUserId())
                || !STATUS_READY.equals(attachment.getStatus())
                || isExpired(attachment)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, ATTACHMENT_NOT_FOUND_MESSAGE);
        }
        String downloadUrl = objectStorageClient.presignGet(attachment.getObjectKey(), Duration.ofMinutes(10));
        return new AttachmentImportSource(
                attachment.getId(),
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getFileSize(),
                downloadUrl,
                attachment.getObjectKey()
        );
    }

    @Override
    public List<AttachmentReference> resolveImagesForChat(
            Long userId,
            Long conversationId,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds,
            int imageTokenEstimate
    ) {
        List<Long> newAttachmentIds = normalizeIds(attachmentIds);
        List<Long> reusedAttachmentIds = removeAlreadyResolvedIds(normalizeIds(reuseAttachmentIds), newAttachmentIds);
        if (newAttachmentIds.isEmpty() && reusedAttachmentIds.isEmpty() && conversationId != null) {
            return autoReuseRecentImages(userId, conversationId, imageTokenEstimate);
        }

        Map<Long, ChatAttachment> resolvedAttachmentsById = new LinkedHashMap<>();
        resolveAttachments(newAttachmentIds, attachment -> validateNewImage(userId, attachment))
                .forEach(attachment -> resolvedAttachmentsById.put(attachment.getId(), attachment));
        resolveAttachments(reusedAttachmentIds, attachment -> validateReusableImage(userId, conversationId, attachment))
                .forEach(attachment -> resolvedAttachmentsById.putIfAbsent(attachment.getId(), attachment));
        List<ChatAttachment> resolvedAttachments = List.copyOf(resolvedAttachmentsById.values());
        validateImageLimit(resolvedAttachments.size());
        return resolvedAttachments.stream()
                .map(attachment -> toAttachmentReference(attachment, imageTokenEstimate))
                .toList();
    }

    @Override
    public Map<Long, List<AttachmentReference>> listMessageAttachmentReferences(
            Long userId,
            List<Long> messageIds,
            int imageTokenEstimate
    ) {
        List<Long> normalizedMessageIds = normalizeIds(messageIds);
        if (normalizedMessageIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ChatAttachment> wrapper = new LambdaQueryWrapper<ChatAttachment>()
                .eq(ChatAttachment::getUserId, userId)
                .in(ChatAttachment::getMessageId, normalizedMessageIds)
                .eq(ChatAttachment::getAttachmentType, IMAGE_TYPE)
                .eq(ChatAttachment::getStatus, STATUS_READY)
                .gt(ChatAttachment::getExpiresAt, OffsetDateTime.now())
                .orderByAsc(ChatAttachment::getMessageId)
                .orderByAsc(ChatAttachment::getId);
        return chatAttachmentMapper.selectList(wrapper).stream()
                .filter(this::isReadyAvailableImage)
                .collect(Collectors.groupingBy(
                        ChatAttachment::getMessageId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                attachment -> toAttachmentReference(attachment, imageTokenEstimate),
                                Collectors.toList()
                        )
                ));
    }

    @Override
    public void bindToMessage(Long userId, Long conversationId, Long messageId, List<Long> attachmentIds) {
        List<Long> normalizedIds = normalizeIds(attachmentIds);
        if (normalizedIds.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Map<Long, ChatAttachment> attachmentsById = new LinkedHashMap<>();
        selectByIds(normalizedIds).forEach(attachment -> attachmentsById.put(attachment.getId(), attachment));
        List<ChatAttachment> attachments = new ArrayList<>();
        for (Long attachmentId : normalizedIds) {
            ChatAttachment attachment = attachmentsById.get(attachmentId);
            validateBindableImage(userId, attachment);
            attachments.add(attachment);
        }
        for (ChatAttachment attachment : attachments) {
            ChatAttachment updatedAttachment = new ChatAttachment();
            updatedAttachment.setId(attachment.getId());
            updatedAttachment.setConversationId(conversationId);
            updatedAttachment.setMessageId(messageId);
            updatedAttachment.setUpdatedAt(now);
            LambdaUpdateWrapper<ChatAttachment> wrapper = new LambdaUpdateWrapper<ChatAttachment>()
                    .eq(ChatAttachment::getId, attachment.getId())
                    .eq(ChatAttachment::getUserId, userId)
                    .eq(ChatAttachment::getStatus, STATUS_READY)
                    .eq(ChatAttachment::getAttachmentType, IMAGE_TYPE)
                    .isNull(ChatAttachment::getMessageId)
                    .gt(ChatAttachment::getExpiresAt, now);
            if (chatAttachmentMapper.update(updatedAttachment, wrapper) != 1) {
                throw uploadFailedException();
            }
        }
    }

    @Override
    public List<AttachmentItem> listMessageAttachments(Long userId, List<Long> messageIds) {
        List<Long> normalizedMessageIds = normalizeIds(messageIds);
        if (normalizedMessageIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<ChatAttachment> wrapper = new LambdaQueryWrapper<ChatAttachment>()
                .eq(ChatAttachment::getUserId, userId)
                .in(ChatAttachment::getMessageId, normalizedMessageIds)
                .orderByAsc(ChatAttachment::getMessageId)
                .orderByAsc(ChatAttachment::getId);
        return chatAttachmentMapper.selectList(wrapper).stream()
                .map(this::toAttachmentItem)
                .toList();
    }

    private void validateImage(String contentType, String fileExt, long fileSize) {
        if (!SUPPORTED_CONTENT_TYPES.contains(contentType) || !SUPPORTED_EXTENSIONS.contains(fileExt)
                || !contentTypeMatchesExtension(contentType, fileExt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, UNSUPPORTED_IMAGE_MESSAGE);
        }
        if (fileSize > MAX_IMAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, IMAGE_TOO_LARGE_MESSAGE);
        }
    }

    private void validateGenericFile(String filename, String contentType, long fileSize) {
        if (filename == null || filename.isBlank() || contentType == null || contentType.isBlank() || fileSize <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, INVALID_FILE_MESSAGE);
        }
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    private List<Long> removeAlreadyResolvedIds(List<Long> ids, List<Long> alreadyResolvedIds) {
        if (ids.isEmpty() || alreadyResolvedIds.isEmpty()) {
            return ids;
        }
        Set<Long> resolvedIdSet = Set.copyOf(alreadyResolvedIds);
        return ids.stream()
                .filter(id -> !resolvedIdSet.contains(id))
                .toList();
    }

    private List<ChatAttachment> resolveAttachments(List<Long> ids, AttachmentValidator validator) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ChatAttachment> attachmentsById = new LinkedHashMap<>();
        selectByIds(ids).forEach(attachment -> attachmentsById.put(attachment.getId(), attachment));
        List<ChatAttachment> attachments = new ArrayList<>();
        for (Long id : ids) {
            ChatAttachment attachment = attachmentsById.get(id);
            if (attachment == null) {
                throw unavailableImageException();
            }
            validator.validate(attachment);
            attachments.add(attachment);
        }
        return attachments;
    }

    private List<ChatAttachment> selectByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return chatAttachmentMapper.selectByIds(ids);
    }

    private void validateNewImage(Long userId, ChatAttachment attachment) {
        validateReadyImage(userId, attachment);
        if (attachment.getMessageId() != null) {
            throw unavailableImageException();
        }
    }

    private void validateReusableImage(Long userId, Long conversationId, ChatAttachment attachment) {
        if (conversationId == null) {
            throw unavailableImageException();
        }
        validateReadyImage(userId, attachment);
        if (!conversationId.equals(attachment.getConversationId())) {
            throw unavailableImageException();
        }
    }

    private void validateReadyImage(Long userId, ChatAttachment attachment) {
        if (!userId.equals(attachment.getUserId())
                || !STATUS_READY.equals(attachment.getStatus())
                || !IMAGE_TYPE.equals(attachment.getAttachmentType())) {
            throw unavailableImageException();
        }
        if (isExpired(attachment)) {
            throw expiredImageException();
        }
    }

    private boolean isReadyAvailableImage(ChatAttachment attachment) {
        return attachment != null
                && attachment.getMessageId() != null
                && STATUS_READY.equals(attachment.getStatus())
                && IMAGE_TYPE.equals(attachment.getAttachmentType())
                && !isExpired(attachment);
    }

    private void validateBindableImage(Long userId, ChatAttachment attachment) {
        if (attachment == null
                || !userId.equals(attachment.getUserId())
                || !STATUS_READY.equals(attachment.getStatus())
                || !IMAGE_TYPE.equals(attachment.getAttachmentType())
                || attachment.getMessageId() != null) {
            throw uploadFailedException();
        }
        if (isExpired(attachment)) {
            throw expiredImageException();
        }
    }

    private List<AttachmentReference> autoReuseRecentImages(
            Long userId,
            Long conversationId,
            int imageTokenEstimate
    ) {
        LambdaQueryWrapper<ChatAttachment> wrapper = new LambdaQueryWrapper<ChatAttachment>()
                .eq(ChatAttachment::getUserId, userId)
                .eq(ChatAttachment::getConversationId, conversationId)
                .isNotNull(ChatAttachment::getMessageId)
                .eq(ChatAttachment::getAttachmentType, IMAGE_TYPE)
                .eq(ChatAttachment::getStatus, STATUS_READY)
                .gt(ChatAttachment::getExpiresAt, OffsetDateTime.now())
                .orderByDesc(ChatAttachment::getMessageId)
                .orderByDesc(ChatAttachment::getId)
                .last("LIMIT " + MAX_CHAT_IMAGES);
        List<ChatAttachment> attachments = new ArrayList<>(chatAttachmentMapper.selectList(wrapper));
        Collections.reverse(attachments);
        return attachments.stream()
                .map(attachment -> toAttachmentReference(attachment, imageTokenEstimate))
                .toList();
    }

    private void validateImageLimit(int imageCount) {
        if (imageCount > MAX_CHAT_IMAGES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, IMAGE_LIMIT_MESSAGE);
        }
    }

    private AttachmentReference toAttachmentReference(ChatAttachment attachment, int imageTokenEstimate) {
        String readUrl = toImageDataUrl(attachment);
        return new AttachmentReference(
                attachment.getId(),
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                readUrl,
                imageTokenEstimate
        );
    }

    private String toImageDataUrl(ChatAttachment attachment) {
        // 模型供应商通常无法访问本地 MinIO 预签名地址，聊天入参改为内嵌 data URL。
        byte[] bytes = objectStorageClient.readBytes(attachment.getObjectKey());
        return "data:" + attachment.getContentType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private AttachmentItem toAttachmentItem(ChatAttachment attachment) {
        boolean expired = STATUS_EXPIRED.equals(attachment.getStatus()) || isExpired(attachment);
        String previewUrl = null;
        String message = null;
        if (expired) {
            message = IMAGE_EXPIRED_MESSAGE;
        } else if (STATUS_READY.equals(attachment.getStatus())) {
            previewUrl = objectStorageClient.presignGet(
                    attachment.getObjectKey(),
                    Duration.ofMinutes(storageProperties.getPresignedReadTtlMinutes())
            );
        }
        return new AttachmentItem(
                attachment.getId(),
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getStatus(),
                expired,
                previewUrl,
                message,
                attachment.getMessageId()
        );
    }

    private boolean isExpired(ChatAttachment attachment) {
        return attachment.getExpiresAt() != null && !attachment.getExpiresAt().isAfter(OffsetDateTime.now());
    }

    private BusinessException unavailableImageException() {
        return new BusinessException(ErrorCode.BAD_REQUEST, IMAGE_UNAVAILABLE_MESSAGE);
    }

    private BusinessException expiredImageException() {
        return new BusinessException(ErrorCode.BAD_REQUEST, IMAGE_EXPIRED_MESSAGE);
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

    private String buildObjectKey(Long userId, String directory, String fileExt, OffsetDateTime now) {
        String suffix = fileExt == null || fileExt.isBlank() ? "" : "." + fileExt;
        return "users/" + userId + "/" + directory + "/" + now.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "/" + UUID.randomUUID() + suffix;
    }

    private boolean contentTypeMatchesExtension(String contentType, String fileExt) {
        return switch (fileExt) {
            case "jpg", "jpeg" -> "image/jpeg".equals(contentType);
            case "png" -> "image/png".equals(contentType);
            case "webp" -> "image/webp".equals(contentType);
            case "gif" -> "image/gif".equals(contentType);
            default -> false;
        };
    }

    private boolean hasValidImageMagic(String contentType, String fileExt, byte[] bytes) {
        if (!contentTypeMatchesExtension(contentType, fileExt)) {
            return false;
        }
        return switch (contentType) {
            case "image/jpeg" -> startsWith(bytes, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "image/png" -> startsWith(bytes, new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            });
            case "image/gif" -> startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "image/webp" -> bytes.length >= 12
                    && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && bytes[8] == 'W'
                    && bytes[9] == 'E'
                    && bytes[10] == 'B'
                    && bytes[11] == 'P';
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private void throwStorageInternalOrUploadFailed(BusinessException exception) {
        if (exception.errorCode() == ErrorCode.INTERNAL_ERROR) {
            throw exception;
        }
        throw uploadFailedException();
    }

    private BusinessException uploadFailedException() {
        return new BusinessException(ErrorCode.BAD_REQUEST, UPLOAD_FAILED_MESSAGE);
    }

    @FunctionalInterface
    private interface AttachmentValidator {

        void validate(ChatAttachment attachment);
    }
}
