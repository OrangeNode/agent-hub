package com.agenthub.skill.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.skill.command.AgentSkillBindingCommand;
import com.agenthub.skill.controller.request.UpdateAgentSkillBindingsRequest;
import com.agenthub.skill.dto.AgentSkillBindingItem;
import com.agenthub.skill.service.AgentSkillBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillController {

    private final AgentSkillBindingService bindingService;

    public AgentSkillController(AgentSkillBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:skill:update')")
    public ApiResponse<List<AgentSkillBindingItem>> listBindings(
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId
    ) {
        return ApiResponse.success(bindingService.listBindings(agentId));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('agent:skill:update')")
    public ApiResponse<List<AgentSkillBindingItem>> updateBindings(
            Authentication authentication,
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId,
            @Valid @RequestBody UpdateAgentSkillBindingsRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        List<AgentSkillBindingCommand> commands = request.skills() == null
                ? List.of()
                : request.skills().stream()
                        .map(item -> new AgentSkillBindingCommand(item.skillId(), item.sort()))
                        .toList();
        return ApiResponse.success(bindingService.updateBindings(loginUser.userId(), agentId, commands));
    }
}
