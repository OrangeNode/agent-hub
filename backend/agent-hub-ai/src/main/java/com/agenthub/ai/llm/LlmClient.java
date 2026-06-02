package com.agenthub.ai.llm;

import java.util.function.Consumer;

public interface LlmClient {

    void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer);
}
