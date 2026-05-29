package com.agenthub.ai.llm;

import java.util.List;
import reactor.core.publisher.Flux;

public class MockLlmClient implements LlmClient {

    @Override
    public ChatMessage complete(ChatRequest request) {
        return new ChatMessage("assistant", String.join(
                "",
                stream(request).map(ChatChunk::content).collectList().blockOptional().orElse(List.of())
        ));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        return Flux.just(
                new ChatChunk("你好，我是 Agent Hub 的模拟助手。"),
                new ChatChunk("当前响应来自自研 LLM Client 抽象。")
        );
    }
}
