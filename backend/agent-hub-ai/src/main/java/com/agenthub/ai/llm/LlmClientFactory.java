package com.agenthub.ai.llm;

import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import com.agenthub.ai.protocol.AiProtocolTypes;

public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    /**
     * 目前聊天链路只保留 OpenAI 兼容协议，其他厂商专用协议统一下线。
     */
    public static LlmClient create(String protocolType) {
        AiProtocolTypes.requireOpenAiCompatible(protocolType);
        return new OpenAiCompatibleClient();
    }
}
