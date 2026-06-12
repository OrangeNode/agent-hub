package com.agenthub.tool.mcp.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.tool.mcp.command.CreateMcpServerCommand;
import com.agenthub.tool.mcp.command.UpdateMcpServerCommand;
import com.agenthub.tool.mcp.controller.request.CreateMcpServerRequest;
import com.agenthub.tool.mcp.controller.request.UpdateMcpServerRequest;
import com.agenthub.tool.mcp.controller.request.UpdateMcpToolEnabledRequest;
import com.agenthub.tool.mcp.dto.McpConnectionTestResult;
import com.agenthub.tool.mcp.dto.McpServerItem;
import com.agenthub.tool.mcp.dto.McpToolCallLogItem;
import com.agenthub.tool.mcp.dto.McpToolItem;
import com.agenthub.tool.mcp.dto.McpToolSyncResult;
import com.agenthub.tool.mcp.service.McpServerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/mcp")
@PreAuthorize("hasAuthority('mcp:server:list')")
public class McpServerController {

    private final McpServerService mcpServerService;

    public McpServerController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @GetMapping("/servers")
    public ApiResponse<List<McpServerItem>> listServers() {
        return ApiResponse.success(mcpServerService.list());
    }

    @PostMapping("/servers")
    @PreAuthorize("hasAuthority('mcp:server:create')")
    public ApiResponse<McpServerItem> createServer(@Valid @RequestBody CreateMcpServerRequest request) {
        return ApiResponse.success(mcpServerService.create(new CreateMcpServerCommand(
                request.serverKey(),
                request.name(),
                request.description(),
                request.endpointUrl(),
                request.transportType(),
                request.protocolVersion(),
                request.authType(),
                request.authToken(),
                request.extraHeaders(),
                request.connectTimeoutSeconds(),
                request.requestTimeoutSeconds(),
                request.enabled(),
                request.sort()
        )));
    }

    @PutMapping("/servers/{serverId}")
    @PreAuthorize("hasAuthority('mcp:server:update')")
    public ApiResponse<McpServerItem> updateServer(
            @PathVariable @Positive(message = "MCP 服务 ID 必须为正数") Long serverId,
            @Valid @RequestBody UpdateMcpServerRequest request
    ) {
        return ApiResponse.success(mcpServerService.update(serverId, new UpdateMcpServerCommand(
                request.name(),
                request.description(),
                request.endpointUrl(),
                request.transportType(),
                request.protocolVersion(),
                request.authType(),
                request.authToken(),
                request.extraHeaders(),
                request.connectTimeoutSeconds(),
                request.requestTimeoutSeconds(),
                request.enabled(),
                request.sort()
        )));
    }

    @DeleteMapping("/servers/{serverId}")
    @PreAuthorize("hasAuthority('mcp:server:delete')")
    public ApiResponse<Void> deleteServer(
            @PathVariable @Positive(message = "MCP 服务 ID 必须为正数") Long serverId
    ) {
        mcpServerService.delete(serverId);
        return ApiResponse.success();
    }

    @PostMapping("/servers/{serverId}/test")
    @PreAuthorize("hasAuthority('mcp:server:test')")
    public ApiResponse<McpConnectionTestResult> testConnection(
            @PathVariable @Positive(message = "MCP 服务 ID 必须为正数") Long serverId
    ) {
        return ApiResponse.success(mcpServerService.testConnection(serverId));
    }

    @PostMapping("/servers/{serverId}/sync-tools")
    @PreAuthorize("hasAuthority('mcp:tool:sync')")
    public ApiResponse<McpToolSyncResult> syncTools(
            @PathVariable @Positive(message = "MCP 服务 ID 必须为正数") Long serverId
    ) {
        return ApiResponse.success(mcpServerService.syncTools(serverId));
    }

    @GetMapping("/servers/{serverId}/tools")
    public ApiResponse<List<McpToolItem>> listTools(
            @PathVariable @Positive(message = "MCP 服务 ID 必须为正数") Long serverId
    ) {
        return ApiResponse.success(mcpServerService.tools(serverId));
    }

    @PutMapping("/tools/{toolId}/enabled")
    @PreAuthorize("hasAuthority('mcp:tool:update')")
    public ApiResponse<McpToolItem> updateToolEnabled(
            @PathVariable @Positive(message = "MCP 工具 ID 必须为正数") Long toolId,
            @Valid @RequestBody UpdateMcpToolEnabledRequest request
    ) {
        return ApiResponse.success(mcpServerService.updateToolEnabled(toolId, Boolean.TRUE.equals(request.enabled())));
    }

    @GetMapping("/calls")
    @PreAuthorize("hasAuthority('mcp:call:list')")
    public ApiResponse<List<McpToolCallLogItem>> callLogs(
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return ApiResponse.success(mcpServerService.callLogs(serverId, conversationId, status, limit, offset));
    }
}
