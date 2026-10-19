package com.agenthub.ai.llm;

/**
 * 调用方在 chunk 回调中抛出该异常表示主动取消流式调用。
 * LlmClient 实现必须原样抛出，不能转换成 ERROR chunk，以便调用方区分取消和上游失败。
 */
public class LlmStreamCancelledException extends RuntimeException {

    public LlmStreamCancelledException(String message) {
        super(message, null, false, false);
    }
}
