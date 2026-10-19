package com.agenthub.chat.run;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.SkillZipSyncRequest;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.skill.service.SkillRuntimeContext;
import com.agenthub.skill.service.SkillRuntimePackage;
import com.agenthub.storage.object.ObjectStorageClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 首次调用沙箱工具前，把本轮启用的 Skill 原始 zip 同步到沙箱工作区。
 */
@Component
public class SkillSandboxSynchronizer {

    private static final Duration SKILL_ZIP_URL_TTL = Duration.ofMinutes(10);

    private final SandboxSessionService sandboxSessionService;
    private final SandboxClient sandboxClient;
    private final ObjectStorageClient objectStorageClient;

    public SkillSandboxSynchronizer(
            SandboxSessionService sandboxSessionService,
            SandboxClient sandboxClient,
            ObjectStorageClient objectStorageClient
    ) {
        this.sandboxSessionService = sandboxSessionService;
        this.sandboxClient = sandboxClient;
        this.objectStorageClient = objectStorageClient;
    }

    /**
     * 每次运行只同步一次；同步失败时复位标记，允许后续沙箱工具调用重试。
     */
    public void syncIfNeeded(
            Long userId,
            Long conversationId,
            String toolName,
            SkillRuntimeContext skillContext,
            AtomicBoolean synced
    ) {
        if (!isSandboxTool(toolName) || skillContext == null || skillContext.packages().isEmpty()) {
            return;
        }
        if (!synced.compareAndSet(false, true)) {
            return;
        }
        try {
            sync(userId, conversationId, skillContext);
        } catch (RuntimeException exception) {
            synced.set(false);
            throw exception;
        }
    }

    public static boolean isSandboxTool(String toolName) {
        return toolName != null && toolName.startsWith("sandbox.");
    }

    private void sync(Long userId, Long conversationId, SkillRuntimeContext skillContext) {
        skillContext.packages().forEach(this::requireSyncable);
        if (sandboxSessionService == null || sandboxClient == null || objectStorageClient == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "沙箱同步服务不可用");
        }
        String sandboxSessionId = sandboxSessionService.ensureSession(userId, conversationId);
        List<SkillZipSyncRequest.Skill> skills = skillContext.packages().stream()
                .map(skill -> new SkillZipSyncRequest.Skill(
                        skill.skillId(),
                        skill.skillKey(),
                        skill.name(),
                        objectStorageClient.presignGet(skill.bucketName(), skill.objectKey(), SKILL_ZIP_URL_TTL),
                        resolveZipSha256(skill),
                        "/workspace/skills/" + skill.skillKey()
                ))
                .toList();
        sandboxClient.syncSkillZips(sandboxSessionId, new SkillZipSyncRequest(skills));
    }

    private void requireSyncable(SkillRuntimePackage skill) {
        if (skill == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Skill 包元数据缺失，无法同步到沙箱");
        }
        if (!hasText(skill.skillKey())) {
            throw missingField(skill, "skillKey");
        }
        if (!hasText(skill.bucketName())) {
            throw missingField(skill, "bucketName");
        }
        if (!hasText(skill.objectKey())) {
            throw missingField(skill, "objectKey");
        }
    }

    private String resolveZipSha256(SkillRuntimePackage skill) {
        if (hasText(skill.zipSha256())) {
            return skill.zipSha256().strip();
        }
        byte[] zipBytes = objectStorageClient.readBytes(skill.bucketName(), skill.objectKey());
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Skill " + displayName(skill) + " 原始 zip 为空，无法同步到沙箱");
        }
        return sha256Hex(zipBytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BusinessException missingField(SkillRuntimePackage skill, String fieldName) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR,
                "Skill " + displayName(skill) + " 缺少 " + fieldName + "，无法同步到沙箱");
    }

    private static String displayName(SkillRuntimePackage skill) {
        String display = hasText(skill.skillKey()) ? skill.skillKey() : skill.name();
        String id = skill.skillId() == null ? "未知" : String.valueOf(skill.skillId());
        return hasText(display) ? id + "(" + display.strip() + ")" : id;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
