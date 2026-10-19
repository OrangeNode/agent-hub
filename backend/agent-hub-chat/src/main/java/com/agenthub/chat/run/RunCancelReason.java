package com.agenthub.chat.run;

import com.agenthub.chat.message.support.AssistantMessageStore;

/**
 * 运行被终止的原因。用户中止和断线保留为 STOPPED，超时和停机属于异常终止记为 FAILED。
 */
public enum RunCancelReason {

    USER_STOPPED(AssistantMessageStore.USER_STOPPED_GENERATION, false),
    CLIENT_DISCONNECTED("SSE 连接已断开", false),
    TIMEOUT("生成时间超过上限，已自动终止", true),
    SHUTDOWN("服务重启，生成已中断", true);

    private final String message;
    private final boolean failure;

    RunCancelReason(String message, boolean failure) {
        this.message = message;
        this.failure = failure;
    }

    public String message() {
        return message;
    }

    public boolean failure() {
        return failure;
    }
}
