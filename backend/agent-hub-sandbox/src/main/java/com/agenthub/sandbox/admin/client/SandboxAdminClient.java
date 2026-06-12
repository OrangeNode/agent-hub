package com.agenthub.sandbox.admin.client;

import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeSession;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import java.util.List;

public interface SandboxAdminClient {

    SandboxRuntimeStatus status();

    List<SandboxRuntimeSession> sessions();

    SandboxResourceSnapshot resources();

    SandboxControlResult reapIdle();

    SandboxControlResult closeSession(String sessionId);

    SandboxControlResult killVm(String vmId, boolean dryRun);

    SandboxControlResult cleanupOrphans(boolean dryRun);

    SandboxControlResult clearRunDir(boolean dryRun);

    SandboxControlResult rebuildWarmPool(boolean dryRun);

    String tailLogs(int lines);
}
