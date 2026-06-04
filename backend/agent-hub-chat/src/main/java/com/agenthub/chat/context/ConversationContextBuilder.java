package com.agenthub.chat.context;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.llm.LlmContentPart;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.summary.entity.ChatConversationSummary;
import com.agenthub.chat.summary.mapper.ChatConversationSummaryMapper;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConversationContextBuilder {

    private static final double SUMMARY_TRIGGER_RATIO = 0.8;
    private static final int SAFETY_MARGIN_TOKENS = 1024;
    private static final int DEFAULT_RESERVED_OUTPUT_TOKENS = 4096;
    private static final int DEFAULT_IMAGE_TOKEN_ESTIMATE = 1500;
    private static final int RECENT_MESSAGE_MAX_COUNT = 20;
    private static final int RECENT_TURN_MIN_MESSAGE_COUNT = 4;
    private static final int DEFAULT_CONTEXT_WINDOW = 8192;
    private static final String ROLE_USER = "USER";
    private static final String SUMMARY_PROMPT = """
            请把以下会话历史压缩为后续对话可用的中文摘要。保留用户目标、已确认约束、关键决策、未完成事项、错误和阻塞点。不要保留无意义闲聊。不要编造历史中不存在的信息。
            """;
    private static final String CURRENT_INPUT_TOO_LONG = "当前输入内容过长，已超过模型上下文限制，请缩短后重试";

    private final ChatMessageMapper messageMapper;
    private final ChatConversationSummaryMapper summaryMapper;
    private final TokenEstimator tokenEstimator;
    private final Function<String, LlmClient> llmClientFactory;

    @Autowired
    public ConversationContextBuilder(
            ChatMessageMapper messageMapper,
            ChatConversationSummaryMapper summaryMapper,
            TokenEstimator tokenEstimator
    ) {
        this(messageMapper, summaryMapper, tokenEstimator, LlmClientFactory::create);
    }

    public ConversationContextBuilder(
            ChatMessageMapper messageMapper,
            ChatConversationSummaryMapper summaryMapper,
            TokenEstimator tokenEstimator,
            Function<String, LlmClient> llmClientFactory
    ) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.tokenEstimator = tokenEstimator;
        this.llmClientFactory = llmClientFactory;
    }

    public ConversationContext build(
            Long userId,
            Long conversationId,
            AiModelRuntimeConfig runtimeConfig,
            String currentPrompt,
            List<AttachmentReference> images
    ) {
        int contextWindow = resolveContextWindow(runtimeConfig);
        int reservedOutputTokens = resolveReservedOutputTokens(runtimeConfig);
        int availableInputTokens = contextWindow - reservedOutputTokens - SAFETY_MARGIN_TOKENS;
        if (availableInputTokens < 1) {
            availableInputTokens = Math.max(1, contextWindow / 2);
        }

        int currentInputEstimate = estimateCurrentInput(currentPrompt, images);
        if (currentInputEstimate > availableInputTokens) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, CURRENT_INPUT_TOO_LONG);
        }

        List<ChatMessage> history = listHistory(userId, conversationId);
        ChatConversationSummary summary = loadSummary(userId, conversationId);
        SummaryUpdate summaryUpdate = maybeUpdateSummary(
                userId,
                conversationId,
                runtimeConfig,
                contextWindow,
                history,
                summary
        );
        ChatConversationSummary effectiveSummary = summaryUpdate.summary();

        List<LlmMessage> messages = new ArrayList<>();
        int usedTokens = 0;
        if (effectiveSummary != null && hasText(effectiveSummary.getSummary())) {
            LlmMessage summaryMessage = new LlmMessage("system", "以下是此前会话摘要：\n" + effectiveSummary.getSummary());
            int summaryTokens = estimateMessage(summaryMessage);
            if (usedTokens + summaryTokens + currentInputEstimate <= availableInputTokens) {
                messages.add(summaryMessage);
                usedTokens += summaryTokens;
            }
        }

        List<ChatMessage> candidates = messagesAfterSummary(history, effectiveSummary);
        List<LlmMessage> recentMessages = selectRecentMessages(candidates,
                availableInputTokens - usedTokens - currentInputEstimate);
        for (LlmMessage recentMessage : recentMessages) {
            int messageTokens = estimateMessage(recentMessage);
            if (usedTokens + messageTokens + currentInputEstimate <= availableInputTokens) {
                messages.add(recentMessage);
                usedTokens += messageTokens;
            }
        }

        LlmMessage currentMessage = buildCurrentUserMessage(currentPrompt, images);
        messages.add(currentMessage);
        usedTokens += currentInputEstimate;
        return new ConversationContext(List.copyOf(messages), summaryUpdate.updated(), usedTokens);
    }

    private List<ChatMessage> listHistory(Long userId, Long conversationId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .orderByAsc(ChatMessage::getId);
        List<ChatMessage> messages = messageMapper.selectList(wrapper);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return List.copyOf(messages);
    }

    private ChatConversationSummary loadSummary(Long userId, Long conversationId) {
        LambdaQueryWrapper<ChatConversationSummary> wrapper = new LambdaQueryWrapper<ChatConversationSummary>()
                .eq(ChatConversationSummary::getConversationId, conversationId)
                .eq(ChatConversationSummary::getUserId, userId)
                .last("LIMIT 1");
        return summaryMapper.selectOne(wrapper);
    }

    private SummaryUpdate maybeUpdateSummary(
            Long userId,
            Long conversationId,
            AiModelRuntimeConfig runtimeConfig,
            int contextWindow,
            List<ChatMessage> history,
            ChatConversationSummary currentSummary
    ) {
        int fullHistoryEstimate = estimateHistory(history) + estimateText(currentSummary == null ? null : currentSummary.getSummary());
        if (fullHistoryEstimate < Math.ceil(contextWindow * SUMMARY_TRIGGER_RATIO)) {
            return new SummaryUpdate(currentSummary, false);
        }

        List<ChatMessage> summaryMessages = messagesToSummarize(history, currentSummary);
        if (summaryMessages.isEmpty()) {
            return new SummaryUpdate(currentSummary, false);
        }

        String generatedSummary = generateSummary(runtimeConfig, currentSummary, summaryMessages);
        if (!hasText(generatedSummary)) {
            return new SummaryUpdate(currentSummary, false);
        }

        ChatConversationSummary savedSummary = upsertSummary(userId, conversationId, currentSummary, generatedSummary,
                summaryMessages.getLast().getId());
        return new SummaryUpdate(savedSummary, true);
    }

    private List<ChatMessage> messagesToSummarize(List<ChatMessage> history, ChatConversationSummary currentSummary) {
        Long summarizedMessageId = currentSummary == null ? null : currentSummary.getSummarizedMessageId();
        List<ChatMessage> unsummarized = history.stream()
                .filter(message -> summarizedMessageId == null
                        || message.getId() == null
                        || message.getId() > summarizedMessageId)
                .toList();
        if (unsummarized.size() <= RECENT_TURN_MIN_MESSAGE_COUNT) {
            return unsummarized;
        }
        return unsummarized.subList(0, unsummarized.size() - RECENT_TURN_MIN_MESSAGE_COUNT);
    }

    private String generateSummary(
            AiModelRuntimeConfig runtimeConfig,
            ChatConversationSummary currentSummary,
            List<ChatMessage> summaryMessages
    ) {
        StringBuilder prompt = new StringBuilder(SUMMARY_PROMPT).append('\n');
        if (currentSummary != null && hasText(currentSummary.getSummary())) {
            prompt.append("已有摘要：\n").append(currentSummary.getSummary()).append("\n\n");
        }
        prompt.append("待压缩历史：\n");
        for (ChatMessage message : summaryMessages) {
            prompt.append(toLlmRole(message.getRole())).append(": ")
                    .append(defaultString(message.getContent()))
                    .append('\n');
        }

        StringBuilder summary = new StringBuilder();
        LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
        client.stream(new LlmStreamRequest(
                runtimeConfig.baseUrl(),
                runtimeConfig.apiKeyCipher(),
                runtimeConfig.apiVersion(),
                runtimeConfig.modelCode(),
                List.of(new LlmMessage("user", prompt.toString())),
                runtimeConfig.maxOutputTokens(),
                false
        ), chunk -> {
            switch (chunk.type()) {
                case ANSWER_DELTA -> summary.append(defaultString(chunk.content()));
                case ERROR -> summary.setLength(0);
                case REASONING_DELTA, DONE, USAGE -> {
                }
            }
        });
        return summary.toString().strip();
    }

    private ChatConversationSummary upsertSummary(
            Long userId,
            Long conversationId,
            ChatConversationSummary currentSummary,
            String generatedSummary,
            Long summarizedMessageId
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        ChatConversationSummary target = currentSummary == null ? new ChatConversationSummary() : currentSummary;
        if (currentSummary == null) {
            target.setConversationId(conversationId);
            target.setUserId(userId);
            target.setCreatedAt(now);
        }
        target.setSummary(generatedSummary);
        target.setSummarizedMessageId(summarizedMessageId);
        target.setTokenEstimate(estimateText(generatedSummary));
        target.setUpdatedAt(now);
        if (currentSummary == null) {
            summaryMapper.insert(target);
        } else {
            summaryMapper.updateById(target);
        }
        return target;
    }

    private List<ChatMessage> messagesAfterSummary(List<ChatMessage> history, ChatConversationSummary summary) {
        Long summarizedMessageId = summary == null ? null : summary.getSummarizedMessageId();
        if (summarizedMessageId == null) {
            return history;
        }
        return history.stream()
                .filter(message -> message.getId() == null || message.getId() > summarizedMessageId)
                .toList();
    }

    private List<LlmMessage> selectRecentMessages(List<ChatMessage> candidates, int budget) {
        if (candidates.isEmpty() || budget <= 0) {
            return List.of();
        }
        List<LlmMessage> selected = new ArrayList<>();
        int usedTokens = 0;
        int start = Math.max(0, candidates.size() - RECENT_MESSAGE_MAX_COUNT);
        for (int i = candidates.size() - 1; i >= start; i--) {
            LlmMessage message = toLlmMessage(candidates.get(i));
            int messageTokens = estimateMessage(message);
            if (usedTokens + messageTokens > budget) {
                if (selected.size() >= RECENT_TURN_MIN_MESSAGE_COUNT) {
                    break;
                }
                continue;
            }
            selected.add(0, message);
            usedTokens += messageTokens;
        }
        return selected;
    }

    private LlmMessage toLlmMessage(ChatMessage message) {
        return new LlmMessage(toLlmRole(message.getRole()), defaultString(message.getContent()));
    }

    private String toLlmRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        if ("assistant".equals(normalizedRole) || "system".equals(normalizedRole)) {
            return normalizedRole;
        }
        return "user";
    }

    private LlmMessage buildCurrentUserMessage(String prompt, List<AttachmentReference> images) {
        if (images == null || images.isEmpty()) {
            return new LlmMessage("user", prompt);
        }
        List<LlmContentPart> parts = new ArrayList<>();
        parts.add(LlmContentPart.text(prompt));
        images.stream()
                .map(AttachmentReference::readUrl)
                .map(LlmContentPart::imageUrl)
                .forEach(parts::add);
        return LlmMessage.user(parts);
    }

    private int estimateHistory(List<ChatMessage> history) {
        return history.stream()
                .mapToInt(message -> estimateText(message.getContent()))
                .sum();
    }

    private int estimateCurrentInput(String prompt, List<AttachmentReference> images) {
        int estimate = estimateText(prompt);
        if (images == null || images.isEmpty()) {
            return estimate;
        }
        return estimate + images.stream()
                .mapToInt(image -> image.tokenEstimate() <= 0 ? DEFAULT_IMAGE_TOKEN_ESTIMATE : image.tokenEstimate())
                .sum();
    }

    private int estimateMessage(LlmMessage message) {
        if (message.hasParts()) {
            int estimate = 0;
            for (LlmContentPart part : message.parts()) {
                if (LlmContentPart.TYPE_TEXT.equals(part.type())) {
                    estimate += estimateText(part.text());
                } else if (LlmContentPart.TYPE_IMAGE_URL.equals(part.type())) {
                    estimate += DEFAULT_IMAGE_TOKEN_ESTIMATE;
                }
            }
            return estimate;
        }
        return estimateText(message.content());
    }

    private int estimateText(String text) {
        return tokenEstimator.estimateText(text);
    }

    private int resolveContextWindow(AiModelRuntimeConfig runtimeConfig) {
        if (runtimeConfig.contextWindow() == null || runtimeConfig.contextWindow() <= SAFETY_MARGIN_TOKENS) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        return runtimeConfig.contextWindow();
    }

    private int resolveReservedOutputTokens(AiModelRuntimeConfig runtimeConfig) {
        if (runtimeConfig.maxOutputTokens() == null || runtimeConfig.maxOutputTokens() <= 0) {
            return DEFAULT_RESERVED_OUTPUT_TOKENS;
        }
        return runtimeConfig.maxOutputTokens();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record SummaryUpdate(ChatConversationSummary summary, boolean updated) {
    }
}
