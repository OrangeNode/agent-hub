package com.agenthub.chat.message;

/**
 * chat_message 表中 role、status 字段的取值，与数据库存量数据保持一致。
 */
public final class ChatMessageStatus {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    public static final String COMPLETED = "COMPLETED";
    public static final String STREAMING = "STREAMING";
    public static final String STOPPED = "STOPPED";
    public static final String FAILED = "FAILED";

    private ChatMessageStatus() {
    }
}
