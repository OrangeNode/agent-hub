package com.agenthub.agent.service;

import com.agenthub.agent.command.CreateAgentCommand;
import com.agenthub.agent.command.UpdateAgentCommand;
import com.agenthub.agent.dto.AgentItem;
import com.agenthub.agent.dto.AgentOptionItem;
import com.agenthub.agent.dto.AgentRuntimeConfig;
import java.util.List;

public interface AgentService {

    List<AgentItem> list();

    List<AgentOptionItem> listAvailable();

    AgentRuntimeConfig requireEnabledRuntimeConfig(String agentKey);

    AgentItem create(CreateAgentCommand command);

    AgentItem update(Long agentId, UpdateAgentCommand command);

    void delete(Long agentId);

    /**
     * 供其他模块校验 Agent 是否存在，避免直接访问 agent 模块的 Mapper 和实体。
     */
    void requireExists(Long agentId);
}
