package com.agenthub.storage.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import com.agenthub.storage.attachment.service.impl.AttachmentServiceImpl;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AttachmentServiceImplTest {

    private static final byte[] PNG_MAGIC = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Test
    void shouldCreatePendingImageUploadUrl() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, properties);
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);
        OffsetDateTime before = OffsetDateTime.now();
        when(objectStorageClient.presignPut(any(), eq("image/png"), eq(Duration.ofMinutes(10))))
                .thenReturn("http://127.0.0.1:9000/agent-hub-chat-images/upload");
        when(mapper.insert(attachmentCaptor.capture())).thenAnswer(invocation -> {
            attachmentCaptor.getValue().setId(100L);
            return 1;
        });

        AttachmentUploadUrl uploadUrl = service.createImageUploadUrl(7L, "cat.png", "image/png", 1024);

        ChatAttachment attachment = attachmentCaptor.getValue();
        assertThat(attachment.getUserId()).isEqualTo(7L);
        assertThat(attachment.getStatus()).isEqualTo("PENDING");
        assertThat(attachment.getAttachmentType()).isEqualTo("IMAGE");
        assertThat(attachment.getExpiresAt()).isBetween(before.plusDays(7), OffsetDateTime.now().plusDays(7));
        assertThat(attachment.getBucketName()).isEqualTo("agent-hub-chat-images");
        assertThat(attachment.getObjectKey()).startsWith("users/7/chat-images/");
        assertThat(attachment.getOriginalFilename()).isEqualTo("cat.png");
        assertThat(attachment.getContentType()).isEqualTo("image/png");
        assertThat(attachment.getFileSize()).isEqualTo(1024L);
        assertThat(attachment.getFileExt()).isEqualTo("png");
        assertThat(uploadUrl.attachmentId()).isEqualTo(100L);
        assertThat(uploadUrl.uploadUrl()).isEqualTo("http://127.0.0.1:9000/agent-hub-chat-images/upload");
        assertThat(uploadUrl.method()).isEqualTo("PUT");
        assertThat(uploadUrl.headers()).containsEntry("Content-Type", "image/png");
        assertThat(uploadUrl.expiresAt()).isBetween(before.plusMinutes(10), OffsetDateTime.now().plusMinutes(10));
    }

    @Test
    void shouldRejectUnsupportedContentType() {
        AttachmentServiceImpl service = new AttachmentServiceImpl(
                mock(ChatAttachmentMapper.class),
                mock(ObjectStorageClient.class),
                storageProperties()
        );

        assertThatThrownBy(() -> service.createImageUploadUrl(7L, "cat.pdf", "application/pdf", 1024))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅支持 jpg、jpeg、png、webp、gif 图片")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectImageLargerThanTwentyMb() {
        AttachmentServiceImpl service = new AttachmentServiceImpl(
                mock(ChatAttachmentMapper.class),
                mock(ObjectStorageClient.class),
                storageProperties()
        );

        assertThatThrownBy(() -> service.createImageUploadUrl(7L, "cat.png", "image/png", 20L * 1024 * 1024 + 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片不能超过 20MB")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldConfirmPendingAttachmentWhenObjectExists() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment pendingAttachment = pendingAttachment();
        when(mapper.selectById(100L)).thenReturn(pendingAttachment);
        when(objectStorageClient.stat("users/7/chat-images/20260604/cat.png"))
                .thenReturn(new ObjectMetadata(1024, "image/png"));
        when(objectStorageClient.readFirstBytes("users/7/chat-images/20260604/cat.png", 16))
                .thenReturn(PNG_MAGIC);
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        service.confirmUpload(7L, 100L);

        verify(mapper).updateById(attachmentCaptor.capture());
        ChatAttachment updatedAttachment = attachmentCaptor.getValue();
        assertThat(updatedAttachment.getId()).isEqualTo(100L);
        assertThat(updatedAttachment.getStatus()).isEqualTo("READY");
        assertThat(updatedAttachment.getConfirmedAt()).isNotNull();
        assertThat(updatedAttachment.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldRejectAttachmentWhenContentTypeAndExtensionMismatch() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment pendingAttachment = pendingAttachment();
        pendingAttachment.setFileExt("jpg");
        when(mapper.selectById(100L)).thenReturn(pendingAttachment);
        when(objectStorageClient.stat("users/7/chat-images/20260604/cat.png"))
                .thenReturn(new ObjectMetadata(1024, "image/png"));

        assertThatThrownBy(() -> service.confirmUpload(7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片上传失败，请重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(ChatAttachment.class));
    }

    @Test
    void shouldRejectAttachmentWhenImageMagicMismatch() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectById(100L)).thenReturn(pendingAttachment());
        when(objectStorageClient.stat("users/7/chat-images/20260604/cat.png"))
                .thenReturn(new ObjectMetadata(1024, "image/png"));
        when(objectStorageClient.readFirstBytes("users/7/chat-images/20260604/cat.png", 16))
                .thenReturn(new byte[] {0x25, 0x50, 0x44, 0x46});

        assertThatThrownBy(() -> service.confirmUpload(7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片上传失败，请重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(ChatAttachment.class));
    }

    @Test
    void shouldPropagateObjectStorageInternalErrorWhenStatFails() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectById(100L)).thenReturn(pendingAttachment());
        when(objectStorageClient.stat("users/7/chat-images/20260604/cat.png"))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "对象存储操作失败"));

        assertThatThrownBy(() -> service.confirmUpload(7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("对象存储操作失败")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(mapper, never()).updateById(any(ChatAttachment.class));
    }

    private StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("agent-hub-chat-images");
        properties.setImageExpireDays(7);
        properties.setPresignedUploadTtlMinutes(10);
        return properties;
    }

    private ChatAttachment pendingAttachment() {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(100L);
        attachment.setUserId(7L);
        attachment.setStatus("PENDING");
        attachment.setObjectKey("users/7/chat-images/20260604/cat.png");
        attachment.setContentType("image/png");
        attachment.setFileSize(1024L);
        attachment.setFileExt("png");
        return attachment;
    }
}
