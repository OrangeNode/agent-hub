package com.agenthub.skill.command;

public record AgentSkillBindingCommand(
        Long skillId,
        Integer sort
) {
}
