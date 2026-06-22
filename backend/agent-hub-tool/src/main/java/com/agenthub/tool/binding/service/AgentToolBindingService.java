package com.agenthub.tool.binding.service;

import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import java.util.List;

public interface AgentToolBindingService {

    List<AgentToolBindingItem> listBindings(Long agentId);

    List<AgentToolOptionItem> listOptions(Long agentId);

    List<AgentToolBindingItem> updateBindings(Long userId, Long agentId, List<AgentToolBindingCommand> commands);
}
