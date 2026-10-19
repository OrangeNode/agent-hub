package com.agenthub.tool.binding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.agent.service.AgentService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import com.agenthub.tool.binding.service.impl.AgentToolBindingServiceImpl;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;

class AgentToolBindingServiceImplTest {

    private final AgentToolBindingMapper bindingMapper = mock(AgentToolBindingMapper.class);
    private final AgentService agentService = mock(AgentService.class);
    private final McpServerMapper serverMapper = mock(McpServerMapper.class);
    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final AgentToolBindingService service = new AgentToolBindingServiceImpl(
            bindingMapper,
            agentService,
            serverMapper,
            toolMapper
    );

    @Test
    void shouldReplaceBindingsAndIgnoreDuplicateToolNames() {
        when(toolMapper.selectById(100L)).thenReturn(tool(100L, 10L, "search", true));
        when(toolMapper.selectById(101L)).thenReturn(tool(101L, 11L, "read", true));
        when(serverMapper.selectById(10L)).thenReturn(server(10L, "github", true, 2));
        when(serverMapper.selectById(11L)).thenReturn(server(11L, "gitlab", true, 1));

        service.updateBindings(7L, 1L, List.of(
                new AgentToolBindingCommand("mcp.github.search", "MCP", 100L, 2),
                new AgentToolBindingCommand("mcp.gitlab.read", "MCP", 101L, 1),
                new AgentToolBindingCommand("mcp.github.search", "MCP", 100L, 9)
        ));

        InOrder inOrder = inOrder(bindingMapper);
        inOrder.verify(bindingMapper).deleteByAgentIdAndProviderType(1L, "MCP");
        ArgumentCaptor<AgentToolBinding> captor = ArgumentCaptor.forClass(AgentToolBinding.class);
        verify(bindingMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getToolName)
                .containsExactly("mcp.github.search", "mcp.gitlab.read");
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getProviderRefId)
                .containsExactly(100L, 101L);
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getSort)
                .containsExactly(2, 1);
        assertThat(captor.getAllValues()).allSatisfy(binding -> {
            assertThat(binding.getAgentId()).isEqualTo(1L);
            assertThat(binding.getProviderType()).isEqualTo("MCP");
            assertThat(binding.getEnabled()).isTrue();
            assertThat(binding.getCreatedBy()).isEqualTo(7L);
            assertThat(binding.getUpdatedBy()).isEqualTo(7L);
            assertThat(binding.getCreatedAt()).isNotNull();
            assertThat(binding.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    void shouldRejectUnsupportedProviderType() {

        assertThatThrownBy(() -> service.updateBindings(7L, 1L, List.of(
                new AgentToolBindingCommand("builtin.echo", "BUILTIN", 100L, 0)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("暂不支持该工具来源")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(bindingMapper, never()).deleteByAgentIdAndProviderType(any(), any());
    }

    @Test
    void shouldRejectMismatchedToolName() {
        when(toolMapper.selectById(100L)).thenReturn(tool(100L, 10L, "search", true));
        when(serverMapper.selectById(10L)).thenReturn(server(10L, "github", true, 1));

        assertThatThrownBy(() -> service.updateBindings(7L, 1L, List.of(
                new AgentToolBindingCommand("mcp.gitlab.search", "MCP", 100L, 0)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("工具标识与 MCP 工具不匹配")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(bindingMapper, never()).deleteByAgentIdAndProviderType(any(), any());
    }

    @Test
    void shouldListBindingsWithServerAndToolEnabledFlags() {
        AgentToolBinding binding = binding(5L, 1L, "mcp.github.search", 100L, true, 3);
        when(bindingMapper.selectByAgentIdAndProviderType(1L, "MCP")).thenReturn(List.of(binding));
        when(toolMapper.selectById(100L)).thenReturn(tool(100L, 10L, "search", false));
        when(serverMapper.selectById(10L)).thenReturn(server(10L, "github", false, 1));

        List<AgentToolBindingItem> items = service.listBindings(1L);

        assertThat(items).hasSize(1);
        AgentToolBindingItem item = items.getFirst();
        assertThat(item.bindingId()).isEqualTo(5L);
        assertThat(item.agentId()).isEqualTo(1L);
        assertThat(item.toolName()).isEqualTo("mcp.github.search");
        assertThat(item.providerType()).isEqualTo("MCP");
        assertThat(item.providerRefId()).isEqualTo(100L);
        assertThat(item.serverKey()).isEqualTo("github");
        assertThat(item.serverName()).isEqualTo("GitHub");
        assertThat(item.toolDisplayName()).isEqualTo("搜索");
        assertThat(item.toolDescription()).isEqualTo("搜索仓库");
        assertThat(item.serverEnabled()).isFalse();
        assertThat(item.toolEnabled()).isFalse();
        assertThat(item.enabled()).isTrue();
        assertThat(item.sort()).isEqualTo(3);
    }

    @Test
    void shouldListMcpOptionsGroupedByServerAndToolOrder() {
        when(serverMapper.selectList(ArgumentMatchers.<Wrapper<McpServer>>any())).thenReturn(List.of(
                server(11L, "gitlab", false, 1),
                server(10L, "github", true, 2)
        ));
        when(toolMapper.selectList(ArgumentMatchers.<Wrapper<McpTool>>any())).thenReturn(
                List.of(tool(101L, 11L, "read", false)),
                List.of(tool(100L, 10L, "search", true), tool(102L, 10L, "write", false))
        );

        List<AgentToolOptionItem> options = service.listOptions(1L);

        assertThat(options).extracting(AgentToolOptionItem::toolName)
                .containsExactly("mcp.gitlab.read", "mcp.github.search", "mcp.github.write");
        assertThat(options).extracting(AgentToolOptionItem::providerRefId)
                .containsExactly(101L, 100L, 102L);
        assertThat(options).extracting(AgentToolOptionItem::serverEnabled)
                .containsExactly(false, true, true);
        assertThat(options).extracting(AgentToolOptionItem::toolEnabled)
                .containsExactly(false, true, false);
    }

    private AgentToolBinding binding(Long id, Long agentId, String toolName, Long providerRefId, boolean enabled, int sort) {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setId(id);
        binding.setAgentId(agentId);
        binding.setToolName(toolName);
        binding.setProviderType("MCP");
        binding.setProviderRefId(providerRefId);
        binding.setEnabled(enabled);
        binding.setSort(sort);
        return binding;
    }

    private McpServer server(Long id, String serverKey, boolean enabled, int sort) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setServerKey(serverKey);
        server.setName(serverKey.equals("github") ? "GitHub" : "GitLab");
        server.setEnabled(enabled);
        server.setSort(sort);
        return server;
    }

    private McpTool tool(Long id, Long serverId, String toolName, boolean enabled) {
        McpTool tool = new McpTool();
        tool.setId(id);
        tool.setServerId(serverId);
        tool.setToolName(toolName);
        tool.setDisplayName(toolName.equals("search") ? "搜索" : toolName);
        tool.setDescription(toolName.equals("search") ? "搜索仓库" : toolName + " 描述");
        tool.setEnabled(enabled);
        return tool;
    }
}
