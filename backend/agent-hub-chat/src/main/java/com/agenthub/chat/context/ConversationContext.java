package com.agenthub.chat.context;

import com.agenthub.ai.llm.LlmMessage;
import java.util.List;

public record ConversationContext(
        List<LlmMessage> messages,
        boolean summaryUpdated,
        int inputTokenEstimate
) {
}
