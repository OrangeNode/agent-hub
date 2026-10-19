package com.agenthub.chat.artifact.service;

import com.agenthub.chat.artifact.dto.ChatArtifactContent;
import com.agenthub.chat.artifact.dto.ChatArtifactDownload;
import com.agenthub.chat.artifact.dto.ChatArtifactItem;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.service.SandboxToolService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * 会话产物的预览与下载。调用方负责先校验会话归属。
 */
@Service
public class ChatArtifactService {

    private static final int MAX_PREVIEW_BYTES = 256 * 1024;
    private static final String ALL_FILES_ZIP_NAME = "全部文件.zip";
    private static final Set<String> PREVIEWABLE_EXTENSIONS = Set.of(
            ".txt", ".md", ".py", ".java", ".js", ".ts", ".json", ".csv",
            ".xml", ".yml", ".yaml", ".html", ".css"
    );

    private final SandboxToolService sandboxToolService;

    public ChatArtifactService(SandboxToolService sandboxToolService) {
        this.sandboxToolService = sandboxToolService;
    }

    public List<ChatArtifactItem> list(Long userId, Long conversationId) {
        if (sandboxToolService == null) {
            return List.of();
        }
        return sandboxToolService.artifacts(userId, conversationId, true).stream()
                .map(ChatArtifactService::toArtifactItem)
                .toList();
    }

    public ChatArtifactContent preview(Long userId, Long conversationId, Long artifactId, int maxBytes) {
        int previewLimit = Math.min(Math.max(maxBytes, 1), MAX_PREVIEW_BYTES);
        SandboxArtifactItem artifact = requireMetadata(userId, conversationId, artifactId);
        byte[] bytes = sandboxToolService.readArtifact(userId, conversationId, artifactId);
        boolean truncated = bytes.length > previewLimit;
        if (!isPreviewableText(artifact)) {
            return new ChatArtifactContent(artifact.artifactId(), artifact.fileName(), artifact.relativePath(),
                    artifact.contentType(), artifact.sizeBytes(), false, truncated, "");
        }
        int length = Math.min(bytes.length, previewLimit);
        return new ChatArtifactContent(artifact.artifactId(), artifact.fileName(), artifact.relativePath(),
                artifact.contentType(), artifact.sizeBytes(), true, truncated,
                new String(bytes, 0, length, StandardCharsets.UTF_8));
    }

    public ChatArtifactDownload download(Long userId, Long conversationId, Long artifactId) {
        SandboxArtifactItem artifact = requireMetadata(userId, conversationId, artifactId);
        return new ChatArtifactDownload(
                artifact.fileName(),
                defaultContentType(artifact.contentType()),
                sandboxToolService.readArtifact(userId, conversationId, artifactId)
        );
    }

    public ChatArtifactDownload downloadAll(Long userId, Long conversationId) {
        if (sandboxToolService == null) {
            return new ChatArtifactDownload(ALL_FILES_ZIP_NAME, "application/zip", new byte[0]);
        }
        List<SandboxArtifactItem> artifacts = sandboxToolService.artifacts(userId, conversationId, true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            Set<String> usedNames = new HashSet<>();
            for (SandboxArtifactItem artifact : artifacts) {
                zip.putNextEntry(new ZipEntry(uniqueZipEntryName(safeZipEntryName(artifact), usedNames)));
                zip.write(sandboxToolService.readArtifact(userId, conversationId, artifact.artifactId()));
                zip.closeEntry();
            }
            zip.finish();
            return new ChatArtifactDownload(ALL_FILES_ZIP_NAME, "application/zip", output.toByteArray());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件打包失败");
        }
    }

    private SandboxArtifactItem requireMetadata(Long userId, Long conversationId, Long artifactId) {
        if (sandboxToolService == null || artifactId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return sandboxToolService.artifacts(userId, conversationId, false).stream()
                .filter(item -> artifactId.equals(item.artifactId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
    }

    private static ChatArtifactItem toArtifactItem(SandboxArtifactItem item) {
        return new ChatArtifactItem(
                item.artifactId(),
                item.fileName(),
                item.relativePath(),
                defaultContentType(item.contentType()),
                item.sizeBytes(),
                item.sha256(),
                item.version()
        );
    }

    private static boolean isPreviewableText(SandboxArtifactItem artifact) {
        String contentType = defaultString(artifact.contentType()).toLowerCase(Locale.ROOT);
        if (contentType.startsWith("text/")
                || contentType.contains("json")
                || contentType.contains("xml")
                || contentType.contains("yaml")) {
            return true;
        }
        String name = defaultString(artifact.fileName()).toLowerCase(Locale.ROOT);
        int dotIndex = name.lastIndexOf('.');
        return dotIndex >= 0 && PREVIEWABLE_EXTENSIONS.contains(name.substring(dotIndex));
    }

    /**
     * 去掉绝对路径前缀和 ../ 片段，防止解压时越出目标目录。
     */
    private static String safeZipEntryName(SandboxArtifactItem artifact) {
        String value = hasText(artifact.relativePath()) ? artifact.relativePath() : artifact.fileName();
        if (!hasText(value)) {
            value = "file-" + artifact.artifactId();
        }
        String normalized = value.replace('\\', '/').replaceAll("^/+", "");
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? "file-" + artifact.artifactId() : String.join("/", segments);
    }

    private static String uniqueZipEntryName(String name, Set<String> usedNames) {
        if (usedNames.add(name)) {
            return name;
        }
        int slashIndex = name.lastIndexOf('/');
        String directory = slashIndex >= 0 ? name.substring(0, slashIndex + 1) : "";
        String fileName = slashIndex >= 0 ? name.substring(slashIndex + 1) : name;
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String suffix = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        for (int index = 2; ; index++) {
            String candidate = directory + base + "-" + index + suffix;
            if (usedNames.add(candidate)) {
                return candidate;
            }
        }
    }

    private static String defaultContentType(String contentType) {
        return hasText(contentType) ? contentType : "application/octet-stream";
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
