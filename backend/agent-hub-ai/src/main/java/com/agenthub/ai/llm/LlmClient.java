package com.agenthub.ai.llm;

import java.util.function.Consumer;

public interface LlmClient {

    /**
     * 按统一请求模型发起流式调用，并把不同协议的增量结果转换为统一 chunk。
     */
    void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer);
}
