package com.agenthub.tool.mcp.client;

import java.util.Map;

public record McpToolDescriptor(
        String name,
        String displayName,
        String description,
        Map<String, Object> inputSchema
) {
}
