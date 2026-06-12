package com.agenthub.tool.mcp.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMcpServerRequest(
        @NotBlank(message = "MCP 服务标识不能为空")
        @Size(max = 64, message = "MCP 服务标识不能超过 64 个字符")
        String serverKey,

        @NotBlank(message = "MCP 服务名称不能为空")
        @Size(max = 128, message = "MCP 服务名称不能超过 128 个字符")
        String name,

        @Size(max = 512, message = "MCP 服务描述不能超过 512 个字符")
        String description,

        @NotBlank(message = "MCP 服务地址不能为空")
        @Size(max = 512, message = "MCP 服务地址不能超过 512 个字符")
        String endpointUrl,

        String transportType,
        String protocolVersion,
        String authType,
        String authToken,
        String extraHeaders,

        @Min(value = 1, message = "连接超时秒数必须为正数")
        @Max(value = 600, message = "连接超时秒数不能超过 600")
        Integer connectTimeoutSeconds,

        @Min(value = 1, message = "请求超时秒数必须为正数")
        @Max(value = 600, message = "请求超时秒数不能超过 600")
        Integer requestTimeoutSeconds,

        Boolean enabled,
        Integer sort
) {
}
