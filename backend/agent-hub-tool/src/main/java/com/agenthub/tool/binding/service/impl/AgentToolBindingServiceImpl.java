package com.agenthub.tool.binding.service.impl;

import com.agenthub.agent.entity.AgentConfig;
import com.agenthub.agent.mapper.AgentConfigMapper;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import com.agenthub.tool.binding.service.AgentToolBindingService;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentToolBindingServiceImpl implements AgentToolBindingService {

    private static final String PROVIDER_TYPE_MCP = "MCP";
    private static final String MCP_PREFIX = "mcp.";

    private final AgentToolBindingMapper bindingMapper;
    private final AgentConfigMapper agentMapper;
    private final McpServerMapper serverMapper;
    private final McpToolMapper toolMapper;

    public AgentToolBindingServiceImpl(
            AgentToolBindingMapper bindingMapper,
            AgentConfigMapper agentMapper,
            McpServerMapper serverMapper,
            McpToolMapper toolMapper
    ) {
        this.bindingMapper = bindingMapper;
        this.agentMapper = agentMapper;
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentToolBindingItem> listBindings(Long agentId) {
        requireAgent(agentId);
        return bindingMapper.selectByAgentIdAndProviderType(agentId, PROVIDER_TYPE_MCP).stream()
                .map(this::toBindingItem)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentToolOptionItem> listOptions(Long agentId) {
        requireAgent(agentId);
        return serverMapper.selectList(new LambdaQueryWrapper<McpServer>()
                        .orderByAsc(McpServer::getSort)
                        .orderByAsc(McpServer::getId))
                .stream()
                .flatMap(server -> toolMapper.selectList(new LambdaQueryWrapper<McpTool>()
                                .eq(McpTool::getServerId, server.getId())
                                .orderByDesc(McpTool::getEnabled)
                                .orderByAsc(McpTool::getToolName))
                        .stream()
                        .map(tool -> toOptionItem(server, tool)))
                .toList();
    }

    @Override
    @Transactional
    public List<AgentToolBindingItem> updateBindings(Long userId, Long agentId, List<AgentToolBindingCommand> commands) {
        requireAgent(agentId);
        Map<String, AgentToolBindingCommand> uniqueCommands = new LinkedHashMap<>();
        if (commands != null) {
            for (AgentToolBindingCommand command : commands) {
                if (command == null || !hasText(command.toolName()) || uniqueCommands.containsKey(command.toolName())) {
                    continue;
                }
                validateCommand(command);
                uniqueCommands.put(command.toolName(), command);
            }
        }

        bindingMapper.deleteByAgentIdAndProviderType(agentId, PROVIDER_TYPE_MCP);
        OffsetDateTime now = OffsetDateTime.now();
        for (AgentToolBindingCommand command : uniqueCommands.values()) {
            AgentToolBinding binding = new AgentToolBinding();
            binding.setAgentId(agentId);
            binding.setToolName(command.toolName());
            binding.setProviderType(PROVIDER_TYPE_MCP);
            binding.setProviderRefId(command.providerRefId());
            binding.setEnabled(true);
            binding.setSort(command.sort() == null ? 0 : command.sort());
            binding.setCreatedBy(userId);
            binding.setCreatedAt(now);
            binding.setUpdatedBy(userId);
            binding.setUpdatedAt(now);
            bindingMapper.insert(binding);
        }
        return listBindings(agentId);
    }

    private void validateCommand(AgentToolBindingCommand command) {
        if (!PROVIDER_TYPE_MCP.equals(command.providerType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "暂不支持该工具来源");
        }
        McpTool tool = requireTool(command.providerRefId());
        McpServer server = requireServer(tool.getServerId());
        if (!exposedToolName(server.getServerKey(), tool.getToolName()).equals(command.toolName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具标识与 MCP 工具不匹配");
        }
    }

    private AgentConfig requireAgent(Long agentId) {
        AgentConfig agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        return agent;
    }

    private McpTool requireTool(Long toolId) {
        McpTool tool = toolId == null ? null : toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP 工具不存在");
        }
        return tool;
    }

    private McpServer requireServer(Long serverId) {
        McpServer server = serverId == null ? null : serverMapper.selectById(serverId);
        if (server == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP 服务不存在");
        }
        return server;
    }

    private AgentToolBindingItem toBindingItem(AgentToolBinding binding) {
        McpTool tool = binding.getProviderRefId() == null ? null : toolMapper.selectById(binding.getProviderRefId());
        McpServer server = tool == null || tool.getServerId() == null ? null : serverMapper.selectById(tool.getServerId());
        return new AgentToolBindingItem(
                binding.getId(),
                binding.getAgentId(),
                binding.getToolName(),
                binding.getProviderType(),
                binding.getProviderRefId(),
                server == null ? null : server.getServerKey(),
                server == null ? null : server.getName(),
                tool == null ? null : tool.getDisplayName(),
                tool == null ? null : tool.getDescription(),
                server != null && Boolean.TRUE.equals(server.getEnabled()),
                tool != null && Boolean.TRUE.equals(tool.getEnabled()),
                Boolean.TRUE.equals(binding.getEnabled()),
                binding.getSort()
        );
    }

    private AgentToolOptionItem toOptionItem(McpServer server, McpTool tool) {
        String exposedToolName = exposedToolName(server.getServerKey(), tool.getToolName());
        return new AgentToolOptionItem(
                exposedToolName,
                PROVIDER_TYPE_MCP,
                tool.getId(),
                server.getServerKey(),
                server.getName(),
                tool.getToolName(),
                exposedToolName,
                tool.getDisplayName(),
                tool.getDescription(),
                Boolean.TRUE.equals(server.getEnabled()),
                Boolean.TRUE.equals(tool.getEnabled())
        );
    }

    private String exposedToolName(String serverKey, String toolName) {
        return MCP_PREFIX + serverKey + "." + toolName;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
