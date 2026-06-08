package com.agenthub.ai.llm;

public enum LlmStreamChunkType {
    REASONING_DELTA,
    ANSWER_DELTA,
    TOOL_CALL,
    USAGE,
    DONE,
    ERROR
}
