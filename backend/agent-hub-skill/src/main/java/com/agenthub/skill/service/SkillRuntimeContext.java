package com.agenthub.skill.service;

import java.util.List;

public record SkillRuntimeContext(
        String prompt,
        List<Long> skillIds
) {

    public SkillRuntimeContext {
        prompt = prompt == null ? "" : prompt;
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
    }
}
