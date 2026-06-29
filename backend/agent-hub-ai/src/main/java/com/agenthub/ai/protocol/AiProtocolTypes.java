package com.agenthub.ai.protocol;

public final class AiProtocolTypes {

    public static final String OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";

    private AiProtocolTypes() {
    }

    public static void requireOpenAiCompatible(String protocolType) {
        if (!OPENAI_COMPATIBLE.equals(protocolType)) {
            throw new IllegalArgumentException("不支持的模型协议: " + protocolType + "，当前仅支持 " + OPENAI_COMPATIBLE);
        }
    }
}
