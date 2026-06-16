package com.agenthub.sandbox.client;

import com.agenthub.sandbox.client.dto.CreateSandboxSessionRequest;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionResponse;
import com.agenthub.sandbox.client.dto.ExecSandboxRequest;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SkillZipSyncRequest;
import com.agenthub.sandbox.client.dto.SkillZipSyncResult;
import java.util.List;
import java.util.function.Consumer;

public interface SandboxClient {

    CreateSandboxSessionResponse createSession(CreateSandboxSessionRequest request);

    boolean sessionExists(String sandboxSessionId);

    void streamExec(String sandboxSessionId, ExecSandboxRequest request, Consumer<SandboxEvent> eventConsumer);

    List<SandboxFileItem> listFiles(String sandboxSessionId, String path, boolean recursive);

    String readFile(String sandboxSessionId, String path, int maxBytes);

    List<SandboxArtifactItem> writeFile(String sandboxSessionId, String path, String content, boolean append);

    SkillZipSyncResult syncSkillZips(String sandboxSessionId, SkillZipSyncRequest request);
}
