package com.agenthub.tool.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import com.agenthub.tool.mcp.client.McpClient;
import com.agenthub.tool.mcp.client.McpInitializeResult;
import com.agenthub.tool.mcp.client.McpToolDescriptor;
import com.agenthub.tool.mcp.command.CreateMcpServerCommand;
import com.agenthub.tool.mcp.command.UpdateMcpServerCommand;
import com.agenthub.tool.mcp.dto.McpConnectionTestResult;
import com.agenthub.tool.mcp.dto.McpServerItem;
import com.agenthub.tool.mcp.dto.McpToolSyncResult;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolCallLogMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.agenthub.tool.mcp.service.impl.McpServerServiceImpl;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class McpServerServiceImplTest {

    @Test
    void shouldCreateServerWithUniqueKey() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpServerServiceImpl service = service(serverMapper);
        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        when(serverMapper.selectCount(any())).thenReturn(0L);
        when(serverMapper.insert(serverCaptor.capture())).thenAnswer(invocation -> {
            serverCaptor.getValue().setId(1L);
            return 1;
        });

        McpServerItem item = service.create(createCommand());

        assertThat(item.serverId()).isEqualTo(1L);
        assertThat(item.serverKey()).isEqualTo("github");
        assertThat(item.hasAuthToken()).isTrue();
        assertThat(serverCaptor.getValue().getExtraHeaders()).isEqualTo("{}");
    }

    @Test
    void shouldRejectDuplicateServerKey() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpServerServiceImpl service = service(serverMapper);
        when(serverMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createCommand()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("MCP 服务标识已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldKeepExistingTokenWhenUpdateTokenBlank() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpServerServiceImpl service = service(serverMapper);
        McpServer existed = server(1L);
        existed.setAuthTokenCipher("old-token");
        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        when(serverMapper.selectById(1L)).thenReturn(existed);
        when(serverMapper.updateById(serverCaptor.capture())).thenReturn(1);

        McpServerItem item = service.update(1L, new UpdateMcpServerCommand(
                "GitHub 新服务",
                "更新描述",
                "https://mcp.example.com/new",
                "STREAMABLE_HTTP",
                "2025-11-25",
                "BEARER",
                "   ",
                null,
                20,
                90,
                true,
                10
        ));

        assertThat(serverCaptor.getValue().getServerKey()).isEqualTo("github");
        assertThat(serverCaptor.getValue().getAuthTokenCipher()).isEqualTo("old-token");
        assertThat(serverCaptor.getValue().getExtraHeaders()).isEqualTo("{}");
        assertThat(item.hasAuthToken()).isTrue();
    }

    @Test
    void shouldTestConnectionByInitializeAndToolsCapability() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpClient mcpClient = mock(McpClient.class);
        McpServerServiceImpl service = service(serverMapper, mock(McpToolMapper.class),
                mock(McpToolCallLogMapper.class), mcpClient);
        McpServer server = server(1L);
        when(serverMapper.selectById(1L)).thenReturn(server);
        when(mcpClient.initialize(server)).thenReturn(new McpInitializeResult(
                "2025-11-25",
                Map.of("tools", Map.of()),
                Map.of("name", "demo")
        ));

        McpConnectionTestResult result = service.testConnection(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(result.serverName()).isEqualTo("demo");
        assertThat(result.message()).isEqualTo("MCP 服务连接成功");
    }

    @Test
    void shouldSyncToolsByUpsertingDescriptors() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpToolMapper toolMapper = mock(McpToolMapper.class);
        McpClient mcpClient = mock(McpClient.class);
        McpServerServiceImpl service = service(serverMapper, toolMapper, mock(McpToolCallLogMapper.class), mcpClient);
        McpServer server = server(1L);
        ArgumentCaptor<McpTool> toolCaptor = ArgumentCaptor.forClass(McpTool.class);
        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        when(serverMapper.selectById(1L)).thenReturn(server);
        when(mcpClient.listTools(server)).thenReturn(List.of(new McpToolDescriptor(
                "search",
                "搜索",
                "搜索仓库",
                Map.of("type", "object")
        )));
        when(toolMapper.selectOne(any())).thenReturn(null);
        when(toolMapper.insert(toolCaptor.capture())).thenReturn(1);
        when(serverMapper.updateById(serverCaptor.capture())).thenReturn(1);

        McpToolSyncResult result = service.syncTools(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.syncedCount()).isEqualTo(1);
        assertThat(toolCaptor.getValue().getServerId()).isEqualTo(1L);
        assertThat(toolCaptor.getValue().getToolName()).isEqualTo("search");
        assertThat(toolCaptor.getValue().getInputSchema()).contains("\"type\":\"object\"");
        assertThat(serverCaptor.getValue().getLastSyncStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldMarkSyncFailedWhenClientThrows() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpClient mcpClient = mock(McpClient.class);
        McpServerServiceImpl service = service(serverMapper, mock(McpToolMapper.class),
                mock(McpToolCallLogMapper.class), mcpClient);
        McpServer server = server(1L);
        ArgumentCaptor<McpServer> serverCaptor = ArgumentCaptor.forClass(McpServer.class);
        when(serverMapper.selectById(1L)).thenReturn(server);
        when(mcpClient.listTools(server)).thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "认证失败"));
        when(serverMapper.updateById(serverCaptor.capture())).thenReturn(1);

        McpToolSyncResult result = service.syncTools(1L);

        assertThat(result.success()).isFalse();
        assertThat(result.syncedCount()).isZero();
        assertThat(result.message()).isEqualTo("认证失败");
        assertThat(serverCaptor.getValue().getLastSyncStatus()).isEqualTo("FAILED");
        assertThat(serverCaptor.getValue().getLastSyncMessage()).isEqualTo("认证失败");
    }

    @Test
    void shouldDeleteCallLogsAndToolsBeforeServer() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpToolMapper toolMapper = mock(McpToolMapper.class);
        McpToolCallLogMapper callLogMapper = mock(McpToolCallLogMapper.class);
        AgentToolBindingMapper bindingMapper = mock(AgentToolBindingMapper.class);
        McpServerServiceImpl service = service(serverMapper, toolMapper, callLogMapper, mock(McpClient.class),
                bindingMapper);
        when(serverMapper.selectById(1L)).thenReturn(server(1L));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(2L, 1L)));

        service.delete(1L);

        InOrder inOrder = inOrder(callLogMapper, bindingMapper, toolMapper, serverMapper);
        inOrder.verify(callLogMapper).delete(any());
        inOrder.verify(bindingMapper).delete(any());
        inOrder.verify(toolMapper).delete(any());
        inOrder.verify(serverMapper).deleteById((Serializable) 1L);
    }

    private McpServerServiceImpl service(McpServerMapper serverMapper) {
        return service(serverMapper, mock(McpToolMapper.class), mock(McpToolCallLogMapper.class), mock(McpClient.class));
    }

    private McpServerServiceImpl service(
            McpServerMapper serverMapper,
            McpToolMapper toolMapper,
            McpToolCallLogMapper callLogMapper,
            McpClient mcpClient
    ) {
        return service(serverMapper, toolMapper, callLogMapper, mcpClient, mock(AgentToolBindingMapper.class));
    }

    private McpServerServiceImpl service(
            McpServerMapper serverMapper,
            McpToolMapper toolMapper,
            McpToolCallLogMapper callLogMapper,
            McpClient mcpClient,
            AgentToolBindingMapper bindingMapper
    ) {
        return new McpServerServiceImpl(serverMapper, toolMapper, callLogMapper, mcpClient, bindingMapper);
    }

    private CreateMcpServerCommand createCommand() {
        return new CreateMcpServerCommand(
                "github",
                "GitHub",
                "GitHub MCP 服务",
                "https://mcp.example.com/mcp",
                "STREAMABLE_HTTP",
                "2025-11-25",
                "BEARER",
                "token",
                null,
                10,
                60,
                true,
                0
        );
    }

    private McpServer server(Long id) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setServerKey("github");
        server.setName("GitHub");
        server.setDescription("GitHub MCP 服务");
        server.setEndpointUrl("https://mcp.example.com/mcp");
        server.setTransportType("STREAMABLE_HTTP");
        server.setProtocolVersion("2025-11-25");
        server.setAuthType("BEARER");
        server.setAuthTokenCipher("token");
        server.setExtraHeaders("{}");
        server.setConnectTimeoutSeconds(10);
        server.setRequestTimeoutSeconds(60);
        server.setEnabled(true);
        server.setSort(0);
        return server;
    }

    private McpTool tool(Long id, Long serverId) {
        McpTool tool = new McpTool();
        tool.setId(id);
        tool.setServerId(serverId);
        tool.setToolName("search");
        return tool;
    }
}
