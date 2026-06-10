package com.agenthub.chat.message.dto;

public record MessageProcessEvent(
        String type,
        String content,
        ToolProcess tool
) {

    public record ToolProcess(
            String toolCallId,
            String name,
            String status,
            String stdout,
            String stderr,
            String result
    ) {
    }
}
