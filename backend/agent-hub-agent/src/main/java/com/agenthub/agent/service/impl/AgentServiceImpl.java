package com.agenthub.agent.service.impl;

import com.agenthub.agent.command.CreateAgentCommand;
import com.agenthub.agent.command.UpdateAgentCommand;
import com.agenthub.agent.dto.AgentItem;
import com.agenthub.agent.dto.AgentOptionItem;
import com.agenthub.agent.dto.AgentRuntimeConfig;
import com.agenthub.agent.entity.AgentConfig;
import com.agenthub.agent.mapper.AgentConfigMapper;
import com.agenthub.agent.service.AgentService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentServiceImpl implements AgentService {

    private final AgentConfigMapper agentMapper;

    public AgentServiceImpl(AgentConfigMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    @Override
    public List<AgentItem> list() {
        return agentMapper.selectJoinedItems();
    }

    @Override
    public List<AgentOptionItem> listAvailable() {
        return agentMapper.selectAvailableOptions();
    }

    @Override
    public AgentRuntimeConfig requireEnabledRuntimeConfig(String agentKey) {
        AgentConfig agent = agentMapper.selectByAgentKey(agentKey);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 不可用");
        }
        return new AgentRuntimeConfig(
                agent.getId(),
                agent.getAgentKey(),
                agent.getName(),
                agent.getSystemPrompt(),
                agent.getDefaultModelId()
        );
    }

    @Override
    @Transactional
    public AgentItem create(CreateAgentCommand command) {
        rejectDuplicateKey(command.agentKey(), null);
        AgentConfig agent = new AgentConfig();
        fillAgent(agent, command);
        insertAgent(agent);
        return agentMapper.selectJoinedById(agent.getId());
    }

    @Override
    @Transactional
    public AgentItem update(Long agentId, UpdateAgentCommand command) {
        AgentConfig existed = requireAgent(agentId);
        rejectDuplicateKey(command.agentKey(), existed.getId());
        AgentConfig agent = new AgentConfig();
        agent.setId(existed.getId());
        fillAgent(agent, command);
        updateAgent(agent);
        return agentMapper.selectJoinedById(agentId);
    }

    @Override
    @Transactional
    public void delete(Long agentId) {
        requireAgent(agentId);
        agentMapper.deleteById(agentId);
    }

    private AgentConfig requireAgent(Long agentId) {
        AgentConfig agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        return agent;
    }

    private void rejectDuplicateKey(String agentKey, Long excludedAgentId) {
        LambdaQueryWrapper<AgentConfig> wrapper = new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentKey, agentKey);
        if (excludedAgentId != null) {
            wrapper.ne(AgentConfig::getId, excludedAgentId);
        }
        if (agentMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 标识已存在");
        }
    }

    private void insertAgent(AgentConfig agent) {
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 标识已存在");
        }
    }

    private void updateAgent(AgentConfig agent) {
        try {
            agentMapper.updateById(agent);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 标识已存在");
        }
    }

    private void fillAgent(AgentConfig agent, CreateAgentCommand command) {
        agent.setAgentKey(command.agentKey());
        agent.setName(command.name());
        agent.setShortName(command.shortName());
        agent.setDescription(command.description());
        agent.setSystemPrompt(command.systemPrompt());
        agent.setDefaultModelId(command.defaultModelId());
        agent.setEnabled(defaultEnabled(command.enabled()));
        agent.setSort(defaultSort(command.sort()));
    }

    private void fillAgent(AgentConfig agent, UpdateAgentCommand command) {
        agent.setAgentKey(command.agentKey());
        agent.setName(command.name());
        agent.setShortName(command.shortName());
        agent.setDescription(command.description());
        agent.setSystemPrompt(command.systemPrompt());
        agent.setDefaultModelId(command.defaultModelId());
        agent.setEnabled(defaultEnabled(command.enabled()));
        agent.setSort(defaultSort(command.sort()));
    }

    private boolean defaultEnabled(Boolean enabled) {
        return enabled == null || enabled;
    }

    private int defaultSort(Integer sort) {
        return sort == null ? 0 : sort;
    }
}
