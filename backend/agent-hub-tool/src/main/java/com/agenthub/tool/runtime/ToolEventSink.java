package com.agenthub.tool.runtime;

@FunctionalInterface
public interface ToolEventSink {

    void output(String toolCallId, String toolName, String stream, String content);

    static ToolEventSink noop() {
        return (toolCallId, toolName, stream, content) -> {
        };
    }
}
