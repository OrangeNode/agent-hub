package com.agenthub.tool.binding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.controller.request.AgentToolBindingRequest;
import com.agenthub.tool.binding.controller.request.UpdateAgentToolBindingsRequest;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.service.AgentToolBindingService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AgentToolBindingControllerTest {

    private static final String REQUIRED_AUTHORITY = "hasAuthority('agent:tool:update')";

    @Test
    void shouldDeclareHttpMappingsAndPermissions() throws NoSuchMethodException {
        RequestMapping requestMapping = AgentToolBindingController.class.getAnnotation(RequestMapping.class);
        assertThat(requestMapping.value()).containsExactly("/api/agents/{agentId}/tools");

        Method listBindings = AgentToolBindingController.class.getMethod("listBindings", Long.class);
        GetMapping listMapping = listBindings.getAnnotation(GetMapping.class);
        assertThat(listMapping.value()).isEmpty();
        assertThat(listMapping.path()).isEmpty();
        assertPreAuthorize(listBindings);

        Method listOptions = AgentToolBindingController.class.getMethod("listOptions", Long.class);
        GetMapping optionsMapping = listOptions.getAnnotation(GetMapping.class);
        assertThat(optionsMapping.value()).containsExactly("/options");
        assertPreAuthorize(listOptions);

        Method updateBindings = AgentToolBindingController.class.getMethod(
                "updateBindings",
                Authentication.class,
                Long.class,
                UpdateAgentToolBindingsRequest.class
        );
        PutMapping updateMapping = updateBindings.getAnnotation(PutMapping.class);
        assertThat(updateMapping.value()).isEmpty();
        assertThat(updateMapping.path()).isEmpty();
        assertPreAuthorize(updateBindings);
    }

    @Test
    void shouldListBindings() {
        AgentToolBindingService service = mock(AgentToolBindingService.class);
        AgentToolBindingController controller = new AgentToolBindingController(service);
        AgentToolBindingItem item = bindingItem();
        when(service.listBindings(1L)).thenReturn(List.of(item));

        ApiResponse<List<AgentToolBindingItem>> response = controller.listBindings(1L);

        assertThat(response.data()).containsExactly(item);
        verify(service).listBindings(1L);
    }

    @Test
    void shouldListOptions() {
        AgentToolBindingService service = mock(AgentToolBindingService.class);
        AgentToolBindingController controller = new AgentToolBindingController(service);
        AgentToolOptionItem item = optionItem();
        when(service.listOptions(1L)).thenReturn(List.of(item));

        ApiResponse<List<AgentToolOptionItem>> response = controller.listOptions(1L);

        assertThat(response.data()).containsExactly(item);
        verify(service).listOptions(1L);
    }

    @Test
    void shouldUpdateBindingsByMappingRequestToCommands() {
        AgentToolBindingService service = mock(AgentToolBindingService.class);
        AgentToolBindingController controller = new AgentToolBindingController(service);
        Authentication authentication = mock(Authentication.class);
        LoginUser loginUser = new LoginUser(7L, "admin", List.of("ADMIN"));
        when(authentication.getPrincipal()).thenReturn(loginUser);
        AgentToolBindingItem item = bindingItem();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolBindingCommand>> captor = ArgumentCaptor.forClass(List.class);
        when(service.updateBindings(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(1L), captor.capture()))
                .thenReturn(List.of(item));

        ApiResponse<List<AgentToolBindingItem>> response = controller.updateBindings(
                authentication,
                1L,
                new UpdateAgentToolBindingsRequest(List.of(
                        new AgentToolBindingRequest("mcp.github.search", "MCP", 100L, 2),
                        new AgentToolBindingRequest("mcp.gitlab.read", "MCP", 101L, 1)
                ))
        );

        assertThat(response.data()).containsExactly(item);
        assertThat(captor.getValue()).extracting(AgentToolBindingCommand::toolName)
                .containsExactly("mcp.github.search", "mcp.gitlab.read");
        assertThat(captor.getValue()).extracting(AgentToolBindingCommand::providerRefId)
                .containsExactly(100L, 101L);
        assertThat(captor.getValue()).extracting(AgentToolBindingCommand::sort)
                .containsExactly(2, 1);
    }

    private AgentToolBindingItem bindingItem() {
        return new AgentToolBindingItem(
                5L,
                1L,
                "mcp.github.search",
                "MCP",
                100L,
                "github",
                "GitHub",
                "搜索",
                "搜索仓库",
                true,
                true,
                true,
                2
        );
    }

    private AgentToolOptionItem optionItem() {
        return new AgentToolOptionItem(
                "mcp.github.search",
                "MCP",
                100L,
                "github",
                "GitHub",
                "search",
                "mcp.github.search",
                "搜索",
                "搜索仓库",
                true,
                true
        );
    }

    private void assertPreAuthorize(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize.value()).isEqualTo(REQUIRED_AUTHORITY);
    }
}
