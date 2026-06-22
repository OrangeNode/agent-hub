package com.agenthub.tool.mcp.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.mcp.client.McpClient;
import com.agenthub.tool.mcp.client.McpInitializeResult;
import com.agenthub.tool.mcp.client.McpToolDescriptor;
import com.agenthub.tool.mcp.command.CreateMcpServerCommand;
import com.agenthub.tool.mcp.command.UpdateMcpServerCommand;
import com.agenthub.tool.mcp.dto.McpConnectionTestResult;
import com.agenthub.tool.mcp.dto.McpServerItem;
import com.agenthub.tool.mcp.dto.McpToolCallLogItem;
import com.agenthub.tool.mcp.dto.McpToolItem;
import com.agenthub.tool.mcp.dto.McpToolSyncResult;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.entity.McpToolCallLog;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolCallLogMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.agenthub.tool.mcp.service.McpServerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpServerServiceImpl implements McpServerService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_JSON = "{}";
    private static final String DEFAULT_TRANSPORT_TYPE = "STREAMABLE_HTTP";
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";
    private static final String DEFAULT_AUTH_TYPE = "NONE";
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final String SYNC_SUCCESS = "SUCCESS";
    private static final String SYNC_FAILED = "FAILED";

    private final McpServerMapper serverMapper;
    private final McpToolMapper toolMapper;
    private final McpToolCallLogMapper callLogMapper;
    private final McpClient mcpClient;

    public McpServerServiceImpl(
            McpServerMapper serverMapper,
            McpToolMapper toolMapper,
            McpToolCallLogMapper callLogMapper,
            McpClient mcpClient
    ) {
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
        this.callLogMapper = callLogMapper;
        this.mcpClient = mcpClient;
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServerItem> list() {
        return serverMapper.selectList(new LambdaQueryWrapper<McpServer>()
                        .orderByAsc(McpServer::getSort)
                        .orderByAsc(McpServer::getId))
                .stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    @Transactional
    public McpServerItem create(CreateMcpServerCommand command) {
        boolean existed = countValue(serverMapper.selectCount(new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getServerKey, command.serverKey()))) > 0;
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP 服务标识已存在");
        }

        McpServer server = new McpServer();
        server.setServerKey(command.serverKey());
        server.setName(command.name());
        server.setDescription(command.description());
        server.setEndpointUrl(command.endpointUrl());
        server.setTransportType(defaultText(command.transportType(), DEFAULT_TRANSPORT_TYPE));
        server.setProtocolVersion(defaultText(command.protocolVersion(), DEFAULT_PROTOCOL_VERSION));
        server.setAuthType(defaultText(command.authType(), DEFAULT_AUTH_TYPE));
        server.setAuthTokenCipher(encryptToken(command.authToken()));
        server.setExtraHeaders(defaultJson(command.extraHeaders()));
        server.setConnectTimeoutSeconds(defaultPositive(command.connectTimeoutSeconds(), DEFAULT_CONNECT_TIMEOUT_SECONDS));
        server.setRequestTimeoutSeconds(defaultPositive(command.requestTimeoutSeconds(), DEFAULT_REQUEST_TIMEOUT_SECONDS));
        server.setEnabled(defaultEnabled(command.enabled()));
        server.setSort(defaultSort(command.sort()));
        serverMapper.insert(server);

        return toItem(server);
    }

    @Override
    @Transactional
    public McpServerItem update(Long serverId, UpdateMcpServerCommand command) {
        McpServer existed = requireServer(serverId);

        McpServer updated = new McpServer();
        updated.setId(existed.getId());
        updated.setServerKey(existed.getServerKey());
        updated.setName(command.name());
        updated.setDescription(command.description());
        updated.setEndpointUrl(command.endpointUrl());
        updated.setTransportType(defaultText(command.transportType(), DEFAULT_TRANSPORT_TYPE));
        updated.setProtocolVersion(defaultText(command.protocolVersion(), DEFAULT_PROTOCOL_VERSION));
        updated.setAuthType(defaultText(command.authType(), DEFAULT_AUTH_TYPE));
        updated.setAuthTokenCipher(hasText(command.authToken())
                ? encryptToken(command.authToken())
                : existed.getAuthTokenCipher());
        updated.setExtraHeaders(defaultJson(command.extraHeaders()));
        updated.setConnectTimeoutSeconds(defaultPositive(command.connectTimeoutSeconds(), DEFAULT_CONNECT_TIMEOUT_SECONDS));
        updated.setRequestTimeoutSeconds(defaultPositive(command.requestTimeoutSeconds(), DEFAULT_REQUEST_TIMEOUT_SECONDS));
        updated.setEnabled(defaultEnabled(command.enabled()));
        updated.setLastSyncStatus(existed.getLastSyncStatus());
        updated.setLastSyncMessage(existed.getLastSyncMessage());
        updated.setLastSyncedAt(existed.getLastSyncedAt());
        updated.setSort(defaultSort(command.sort()));
        serverMapper.updateById(updated);

        return toItem(updated);
    }

    @Override
    @Transactional
    public void delete(Long serverId) {
        requireServer(serverId);
        callLogMapper.delete(new LambdaQueryWrapper<McpToolCallLog>()
                .eq(McpToolCallLog::getServerId, serverId));
        toolMapper.delete(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId));
        serverMapper.deleteById((Serializable) serverId);
    }

    @Override
    @Transactional(readOnly = true)
    public McpConnectionTestResult testConnection(Long serverId) {
        McpServer server = requireServer(serverId);
        try {
            McpInitializeResult result = mcpClient.initialize(server);
            boolean supportsTools = result.capabilities() != null && result.capabilities().containsKey("tools");
            return new McpConnectionTestResult(
                    supportsTools,
                    result.protocolVersion(),
                    serverName(result.serverInfo()),
                    supportsTools ? "MCP 服务连接成功" : "MCP 服务未声明 tools 能力"
            );
        } catch (Exception exception) {
            return new McpConnectionTestResult(false, null, null, exceptionMessage(exception, "MCP 服务连接失败"));
        }
    }

    @Override
    @Transactional
    public McpToolSyncResult syncTools(Long serverId) {
        McpServer server = requireServer(serverId);
        try {
            List<McpToolDescriptor> descriptors = mcpClient.listTools(server);
            int syncedCount = 0;
            for (McpToolDescriptor descriptor : descriptors) {
                if (descriptor == null || !hasText(descriptor.name())) {
                    continue;
                }
                upsertTool(server, descriptor);
                syncedCount++;
            }
            updateSyncStatus(server, SYNC_SUCCESS, "同步成功，共 " + syncedCount + " 个工具");
            return new McpToolSyncResult(true, syncedCount, "同步成功");
        } catch (Exception exception) {
            String message = exceptionMessage(exception, "同步 MCP 工具失败");
            updateSyncStatus(server, SYNC_FAILED, message);
            return new McpToolSyncResult(false, 0, message);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpToolItem> tools(Long serverId) {
        McpServer server = requireServer(serverId);
        return toolMapper.selectList(new LambdaQueryWrapper<McpTool>()
                        .eq(McpTool::getServerId, serverId)
                        .orderByAsc(McpTool::getServerId)
                        .orderByDesc(McpTool::getEnabled)
                        .orderByAsc(McpTool::getToolName))
                .stream()
                .map(tool -> toToolItem(tool, server.getServerKey()))
                .toList();
    }

    @Override
    @Transactional
    public McpToolItem updateToolEnabled(Long toolId, boolean enabled) {
        McpTool tool = requireTool(toolId);
        McpServer server = requireServer(tool.getServerId());
        tool.setEnabled(enabled);
        toolMapper.updateById(tool);
        return toToolItem(tool, server.getServerKey());
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpToolCallLogItem> callLogs(
            Long serverId,
            Long conversationId,
            String status,
            Integer limit,
            Integer offset
    ) {
        if (serverId != null) {
            requireServer(serverId);
        }
        LambdaQueryWrapper<McpToolCallLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(serverId != null, McpToolCallLog::getServerId, serverId)
                .eq(conversationId != null, McpToolCallLog::getConversationId, conversationId)
                .eq(hasText(status), McpToolCallLog::getStatus, status)
                .orderByDesc(McpToolCallLog::getStartedAt)
                .orderByDesc(McpToolCallLog::getId)
                .last("LIMIT " + normalizeLimit(limit) + " OFFSET " + normalizeOffset(offset));

        Map<Long, String> serverKeys = new HashMap<>();
        return callLogMapper.selectList(wrapper).stream()
                .map(log -> toCallLogItem(log, serverKeys))
                .toList();
    }

    private void upsertTool(McpServer server, McpToolDescriptor descriptor) {
        McpTool tool = toolMapper.selectOne(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, server.getId())
                .eq(McpTool::getToolName, descriptor.name()));
        boolean existed = tool != null;
        if (!existed) {
            tool = new McpTool();
            tool.setServerId(server.getId());
            tool.setToolName(descriptor.name());
            tool.setEnabled(true);
        }
        tool.setDisplayName(defaultText(descriptor.displayName(), descriptor.name()));
        tool.setDescription(descriptor.description());
        tool.setInputSchema(serializeSchema(descriptor.inputSchema()));
        tool.setLastSeenAt(OffsetDateTime.now());
        if (existed) {
            toolMapper.updateById(tool);
        } else {
            toolMapper.insert(tool);
        }
    }

    private void updateSyncStatus(McpServer server, String status, String message) {
        McpServer updated = new McpServer();
        updated.setId(server.getId());
        updated.setLastSyncStatus(status);
        updated.setLastSyncMessage(message);
        updated.setLastSyncedAt(OffsetDateTime.now());
        serverMapper.updateById(updated);
    }

    private McpServer requireServer(Long serverId) {
        McpServer server = serverMapper.selectById(serverId);
        if (server == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP 服务不存在");
        }
        return server;
    }

    private McpTool requireTool(Long toolId) {
        McpTool tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP 工具不存在");
        }
        return tool;
    }

    private McpServerItem toItem(McpServer server) {
        return new McpServerItem(
                server.getId(),
                server.getServerKey(),
                server.getName(),
                server.getDescription(),
                server.getEndpointUrl(),
                server.getTransportType(),
                server.getProtocolVersion(),
                server.getAuthType(),
                hasText(server.getAuthTokenCipher()),
                server.getExtraHeaders(),
                server.getConnectTimeoutSeconds(),
                server.getRequestTimeoutSeconds(),
                Boolean.TRUE.equals(server.getEnabled()),
                server.getLastSyncStatus(),
                server.getLastSyncMessage(),
                server.getLastSyncedAt(),
                countTools(server.getId()),
                server.getSort() == null ? 0 : server.getSort()
        );
    }

    private McpToolItem toToolItem(McpTool tool, String serverKey) {
        return new McpToolItem(
                tool.getId(),
                tool.getServerId(),
                serverKey,
                tool.getToolName(),
                exposedToolName(serverKey, tool.getToolName()),
                tool.getDisplayName(),
                tool.getDescription(),
                tool.getInputSchema(),
                Boolean.TRUE.equals(tool.getEnabled()),
                tool.getLastSeenAt()
        );
    }

    private McpToolCallLogItem toCallLogItem(McpToolCallLog log, Map<Long, String> serverKeys) {
        String serverKey = serverKeys.computeIfAbsent(log.getServerId(), this::findServerKey);
        return new McpToolCallLogItem(
                log.getId(),
                serverKey,
                log.getExposedToolName(),
                log.getRemoteToolName(),
                log.getConversationId(),
                log.getUserId(),
                log.getStatus(),
                log.getErrorMessage(),
                log.getDurationMs(),
                log.getStartedAt()
        );
    }

    private String findServerKey(Long serverId) {
        if (serverId == null) {
            return null;
        }
        McpServer server = serverMapper.selectById(serverId);
        return server == null ? null : server.getServerKey();
    }

    private int countTools(Long serverId) {
        if (serverId == null) {
            return 0;
        }
        Long count = toolMapper.selectCount(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getServerId, serverId));
        return Math.toIntExact(countValue(count));
    }

    private String serializeSchema(Map<String, Object> schema) {
        try {
            Map<String, Object> safeSchema = schema == null || schema.isEmpty()
                    ? Map.of("type", "object")
                    : schema;
            return OBJECT_MAPPER.writeValueAsString(safeSchema);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 工具参数结构序列化失败");
        }
    }

    private String exposedToolName(String serverKey, String toolName) {
        return "mcp." + serverKey + "." + toolName;
    }

    private String serverName(Map<String, Object> serverInfo) {
        if (serverInfo == null) {
            return null;
        }
        Object name = serverInfo.get("name");
        return name == null ? null : String.valueOf(name);
    }

    private String encryptToken(String token) {
        return token;
    }

    private String defaultJson(String value) {
        return hasText(value) ? value : DEFAULT_JSON;
    }

    private String defaultText(String value, String defaultValue) {
        return hasText(value) ? value : defaultValue;
    }

    private int defaultPositive(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private boolean defaultEnabled(Boolean enabled) {
        return enabled == null || enabled;
    }

    private int defaultSort(Integer sort) {
        return sort == null ? 0 : sort;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    private long countValue(Long count) {
        return count == null ? 0 : count;
    }

    private String exceptionMessage(Exception exception, String defaultMessage) {
        return hasText(exception.getMessage()) ? exception.getMessage() : defaultMessage;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
