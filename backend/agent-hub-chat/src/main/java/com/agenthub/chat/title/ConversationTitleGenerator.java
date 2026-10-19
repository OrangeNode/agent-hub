package com.agenthub.chat.title;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 优先让模型生成会话标题，模型失败或返回无效标题时使用本地兜底标题。
 */
@Component
public class ConversationTitleGenerator {

    private static final int TITLE_MAX_OUTPUT_TOKENS = 64;

    private final ConversationTitleService titleService;
    private final Function<String, LlmClient> llmClientFactory;

    @Autowired
    public ConversationTitleGenerator(ConversationTitleService titleService) {
        this(titleService, LlmClientFactory::create);
    }

    public ConversationTitleGenerator(ConversationTitleService titleService, Function<String, LlmClient> llmClientFactory) {
        this.titleService = titleService;
        this.llmClientFactory = llmClientFactory;
    }

    public String generate(AiModelRuntimeConfig runtimeConfig, String prompt) {
        String fallbackTitle = titleService.generateFallbackTitle(prompt);
        try {
            LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
            StringBuilder title = new StringBuilder();
            client.stream(buildRequest(runtimeConfig, prompt), chunk -> {
                if (chunk.type() == LlmStreamChunkType.ANSWER_DELTA) {
                    title.append(chunk.content() == null ? "" : chunk.content());
                } else if (chunk.type() == LlmStreamChunkType.ERROR) {
                    title.setLength(0);
                }
            });
            String cleanedTitle = titleService.cleanGeneratedTitle(title.toString());
            return "新会话".equals(cleanedTitle) ? fallbackTitle : cleanedTitle;
        } catch (Exception exception) {
            return fallbackTitle;
        }
    }

    private LlmStreamRequest buildRequest(AiModelRuntimeConfig runtimeConfig, String prompt) {
        return new LlmStreamRequest(
                runtimeConfig.baseUrl(),
                runtimeConfig.apiKeyCipher(),
                runtimeConfig.authType(),
                runtimeConfig.apiVersion(),
                runtimeConfig.extraHeaders(),
                runtimeConfig.extraBody(),
                runtimeConfig.modelCode(),
                List.of(new LlmMessage("user", titleService.buildTitlePrompt(prompt))),
                TITLE_MAX_OUTPUT_TOKENS,
                false
        );
    }
}
