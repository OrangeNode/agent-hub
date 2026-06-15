package com.agenthub.skill.controller.request;

import jakarta.validation.Valid;
import java.util.List;

public record UpdateAgentSkillBindingsRequest(
        List<@Valid AgentSkillBindingRequest> skills
) {
}
