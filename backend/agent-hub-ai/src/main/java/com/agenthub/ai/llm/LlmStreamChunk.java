package com.agenthub.ai.llm;

import com.agenthub.ai.llm.tool.LlmToolCall;

public record LlmStreamChunk(LlmStreamChunkType type, String content, String errorMessage, LlmToolCall toolCall) {

    public LlmStreamChunk(LlmStreamChunkType type, String content, String errorMessage) {
        this(type, content, errorMessage, null);
    }

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

    public static LlmStreamChunk toolCall(LlmToolCall toolCall) {
        return new LlmStreamChunk(LlmStreamChunkType.TOOL_CALL, null, null, toolCall);
    }
}
