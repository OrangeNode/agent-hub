package com.agenthub.tool.mcp.client;

import java.util.Map;

public record McpInitializeResult(
        String protocolVersion,
        Map<String, Object> capabilities,
        Map<String, Object> serverInfo
) {
}
