package com.agenthub.storage.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.agenthub.storage.attachment.job.AttachmentCleanupJob;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectStorageClient;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AttachmentCleanupJobTest {

    @Test
    void shouldDeletePendingAttachmentsOlderThanConfiguredMinutes() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentCleanupJob job = new AttachmentCleanupJob(mapper, objectStorageClient, properties);
        ChatAttachment attachment = attachment(1L, "PENDING", "pending-object");
        attachment.setCreatedAt(OffsetDateTime.now().minusMinutes(61));
        when(mapper.selectList(any())).thenReturn(List.of(attachment));
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        job.cleanupTemporaryAttachments();

        verify(objectStorageClient).delete("pending-object");
        verify(mapper).updateById(attachmentCaptor.capture());
        ChatAttachment updatedAttachment = attachmentCaptor.getValue();
        assertThat(updatedAttachment.getId()).isEqualTo(1L);
        assertThat(updatedAttachment.getStatus()).isEqualTo("DELETED");
        assertThat(updatedAttachment.getDeletedAt()).isNotNull();
        assertThat(updatedAttachment.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldDeleteReadyAttachmentsWithoutMessageOlderThanConfiguredHours() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentCleanupJob job = new AttachmentCleanupJob(mapper, objectStorageClient, properties);
        ChatAttachment attachment = attachment(2L, "READY", "ready-object");
        attachment.setMessageId(null);
        attachment.setCreatedAt(OffsetDateTime.now().minusHours(25));
        when(mapper.selectList(any())).thenReturn(List.of(attachment));
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        job.cleanupTemporaryAttachments();

        verify(objectStorageClient).delete("ready-object");
        verify(mapper).updateById(attachmentCaptor.capture());
        ChatAttachment updatedAttachment = attachmentCaptor.getValue();
        assertThat(updatedAttachment.getId()).isEqualTo(2L);
        assertThat(updatedAttachment.getStatus()).isEqualTo("DELETED");
        assertThat(updatedAttachment.getDeletedAt()).isNotNull();
    }

    @Test
    void shouldMarkExpiredReadyAttachments() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentCleanupJob job = new AttachmentCleanupJob(mapper, objectStorageClient, properties);
        ChatAttachment attachment = attachment(3L, "READY", "expired-object");
        attachment.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(mapper.selectList(any())).thenReturn(List.of(attachment));
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        job.markExpiredAttachments();

        verify(objectStorageClient, never()).delete(any());
        verify(mapper).updateById(attachmentCaptor.capture());
        ChatAttachment updatedAttachment = attachmentCaptor.getValue();
        assertThat(updatedAttachment.getId()).isEqualTo(3L);
        assertThat(updatedAttachment.getStatus()).isEqualTo("EXPIRED");
        assertThat(updatedAttachment.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldContinueCleanupWhenOneObjectDeleteFails() {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentCleanupJob job = new AttachmentCleanupJob(mapper, objectStorageClient, properties);
        ChatAttachment failedAttachment = attachment(4L, "PENDING", "failed-object");
        ChatAttachment successfulAttachment = attachment(5L, "PENDING", "successful-object");
        when(mapper.selectList(any())).thenReturn(List.of(failedAttachment, successfulAttachment));
        doThrow(new RuntimeException("delete failed")).when(objectStorageClient).delete("failed-object");
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        job.cleanupTemporaryAttachments();

        verify(objectStorageClient).delete("failed-object");
        verify(objectStorageClient).delete("successful-object");
        verify(mapper).updateById(attachmentCaptor.capture());
        ChatAttachment updatedAttachment = attachmentCaptor.getValue();
        assertThat(updatedAttachment.getId()).isEqualTo(5L);
        assertThat(updatedAttachment.getStatus()).isEqualTo("DELETED");
        assertThat(updatedAttachment.getDeletedAt()).isNotNull();
    }

    @Test
    void shouldContinueCleanupWhenOneStatusUpdateFails(CapturedOutput output) {
        ChatAttachmentMapper mapper = mock(ChatAttachmentMapper.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        StorageProperties properties = storageProperties();
        AttachmentCleanupJob job = new AttachmentCleanupJob(mapper, objectStorageClient, properties);
        ChatAttachment failedAttachment = attachment(6L, "PENDING", "update-failed-object");
        ChatAttachment successfulAttachment = attachment(7L, "PENDING", "update-successful-object");
        when(mapper.selectList(any())).thenReturn(List.of(failedAttachment, successfulAttachment));
        when(mapper.updateById(any(ChatAttachment.class)))
                .thenThrow(new RuntimeException("update failed"))
                .thenReturn(1);
        ArgumentCaptor<ChatAttachment> attachmentCaptor = ArgumentCaptor.forClass(ChatAttachment.class);

        job.cleanupTemporaryAttachments();

        verify(objectStorageClient).delete("update-failed-object");
        verify(objectStorageClient).delete("update-successful-object");
        verify(mapper, times(2)).updateById(attachmentCaptor.capture());
        assertThat(attachmentCaptor.getAllValues()).extracting(ChatAttachment::getId)
                .containsExactly(6L, 7L);
        assertThat(output).contains("更新附件清理状态失败");
        assertThat(output).doesNotContain("清理附件对象失败，attachmentId=6");
    }

    private StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setPendingCleanupMinutes(60);
        properties.setUnusedReadyCleanupHours(24);
        return properties;
    }

    private ChatAttachment attachment(Long id, String status, String objectKey) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setStatus(status);
        attachment.setObjectKey(objectKey);
        attachment.setCreatedAt(OffsetDateTime.now().minusDays(2));
        return attachment;
    }
}
