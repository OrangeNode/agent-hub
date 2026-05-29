package com.agenthub.agent.tool;

import com.agenthub.ai.llm.ChatRequest;
import com.agenthub.agent.runtime.RuntimeEvent;
import reactor.core.publisher.Flux;

public interface ToolLoop {

    Flux<RuntimeEvent> execute(ChatRequest request);
}
