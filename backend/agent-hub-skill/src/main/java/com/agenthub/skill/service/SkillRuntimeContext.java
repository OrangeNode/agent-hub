package com.agenthub.skill.service;

import java.util.List;

public record SkillRuntimeContext(
        String prompt,
        List<Long> skillIds,
        List<SkillRuntimePackage> packages
) {

    public SkillRuntimeContext {
        prompt = prompt == null ? "" : prompt;
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    public SkillRuntimeContext(String prompt, List<Long> skillIds) {
        this(prompt, skillIds, List.of());
    }
}
