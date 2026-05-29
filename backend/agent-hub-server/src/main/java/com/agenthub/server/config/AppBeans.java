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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppBeans {

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
