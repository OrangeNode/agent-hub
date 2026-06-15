package com.agenthub.skill.service;

import com.agenthub.skill.command.AgentSkillBindingCommand;
import com.agenthub.skill.dto.AgentSkillBindingItem;
import java.util.List;

public interface AgentSkillBindingService {

    List<AgentSkillBindingItem> listBindings(Long agentId);

    List<AgentSkillBindingItem> updateBindings(Long userId, Long agentId, List<AgentSkillBindingCommand> commands);
}
