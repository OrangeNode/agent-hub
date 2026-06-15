package com.agenthub.skill.service;

import java.util.List;

public interface SkillRuntimeService {

    SkillRuntimeContext buildRuntimeContext(Long agentId, List<Long> selectedSkillIds);

    String readSkillMarkdown(Long skillId, List<Long> allowedSkillIds);
}
