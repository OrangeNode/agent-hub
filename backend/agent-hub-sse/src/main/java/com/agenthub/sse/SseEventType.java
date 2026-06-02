package com.agenthub.sse;

public enum SseEventType {
    CONVERSATION("conversation"),
    MESSAGE("message"),
    REASONING_DELTA("reasoning_delta"),
    ANSWER_DELTA("answer_delta"),
    MESSAGE_COMPLETED("message_completed"),
    ERROR("error"),
    DONE("done");

    private final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
