package com.agenthub.sandbox.service;

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
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.ExecSandboxRequest;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.execution.entity.SandboxExecution;
import com.agenthub.sandbox.execution.mapper.SandboxExecutionMapper;
import com.agenthub.sandbox.policy.CommandPolicy;
import com.agenthub.sandbox.service.impl.SandboxToolServiceImpl;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import com.agenthub.storage.attachment.dto.AttachmentImportSource;
import com.agenthub.storage.attachment.service.AttachmentService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxToolServiceImplTest {

    @Test
    void shouldValidateCommandBeforeCallingSandboxClient() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxToolServiceImpl service = service(client, mock(SandboxExecutionMapper.class));

        assertThatThrownBy(() -> service.exec(7L, 100L, "sbx_1", "sudo whoami", "/workspace", 30, event -> {
        }))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);

        verify(client, never()).streamExec(any(), any(), any());
    }

    @Test
    void shouldPersistExecutionAndForwardEvents() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        List<SandboxExecution> insertedSnapshots = new ArrayList<>();
        when(executionMapper.insert(any(SandboxExecution.class))).thenAnswer(invocation -> {
            SandboxExecution execution = invocation.getArgument(0);
            execution.setId(77L);
            insertedSnapshots.add(copy(execution));
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SandboxEvent> consumer = invocation.getArgument(2);
            consumer.accept(new SandboxEvent("stdout_delta", "exec_77", "ok", null, null, null, null, null, false));
            consumer.accept(new SandboxEvent("completed", "exec_77", null, 0, 120L, null, null, null, false));
            return null;
        }).when(client).streamExec(any(), any(), any());
        SandboxToolServiceImpl service = service(client, executionMapper);
        List<SandboxEvent> forwarded = new ArrayList<>();

        service.exec(7L, 100L, "sbx_1", "python3 main.py", "/workspace", 30, forwarded::add);

        assertThat(forwarded)
                .extracting(SandboxEvent::type)
                .containsExactly("stdout_delta", "completed");
        SandboxExecution inserted = insertedSnapshots.getFirst();
        assertThat(inserted.getConversationId()).isEqualTo(100L);
        assertThat(inserted.getToolName()).isEqualTo("exec");
        assertThat(inserted.getCommand()).isEqualTo("python3 main.py");
        assertThat(inserted.getStatus()).isEqualTo("RUNNING");

        ArgumentCaptor<SandboxExecution> updateCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(executionMapper).updateById(updateCaptor.capture());
        SandboxExecution completed = updateCaptor.getValue();
        assertThat(completed.getId()).isEqualTo(77L);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getExitCode()).isZero();
        assertThat(completed.getStdoutExcerpt()).isEqualTo("ok");
        assertThat(completed.getDurationMs()).isEqualTo(120L);
    }

    @Test
    void shouldMarkTimeoutExecution() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        when(executionMapper.insert(any(SandboxExecution.class))).thenAnswer(invocation -> {
            SandboxExecution execution = invocation.getArgument(0);
            execution.setId(77L);
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SandboxEvent> consumer = invocation.getArgument(2);
            consumer.accept(new SandboxEvent("completed", "exec_77", null, -1, 30000L, null, null, null, true));
            return null;
        }).when(client).streamExec(any(), any(), any());
        SandboxToolServiceImpl service = service(client, executionMapper);

        service.exec(7L, 100L, "sbx_1", "python3 main.py", "/workspace", 30, event -> {
        });

        ArgumentCaptor<SandboxExecution> updateCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(executionMapper).updateById(updateCaptor.capture());
        SandboxExecution timeout = updateCaptor.getValue();
        assertThat(timeout.getStatus()).isEqualTo("TIMEOUT");
        assertThat(timeout.getErrorCode()).isEqualTo(ErrorCode.COMMAND_TIMEOUT.code());
    }

    @Test
    void shouldFailExecutionWhenStreamEndsWithoutTerminalEvent() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        when(executionMapper.insert(any(SandboxExecution.class))).thenAnswer(invocation -> {
            SandboxExecution execution = invocation.getArgument(0);
            execution.setId(77L);
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SandboxEvent> consumer = invocation.getArgument(2);
            consumer.accept(new SandboxEvent("stdout_delta", "exec_77", "partial", null, null, null, null, null, false));
            return null;
        }).when(client).streamExec(any(), any(), any());
        SandboxToolServiceImpl service = service(client, executionMapper);

        service.exec(7L, 100L, "sbx_1", "python3 main.py", "/workspace", 30, event -> {
        });

        ArgumentCaptor<SandboxExecution> updateCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(executionMapper).updateById(updateCaptor.capture());
        SandboxExecution failed = updateCaptor.getValue();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.COMMAND_FAILED.code());
    }

    @Test
    void shouldRejectExecutionWhenSessionDoesNotBelongToUserConversation() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L, 8L, 100L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                mock(SandboxArtifactMapper.class)
        );

        assertThatThrownBy(() -> service.exec(7L, 100L, "sbx_1", "python3 main.py", "/workspace", 30, event -> {
        }))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(client, never()).streamExec(any(), any(), any());
    }

    @Test
    void shouldRejectFileReadWhenSessionDoesNotBelongToConversation() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L, 7L, 101L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                mock(SandboxArtifactMapper.class)
        );

        assertThatThrownBy(() -> service.readFile(7L, 100L, "sbx_1", "/workspace/out.txt", 1024))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(client, never()).readFile(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldWriteRelativeModelOutputIntoArtifactsDirectory() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxToolServiceImpl service = service(client, mock(SandboxExecutionMapper.class));

        service.writeFile(7L, 100L, "sbx_1", "作文.txt", "正文", false);

        verify(client).writeFile("sbx_1", "/workspace/artifacts/作文.txt", "正文", false);
    }

    @Test
    void shouldWriteWorkspaceWorkFileWithoutPersistingArtifact() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                artifactMapper
        );

        service.writeWorkspaceFile(7L, 100L, "sbx_1",
                "/workspace/work/docx/Program.cs", "class Program {}", false);

        verify(client).writeFile("sbx_1", "/workspace/work/docx/Program.cs", "class Program {}", false);
        verify(artifactMapper, never()).insert(any(com.agenthub.sandbox.artifact.entity.SandboxArtifact.class));
    }

    @Test
    void shouldWriteTempWorkFileWithoutPersistingArtifact() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                artifactMapper
        );

        service.writeWorkspaceFile(7L, 100L, "sbx_1",
                "/tmp/other-skill/Program.cs", "class Program {}", false);

        verify(client).writeFile("sbx_1", "/tmp/other-skill/Program.cs", "class Program {}", false);
        verify(artifactMapper, never()).insert(any(com.agenthub.sandbox.artifact.entity.SandboxArtifact.class));
    }

    @Test
    void shouldPersistArtifactsReturnedByWriteFile() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        when(client.writeFile("sbx_1", "/workspace/artifacts/output.txt", "hello", false)).thenReturn(List.of(
                new SandboxArtifactItem(
                        null,
                        "output.txt",
                        "output.txt",
                        "bucket",
                        "sandbox-artifacts/100/sha/output.txt",
                        "text/plain",
                        5L,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        1
                )
        ));
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                artifactMapper
        );

        service.writeFile(7L, 100L, "sbx_1", "output.txt", "hello", false);

        ArgumentCaptor<com.agenthub.sandbox.artifact.entity.SandboxArtifact> artifactCaptor =
                ArgumentCaptor.forClass(com.agenthub.sandbox.artifact.entity.SandboxArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        assertThat(artifactCaptor.getValue().getConversationId()).isEqualTo(100L);
        assertThat(artifactCaptor.getValue().getExecutionId()).isNull();
        assertThat(artifactCaptor.getValue().getFileName()).isEqualTo("output.txt");
        verify(client, never()).streamExec(any(), any(), any());
    }

    @Test
    void shouldTriggerLegacyArtifactSyncWhenWriteFileReturnsNoArtifacts() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        when(client.writeFile("sbx_1", "/workspace/artifacts/output.txt", "hello", false)).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SandboxEvent> consumer = invocation.getArgument(2);
            consumer.accept(new SandboxEvent("artifact_synced", null, null, null, null, null, null,
                    new SandboxArtifactItem(
                            null,
                            "output.txt",
                            "output.txt",
                            "bucket",
                            "sandbox-artifacts/100/sha/output.txt",
                            "text/plain",
                            5L,
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            1
                    ),
                    false));
            consumer.accept(new SandboxEvent("completed", null, null, 0, 10L, null, null, null, false));
            return null;
        }).when(client).streamExec(any(), any(), any());
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                artifactMapper
        );

        service.writeFile(7L, 100L, "sbx_1", "output.txt", "hello", false);

        ArgumentCaptor<ExecSandboxRequest> requestCaptor = ArgumentCaptor.forClass(ExecSandboxRequest.class);
        verify(client).streamExec(org.mockito.ArgumentMatchers.eq("sbx_1"), requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().command()).isEqualTo("pwd");
        ArgumentCaptor<com.agenthub.sandbox.artifact.entity.SandboxArtifact> artifactCaptor =
                ArgumentCaptor.forClass(com.agenthub.sandbox.artifact.entity.SandboxArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        assertThat(artifactCaptor.getValue().getConversationId()).isEqualTo(100L);
        assertThat(artifactCaptor.getValue().getExecutionId()).isNull();
        assertThat(artifactCaptor.getValue().getFileName()).isEqualTo("output.txt");
    }

    @Test
    void shouldUploadRelativeAttachmentIntoInputDirectory() {
        SandboxClient client = mock(SandboxClient.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        when(attachmentService.requireImportSource(7L, 10L)).thenReturn(new AttachmentImportSource(
                10L,
                "data.csv",
                "text/csv",
                5L,
                "data:text/plain;base64,aGVsbG8=",
                "users/7/chat-files/data.csv"
        ));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                mock(SandboxArtifactMapper.class),
                attachmentService
        );

        service.uploadFile(7L, 100L, "sbx_1", 10L, "data.csv");

        verify(client).writeFile("sbx_1", "/workspace/input/data.csv", "hello", false);
    }

    @Test
    void shouldPersistSyncedArtifactEvent() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        when(executionMapper.insert(any(SandboxExecution.class))).thenAnswer(invocation -> {
            SandboxExecution execution = invocation.getArgument(0);
            execution.setId(77L);
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SandboxEvent> consumer = invocation.getArgument(2);
            consumer.accept(new SandboxEvent("artifact_synced", null, null, null, null, null, null,
                    new com.agenthub.sandbox.client.dto.SandboxArtifactItem(
                            null,
                            "out.txt",
                            "reports/out.txt",
                            "bucket",
                            "sandbox-artifacts/100/sha/reports/out.txt",
                            "text/plain",
                            2L,
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            1
                    ),
                    false));
            consumer.accept(new SandboxEvent("completed", "exec_77", null, 0, 120L, null, null, null, false));
            return null;
        }).when(client).streamExec(any(), any(), any());
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                executionMapper,
                artifactMapper
        );

        service.exec(7L, 100L, "sbx_1", "python3 main.py", "/workspace", 30, event -> {
        });

        ArgumentCaptor<com.agenthub.sandbox.artifact.entity.SandboxArtifact> artifactCaptor =
                ArgumentCaptor.forClass(com.agenthub.sandbox.artifact.entity.SandboxArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        assertThat(artifactCaptor.getValue().getConversationId()).isEqualTo(100L);
        assertThat(artifactCaptor.getValue().getExecutionId()).isEqualTo(77L);
        assertThat(artifactCaptor.getValue().getFileName()).isEqualTo("out.txt");
    }

    @Test
    void shouldNotMarkExecutionFailedWhenEventConsumerFails() {
        SandboxClient client = mock(SandboxClient.class);
        SandboxExecutionMapper executionMapper = mock(SandboxExecutionMapper.class);
        when(executionMapper.insert(any(SandboxExecution.class))).thenAnswer(invocation -> {
            SandboxExecution execution = invocation.getArgument(0);
            execution.setId(77L);
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SandboxEvent> consumer = invocation.getArgument(2);
            consumer.accept(new SandboxEvent("completed", "exec_77", null, 0, 120L, null, null, null, false));
            return null;
        }).when(client).streamExec(any(), any(), any());
        SandboxToolServiceImpl service = service(client, executionMapper);

        assertThatThrownBy(() -> service.exec(7L, 100L, "sbx_1", "python3 main.py", "/workspace", 30, event -> {
            throw new IllegalStateException("sse closed");
        })).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<SandboxExecution> updateCaptor = ArgumentCaptor.forClass(SandboxExecution.class);
        verify(executionMapper, org.mockito.Mockito.atLeastOnce()).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getAllValues().getLast().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldUploadAttachmentContentIntoWorkspace() {
        SandboxClient client = mock(SandboxClient.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        when(attachmentService.requireImportSource(7L, 10L)).thenReturn(new AttachmentImportSource(
                10L,
                "input.txt",
                "text/plain",
                5L,
                "data:text/plain;base64,aGVsbG8=",
                "users/7/chat-files/input.txt"
        ));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                mock(SandboxArtifactMapper.class),
                attachmentService
        );

        service.uploadFile(7L, 100L, "sbx_1", 10L, "/workspace/input/input.txt");

        verify(client).writeFile("sbx_1", "/workspace/input/input.txt", "hello", false);
    }

    @Test
    void shouldUploadBinaryAttachmentBytesIntoWorkspace() {
        SandboxClient client = mock(SandboxClient.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        when(attachmentService.requireImportSource(7L, 10L)).thenReturn(new AttachmentImportSource(
                10L,
                "slide.png",
                "image/png",
                7L,
                "data:image/png;base64,UEsDBP8AgA==",
                "users/7/chat-images/slide.png"
        ));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                mock(SandboxArtifactMapper.class),
                attachmentService
        );

        service.uploadFile(7L, 100L, "sbx_1", 10L, "/workspace/input/slide.png");

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(client).writeFileBytes(eq("sbx_1"), eq("/workspace/input/slide.png"), bytesCaptor.capture(), eq(false));
        assertThat(bytesCaptor.getValue()).containsExactly(0x50, 0x4b, 0x03, 0x04, 0xff, 0x00, 0x80);
        verify(client, never()).writeFile(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void shouldUploadOfficeAttachmentBytesIntoWorkspace() {
        SandboxClient client = mock(SandboxClient.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        when(attachmentService.requireImportSource(7L, 10L)).thenReturn(new AttachmentImportSource(
                10L,
                "document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                7L,
                "data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;base64,UEsDBP8AgA==",
                "users/7/chat-files/document.docx"
        ));
        SandboxToolServiceImpl service = new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                mock(SandboxExecutionMapper.class),
                mock(SandboxArtifactMapper.class),
                attachmentService
        );

        service.uploadFile(7L, 100L, "sbx_1", 10L, "/workspace/input/document.docx");

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(client).writeFileBytes(eq("sbx_1"), eq("/workspace/input/document.docx"), bytesCaptor.capture(), eq(false));
        assertThat(bytesCaptor.getValue()).containsExactly(0x50, 0x4b, 0x03, 0x04, 0xff, 0x00, 0x80);
        verify(client, never()).writeFile(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private SandboxToolServiceImpl service(SandboxClient client, SandboxExecutionMapper executionMapper) {
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        when(sessionMapper.selectBySandboxSessionId("sbx_1")).thenReturn(session(55L));
        return new SandboxToolServiceImpl(
                client,
                new CommandPolicy(200, 60),
                sessionMapper,
                executionMapper,
                mock(SandboxArtifactMapper.class)
        );
    }

    private SandboxSession session(Long id) {
        return session(id, 7L, 100L);
    }

    private SandboxSession session(Long id, Long userId, Long conversationId) {
        SandboxSession session = new SandboxSession();
        session.setId(id);
        session.setUserId(userId);
        session.setConversationId(conversationId);
        session.setSandboxSessionId("sbx_1");
        return session;
    }

    private SandboxExecution copy(SandboxExecution source) {
        SandboxExecution copied = new SandboxExecution();
        copied.setId(source.getId());
        copied.setConversationId(source.getConversationId());
        copied.setToolName(source.getToolName());
        copied.setCommand(source.getCommand());
        copied.setWorkingDir(source.getWorkingDir());
        copied.setTimeoutSeconds(source.getTimeoutSeconds());
        copied.setStatus(source.getStatus());
        copied.setStartedAt(source.getStartedAt());
        return copied;
    }
}
