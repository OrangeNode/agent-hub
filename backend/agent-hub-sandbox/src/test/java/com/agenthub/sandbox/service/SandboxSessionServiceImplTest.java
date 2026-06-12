package com.agenthub.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.sandbox.artifact.entity.SandboxArtifact;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionRequest;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionResponse;
import com.agenthub.sandbox.service.impl.SandboxSessionServiceImpl;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxSessionServiceImplTest {

    @Test
    void shouldReuseActiveConversationSession() {
        SandboxClient sandboxClient = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxSession existing = session("sbx_1");
        when(sessionMapper.selectActiveByConversation(7L, 100L)).thenReturn(existing);
        when(sandboxClient.sessionExists("sbx_1")).thenReturn(true);
        SandboxSessionServiceImpl service = new SandboxSessionServiceImpl(
                sandboxClient,
                sessionMapper,
                mock(SandboxArtifactMapper.class)
        );

        String sandboxSessionId = service.ensureSession(7L, 100L);

        assertThat(sandboxSessionId).isEqualTo("sbx_1");
        assertThat(existing.getLastActiveAt()).isNotNull();
        verify(sessionMapper).updateById(existing);
        verify(sandboxClient, never()).createSession(any());
    }

    @Test
    void shouldCreateSessionWhenConversationHasNoActiveSession() {
        SandboxClient sandboxClient = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        when(sessionMapper.selectActiveByConversation(7L, 100L)).thenReturn(null);
        when(sessionMapper.selectBySandboxSessionId("sbx_new")).thenReturn(null);
        when(artifactMapper.selectLatestByConversation(100L)).thenReturn(List.of());
        when(sandboxClient.createSession(any(CreateSandboxSessionRequest.class)))
                .thenReturn(new CreateSandboxSessionResponse("sbx_new", "ready"));
        SandboxSessionServiceImpl service = new SandboxSessionServiceImpl(sandboxClient, sessionMapper, artifactMapper);

        String sandboxSessionId = service.ensureSession(7L, 100L);

        assertThat(sandboxSessionId).isEqualTo("sbx_new");
        ArgumentCaptor<CreateSandboxSessionRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateSandboxSessionRequest.class);
        verify(sandboxClient).createSession(requestCaptor.capture());
        assertThat(requestCaptor.getValue().conversationId()).isEqualTo("100");
        assertThat(requestCaptor.getValue().artifacts()).isEmpty();

        ArgumentCaptor<SandboxSession> sessionCaptor = ArgumentCaptor.forClass(SandboxSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        SandboxSession inserted = sessionCaptor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getConversationId()).isEqualTo(100L);
        assertThat(inserted.getSandboxSessionId()).isEqualTo("sbx_new");
        assertThat(inserted.getStatus()).isEqualTo("READY");
        assertThat(inserted.getImage()).isEqualTo("minimal");
        assertThat(inserted.getVcpu()).isEqualTo(1);
        assertThat(inserted.getMemoryMb()).isEqualTo(1024);
        assertThat(inserted.getDiskGb()).isEqualTo(5);
    }

    @Test
    void shouldCloseMissingRuntimeSessionAndCreateReplacementWithRestoredArtifacts() {
        SandboxClient sandboxClient = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        SandboxSession staleSession = session("sbx_stale");
        when(sessionMapper.selectActiveByConversation(7L, 100L)).thenReturn(staleSession);
        when(sandboxClient.sessionExists("sbx_stale")).thenReturn(false);
        when(artifactMapper.selectLatestByConversation(100L)).thenReturn(List.of(artifact(
                "reports/result.txt",
                "result.txt",
                "agent-hub-sandbox-artifacts",
                "sandbox-artifacts/100/hash/reports/result.txt",
                "text/plain",
                12L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                2
        )));
        when(sandboxClient.createSession(any(CreateSandboxSessionRequest.class)))
                .thenReturn(new CreateSandboxSessionResponse("sbx_new", "ready"));
        when(sessionMapper.selectBySandboxSessionId("sbx_new")).thenReturn(null);
        SandboxSessionServiceImpl service = new SandboxSessionServiceImpl(sandboxClient, sessionMapper, artifactMapper);

        String sandboxSessionId = service.ensureSession(7L, 100L);

        assertThat(sandboxSessionId).isEqualTo("sbx_new");
        verify(sessionMapper).markClosedBySandboxSessionId(
                org.mockito.ArgumentMatchers.eq("sbx_stale"),
                any(OffsetDateTime.class)
        );
        ArgumentCaptor<CreateSandboxSessionRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateSandboxSessionRequest.class);
        verify(sandboxClient).createSession(requestCaptor.capture());
        assertThat(requestCaptor.getValue().conversationId()).isEqualTo("100");
        assertThat(requestCaptor.getValue().artifacts())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.fileName()).isEqualTo("result.txt");
                    assertThat(item.relativePath()).isEqualTo("reports/result.txt");
                    assertThat(item.storageBucket()).isEqualTo("agent-hub-sandbox-artifacts");
                    assertThat(item.storageKey()).isEqualTo("sandbox-artifacts/100/hash/reports/result.txt");
                    assertThat(item.sha256()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
                    assertThat(item.version()).isEqualTo(2);
                });
    }

    @Test
    void shouldReusePersistedRuntimeSessionWhenSandboxServiceReturnsExistingId() {
        SandboxClient sandboxClient = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxArtifactMapper artifactMapper = mock(SandboxArtifactMapper.class);
        SandboxSession existing = session("sbx_existing");
        when(sessionMapper.selectActiveByConversation(7L, 100L)).thenReturn(null);
        when(artifactMapper.selectLatestByConversation(100L)).thenReturn(List.of());
        when(sandboxClient.createSession(any(CreateSandboxSessionRequest.class)))
                .thenReturn(new CreateSandboxSessionResponse("sbx_existing", "ready"));
        when(sessionMapper.selectBySandboxSessionId("sbx_existing")).thenReturn(existing);
        SandboxSessionServiceImpl service = new SandboxSessionServiceImpl(sandboxClient, sessionMapper, artifactMapper);

        String sandboxSessionId = service.ensureSession(7L, 100L);

        assertThat(sandboxSessionId).isEqualTo("sbx_existing");
        verify(sessionMapper).updateById(existing);
        verify(sessionMapper, never()).insert(any(SandboxSession.class));
    }

    private SandboxSession session(String sandboxSessionId) {
        SandboxSession session = new SandboxSession();
        session.setId(1L);
        session.setUserId(7L);
        session.setConversationId(100L);
        session.setSandboxSessionId(sandboxSessionId);
        session.setStatus("READY");
        return session;
    }

    private SandboxArtifact artifact(
            String relativePath,
            String fileName,
            String storageBucket,
            String storageKey,
            String contentType,
            Long sizeBytes,
            String sha256,
            Integer version
    ) {
        SandboxArtifact artifact = new SandboxArtifact();
        artifact.setRelativePath(relativePath);
        artifact.setFileName(fileName);
        artifact.setStorageBucket(storageBucket);
        artifact.setStorageKey(storageKey);
        artifact.setContentType(contentType);
        artifact.setSizeBytes(sizeBytes);
        artifact.setSha256(sha256);
        artifact.setVersion(version);
        return artifact;
    }
}
