package com.agenthub.tool.mcp;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import com.agenthub.tool.mcp.client.McpClient;
import com.agenthub.tool.mcp.client.McpToolCallResult;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.entity.McpToolCallLog;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolCallLogMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpToolProvider implements ToolProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String PREFIX = "mcp.";
    private static final int EXCERPT_MAX_LENGTH = 8192;
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String PROVIDER_TYPE_MCP = "MCP";
    private static final Map<String, Object> DEFAULT_PARAMETERS = Map.of("type", "object");

    private final McpServerMapper serverMapper;
    private final McpToolMapper toolMapper;
    private final McpToolCallLogMapper callLogMapper;
    private final McpClient mcpClient;
    private final AgentToolBindingMapper bindingMapper;

    public McpToolProvider(
            McpServerMapper serverMapper,
            McpToolMapper toolMapper,
            McpToolCallLogMapper callLogMapper,
            McpClient mcpClient,
            AgentToolBindingMapper bindingMapper
    ) {
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
        this.callLogMapper = callLogMapper;
        this.mcpClient = mcpClient;
        this.bindingMapper = bindingMapper;
    }

    @Override
    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        if (context == null || context.agentId() == null) {
            return List.of();
        }
        List<LlmToolDefinition> definitions = new ArrayList<>();
        List<AgentToolBinding> bindings = bindingMapper.selectByAgentIdAndProviderType(
                context.agentId(),
                PROVIDER_TYPE_MCP
        );
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        for (AgentToolBinding binding : bindings) {
            if (binding == null || !Boolean.TRUE.equals(binding.getEnabled())
                    || !PROVIDER_TYPE_MCP.equals(binding.getProviderType())) {
                continue;
            }
            McpTool tool = binding.getProviderRefId() == null ? null : toolMapper.selectById(binding.getProviderRefId());
            if (tool == null || !Boolean.TRUE.equals(tool.getEnabled())) {
                continue;
            }
            McpServer server = tool.getServerId() == null ? null : serverMapper.selectById(tool.getServerId());
            if (server == null || !Boolean.TRUE.equals(server.getEnabled())) {
                continue;
            }
            definitions.add(new LlmToolDefinition(
                    exposedToolName(server.getServerKey(), tool.getToolName()),
                    defaultText(tool.getDescription(), tool.getDisplayName()),
                    parseParameters(tool.getInputSchema())
            ));
        }
        return definitions;
    }

    @Override
    public boolean supports(String toolName) {
        if (!hasText(toolName) || !toolName.startsWith(PREFIX)) {
            return false;
        }
        int secondDot = toolName.indexOf('.', PREFIX.length());
        return secondDot > PREFIX.length() && secondDot < toolName.length() - 1;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        ParsedToolName parsed = parseToolName(request.toolName());
        if (parsed == null) {
            return ToolExecutionResult.failed("不支持的工具: " + request.toolName());
        }

        if (!isAuthorized(context, request.toolName())) {
            return ToolExecutionResult.failed("当前 Agent 未授权使用工具: " + request.toolName());
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        long startedNanos = System.nanoTime();
        McpServer server = null;
        McpTool tool = null;
        try {
            server = findServer(parsed.serverKey());
            if (server == null) {
                return ToolExecutionResult.failed("MCP 服务不存在: " + parsed.serverKey());
            }
            if (!Boolean.TRUE.equals(server.getEnabled())) {
                return failedWithLog(request, context, parsed, server, null, "MCP 服务未启用: " + parsed.serverKey(),
                        startedAt, startedNanos);
            }

            tool = findTool(server.getId(), parsed.remoteToolName());
            if (tool == null) {
                return failedWithLog(request, context, parsed, server, null,
                        "MCP 工具不存在: " + request.toolName(), startedAt, startedNanos);
            }
            if (!Boolean.TRUE.equals(tool.getEnabled())) {
                return failedWithLog(request, context, parsed, server, tool,
                        "MCP 工具未启用: " + request.toolName(), startedAt, startedNanos);
            }

            McpToolCallResult callResult = mcpClient.callTool(server, parsed.remoteToolName(), request.arguments());
            String content = callResult == null ? "" : defaultString(callResult.content());
            if (callResult != null && callResult.error()) {
                writeLog(request, context, parsed, server, tool, STATUS_FAILED, content, content, startedAt, startedNanos);
                return ToolExecutionResult.failed(content);
            }
            writeLog(request, context, parsed, server, tool, STATUS_COMPLETED, content, null, startedAt, startedNanos);
            return ToolExecutionResult.completed(content);
        } catch (Exception exception) {
            String message = exceptionMessage(exception);
            if (server != null) {
                writeLog(request, context, parsed, server, tool, STATUS_FAILED, null, message, startedAt, startedNanos);
            }
            return ToolExecutionResult.failed(message);
        }
    }

    private ToolExecutionResult failedWithLog(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            ParsedToolName parsed,
            McpServer server,
            McpTool tool,
            String message,
            OffsetDateTime startedAt,
            long startedNanos
    ) {
        writeLog(request, context, parsed, server, tool, STATUS_FAILED, null, message, startedAt, startedNanos);
        return ToolExecutionResult.failed(message);
    }

    private McpServer findServer(String serverKey) {
        return serverMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getServerKey, serverKey));
    }

    private McpTool findTool(Long serverId, String remoteToolName) {
        return toolMapper.selectOne(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId)
                .eq(McpTool::getToolName, remoteToolName));
    }

    private boolean isAuthorized(ToolExecutionContext context, String toolName) {
        if (context == null || context.agentId() == null) {
            return false;
        }
        AgentToolBinding binding = bindingMapper.selectByAgentIdAndToolName(context.agentId(), toolName);
        return binding != null
                && Boolean.TRUE.equals(binding.getEnabled())
                && PROVIDER_TYPE_MCP.equals(binding.getProviderType());
    }

    private void writeLog(
            ToolExecutionRequest request,
            ToolExecutionContext context,
            ParsedToolName parsed,
            McpServer server,
            McpTool tool,
            String status,
            String result,
            String errorMessage,
            OffsetDateTime startedAt,
            long startedNanos
    ) {
        McpToolCallLog log = new McpToolCallLog();
        log.setServerId(server.getId());
        log.setToolId(tool == null ? null : tool.getId());
        log.setConversationId(context.conversationId());
        log.setUserId(context.userId());
        log.setMessageId(context.assistantMessageId());
        log.setToolCallId(request.toolCallId());
        log.setExposedToolName(request.toolName());
        log.setRemoteToolName(parsed.remoteToolName());
        log.setArgumentsExcerpt(excerpt(toJson(request.arguments())));
        log.setResultExcerpt(excerpt(result));
        log.setStatus(status);
        log.setErrorMessage(excerpt(errorMessage));
        log.setDurationMs(Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
        log.setStartedAt(startedAt);
        log.setCompletedAt(OffsetDateTime.now());
        callLogMapper.insert(log);
    }

    private ParsedToolName parseToolName(String toolName) {
        if (!supports(toolName)) {
            return null;
        }
        int secondDot = toolName.indexOf('.', PREFIX.length());
        String serverKey = toolName.substring(PREFIX.length(), secondDot);
        String remoteToolName = toolName.substring(secondDot + 1);
        return new ParsedToolName(serverKey, remoteToolName);
    }

    private Map<String, Object> parseParameters(String inputSchema) {
        if (!hasText(inputSchema)) {
            return DEFAULT_PARAMETERS;
        }
        try {
            Map<String, Object> parameters = OBJECT_MAPPER.readValue(inputSchema, MAP_TYPE);
            return parameters == null || parameters.isEmpty() ? DEFAULT_PARAMETERS : parameters;
        } catch (JsonProcessingException exception) {
            return DEFAULT_PARAMETERS;
        }
    }

    private String toJson(Map<String, Object> arguments) {
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException exception) {
            return String.valueOf(arguments);
        }
    }

    private String exposedToolName(String serverKey, String toolName) {
        return PREFIX + serverKey + "." + toolName;
    }

    private String excerpt(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= EXCERPT_MAX_LENGTH ? value : value.substring(0, EXCERPT_MAX_LENGTH);
    }

    private String defaultText(String value, String defaultValue) {
        return hasText(value) ? value : defaultString(defaultValue);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String exceptionMessage(Exception exception) {
        return hasText(exception.getMessage()) ? exception.getMessage() : "MCP 工具调用失败";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ParsedToolName(String serverKey, String remoteToolName) {
    }
}
