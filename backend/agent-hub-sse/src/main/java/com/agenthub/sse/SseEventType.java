package com.agenthub.sse;

public enum SseEventType {
    CONVERSATION("conversation"),
    META("meta"),
    MESSAGE("message"),
    REASONING_DELTA("reasoning_delta"),
    ANSWER_DELTA("answer_delta"),
    TOOL_CALL("tool_call"),
    TOOL_OUTPUT("tool_output"),
    TOOL_RESULT("tool_result"),
    MESSAGE_COMPLETED("message_completed"),
    TASK_PROGRESS_STARTED("task_progress_started"),
    TASK_PROGRESS_UPDATED("task_progress_updated"),
    TASK_STEP_STARTED("task_step_started"),
    TASK_STEP_COMPLETED("task_step_completed"),
    TASK_STEP_FAILED("task_step_failed"),
    TASK_PLAN_REVISED("task_plan_revised"),
    TASK_PROGRESS_COMPLETED("task_progress_completed"),
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
