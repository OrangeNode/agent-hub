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
    private final AgentToolBindingMapper bindingMapper = mock(AgentToolBindingMapper.class);
    private final McpToolProvider provider = new McpToolProvider(
            serverMapper,
            toolMapper,
            callLogMapper,
            mcpClient,
            bindingMapper
    );

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeOnlyBoundEnabledMcpToolsWithNamespace() {
        McpServer github = server(1L, "github", true);
        McpServer gitlab = server(3L, "gitlab", true);
        McpTool githubTool = tool(2L, 1L, "search_repositories", true);
        McpTool gitlabTool = tool(4L, 3L, "get_project", true);
        when(bindingMapper.selectByAgentIdAndProviderType(1L, "MCP")).thenReturn(List.of(
                binding(10L, "mcp.gitlab.get_project", 4L, true, "MCP"),
                binding(11L, "mcp.github.search_repositories", 2L, true, "MCP"),
                binding(12L, "mcp.github.disabled", 5L, false, "MCP")
        ));
        when(toolMapper.selectById(4L)).thenReturn(gitlabTool);
        when(toolMapper.selectById(2L)).thenReturn(githubTool);
        when(serverMapper.selectById(3L)).thenReturn(gitlab);
        when(serverMapper.selectById(1L)).thenReturn(github);

        List<LlmToolDefinition> tools = provider.listTools(context());

        assertThat(tools).extracting(LlmToolDefinition::name)
                .containsExactly("mcp.gitlab.get_project", "mcp.github.search_repositories");
        assertThat(tools.get(1).description()).isEqualTo("搜索 GitHub 仓库");
        assertThat(tools.get(1).parameters()).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) tools.get(1).parameters().get("properties");
        assertThat(properties).containsKey("query");
    }

    @Test
    void shouldNotExposeMcpToolsWhenAgentIdMissing() {
        assertThat(provider.listTools(new ToolExecutionContext(
                7L,
                100L,
                200L,
                "official-writing",
                ToolEventSink.noop()
        ))).isEmpty();
        assertThat(provider.listTools(null)).isEmpty();
        verifyNoInteractions(bindingMapper, serverMapper, toolMapper);
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
        when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(binding());
        when(toolMapper.selectById(2L)).thenReturn(tool);
        when(serverMapper.selectById(1L)).thenReturn(server);
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
    void shouldNotExecuteUnboundTool() {
        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", Map.of("query", "codex")),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("当前 Agent 未授权使用工具: mcp.github.search_repositories");
        verify(bindingMapper).selectByAgentIdAndToolName(1L, "mcp.github.search_repositories");
        verifyNoInteractions(serverMapper, toolMapper, callLogMapper, mcpClient);
    }

    @Test
    void shouldNotAuthorizeRecreatedToolWithStaleBindingProviderRefId() {
        McpTool oldTool = tool(2L, 9L, "search_repositories", true);
        McpServer oldServer = server(9L, "deleted-github", true);
        when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(binding());
        when(toolMapper.selectById(2L)).thenReturn(oldTool);
        when(serverMapper.selectById(9L)).thenReturn(oldServer);

        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", Map.of("query", "codex")),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("当前 Agent 未授权使用工具: mcp.github.search_repositories");
        verifyNoInteractions(callLogMapper, mcpClient);
    }

    @Test
    void shouldReturnFailedResultAndLogWhenMcpClientFails() {
        McpServer server = server(true);
        McpTool tool = tool(true);
        ArgumentCaptor<McpToolCallLog> logCaptor = ArgumentCaptor.forClass(McpToolCallLog.class);
        when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(binding());
        when(toolMapper.selectById(2L)).thenReturn(tool);
        when(serverMapper.selectById(1L)).thenReturn(server);
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
        when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(binding());
        when(toolMapper.selectById(2L)).thenReturn(tool(true));
        when(serverMapper.selectById(1L)).thenReturn(server);
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
        when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(binding());
        when(toolMapper.selectById(2L)).thenReturn(tool);
        when(serverMapper.selectById(1L)).thenReturn(server);
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
        return new ToolExecutionContext(
                7L,
                100L,
                200L,
                "official-writing",
                1L,
                List.of(),
                ToolEventSink.noop()
        );
    }

    private McpServer server(boolean enabled) {
        return server(1L, "github", enabled);
    }

    private McpServer server(Long id, String serverKey, boolean enabled) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setServerKey(serverKey);
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
        return tool(2L, 1L, "search_repositories", enabled);
    }

    private McpTool tool(Long id, Long serverId, String toolName, boolean enabled) {
        McpTool tool = new McpTool();
        tool.setId(id);
        tool.setServerId(serverId);
        tool.setToolName(toolName);
        tool.setDisplayName("搜索仓库");
        tool.setDescription("搜索 GitHub 仓库");
        tool.setInputSchema("""
                {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                """);
        tool.setEnabled(enabled);
        return tool;
    }

    private AgentToolBinding binding() {
        return binding(10L, "mcp.github.search_repositories", 2L, true, "MCP");
    }

    private AgentToolBinding binding(Long id, String toolName, Long providerRefId, boolean enabled, String providerType) {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setId(id);
        binding.setAgentId(1L);
        binding.setToolName(toolName);
        binding.setProviderType(providerType);
        binding.setProviderRefId(providerRefId);
        binding.setEnabled(enabled);
        binding.setSort(1);
        return binding;
    }
}
