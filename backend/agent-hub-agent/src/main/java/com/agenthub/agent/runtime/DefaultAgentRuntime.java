package com.agenthub.agent.runtime;

import com.agenthub.ai.llm.ChatRequest;
import com.agenthub.agent.tool.ToolLoop;
import reactor.core.publisher.Flux;

public class DefaultAgentRuntime implements AgentRuntime {

    private final ToolLoop toolLoop;

    public DefaultAgentRuntime(ToolLoop toolLoop) {
        this.toolLoop = toolLoop;
    }

    @Override
    public Flux<RuntimeEvent> run(ChatRequest request) {
        return toolLoop.execute(request);
    }
}
