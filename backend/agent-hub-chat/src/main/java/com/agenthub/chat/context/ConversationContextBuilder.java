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
import com.agenthub.storage.attachment.service.AttachmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final AttachmentService attachmentService;
    private final TokenEstimator tokenEstimator;
    private final Function<String, LlmClient> llmClientFactory;

    @Autowired
    public ConversationContextBuilder(
            ChatMessageMapper messageMapper,
            ChatConversationSummaryMapper summaryMapper,
            AttachmentService attachmentService,
            TokenEstimator tokenEstimator
    ) {
        this(messageMapper, summaryMapper, attachmentService, tokenEstimator, LlmClientFactory::create);
    }

    public ConversationContextBuilder(
            ChatMessageMapper messageMapper,
            ChatConversationSummaryMapper summaryMapper,
            AttachmentService attachmentService,
            TokenEstimator tokenEstimator,
            Function<String, LlmClient> llmClientFactory
    ) {
        this.messageMapper = messageMapper;
        this.summaryMapper = summaryMapper;
        this.attachmentService = attachmentService;
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
        return build(userId, conversationId, runtimeConfig, currentPrompt, images, null);
    }

    public ConversationContext build(
            Long userId,
            Long conversationId,
            AiModelRuntimeConfig runtimeConfig,
            String currentPrompt,
            List<AttachmentReference> images,
            String systemPrompt
    ) {
        return build(userId, conversationId, runtimeConfig, currentPrompt, images, systemPrompt, null);
    }

    public ConversationContext build(
            Long userId,
            Long conversationId,
            AiModelRuntimeConfig runtimeConfig,
            String currentPrompt,
            List<AttachmentReference> images,
            String systemPrompt,
            OffsetDateTime currentMessageTime
    ) {
        int contextWindow = resolveContextWindow(runtimeConfig);
        int reservedOutputTokens = resolveReservedOutputTokens(runtimeConfig);
        int historyImageTokenEstimate = resolveImageTokenEstimate(runtimeConfig);
        int availableInputTokens = contextWindow - reservedOutputTokens - SAFETY_MARGIN_TOKENS;
        if (availableInputTokens <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, CURRENT_INPUT_TOO_LONG);
        }

        int currentInputEstimate = estimateCurrentInput(currentPrompt, images, currentMessageTime);
        if (currentInputEstimate > availableInputTokens) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, CURRENT_INPUT_TOO_LONG);
        }

        List<ChatMessage> history = listHistory(userId, conversationId);
        Map<Long, List<AttachmentReference>> attachmentsByMessageId =
                listHistoryAttachments(userId, history, historyImageTokenEstimate);
        ChatConversationSummary summary = loadSummary(userId, conversationId);
        SummaryUpdate summaryUpdate = maybeUpdateSummary(
                userId,
                conversationId,
                runtimeConfig,
                contextWindow,
                availableInputTokens,
                historyImageTokenEstimate,
                history,
                attachmentsByMessageId,
                summary
        );
        ChatConversationSummary effectiveSummary = summaryUpdate.summary();

        List<LlmMessage> messages = new ArrayList<>();
        int usedTokens = 0;
        if (hasText(systemPrompt)) {
            LlmMessage systemMessage = new LlmMessage("system", systemPrompt.strip());
            int systemTokens = estimateMessage(systemMessage, historyImageTokenEstimate);
            if (systemTokens + currentInputEstimate > availableInputTokens) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, CURRENT_INPUT_TOO_LONG);
            }
            messages.add(systemMessage);
            usedTokens += systemTokens;
        }
        if (effectiveSummary != null && hasText(effectiveSummary.getSummary())) {
            LlmMessage summaryMessage = new LlmMessage("system", "以下是此前会话摘要：\n" + effectiveSummary.getSummary());
            int summaryTokens = estimateMessage(summaryMessage, historyImageTokenEstimate);
            if (usedTokens + summaryTokens + currentInputEstimate <= availableInputTokens) {
                messages.add(summaryMessage);
                usedTokens += summaryTokens;
            }
        }

        List<ChatMessage> candidates = messagesAfterSummary(history, effectiveSummary);
        List<LlmMessage> recentMessages = selectRecentMessages(candidates,
                attachmentsByMessageId,
                availableInputTokens - usedTokens - currentInputEstimate,
                historyImageTokenEstimate);
        for (LlmMessage recentMessage : recentMessages) {
            int messageTokens = estimateMessage(recentMessage, historyImageTokenEstimate);
            if (usedTokens + messageTokens + currentInputEstimate <= availableInputTokens) {
                messages.add(recentMessage);
                usedTokens += messageTokens;
            }
        }

        LlmMessage currentMessage = buildCurrentUserMessage(currentPrompt, images, currentMessageTime);
        messages.add(currentMessage);
        usedTokens += currentInputEstimate;
        return new ConversationContext(List.copyOf(messages), summaryUpdate.updated(), usedTokens);
    }

    private List<ChatMessage> listHistory(Long userId, Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
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

    private Map<Long, List<AttachmentReference>> listHistoryAttachments(
            Long userId,
            List<ChatMessage> history,
            int imageTokenEstimate
    ) {
        List<Long> messageIds = history.stream()
                .map(ChatMessage::getId)
                .filter(id -> id != null)
                .toList();
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AttachmentReference>> attachments =
                attachmentService.listMessageAttachmentReferences(userId, messageIds, imageTokenEstimate);
        if (attachments == null || attachments.isEmpty()) {
            return Map.of();
        }
        return attachments;
    }

    private ChatConversationSummary loadSummary(Long userId, Long conversationId) {
        if (conversationId == null) {
            return null;
        }
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
            int availableInputTokens,
            int historyImageTokenEstimate,
            List<ChatMessage> history,
            Map<Long, List<AttachmentReference>> attachmentsByMessageId,
            ChatConversationSummary currentSummary
    ) {
        int fullHistoryEstimate = estimateHistory(history, attachmentsByMessageId, historyImageTokenEstimate)
                + estimateText(currentSummary == null ? null : currentSummary.getSummary());
        int summaryTriggerTokens = (int) Math.ceil(Math.min(contextWindow * SUMMARY_TRIGGER_RATIO, availableInputTokens));
        if (fullHistoryEstimate < summaryTriggerTokens) {
            return new SummaryUpdate(currentSummary, false);
        }

        List<ChatMessage> summaryMessages = messagesToSummarize(history, currentSummary);
        if (summaryMessages.isEmpty()) {
            return new SummaryUpdate(currentSummary, false);
        }

        SummaryPrompt summaryPrompt = buildSummaryPrompt(currentSummary, summaryMessages, availableInputTokens);
        if (summaryPrompt.includedMessages().isEmpty()) {
            return new SummaryUpdate(currentSummary, false);
        }

        try {
            String generatedSummary = generateSummary(runtimeConfig, summaryPrompt.prompt());
            if (!hasText(generatedSummary)) {
                return new SummaryUpdate(currentSummary, false);
            }

            ChatConversationSummary savedSummary = upsertSummary(userId, conversationId, currentSummary, generatedSummary,
                    summaryPrompt.includedMessages().getLast().getId());
            return new SummaryUpdate(savedSummary, true);
        } catch (RuntimeException exception) {
            return new SummaryUpdate(currentSummary, false);
        }
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

    private SummaryPrompt buildSummaryPrompt(
            ChatConversationSummary currentSummary,
            List<ChatMessage> summaryMessages,
            int availableInputTokens
    ) {
        StringBuilder prompt = new StringBuilder(SUMMARY_PROMPT).append('\n');
        if (currentSummary != null && hasText(currentSummary.getSummary())) {
            prompt.append("已有摘要：\n").append(currentSummary.getSummary()).append("\n\n");
        }
        prompt.append("待压缩历史：\n");
        int usedTokens = estimateText(prompt.toString());
        List<ChatMessage> includedMessages = new ArrayList<>();
        for (ChatMessage message : summaryMessages) {
            String line = toLlmRole(message.getRole()) + ": " + defaultString(message.getContent()) + '\n';
            int lineTokens = estimateText(line);
            if (usedTokens + lineTokens > availableInputTokens) {
                break;
            }
            prompt.append(line);
            usedTokens += lineTokens;
            includedMessages.add(message);
        }
        return new SummaryPrompt(prompt.toString(), List.copyOf(includedMessages));
    }

    private String generateSummary(AiModelRuntimeConfig runtimeConfig, String prompt) {
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

    private List<LlmMessage> selectRecentMessages(
            List<ChatMessage> candidates,
            Map<Long, List<AttachmentReference>> attachmentsByMessageId,
            int budget,
            int imageTokenEstimate
    ) {
        if (candidates.isEmpty() || budget <= 0) {
            return List.of();
        }
        List<LlmMessage> selected = new ArrayList<>();
        int usedTokens = 0;
        int start = Math.max(0, candidates.size() - RECENT_MESSAGE_MAX_COUNT);
        for (int i = candidates.size() - 1; i >= start; i--) {
            LlmMessage message = toLlmMessage(candidates.get(i), attachmentsByMessageId);
            int messageTokens = estimateMessage(message, imageTokenEstimate);
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

    private LlmMessage toLlmMessage(
            ChatMessage message,
            Map<Long, List<AttachmentReference>> attachmentsByMessageId
    ) {
        String role = toLlmRole(message.getRole());
        List<AttachmentReference> attachments = attachmentsFor(message, attachmentsByMessageId);
        if (!"user".equals(role) || attachments.isEmpty()) {
            return new LlmMessage(role, withMessageTime(message.getCreatedAt(), message.getContent()));
        }
        List<LlmContentPart> parts = new ArrayList<>();
        String text = withAttachmentImportHints(withMessageTime(message.getCreatedAt(), message.getContent()),
                attachmentHintsFromReferences(attachments));
        if (hasText(text)) {
            parts.add(LlmContentPart.text(text));
        }
        attachments.stream()
                .map(AttachmentReference::readUrl)
                .map(LlmContentPart::imageUrl)
                .forEach(parts::add);
        return LlmMessage.user(parts);
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
        return buildCurrentUserMessage(prompt, images, null);
    }

    private LlmMessage buildCurrentUserMessage(
            String prompt,
            List<AttachmentReference> images,
            OffsetDateTime currentMessageTime
    ) {
        String content = withMessageTime(currentMessageTime, prompt);
        if (images == null || images.isEmpty()) {
            return new LlmMessage("user", content);
        }
        List<LlmContentPart> parts = new ArrayList<>();
        String text = withAttachmentImportHints(content, attachmentHintsFromReferences(images));
        if (hasText(text)) {
            parts.add(LlmContentPart.text(text));
        }
        images.stream()
                .map(AttachmentReference::readUrl)
                .map(LlmContentPart::imageUrl)
                .forEach(parts::add);
        return LlmMessage.user(parts);
    }

    private int estimateHistory(
            List<ChatMessage> history,
            Map<Long, List<AttachmentReference>> attachmentsByMessageId,
            int imageTokenEstimate
    ) {
        return history.stream()
                .mapToInt(message -> {
                    List<AttachmentReference> attachments = attachmentsFor(message, attachmentsByMessageId);
                    String text = withAttachmentImportHints(
                            withMessageTime(message.getCreatedAt(), message.getContent()),
                            attachmentHintsFromReferences(attachments)
                    );
                    return estimateText(text) + attachments.size() * imageTokenEstimate;
                })
                .sum();
    }

    private List<AttachmentReference> attachmentsFor(
            ChatMessage message,
            Map<Long, List<AttachmentReference>> attachmentsByMessageId
    ) {
        if (message.getId() == null) {
            return List.of();
        }
        return attachmentsByMessageId.getOrDefault(message.getId(), List.of());
    }

    private int estimateCurrentInput(String prompt, List<AttachmentReference> images) {
        return estimateCurrentInput(prompt, images, null);
    }

    private int estimateCurrentInput(String prompt, List<AttachmentReference> images, OffsetDateTime currentMessageTime) {
        int estimate = estimateText(withAttachmentImportHints(
                withMessageTime(currentMessageTime, prompt),
                attachmentHintsFromReferences(images)
        ));
        if (images == null || images.isEmpty()) {
            return estimate;
        }
        return estimate + images.stream()
                .mapToInt(image -> image.tokenEstimate() <= 0 ? DEFAULT_IMAGE_TOKEN_ESTIMATE : image.tokenEstimate())
                .sum();
    }

    private String withMessageTime(OffsetDateTime messageTime, String content) {
        String safeContent = defaultString(content);
        if (messageTime == null) {
            return safeContent;
        }
        String prefix = "[消息时间：" + messageTime + "]";
        return safeContent.isBlank() ? prefix : prefix + "\n" + safeContent;
    }

    private String withAttachmentImportHints(String content, List<AttachmentImportHint> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return content;
        }
        StringBuilder builder = new StringBuilder(defaultString(content));
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("可导入附件清单：\n");
        for (AttachmentImportHint attachment : attachments) {
            builder.append("- attachment_id=")
                    .append(attachment.attachmentId())
                    .append(", filename=")
                    .append(defaultString(attachment.filename()))
                    .append(", content_type=")
                    .append(defaultString(attachment.contentType()))
                    .append('\n');
        }
        builder.append("需要把用户上传文件放入 /workspace/input 时，请调用 sandbox.upload_file 并使用对应 attachment_id。");
        return builder.toString();
    }

    private List<AttachmentImportHint> attachmentHintsFromReferences(List<AttachmentReference> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .filter(image -> image != null && image.attachmentId() != null)
                .map(image -> new AttachmentImportHint(image.attachmentId(), image.filename(), image.contentType()))
                .toList();
    }

    private int estimateMessage(LlmMessage message, int imageTokenEstimate) {
        if (message.hasParts()) {
            int estimate = 0;
            for (LlmContentPart part : message.parts()) {
                if (LlmContentPart.TYPE_TEXT.equals(part.type())) {
                    estimate += estimateText(part.text());
                } else if (LlmContentPart.TYPE_IMAGE_URL.equals(part.type())) {
                    estimate += imageTokenEstimate;
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
        if (runtimeConfig.contextWindow() == null || runtimeConfig.contextWindow() <= 0) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        return runtimeConfig.contextWindow();
    }

    private int resolveImageTokenEstimate(AiModelRuntimeConfig runtimeConfig) {
        if (runtimeConfig.imageTokenEstimate() == null || runtimeConfig.imageTokenEstimate() <= 0) {
            return DEFAULT_IMAGE_TOKEN_ESTIMATE;
        }
        return runtimeConfig.imageTokenEstimate();
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

    private record SummaryPrompt(String prompt, List<ChatMessage> includedMessages) {
    }

    private record AttachmentImportHint(Long attachmentId, String filename, String contentType) {
    }
}
