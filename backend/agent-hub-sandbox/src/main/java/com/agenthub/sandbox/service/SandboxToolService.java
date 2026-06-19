package com.agenthub.sandbox.service;

import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import java.util.List;
import java.util.function.Consumer;

public interface SandboxToolService {

    void exec(Long userId, Long conversationId, String sandboxSessionId, String command,
              String workingDir, int timeoutSeconds, Consumer<SandboxEvent> eventConsumer);

    List<SandboxFileItem> listFiles(Long userId, Long conversationId, String sandboxSessionId,
                                    String path, boolean recursive);

    String readFile(Long userId, Long conversationId, String sandboxSessionId,
                    String path, int maxBytes);

    void writeFile(Long userId, Long conversationId, String sandboxSessionId,
                   String path, String content, boolean append);

    void writeWorkspaceFile(Long userId, Long conversationId, String sandboxSessionId,
                            String path, String content, boolean append);

    void uploadFile(Long userId, Long conversationId, String sandboxSessionId,
                    Long attachmentId, String targetPath);

    List<SandboxArtifactItem> artifacts(Long userId, Long conversationId, boolean latestOnly);

    byte[] readArtifact(Long userId, Long conversationId, Long artifactId);
}
