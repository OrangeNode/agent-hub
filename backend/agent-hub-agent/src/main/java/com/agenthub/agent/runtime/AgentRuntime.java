package com.agenthub.agent.runtime;

import com.agenthub.ai.llm.ChatRequest;
import reactor.core.publisher.Flux;

public interface AgentRuntime {

    Flux<RuntimeEvent> run(ChatRequest request);
}
