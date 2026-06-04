package com.agenthub.ai.llm.openai;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import java.util.function.Consumer;

public class OpenAiResponsesClient implements LlmClient {

    /**
     * Responses 协议暂未实现，先以统一错误 chunk 告知调用方。
     */
    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        chunkConsumer.accept(LlmStreamChunk.error("暂不支持 OpenAI Responses 协议"));
    }
}
