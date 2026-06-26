package com.agenthub.chat.task.runtime;

import java.util.List;

public record AgentTaskPlan(
        String title,
        String summary,
        List<String> steps
) {
}
