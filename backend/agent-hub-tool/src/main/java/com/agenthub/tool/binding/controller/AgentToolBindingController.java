package com.agenthub.tool.binding.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.controller.request.UpdateAgentToolBindingsRequest;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.service.AgentToolBindingService;
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
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolBindingController {

    private final AgentToolBindingService bindingService;

    public AgentToolBindingController(AgentToolBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:tool:update')")
    public ApiResponse<List<AgentToolBindingItem>> listBindings(
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId
    ) {
        return ApiResponse.success(bindingService.listBindings(agentId));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('agent:tool:update')")
    public ApiResponse<List<AgentToolOptionItem>> listOptions(
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId
    ) {
        return ApiResponse.success(bindingService.listOptions(agentId));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('agent:tool:update')")
    public ApiResponse<List<AgentToolBindingItem>> updateBindings(
            Authentication authentication,
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId,
            @Valid @RequestBody UpdateAgentToolBindingsRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        List<AgentToolBindingCommand> commands = request.tools() == null
                ? List.of()
                : request.tools().stream()
                        .map(item -> new AgentToolBindingCommand(
                                item.toolName(),
                                item.providerType(),
                                item.providerRefId(),
                                item.sort()
                        ))
                        .toList();
        return ApiResponse.success(bindingService.updateBindings(loginUser.userId(), agentId, commands));
    }
}
