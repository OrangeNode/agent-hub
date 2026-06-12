package com.agenthub.tool.mcp.client;

import com.agenthub.tool.mcp.entity.McpServer;
import java.util.List;
import java.util.Map;

public interface McpClient {

    McpInitializeResult initialize(McpServer server);

    List<McpToolDescriptor> listTools(McpServer server);

    McpToolCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments);
}
