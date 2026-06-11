package com.agenthub.agent.controller;

import com.agenthub.agent.command.CreateAgentCommand;
import com.agenthub.agent.command.UpdateAgentCommand;
import com.agenthub.agent.controller.request.CreateAgentRequest;
import com.agenthub.agent.controller.request.UpdateAgentRequest;
import com.agenthub.agent.dto.AgentItem;
import com.agenthub.agent.dto.AgentOptionItem;
import com.agenthub.agent.service.AgentService;
import com.agenthub.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:list')")
    public ApiResponse<List<AgentItem>> listAgents() {
        return ApiResponse.success(agentService.list());
    }

    @GetMapping("/available")
    public ApiResponse<List<AgentOptionItem>> listAvailableAgents() {
        return ApiResponse.success(agentService.listAvailable());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:list')")
    public ApiResponse<AgentItem> createAgent(@Valid @RequestBody CreateAgentRequest request) {
        return ApiResponse.success(agentService.create(new CreateAgentCommand(
                request.agentKey(),
                request.name(),
                request.shortName(),
                request.description(),
                request.systemPrompt(),
                request.defaultModelId(),
                request.enabled(),
                request.sort()
        )));
    }

    @PutMapping("/{agentId}")
    @PreAuthorize("hasAuthority('agent:list')")
    public ApiResponse<AgentItem> updateAgent(
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId,
            @Valid @RequestBody UpdateAgentRequest request
    ) {
        return ApiResponse.success(agentService.update(agentId, new UpdateAgentCommand(
                request.agentKey(),
                request.name(),
                request.shortName(),
                request.description(),
                request.systemPrompt(),
                request.defaultModelId(),
                request.enabled(),
                request.sort()
        )));
    }

    @DeleteMapping("/{agentId}")
    @PreAuthorize("hasAuthority('agent:list')")
    public ApiResponse<Void> deleteAgent(@PathVariable @Positive(message = "Agent 必须为正数") Long agentId) {
        agentService.delete(agentId);
        return ApiResponse.success();
    }
}
