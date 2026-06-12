package com.agenthub.tool.mcp.controller.request;

import jakarta.validation.constraints.NotNull;

public record UpdateMcpToolEnabledRequest(
        @NotNull(message = "MCP 工具启用状态不能为空")
        Boolean enabled
) {
}
