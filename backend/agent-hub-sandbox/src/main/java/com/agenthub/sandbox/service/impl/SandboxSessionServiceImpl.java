package com.agenthub.sandbox.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.artifact.entity.SandboxArtifact;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionRequest;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionResponse;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.session.entity.SandboxSession;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SandboxSessionServiceImpl implements SandboxSessionService {

    private static final String STATUS_READY = "READY";
    private static final String DEFAULT_IMAGE = "minimal";
    private static final int DEFAULT_VCPU = 1;
    private static final int DEFAULT_MEMORY_MB = 1024;
    private static final int DEFAULT_DISK_GB = 5;

    private final SandboxClient sandboxClient;
    private final SandboxSessionMapper sessionMapper;
    private final SandboxArtifactMapper artifactMapper;

    public SandboxSessionServiceImpl(
            SandboxClient sandboxClient,
            SandboxSessionMapper sessionMapper,
            SandboxArtifactMapper artifactMapper
    ) {
        this.sandboxClient = sandboxClient;
        this.sessionMapper = sessionMapper;
        this.artifactMapper = artifactMapper;
    }

    @Override
    public String ensureSession(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户和会话不能为空");
        }
        SandboxSession existing = sessionMapper.selectActiveByConversation(userId, conversationId);
        if (existing != null) {
            if (sandboxClient.sessionExists(existing.getSandboxSessionId())) {
                existing.setLastActiveAt(OffsetDateTime.now());
                sessionMapper.updateById(existing);
                return existing.getSandboxSessionId();
            }
            sessionMapper.markClosedBySandboxSessionId(existing.getSandboxSessionId(), OffsetDateTime.now());
        }

        CreateSandboxSessionResponse response = sandboxClient.createSession(
                new CreateSandboxSessionRequest(String.valueOf(conversationId), restoreArtifacts(conversationId)));
        if (response == null || response.sandboxSessionId() == null || response.sandboxSessionId().isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "沙箱会话创建失败");
        }
        SandboxSession persisted = sessionMapper.selectBySandboxSessionId(response.sandboxSessionId());
        if (persisted != null) {
            persisted.setStatus(normalizeStatus(response.status()));
            persisted.setLastActiveAt(OffsetDateTime.now());
            sessionMapper.updateById(persisted);
            return persisted.getSandboxSessionId();
        }
        SandboxSession session = new SandboxSession();
        session.setUserId(userId);
        session.setConversationId(conversationId);
        session.setSandboxSessionId(response.sandboxSessionId());
        session.setStatus(normalizeStatus(response.status()));
        session.setImage(DEFAULT_IMAGE);
        session.setVcpu(DEFAULT_VCPU);
        session.setMemoryMb(DEFAULT_MEMORY_MB);
        session.setDiskGb(DEFAULT_DISK_GB);
        session.setCreatedAt(OffsetDateTime.now());
        session.setLastActiveAt(session.getCreatedAt());
        sessionMapper.insert(session);
        return session.getSandboxSessionId();
    }

    private List<SandboxArtifactItem> restoreArtifacts(Long conversationId) {
        return artifactMapper.selectLatestByConversation(conversationId).stream()
                .map(this::toRestoreArtifact)
                .toList();
    }

    private SandboxArtifactItem toRestoreArtifact(SandboxArtifact artifact) {
        return new SandboxArtifactItem(
                artifact.getId(),
                artifact.getFileName(),
                artifact.getRelativePath(),
                artifact.getStorageBucket(),
                artifact.getStorageKey(),
                artifact.getContentType(),
                artifact.getSizeBytes(),
                artifact.getSha256(),
                artifact.getVersion()
        );
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_READY;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
