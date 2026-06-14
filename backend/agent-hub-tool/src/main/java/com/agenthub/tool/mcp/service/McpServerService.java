package com.agenthub.tool.mcp.service;

import com.agenthub.tool.mcp.command.CreateMcpServerCommand;
import com.agenthub.tool.mcp.command.UpdateMcpServerCommand;
import com.agenthub.tool.mcp.dto.McpConnectionTestResult;
import com.agenthub.tool.mcp.dto.McpServerItem;
import com.agenthub.tool.mcp.dto.McpToolCallLogItem;
import com.agenthub.tool.mcp.dto.McpToolItem;
import com.agenthub.tool.mcp.dto.McpToolSyncResult;
import java.util.List;

public interface McpServerService {

    List<McpServerItem> list();

    McpServerItem create(CreateMcpServerCommand command);

    McpServerItem update(Long serverId, UpdateMcpServerCommand command);

    void delete(Long serverId);

    McpConnectionTestResult testConnection(Long serverId);

    McpToolSyncResult syncTools(Long serverId);

    List<McpToolItem> tools(Long serverId);

    McpToolItem updateToolEnabled(Long toolId, boolean enabled);

    List<McpToolCallLogItem> callLogs(
            Long serverId,
            Long conversationId,
            String status,
            Integer limit,
            Integer offset
    );
}
