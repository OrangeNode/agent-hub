package com.agenthub.storage.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentImportSource;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import com.agenthub.storage.attachment.service.impl.AttachmentServiceImpl;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
    void shouldCreatePendingGenericAttachmentUploadUrl() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, properties);
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);
        OffsetDateTime before = OffsetDateTime.now();
        when(objectStorageClient.presignPut(any(), eq("text/csv"), eq(Duration.ofMinutes(10))))
                .thenReturn("http://127.0.0.1:9000/agent-hub-chat-images/upload-file");
        when(mapper.insert(attachmentCaptor.capture())).thenAnswer(invocation -> {
            attachmentCaptor.getValue().setId(101L);
            return 1;
        });

        AttachmentUploadUrl uploadUrl = service.createAttachmentUploadUrl(7L, "data.csv", "text/csv", 1024);

        ChatAttachment attachment = attachmentCaptor.getValue();
        assertThat(attachment.getUserId()).isEqualTo(7L);
        assertThat(attachment.getStatus()).isEqualTo("PENDING");
        assertThat(attachment.getAttachmentType()).isEqualTo("FILE");
        assertThat(attachment.getBucketName()).isEqualTo("agent-hub-chat-images");
        assertThat(attachment.getObjectKey()).startsWith("users/7/chat-files/");
        assertThat(attachment.getOriginalFilename()).isEqualTo("data.csv");
        assertThat(attachment.getContentType()).isEqualTo("text/csv");
        assertThat(attachment.getFileSize()).isEqualTo(1024L);
        assertThat(attachment.getFileExt()).isEqualTo("csv");
        assertThat(uploadUrl.attachmentId()).isEqualTo(101L);
        assertThat(uploadUrl.uploadUrl()).isEqualTo("http://127.0.0.1:9000/agent-hub-chat-images/upload-file");
        assertThat(uploadUrl.method()).isEqualTo("PUT");
        assertThat(uploadUrl.headers()).containsEntry("Content-Type", "text/csv");
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
    void shouldConfirmPendingGenericAttachmentWithoutImageMagicCheck() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment pendingAttachment = pendingFileAttachment();
        when(mapper.selectById(101L)).thenReturn(pendingAttachment);
        when(objectStorageClient.stat("users/7/chat-files/20260604/data.csv"))
                .thenReturn(new ObjectMetadata(1024, "text/csv"));
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        service.confirmUpload(7L, 101L);

        verify(objectStorageClient, never()).readFirstBytes(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper).updateById(attachmentCaptor.capture());
        assertThat(attachmentCaptor.getValue().getStatus()).isEqualTo("READY");
        assertThat(attachmentCaptor.getValue().getConfirmedAt()).isNotNull();
    }

    @Test
    void shouldRejectPendingAttachmentWithUnknownType() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment pendingAttachment = pendingFileAttachment();
        pendingAttachment.setAttachmentType("UNKNOWN");
        when(mapper.selectById(101L)).thenReturn(pendingAttachment);
        when(objectStorageClient.stat("users/7/chat-files/20260604/data.csv"))
                .thenReturn(new ObjectMetadata(1024, "text/csv"));

        assertThatThrownBy(() -> service.confirmUpload(7L, 101L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片上传失败，请重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(ChatAttachment.class));
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

    @Test
    void shouldRejectInvalidNewImagesForChat() {
        assertResolveNewImageRejected(readyAttachment(100L, 8L, 200L, null, OffsetDateTime.now().plusDays(1)));
        assertResolveNewImageRejected(attachment(100L, 7L, 200L, null, "PENDING", OffsetDateTime.now().plusDays(1)));
        assertResolveNewImageRejected(readyAttachment(100L, 7L, 200L, 300L, OffsetDateTime.now().plusDays(1)));
        assertResolveNewImageRejected(readyAttachment(100L, 7L, 200L, null, OffsetDateTime.now().minusSeconds(1)),
                "图片已过期");
    }

    @Test
    void shouldRejectMoreThanEightImagesForChat() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        List<Long> attachmentIds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        when(mapper.selectByIds(attachmentIds)).thenReturn(attachmentIds.stream()
                .map(id -> readyAttachment(id, 7L, 200L, null, OffsetDateTime.now().plusDays(1)))
                .toList());

        assertThatThrownBy(() -> service.resolveImagesForChat(7L, 200L, attachmentIds, List.of(), 1500))
                .isInstanceOf(BusinessException.class)
                .hasMessage("单条消息最多上传 8 张图片")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(objectStorageClient, never()).readBytes(any());
    }

    @Test
    void shouldResolveDuplicateImageOnlyOnce() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment attachment = readyAttachment(1L, 7L, 200L, null, OffsetDateTime.now().plusDays(1));
        when(mapper.selectByIds(List.of(1L))).thenReturn(List.of(attachment));
        when(objectStorageClient.readBytes("users/7/chat-images/20260604/cat.png"))
                .thenReturn(PNG_MAGIC);

        List<AttachmentReference> references =
                service.resolveImagesForChat(7L, 200L, List.of(1L), List.of(1L), 1500);

        assertThat(references).hasSize(1);
        assertThat(references.getFirst().attachmentId()).isEqualTo(1L);
        verify(objectStorageClient, times(1)).readBytes("users/7/chat-images/20260604/cat.png");
    }

    @Test
    void shouldResolveChatImageAsDataUrlForLlmInput() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment attachment = readyAttachment(1L, 7L, 200L, null, OffsetDateTime.now().plusDays(1));
        when(mapper.selectByIds(List.of(1L))).thenReturn(List.of(attachment));
        when(objectStorageClient.readBytes("users/7/chat-images/20260604/cat.png"))
                .thenReturn(PNG_MAGIC);

        List<AttachmentReference> references =
                service.resolveImagesForChat(7L, 200L, List.of(1L), List.of(), 1500);

        assertThat(references.getFirst().readUrl()).startsWith("data:image/png;base64,");
    }

    @Test
    void shouldAutoReuseRecentReadyImagesInConversation() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment olderImage = readyAttachment(1L, 7L, 200L, 300L, OffsetDateTime.now().plusDays(1));
        ChatAttachment latestImage = readyAttachment(2L, 7L, 200L, 302L, OffsetDateTime.now().plusDays(1));
        when(mapper.selectList(any())).thenReturn(List.of(latestImage, olderImage));
        when(objectStorageClient.readBytes("users/7/chat-images/20260604/cat.png"))
                .thenReturn(PNG_MAGIC);

        List<AttachmentReference> references =
                service.resolveImagesForChat(7L, 200L, List.of(), List.of(), 1500);

        assertThat(references).hasSize(2);
        assertThat(references).extracting(AttachmentReference::attachmentId)
                .containsExactly(1L, 2L);
        verify(objectStorageClient, times(2))
                .readBytes("users/7/chat-images/20260604/cat.png");
    }

    @Test
    void shouldBindToMessageForReadyUnboundUserAttachments() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        List<Long> attachmentIds = List.of(1L, 2L);
        when(mapper.selectByIds(attachmentIds)).thenReturn(List.of(
                readyAttachment(1L, 7L, null, null, OffsetDateTime.now().plusDays(1)),
                readyAttachment(2L, 7L, null, null, OffsetDateTime.now().plusDays(1))
        ));
        when(mapper.update(any(ChatAttachment.class), any())).thenReturn(1);
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        service.bindToMessage(7L, 200L, 300L, attachmentIds);

        verify(mapper, times(2)).update(attachmentCaptor.capture(), any());
        assertThat(attachmentCaptor.getAllValues())
                .extracting(ChatAttachment::getId)
                .containsExactly(1L, 2L);
        assertThat(attachmentCaptor.getAllValues())
                .allSatisfy(updatedAttachment -> {
                    assertThat(updatedAttachment.getConversationId()).isEqualTo(200L);
                    assertThat(updatedAttachment.getMessageId()).isEqualTo(300L);
                    assertThat(updatedAttachment.getUpdatedAt()).isNotNull();
                });
    }

    @Test
    void shouldRejectExpiredAttachmentWhenBindToMessage() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectByIds(List.of(1L))).thenReturn(List.of(
                readyAttachment(1L, 7L, null, null, OffsetDateTime.now().minusSeconds(1))
        ));

        assertThatThrownBy(() -> service.bindToMessage(7L, 200L, 300L, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片已过期")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(ChatAttachment.class));
    }

    @Test
    void shouldRejectWhenConditionalBindUpdateFails() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                readyAttachment(1L, 7L, null, null, OffsetDateTime.now().plusDays(1)),
                readyAttachment(2L, 7L, null, null, OffsetDateTime.now().plusDays(1))
        ));
        when(mapper.update(any(ChatAttachment.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.bindToMessage(7L, 200L, 300L, List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片上传失败，请重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, times(1)).update(any(ChatAttachment.class), any());
    }

    @Test
    void shouldRejectInvalidAttachmentWhenBindToMessage() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                readyAttachment(1L, 7L, null, null, OffsetDateTime.now().plusDays(1))
        ));

        assertThatThrownBy(() -> service.bindToMessage(7L, 200L, 300L, List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片上传失败，请重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(ChatAttachment.class));
    }

    @Test
    void shouldListMessageAttachmentsWithPreviewUrlAndExpiredMessage() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, properties);
        ChatAttachment activeAttachment = readyAttachment(1L, 7L, 200L, 300L, OffsetDateTime.now().plusDays(1));
        ChatAttachment expiredAttachment = readyAttachment(2L, 7L, 200L, 301L, OffsetDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(activeAttachment, expiredAttachment));
        when(objectStorageClient.presignGet("users/7/chat-images/20260604/cat.png", Duration.ofMinutes(15)))
                .thenReturn("https://example.com/preview.png");

        List<com.agenthub.storage.attachment.dto.AttachmentItem> items =
                service.listMessageAttachments(7L, List.of(300L, 301L));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).messageId()).isEqualTo(300L);
        assertThat(items.get(0).expired()).isFalse();
        assertThat(items.get(0).previewUrl()).isEqualTo("https://example.com/preview.png");
        assertThat(items.get(1).messageId()).isEqualTo(301L);
        assertThat(items.get(1).expired()).isTrue();
        assertThat(items.get(1).previewUrl()).isNull();
        assertThat(items.get(1).message()).isEqualTo("图片已过期");
    }

    @Test
    void shouldListMessageAttachmentReferencesAsDataUrlsForLlmHistory() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment activeAttachment = readyAttachment(1L, 7L, 200L, 300L, OffsetDateTime.now().plusDays(1));
        ChatAttachment expiredAttachment = readyAttachment(2L, 7L, 200L, 301L, OffsetDateTime.now().minusSeconds(1));
        when(mapper.selectList(any())).thenReturn(List.of(activeAttachment, expiredAttachment));
        when(objectStorageClient.readBytes("users/7/chat-images/20260604/cat.png"))
                .thenReturn(PNG_MAGIC);

        Map<Long, List<AttachmentReference>> references =
                service.listMessageAttachmentReferences(7L, List.of(300L, 301L), 1500);

        assertThat(references).containsOnlyKeys(300L);
        assertThat(references.get(300L)).hasSize(1);
        assertThat(references.get(300L).getFirst().attachmentId()).isEqualTo(1L);
        assertThat(references.get(300L).getFirst().readUrl()).startsWith("data:image/png;base64,");
        verify(objectStorageClient, times(1)).readBytes("users/7/chat-images/20260604/cat.png");
    }

    @Test
    void shouldResolveAttachmentImportSourceForOwner() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        ChatAttachment attachment = readyFileAttachment(10L, 7L);
        when(mapper.selectById(10L)).thenReturn(attachment);
        when(objectStorageClient.presignGet("users/7/chat-files/20260604/data.csv", Duration.ofMinutes(10)))
                .thenReturn("http://minio-internal/data.csv");

        AttachmentImportSource source = service.requireImportSource(7L, 10L);

        assertThat(source.attachmentId()).isEqualTo(10L);
        assertThat(source.filename()).isEqualTo("data.csv");
        assertThat(source.contentType()).isEqualTo("text/csv");
        assertThat(source.fileSize()).isEqualTo(1024L);
        assertThat(source.downloadUrl()).isEqualTo("http://minio-internal/data.csv");
        assertThat(source.objectKey()).isEqualTo("users/7/chat-files/20260604/data.csv");
    }

    @Test
    void shouldRejectAttachmentImportSourceForNonOwner() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectById(10L)).thenReturn(readyFileAttachment(10L, 8L));

        assertThatThrownBy(() -> service.requireImportSource(7L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("附件不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(objectStorageClient, never()).presignGet(any(), any());
    }

    private StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("agent-hub-chat-images");
        properties.setImageExpireDays(7);
        properties.setPresignedUploadTtlMinutes(10);
        properties.setPresignedReadTtlMinutes(15);
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
        attachment.setAttachmentType("IMAGE");
        return attachment;
    }

    private ChatAttachment pendingFileAttachment() {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(101L);
        attachment.setUserId(7L);
        attachment.setStatus("PENDING");
        attachment.setObjectKey("users/7/chat-files/20260604/data.csv");
        attachment.setContentType("text/csv");
        attachment.setFileSize(1024L);
        attachment.setFileExt("csv");
        attachment.setAttachmentType("FILE");
        return attachment;
    }

    private ChatAttachment readyFileAttachment(Long id, Long userId) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setUserId(userId);
        attachment.setStatus("READY");
        attachment.setObjectKey("users/7/chat-files/20260604/data.csv");
        attachment.setOriginalFilename("data.csv");
        attachment.setContentType("text/csv");
        attachment.setFileSize(1024L);
        attachment.setFileExt("csv");
        attachment.setAttachmentType("FILE");
        attachment.setExpiresAt(OffsetDateTime.now().plusDays(1));
        return attachment;
    }

    private void assertResolveNewImageRejected(ChatAttachment attachment) {
        assertResolveNewImageRejected(attachment, "图片不可用");
    }

    private void assertResolveNewImageRejected(ChatAttachment attachment, String message) {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        AttachmentServiceImpl service = new AttachmentServiceImpl(mapper, objectStorageClient, storageProperties());
        when(mapper.selectByIds(List.of(attachment.getId()))).thenReturn(List.of(attachment));

        assertThatThrownBy(() -> service.resolveImagesForChat(
                7L,
                200L,
                List.of(attachment.getId()),
                List.of(),
                1500
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage(message)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(objectStorageClient, never()).readBytes(any());
    }

    private ChatAttachment readyAttachment(
            Long id,
            Long userId,
            Long conversationId,
            Long messageId,
            OffsetDateTime expiresAt
    ) {
        return attachment(id, userId, conversationId, messageId, "READY", expiresAt);
    }

    private ChatAttachment attachment(
            Long id,
            Long userId,
            Long conversationId,
            Long messageId,
            String status,
            OffsetDateTime expiresAt
    ) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setUserId(userId);
        attachment.setConversationId(conversationId);
        attachment.setMessageId(messageId);
        attachment.setStatus(status);
        attachment.setObjectKey("users/7/chat-images/20260604/cat.png");
        attachment.setOriginalFilename("cat.png");
        attachment.setContentType("image/png");
        attachment.setAttachmentType("IMAGE");
        attachment.setExpiresAt(expiresAt);
        return attachment;
    }
}
