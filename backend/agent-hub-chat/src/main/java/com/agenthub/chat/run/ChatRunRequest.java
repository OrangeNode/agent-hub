package com.agenthub.chat.run;

import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.task.runtime.AgentRunDecision;
import com.agenthub.chat.task.runtime.TaskRunContext;
import com.agenthub.skill.service.SkillRuntimeContext;
import java.util.List;

/**
 * 一次运行所需的全部输入，在请求线程中准备完毕后交给生成线程。
 */
public record ChatRunRequest(
        Long userId,
        Long conversationId,
        AiModelRuntimeConfig runtimeConfig,
        List<LlmMessage> messages,
        ChatMessage assistantMessage,
        String agentKey,
        Long agentId,
        boolean thinkingEnabled,
        SkillRuntimeContext skillContext,
        String prompt,
        TaskRunContext taskRunContext,
        AgentRunDecision runDecision,
        boolean hasAttachments
) {
}
