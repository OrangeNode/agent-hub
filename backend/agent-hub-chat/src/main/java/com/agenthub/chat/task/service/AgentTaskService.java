package com.agenthub.chat.task.service;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.runtime.AgentTaskPlan;
import com.agenthub.chat.task.runtime.RunMode;
import java.util.List;

public interface AgentTaskService {

    TaskProgressItem createRun(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            RunMode mode,
            String title,
            String planSummary,
            List<String> steps
    );

    TaskProgressItem replacePlan(Long taskRunId, AgentTaskPlan plan);

    TaskProgressItem markStepRunning(Long taskRunId, Integer stepIndex);

    TaskProgressItem markStepCompleted(Long taskRunId, Integer stepIndex);

    TaskProgressItem markFailed(Long taskRunId, String errorMessage);

    TaskProgressItem markCompleted(Long taskRunId);

    void stopByAssistantMessageId(Long userId, Long assistantMessageId);

    TaskProgressItem findActiveProgress(Long userId, Long conversationId);

    TaskProgressItem findLatestProgress(Long userId, Long conversationId);
}
