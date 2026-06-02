package com.agenthub.ai.llm;

public record LlmStreamChunk(LlmStreamChunkType type, String content, String errorMessage) {

    public static LlmStreamChunk answer(String content) {
        return new LlmStreamChunk(LlmStreamChunkType.ANSWER_DELTA, content, null);
    }

    public static LlmStreamChunk reasoning(String content) {
        return new LlmStreamChunk(LlmStreamChunkType.REASONING_DELTA, content, null);
    }

    public static LlmStreamChunk done() {
        return new LlmStreamChunk(LlmStreamChunkType.DONE, null, null);
    }

    public static LlmStreamChunk error(String errorMessage) {
        return new LlmStreamChunk(LlmStreamChunkType.ERROR, null, errorMessage);
    }
}
