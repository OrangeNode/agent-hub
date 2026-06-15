package com.agenthub.skill.dto;

public record AgentSkillBindingItem(
        Long bindingId,
        Long agentId,
        Long skillId,
        String skillKey,
        String skillName,
        Integer sort
) {
}
