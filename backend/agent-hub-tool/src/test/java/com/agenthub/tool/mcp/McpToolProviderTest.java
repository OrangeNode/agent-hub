package com.agenthub.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.mcp.client.McpClient;
import com.agenthub.tool.mcp.client.McpToolCallResult;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.entity.McpToolCallLog;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolCallLogMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.agenthub.tool.runtime.ToolEventSink;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class McpToolProviderTest {

    private final McpServerMapper serverMapper = mock(McpServerMapper.class);
    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final McpToolCallLogMapper callLogMapper = mock(McpToolCallLogMapper.class);
    private final McpClient mcpClient = mock(McpClient.class);
    private final McpToolProvider provider = new McpToolProvider(
            serverMapper,
            toolMapper,
            callLogMapper,
            mcpClient
    );

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeEnabledMcpToolsWithNamespace() {
        McpServer server = server(true);
        McpTool tool = tool(true);
        when(serverMapper.selectList(any())).thenReturn(List.of(server));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool));

        List<LlmToolDefinition> tools = provider.listTools(context());

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo("mcp.github.search_repositories");
        assertThat(tools.getFirst().description()).isEqualTo("搜索 GitHub 仓库");
        assertThat(tools.getFirst().parameters()).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) tools.getFirst().parameters().get("properties");
        assertThat(properties).containsKey("query");
    }

    @Test
    void shouldSupportOnlyMcpNamespacedTools() {
        assertThat(provider.supports("mcp.github.search")).isTrue();
        assertThat(provider.supports("mcp.github.search.extra")).isTrue();
        assertThat(provider.supports("sandbox.exec")).isFalse();
        assertThat(provider.supports("mcp.github")).isFalse();
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    void shouldExecuteRemoteToolAndWriteCallLog() {
        McpServer server = server(true);
        McpTool tool = tool(true);
        Map<String, Object> arguments = Map.of("query", "codex");
        ArgumentCaptor<McpToolCallLog> logCaptor = ArgumentCaptor.forClass(McpToolCallLog.class);
        when(serverMapper.selectOne(any())).thenReturn(server);
        when(toolMapper.selectOne(any())).thenReturn(tool);
        when(mcpClient.callTool(server, "search_repositories", arguments))
                .thenReturn(new McpToolCallResult(false, "ok"));
        when(callLogMapper.insert(logCaptor.capture())).thenReturn(1);

        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", arguments),
                context()
        );

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.content()).isEqualTo("ok");
        verify(mcpClient).callTool(server, "search_repositories", arguments);
        McpToolCallLog log = logCaptor.getValue();
        assertThat(log.getServerId()).isEqualTo(1L);
        assertThat(log.getToolId()).isEqualTo(2L);
        assertThat(log.getConversationId()).isEqualTo(100L);
        assertThat(log.getUserId()).isEqualTo(7L);
        assertThat(log.getMessageId()).isEqualTo(200L);
        assertThat(log.getToolCallId()).isEqualTo("call-1");
        assertThat(log.getExposedToolName()).isEqualTo("mcp.github.search_repositories");
        assertThat(log.getRemoteToolName()).isEqualTo("search_repositories");
        assertThat(log.getArgumentsExcerpt()).contains("\"query\":\"codex\"");
        assertThat(log.getResultExcerpt()).isEqualTo("ok");
        assertThat(log.getStatus()).isEqualTo("COMPLETED");
        assertThat(log.getErrorMessage()).isNull();
        assertThat(log.getDurationMs()).isNotNegative();
        assertThat(log.getStartedAt()).isNotNull();
        assertThat(log.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldReturnFailedResultAndLogWhenMcpClientFails() {
        McpServer server = server(true);
        McpTool tool = tool(true);
        ArgumentCaptor<McpToolCallLog> logCaptor = ArgumentCaptor.forClass(McpToolCallLog.class);
        when(serverMapper.selectOne(any())).thenReturn(server);
        when(toolMapper.selectOne(any())).thenReturn(tool);
        when(mcpClient.callTool(eq(server), eq("search_repositories"), any()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "认证失败"));
        when(callLogMapper.insert(logCaptor.capture())).thenReturn(1);

        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", Map.of("query", "codex")),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("认证失败");
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(logCaptor.getValue().getErrorMessage()).isEqualTo("认证失败");
    }

    @Test
    void shouldReturnFailedResultAndLogWhenServerDisabled() {
        McpServer server = server(false);
        ArgumentCaptor<McpToolCallLog> logCaptor = ArgumentCaptor.forClass(McpToolCallLog.class);
        when(serverMapper.selectOne(any())).thenReturn(server);
        when(callLogMapper.insert(logCaptor.capture())).thenReturn(1);

        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", Map.of("query", "codex")),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("MCP 服务未启用: github");
        assertThat(logCaptor.getValue().getServerId()).isEqualTo(1L);
        assertThat(logCaptor.getValue().getToolId()).isNull();
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("FAILED");
        verifyNoInteractions(mcpClient);
    }

    @Test
    void shouldReturnFailedResultAndLogWhenToolDisabled() {
        McpServer server = server(true);
        McpTool tool = tool(false);
        ArgumentCaptor<McpToolCallLog> logCaptor = ArgumentCaptor.forClass(McpToolCallLog.class);
        when(serverMapper.selectOne(any())).thenReturn(server);
        when(toolMapper.selectOne(any())).thenReturn(tool);
        when(callLogMapper.insert(logCaptor.capture())).thenReturn(1);

        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", Map.of("query", "codex")),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("MCP 工具未启用: mcp.github.search_repositories");
        assertThat(logCaptor.getValue().getServerId()).isEqualTo(1L);
        assertThat(logCaptor.getValue().getToolId()).isEqualTo(2L);
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("FAILED");
        verifyNoInteractions(mcpClient);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, 100L, 200L, "official-writing", ToolEventSink.noop());
    }

    private McpServer server(boolean enabled) {
        McpServer server = new McpServer();
        server.setId(1L);
        server.setServerKey("github");
        server.setName("GitHub");
        server.setEndpointUrl("https://mcp.example.com/mcp");
        server.setTransportType("STREAMABLE_HTTP");
        server.setProtocolVersion("2025-11-25");
        server.setAuthType("BEARER");
        server.setAuthTokenCipher("token");
        server.setExtraHeaders("{}");
        server.setConnectTimeoutSeconds(10);
        server.setRequestTimeoutSeconds(60);
        server.setEnabled(enabled);
        return server;
    }

    private McpTool tool(boolean enabled) {
        McpTool tool = new McpTool();
        tool.setId(2L);
        tool.setServerId(1L);
        tool.setToolName("search_repositories");
        tool.setDisplayName("搜索仓库");
        tool.setDescription("搜索 GitHub 仓库");
        tool.setInputSchema("""
                {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                """);
        tool.setEnabled(enabled);
        return tool;
    }
}
