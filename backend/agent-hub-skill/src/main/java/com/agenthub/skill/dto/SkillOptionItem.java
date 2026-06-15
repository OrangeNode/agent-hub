package com.agenthub.skill.dto;

public record SkillOptionItem(
        Long skillId,
        String skillKey,
        String name,
        String description
) {
}
