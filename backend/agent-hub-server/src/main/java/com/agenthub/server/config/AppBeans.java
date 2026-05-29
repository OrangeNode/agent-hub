package com.agenthub.server.config;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.MockLlmClient;
import com.agenthub.agent.runtime.AgentRuntime;
import com.agenthub.agent.runtime.DefaultAgentRuntime;
import com.agenthub.agent.tool.SimpleToolLoop;
import com.agenthub.agent.tool.SimpleToolRegistry;
import com.agenthub.agent.tool.ToolLoop;
import com.agenthub.agent.tool.ToolRegistry;
import com.agenthub.chat.ChatStreamService;
import com.agenthub.chat.DefaultChatStreamService;
import com.agenthub.system.auth.AuthService;
import com.agenthub.system.auth.InMemoryAuthService;
import com.agenthub.system.menu.InMemoryMenuService;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.role.InMemoryRoleService;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.support.InMemorySystemStore;
import com.agenthub.system.user.InMemoryUserService;
import com.agenthub.system.user.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppBeans {

    @Bean
    InMemorySystemStore inMemorySystemStore() {
        return new InMemorySystemStore();
    }

    @Bean
    UserService userService(InMemorySystemStore store) {
        return new InMemoryUserService(store);
    }

    @Bean
    RoleService roleService(InMemorySystemStore store) {
        return new InMemoryRoleService(store);
    }

    @Bean
    MenuService menuService(InMemorySystemStore store) {
        return new InMemoryMenuService(store);
    }

    @Bean
    AuthService authService(UserService userService, RoleService roleService, MenuService menuService) {
        return new InMemoryAuthService(userService, roleService, menuService);
    }

    @Bean
    LlmClient llmClient() {
        return new MockLlmClient();
    }

    @Bean
    ToolRegistry toolRegistry() {
        return new SimpleToolRegistry();
    }

    @Bean
    ToolLoop toolLoop(LlmClient llmClient) {
        return new SimpleToolLoop(llmClient);
    }

    @Bean
    AgentRuntime agentRuntime(ToolLoop toolLoop) {
        return new DefaultAgentRuntime(toolLoop);
    }

    @Bean
    ChatStreamService chatStreamService(LlmClient llmClient) {
        return new DefaultChatStreamService(llmClient);
    }
}
