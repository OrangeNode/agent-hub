package com.agenthub.ai.llm;

import com.agenthub.ai.llm.anthropic.AnthropicMessagesClient;
import com.agenthub.ai.llm.openai.OpenAiChatCompletionsClient;
import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import com.agenthub.ai.llm.openai.OpenAiResponsesClient;

public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    /**
     * 根据模型协议类型创建对应的 LLM 客户端适配器。
     */
    public static LlmClient create(String protocolType) {
        if (protocolType == null) {
            throw new IllegalArgumentException("不支持的模型协议: null");
        }
        return switch (protocolType) {
            case "OPENAI_CHAT_COMPLETIONS" -> new OpenAiChatCompletionsClient();
            case "OPENAI_RESPONSES" -> new OpenAiResponsesClient();
            case "ANTHROPIC_MESSAGES" -> new AnthropicMessagesClient();
            case "OPENAI_COMPATIBLE" -> new OpenAiCompatibleClient();
            default -> throw new IllegalArgumentException("不支持的模型协议: " + protocolType);
        };
    }
}
