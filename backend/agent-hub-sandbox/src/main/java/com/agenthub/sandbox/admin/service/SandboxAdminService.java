package com.agenthub.sandbox.admin.service;

import com.agenthub.sandbox.admin.dto.SandboxAdminArtifactItem;
import com.agenthub.sandbox.admin.dto.SandboxAdminExecutionItem;
import com.agenthub.sandbox.admin.dto.SandboxAdminSessionItem;
import com.agenthub.sandbox.admin.dto.SandboxControlRequest;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxOverview;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import java.util.List;

public interface SandboxAdminService {

    SandboxOverview overview();

    List<SandboxAdminSessionItem> sessions(
            String status,
            Long conversationId,
            String sandboxSessionId,
            Integer limit,
            Integer offset
    );

    List<SandboxAdminExecutionItem> executions(
            Long conversationId,
            Long sandboxSessionDbId,
            String status,
            Integer limit,
            Integer offset
    );

    List<SandboxAdminArtifactItem> artifacts(
            Long conversationId,
            Long sandboxSessionDbId,
            Integer limit,
            Integer offset
    );

    SandboxResourceSnapshot resources();

    SandboxControlResult reapIdle();

    SandboxControlResult closeSession(String sessionId);

    SandboxControlResult closeMissingRuntimeSessions();

    SandboxControlResult killVm(String vmId, SandboxControlRequest request);

    SandboxControlResult cleanupOrphans(SandboxControlRequest request);

    SandboxControlResult clearRunDir(SandboxControlRequest request);

    SandboxControlResult rebuildWarmPool(SandboxControlRequest request);

    String tailLogs(int lines);
}
