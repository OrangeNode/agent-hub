package com.agenthub.server.sandbox;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.common.api.PageResult;
import com.agenthub.sandbox.admin.dto.SandboxAdminArtifactItem;
import com.agenthub.sandbox.admin.dto.SandboxAdminExecutionItem;
import com.agenthub.sandbox.admin.dto.SandboxAdminSessionItem;
import com.agenthub.sandbox.admin.dto.SandboxControlRequest;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxOverview;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.service.SandboxAdminService;
import com.agenthub.system.operationlog.annotation.OperationLog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sandbox/admin")
@PreAuthorize("hasAuthority('sandbox:monitor:view')")
public class SandboxAdminController {

    private final SandboxAdminService sandboxAdminService;

    public SandboxAdminController(SandboxAdminService sandboxAdminService) {
        this.sandboxAdminService = sandboxAdminService;
    }

    @GetMapping("/overview")
    public ApiResponse<SandboxOverview> overview() {
        return ApiResponse.success(sandboxAdminService.overview());
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResult<SandboxAdminSessionItem>> sessions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String sandboxSessionId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return ApiResponse.success(sandboxAdminService.sessions(status, conversationId, sandboxSessionId, limit, offset));
    }

    @GetMapping("/executions")
    public ApiResponse<PageResult<SandboxAdminExecutionItem>> executions(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) Long sandboxSessionDbId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return ApiResponse.success(sandboxAdminService.executions(conversationId, sandboxSessionDbId, status, limit, offset));
    }

    @GetMapping("/artifacts")
    public ApiResponse<PageResult<SandboxAdminArtifactItem>> artifacts(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) Long sandboxSessionDbId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return ApiResponse.success(sandboxAdminService.artifacts(conversationId, sandboxSessionDbId, limit, offset));
    }

    @GetMapping("/resources")
    public ApiResponse<SandboxResourceSnapshot> resources() {
        return ApiResponse.success(sandboxAdminService.resources());
    }

    @PostMapping("/reap-idle")
    @PreAuthorize("hasAuthority('sandbox:runtime:reap')")
    @OperationLog(module = "Sandbox运维", action = "回收空闲运行时", targetType = "SANDBOX", recordResult = true)
    public ApiResponse<SandboxControlResult> reapIdle() {
        return ApiResponse.success(sandboxAdminService.reapIdle());
    }

    @PostMapping("/sessions/{sessionId}/close")
    @PreAuthorize("hasAuthority('sandbox:session:close')")
    @OperationLog(module = "Sandbox运维", action = "关闭会话", target = "#sessionId", targetType = "SESSION", recordResult = true)
    public ApiResponse<SandboxControlResult> closeSession(@PathVariable String sessionId) {
        return ApiResponse.success(sandboxAdminService.closeSession(sessionId));
    }

    @PostMapping("/sessions/close-missing-runtime")
    @PreAuthorize("hasAuthority('sandbox:session:close')")
    @OperationLog(module = "Sandbox运维", action = "关闭缺失运行态会话", targetType = "SESSION", recordResult = true)
    public ApiResponse<SandboxControlResult> closeMissingRuntimeSessions() {
        return ApiResponse.success(sandboxAdminService.closeMissingRuntimeSessions());
    }

    @PostMapping("/vms/{vmId}/kill")
    @PreAuthorize("hasAuthority('sandbox:runtime:kill')")
    @OperationLog(module = "Sandbox运维", action = "终止VM", target = "#vmId", targetType = "VM",
            recordParams = true, recordResult = true, requireReason = true)
    public ApiResponse<SandboxControlResult> killVm(
            @PathVariable String vmId,
            @RequestBody SandboxControlRequest request
    ) {
        return ApiResponse.success(sandboxAdminService.killVm(vmId, request));
    }

    @PostMapping("/resources/cleanup-orphans")
    @PreAuthorize("hasAuthority('sandbox:runtime:cleanup')")
    @OperationLog(module = "Sandbox运维", action = "清理残留资源", targetType = "RESOURCE",
            recordParams = true, recordResult = true, requireReason = true)
    public ApiResponse<SandboxControlResult> cleanupOrphans(@RequestBody SandboxControlRequest request) {
        return ApiResponse.success(sandboxAdminService.cleanupOrphans(request));
    }

    @PostMapping("/run-dir/clear")
    @PreAuthorize("hasAuthority('sandbox:runtime:cleanup')")
    @OperationLog(module = "Sandbox运维", action = "清空运行目录", targetType = "RUN_DIR",
            recordParams = true, recordResult = true, requireReason = true)
    public ApiResponse<SandboxControlResult> clearRunDir(@RequestBody SandboxControlRequest request) {
        return ApiResponse.success(sandboxAdminService.clearRunDir(request));
    }

    @PostMapping("/warm-pool/rebuild")
    @PreAuthorize("hasAuthority('sandbox:runtime:cleanup')")
    @OperationLog(module = "Sandbox运维", action = "重建预热池", targetType = "WARM_POOL",
            recordParams = true, recordResult = true, requireReason = true)
    public ApiResponse<SandboxControlResult> rebuildWarmPool(@RequestBody SandboxControlRequest request) {
        return ApiResponse.success(sandboxAdminService.rebuildWarmPool(request));
    }

    @GetMapping("/logs/tail")
    public ApiResponse<String> tailLogs(@RequestParam(defaultValue = "100") int lines) {
        return ApiResponse.success(sandboxAdminService.tailLogs(lines));
    }
}
