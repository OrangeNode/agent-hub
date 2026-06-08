package com.agenthub.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionRequest;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionResponse;
import com.agenthub.sandbox.service.impl.SandboxSessionServiceImpl;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxSessionServiceImplTest {

    @Test
    void shouldReuseActiveConversationSession() {
        SandboxClient sandboxClient = mock(SandboxClient.class);
        SandboxSessionMapper sessionMapper = mock(SandboxSessionMapper.class);
        SandboxSession existing = session("sbx_1");
        when(sessionMapper.selectActiveByConversation(7L, 100L)).thenReturn(existing);
        SandboxSessionServiceImpl service = new SandboxSessionServiceImpl(sandboxClient, sessionMapper);

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
        when(sessionMapper.selectActiveByConversation(7L, 100L)).thenReturn(null);
        when(sandboxClient.createSession(any(CreateSandboxSessionRequest.class)))
                .thenReturn(new CreateSandboxSessionResponse("sbx_new", "READY"));
        SandboxSessionServiceImpl service = new SandboxSessionServiceImpl(sandboxClient, sessionMapper);

        String sandboxSessionId = service.ensureSession(7L, 100L);

        assertThat(sandboxSessionId).isEqualTo("sbx_new");
        ArgumentCaptor<CreateSandboxSessionRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateSandboxSessionRequest.class);
        verify(sandboxClient).createSession(requestCaptor.capture());
        assertThat(requestCaptor.getValue().conversationId()).isEqualTo("100");

        ArgumentCaptor<SandboxSession> sessionCaptor = ArgumentCaptor.forClass(SandboxSession.class);
        verify(sessionMapper).insert(sessionCaptor.capture());
        SandboxSession inserted = sessionCaptor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getConversationId()).isEqualTo(100L);
        assertThat(inserted.getSandboxSessionId()).isEqualTo("sbx_new");
        assertThat(inserted.getImage()).isEqualTo("minimal");
        assertThat(inserted.getVcpu()).isEqualTo(1);
        assertThat(inserted.getMemoryMb()).isEqualTo(1024);
        assertThat(inserted.getDiskGb()).isEqualTo(5);
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
}
