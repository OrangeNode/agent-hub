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
}
