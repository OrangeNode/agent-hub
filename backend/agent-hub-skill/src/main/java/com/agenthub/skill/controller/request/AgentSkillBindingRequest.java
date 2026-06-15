package com.agenthub.skill.controller.request;

import jakarta.validation.constraints.Positive;

public record AgentSkillBindingRequest(
        @Positive(message = "Skill 必须为正数")
        Long skillId,
        Integer sort
) {
}
