package com.agenthub.tool.binding.controller.request;

import jakarta.validation.Valid;
import java.util.List;

public record UpdateAgentToolBindingsRequest(
        List<@Valid AgentToolBindingRequest> tools
) {
}
