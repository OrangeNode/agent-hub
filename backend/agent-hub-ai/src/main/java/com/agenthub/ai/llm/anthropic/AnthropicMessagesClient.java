package com.agenthub.ai.llm.anthropic;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import java.util.function.Consumer;

public class AnthropicMessagesClient implements LlmClient {

    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        chunkConsumer.accept(LlmStreamChunk.done());
    }
}
