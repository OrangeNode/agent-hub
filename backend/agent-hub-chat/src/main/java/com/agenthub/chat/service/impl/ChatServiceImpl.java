package com.agenthub.chat.service.impl;

import com.agenthub.agent.dto.AgentRuntimeConfig;
import com.agenthub.agent.service.AgentService;
import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.chat.context.ConversationContext;
import com.agenthub.chat.context.ConversationContextBuilder;
import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.conversation.entity.ChatConversation;
import com.agenthub.chat.conversation.mapper.ChatConversationMapper;
import com.agenthub.chat.message.dto.MessageItem;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.service.ChatService;
import com.agenthub.chat.title.ConversationTitleService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventSender;
import com.agenthub.sse.SseEventType;
import com.agenthub.storage.attachment.dto.AttachmentItem;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.service.AttachmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatServiceImpl implements ChatService {

    private static final long SSE_TIMEOUT = 0L;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_STREAMING = "STREAMING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String LLM_STREAM_FAILED = "LLM_STREAM_FAILED";
    private static final String SSE_CONNECTION_CLOSED = "SSE 连接已断开";
    private static final int TITLE_MAX_OUTPUT_TOKENS = 64;
    private static final int DEFAULT_IMAGE_TOKEN_ESTIMATE = 1500;
    private static final String CONTEXT_LENGTH_RETRY_FAILED_MESSAGE =
            "模型上下文长度仍然超限，请缩短输入或切换更大上下文模型";

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AiModelService modelService;
    private final ConversationTitleService titleService;
    private final TransactionTemplate transactionTemplate;
    private final AttachmentService attachmentService;
    private final AgentService agentService;
    private final SseEventSender sseEventSender;
    private final Function<String, LlmClient> llmClientFactory;
    private final ConversationContextBuilder contextBuilder;
    private final Executor streamExecutor;

    @Autowired
    public ChatServiceImpl(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            ConversationTitleService titleService,
            TransactionTemplate transactionTemplate,
            AttachmentService attachmentService,
            AgentService agentService,
            ConversationContextBuilder contextBuilder
    ) {
        this(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, new SseEventSender(),
                attachmentService, agentService, contextBuilder, LlmClientFactory::create, defaultStreamExecutor());
    }

    public ChatServiceImpl(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            ConversationTitleService titleService,
            TransactionTemplate transactionTemplate,
            SseEventSender sseEventSender,
            AttachmentService attachmentService,
            ConversationContextBuilder contextBuilder,
            Function<String, LlmClient> llmClientFactory,
            Executor streamExecutor
    ) {
        this(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, null, contextBuilder, llmClientFactory, streamExecutor);
    }

    public ChatServiceImpl(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            ConversationTitleService titleService,
            TransactionTemplate transactionTemplate,
            SseEventSender sseEventSender,
            AttachmentService attachmentService,
            AgentService agentService,
            ConversationContextBuilder contextBuilder,
            Function<String, LlmClient> llmClientFactory,
            Executor streamExecutor
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.modelService = modelService;
        this.titleService = titleService;
        this.transactionTemplate = transactionTemplate;
        this.attachmentService = attachmentService;
        this.agentService = agentService;
        this.sseEventSender = sseEventSender;
        this.llmClientFactory = llmClientFactory;
        this.contextBuilder = contextBuilder;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 创建 SSE 通道并异步执行模型流式调用，调用方可立即拿到响应连接。
     */
    @Override
    public SseEmitter stream(
            Long userId,
            Long conversationId,
            Long modelId,
            String agentKey,
            String prompt,
            boolean thinkingEnabled,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds
    ) {
        String normalizedPrompt = normalizePrompt(prompt);
        requireChatInput(normalizedPrompt, attachmentIds, reuseAttachmentIds);
        AgentRuntimeConfig agentRuntime = resolveAgentRuntime(agentKey);
        AiModelRuntimeConfig runtimeConfig = requireRuntimeConfig(modelId, agentRuntime);
        ChatPreparation preparation = prepareChat(
                userId,
                conversationId,
                resolvedAgentKey(agentKey, agentRuntime),
                normalizedPrompt,
                runtimeConfig,
                agentRuntime,
                attachmentIds,
                reuseAttachmentIds
        );
        PreparedChat preparedChat = transactionTemplate.execute(status -> persistChatRecords(preparation));
        if (preparedChat == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "聊天记录准备失败");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        boolean connected = send(emitter, SseEventType.CONVERSATION,
                new SseEventData.Conversation(preparedChat.conversation().getId(), preparedChat.conversation().getTitle()))
                && send(emitter, SseEventType.MESSAGE,
                new SseEventData.Message(preparedChat.assistantMessage().getId(), ROLE_ASSISTANT));
        if (connected) {
            streamExecutor.execute(() -> streamLlm(emitter, runtimeConfig, preparedChat.context().messages(),
                    preparedChat.assistantMessage(), thinkingEnabled));
        } else {
            // 初始事件发送失败说明客户端已断开，直接标记助手消息失败。
            failAssistantMessage(preparedChat.assistantMessage(), new StringBuilder(), new StringBuilder(), SSE_CONNECTION_CLOSED);
            sseEventSender.complete(emitter);
        }
        return emitter;
    }

    private ChatPreparation prepareChat(
            Long userId,
            Long conversationId,
            String agentKey,
            String normalizedPrompt,
            AiModelRuntimeConfig runtimeConfig,
            AgentRuntimeConfig agentRuntime,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds
    ) {
        ChatConversation conversation = resolveConversation(userId, conversationId, agentKey, runtimeConfig, normalizedPrompt);
        int imageTokenEstimate = runtimeConfig.imageTokenEstimate() == null
                ? DEFAULT_IMAGE_TOKEN_ESTIMATE
                : runtimeConfig.imageTokenEstimate();
        Long resolvedConversationId = conversationId == null ? null : conversation.getId();
        List<AttachmentReference> images = attachmentService.resolveImagesForChat(
                userId,
                resolvedConversationId,
                attachmentIds,
                reuseAttachmentIds,
                imageTokenEstimate
        );
        if (!images.isEmpty() && !runtimeConfig.imageInputSupported()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前模型不支持图片输入");
        }
        ConversationContext context = contextBuilder.build(userId, resolvedConversationId, runtimeConfig, normalizedPrompt,
                images, systemPrompt(agentRuntime));
        return new ChatPreparation(
                userId,
                conversationId == null,
                conversation,
                normalizedPrompt,
                runtimeConfig,
                attachmentIds,
                images,
                context
        );
    }

    private PreparedChat persistChatRecords(ChatPreparation preparation) {
        ChatConversation conversation = preparation.conversation();
        if (preparation.newConversation()) {
            conversationMapper.insert(conversation);
        }
        ChatMessage userMessage = saveUserMessage(preparation.userId(), conversation.getId(),
                preparation.normalizedPrompt(), preparation.runtimeConfig());
        attachmentService.bindToMessage(preparation.userId(), conversation.getId(), userMessage.getId(),
                preparation.attachmentIds());
        ChatMessage assistantMessage = saveAssistantMessage(preparation.userId(), conversation.getId(),
                preparation.runtimeConfig());
        touchConversation(conversation);
        return new PreparedChat(conversation, userMessage, assistantMessage, preparation.images(), preparation.context());
    }

    private static Executor defaultStreamExecutor() {
        return command -> Thread.ofVirtual().name("chat-stream-").start(command);
    }

    private record PreparedChat(
            ChatConversation conversation,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<AttachmentReference> images,
            ConversationContext context
    ) {
    }

    private record ChatPreparation(
            Long userId,
            boolean newConversation,
            ChatConversation conversation,
            String normalizedPrompt,
            AiModelRuntimeConfig runtimeConfig,
            List<Long> attachmentIds,
            List<AttachmentReference> images,
            ConversationContext context
    ) {
    }

    private boolean hasIds(List<Long> ids) {
        return ids != null && ids.stream().anyMatch(id -> id != null);
    }

    /**
     * 查询用户仍处于有效状态的会话列表。
     */
    @Override
    public List<ConversationItem> listConversations(Long userId) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getUserId, userId)
                .eq(ChatConversation::getStatus, STATUS_ACTIVE)
                .orderByDesc(ChatConversation::getUpdatedAt)
                .orderByDesc(ChatConversation::getId);
        return conversationMapper.selectList(wrapper).stream()
                .map(this::toConversationItem)
                .toList();
    }

    /**
     * 查询会话消息前先校验会话归属，避免越权读取。
     */
    @Override
    public List<MessageItem> listMessages(Long userId, Long conversationId) {
        requireActiveConversation(userId, conversationId);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .orderByAsc(ChatMessage::getId);
        List<ChatMessage> messages = messageMapper.selectList(wrapper);
        List<Long> messageIds = messages.stream()
                .map(ChatMessage::getId)
                .toList();
        Map<Long, List<AttachmentItem>> attachmentsByMessageId = attachmentService
                .listMessageAttachments(userId, messageIds)
                .stream()
                .collect(Collectors.groupingBy(AttachmentItem::messageId));
        return messages.stream()
                .map(message -> toMessageItem(message,
                        attachmentsByMessageId.getOrDefault(message.getId(), List.of())))
                .toList();
    }

    /**
     * 将会话标记为删除状态，保留历史消息数据。
     */
    @Override
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        ChatConversation conversation = requireActiveConversation(userId, conversationId);
        conversation.setStatus(STATUS_DELETED);
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    /**
     * 更新会话标题，并刷新会话更新时间用于列表排序。
     */
    @Override
    @Transactional
    public void renameConversation(Long userId, Long conversationId, String title) {
        String normalizedTitle = requireTitle(title);
        ChatConversation conversation = requireActiveConversation(userId, conversationId);
        conversation.setTitle(normalizedTitle);
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    private String normalizePrompt(String prompt) {
        return prompt == null ? "" : prompt.strip();
    }

    private void requireChatInput(String prompt, List<Long> attachmentIds, List<Long> reuseAttachmentIds) {
        if ((prompt == null || prompt.isBlank()) && !hasIds(attachmentIds) && !hasIds(reuseAttachmentIds)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能为空");
        }
        return title.strip();
    }

    private AiModelRuntimeConfig requireRuntimeConfig(Long modelId) {
        return requireRuntimeConfig(modelId, null);
    }

    private AiModelRuntimeConfig requireRuntimeConfig(Long modelId, AgentRuntimeConfig agentRuntime) {
        if (modelId == null) {
            if (agentRuntime != null && agentRuntime.defaultModelId() != null) {
                return modelService.requireAvailableRuntimeConfig(agentRuntime.defaultModelId());
            }
            return modelService.requireDefaultRuntimeConfig();
        }
        return modelService.requireAvailableRuntimeConfig(modelId);
    }

    private AgentRuntimeConfig resolveAgentRuntime(String agentKey) {
        if (!hasText(agentKey) || agentService == null) {
            return null;
        }
        return agentService.requireEnabledRuntimeConfig(agentKey.strip());
    }

    private String resolvedAgentKey(String agentKey, AgentRuntimeConfig agentRuntime) {
        if (agentRuntime != null) {
            return agentRuntime.agentKey();
        }
        return hasText(agentKey) ? agentKey.strip() : null;
    }

    private String systemPrompt(AgentRuntimeConfig agentRuntime) {
        return agentRuntime == null ? null : agentRuntime.systemPrompt();
    }

    /**
     * 根据 conversationId 决定创建新会话还是续写已有会话。
     */
    private ChatConversation resolveConversation(
            Long userId,
            Long conversationId,
            String agentKey,
            AiModelRuntimeConfig runtimeConfig,
            String prompt
    ) {
        if (conversationId == null) {
            return createConversation(userId, agentKey, runtimeConfig, prompt);
        }
        return requireActiveConversation(userId, conversationId);
    }

    private ChatConversation createConversation(
            Long userId,
            String agentKey,
            AiModelRuntimeConfig runtimeConfig,
            String prompt
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTitle(generateConversationTitle(runtimeConfig, prompt));
        conversation.setAgentKey(agentKey);
        conversation.setModelId(runtimeConfig.modelId());
        conversation.setProviderId(runtimeConfig.providerId());
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        return conversation;
    }

    private ChatConversation requireActiveConversation(Long userId, Long conversationId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || !userId.equals(conversation.getUserId())
                || !STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    /**
     * 优先让模型生成会话标题，模型失败或返回无效标题时使用本地兜底标题。
     */
    private String generateConversationTitle(AiModelRuntimeConfig runtimeConfig, String prompt) {
        String fallbackTitle = titleService.generateFallbackTitle(prompt);
        try {
            LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
            StringBuilder title = new StringBuilder();
            client.stream(buildTitleRequest(runtimeConfig, prompt), chunk -> {
                if (chunk.type() == LlmStreamChunkType.ANSWER_DELTA) {
                    title.append(defaultString(chunk.content()));
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

    private ChatMessage saveUserMessage(
            Long userId,
            Long conversationId,
            String prompt,
            AiModelRuntimeConfig runtimeConfig
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(ROLE_USER);
        message.setContent(prompt);
        message.setReasoningContent(null);
        message.setProviderId(runtimeConfig.providerId());
        message.setModelId(runtimeConfig.modelId());
        message.setStatus(STATUS_COMPLETED);
        message.setCreatedAt(now);
        message.setCompletedAt(now);
        messageMapper.insert(message);
        return message;
    }

    private ChatMessage saveAssistantMessage(Long userId, Long conversationId, AiModelRuntimeConfig runtimeConfig) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(ROLE_ASSISTANT);
        message.setContent("");
        message.setReasoningContent("");
        message.setProviderId(runtimeConfig.providerId());
        message.setModelId(runtimeConfig.modelId());
        message.setStatus(STATUS_STREAMING);
        message.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    private void touchConversation(ChatConversation conversation) {
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    /**
     * 调用 LLM 客户端并把统一 chunk 转成 SSE 事件，同时维护助手消息最终状态。
     */
    private void streamLlm(
            SseEmitter emitter,
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            ChatMessage assistantMessage,
            boolean thinkingEnabled
    ) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        // 只有用户开启思考且模型声明支持时，才向前端透出推理片段。
        boolean reasoningRequested = thinkingEnabled && runtimeConfig.reasoningSupported();
        try {
            LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
            StreamAttemptOutcome outcome = streamOnce(client, runtimeConfig, messages, reasoningRequested,
                    emitter, assistantMessage, content, reasoningContent, finished);
            if (outcome.retryRequested() && !finished.get()) {
                StreamAttemptOutcome retryOutcome = streamOnce(client, runtimeConfig, compactMessagesForRetry(messages),
                        reasoningRequested, emitter, assistantMessage, content, reasoningContent, finished);
                if (retryOutcome.retryRequested() && finished.compareAndSet(false, true)) {
                    failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent,
                            CONTEXT_LENGTH_RETRY_FAILED_MESSAGE);
                }
            }
        } catch (Exception exception) {
            if (isRetryableBeforeDelta(content, reasoningContent) && isContextLengthError(exception.getMessage())) {
                retryAfterContextLengthException(emitter, runtimeConfig, messages, assistantMessage, reasoningRequested,
                        content, reasoningContent, finished, exception);
            } else if (finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, exception.getMessage());
            }
        } finally {
            send(emitter, SseEventType.DONE, new SseEventData.Done());
            sseEventSender.complete(emitter);
        }
    }

    private LlmStreamRequest buildRequest(
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            boolean reasoningRequested
    ) {
        return new LlmStreamRequest(
                runtimeConfig.baseUrl(),
                runtimeConfig.apiKeyCipher(),
                runtimeConfig.apiVersion(),
                runtimeConfig.modelCode(),
                messages,
                runtimeConfig.maxOutputTokens(),
                reasoningRequested
        );
    }

    private StreamAttemptOutcome streamOnce(
            LlmClient client,
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            boolean reasoningRequested,
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            AtomicBoolean finished
    ) {
        AtomicBoolean retryRequested = new AtomicBoolean(false);
        AtomicBoolean sawDelta = new AtomicBoolean(false);
        client.stream(buildRequest(runtimeConfig, messages, reasoningRequested), chunk -> {
            if (retryRequested.get()) {
                return;
            }
            if (isDeltaChunk(chunk)) {
                sawDelta.set(true);
            }
            if (chunk.type() == LlmStreamChunkType.ERROR
                    && !sawDelta.get()
                    && isContextLengthError(chunk.errorMessage())) {
                retryRequested.set(true);
                return;
            }
            handleChunk(emitter, assistantMessage, content, reasoningContent, finished, reasoningRequested, chunk);
        });
        return new StreamAttemptOutcome(retryRequested.get());
    }

    private void retryAfterContextLengthException(
            SseEmitter emitter,
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            ChatMessage assistantMessage,
            boolean reasoningRequested,
            StringBuilder content,
            StringBuilder reasoningContent,
            AtomicBoolean finished,
            Exception originalException
    ) {
        try {
            LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
            StreamAttemptOutcome retryOutcome = streamOnce(client, runtimeConfig, compactMessagesForRetry(messages),
                    reasoningRequested, emitter, assistantMessage, content, reasoningContent, finished);
            if (retryOutcome.retryRequested() && finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent,
                        CONTEXT_LENGTH_RETRY_FAILED_MESSAGE);
            }
        } catch (Exception retryException) {
            if (finished.compareAndSet(false, true)) {
                String message = isContextLengthError(retryException.getMessage())
                        ? CONTEXT_LENGTH_RETRY_FAILED_MESSAGE
                        : defaultString(retryException.getMessage());
                if (message.isBlank()) {
                    message = originalException.getMessage();
                }
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, message);
            }
        }
    }

    private List<LlmMessage> compactMessagesForRetry(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            LlmMessage message = messages.get(index);
            if ("user".equals(message.role())) {
                return List.of(message);
            }
        }
        return List.of(messages.getLast());
    }

    private boolean isRetryableBeforeDelta(StringBuilder content, StringBuilder reasoningContent) {
        return content.isEmpty() && reasoningContent.isEmpty();
    }

    private boolean isDeltaChunk(LlmStreamChunk chunk) {
        return chunk.type() == LlmStreamChunkType.ANSWER_DELTA
                || chunk.type() == LlmStreamChunkType.REASONING_DELTA;
    }

    private boolean isContextLengthError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        return normalized.contains("context length")
                || normalized.contains("context_length_exceeded")
                || normalized.contains("maximum context")
                || normalized.contains("max context")
                || normalized.contains("too many tokens")
                || normalized.contains("token limit")
                || normalized.contains("超过上下文")
                || normalized.contains("上下文长度")
                || (normalized.contains("token") && normalized.contains("exceed"));
    }

    private LlmStreamRequest buildTitleRequest(AiModelRuntimeConfig runtimeConfig, String prompt) {
        return new LlmStreamRequest(
                runtimeConfig.baseUrl(),
                runtimeConfig.apiKeyCipher(),
                runtimeConfig.apiVersion(),
                runtimeConfig.modelCode(),
                List.of(new LlmMessage("user", titleService.buildTitlePrompt(prompt))),
                TITLE_MAX_OUTPUT_TOKENS,
                false
        );
    }

    /**
     * 处理模型流式 chunk，保证成功、失败、断线三类终态只落库一次。
     */
    private void handleChunk(
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            AtomicBoolean finished,
            boolean reasoningRequested,
            LlmStreamChunk chunk
    ) {
        if (finished.get()) {
            return;
        }
        LlmStreamChunkType type = chunk.type();
        String delta = defaultString(chunk.content());
        if (type == LlmStreamChunkType.REASONING_DELTA) {
            if (!reasoningRequested) {
                return;
            }
            reasoningContent.append(delta);
            assistantMessage.setReasoningContent(reasoningContent.toString());
            if (!send(emitter, SseEventType.REASONING_DELTA, new SseEventData.Delta(delta))
                    && finished.compareAndSet(false, true)) {
                failAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED);
            }
        } else if (type == LlmStreamChunkType.ANSWER_DELTA) {
            content.append(delta);
            assistantMessage.setContent(content.toString());
            if (!send(emitter, SseEventType.ANSWER_DELTA, new SseEventData.Delta(delta))
                    && finished.compareAndSet(false, true)) {
                failAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED);
            }
        } else if (type == LlmStreamChunkType.ERROR) {
            if (finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, chunk.errorMessage());
            }
        } else if (type == LlmStreamChunkType.DONE) {
            if (finished.compareAndSet(false, true)) {
                completeAssistantMessage(emitter, assistantMessage, content, reasoningContent);
            }
        }
    }

    /**
     * 将助手消息标记为完成，并通知前端消息已结束。
     */
    private void completeAssistantMessage(
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent
    ) {
        assistantMessage.setContent(content.toString());
        assistantMessage.setReasoningContent(reasoningContent.toString());
        assistantMessage.setStatus(STATUS_COMPLETED);
        assistantMessage.setCompletedAt(OffsetDateTime.now());
        messageMapper.updateById(assistantMessage);
        if (!send(emitter, SseEventType.MESSAGE_COMPLETED,
                new SseEventData.MessageCompleted(assistantMessage.getId(), STATUS_COMPLETED))) {
            failAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED);
        }
    }

    /**
     * 将助手消息标记为失败，并通过 SSE 发送统一错误事件。
     */
    private void failAssistantMessageAndSendError(
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String errorMessage
    ) {
        String resolvedMessage = failAssistantMessage(assistantMessage, content, reasoningContent, errorMessage);
        send(emitter, SseEventType.ERROR, new SseEventData.Error(LLM_STREAM_FAILED, resolvedMessage));
    }

    /**
     * 持久化失败状态时保留已生成的正文和推理内容，方便用户看到部分结果。
     */
    private String failAssistantMessage(
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String errorMessage
    ) {
        String resolvedMessage = errorMessage == null || errorMessage.isBlank() ? "模型调用失败" : errorMessage;
        assistantMessage.setContent(content.toString());
        assistantMessage.setReasoningContent(reasoningContent.toString());
        assistantMessage.setStatus(STATUS_FAILED);
        assistantMessage.setErrorMessage(resolvedMessage);
        assistantMessage.setCompletedAt(OffsetDateTime.now());
        messageMapper.updateById(assistantMessage);
        return resolvedMessage;
    }

    /**
     * 包装 SSE 发送结果，调用方据此判断客户端连接是否仍然可用。
     */
    private boolean send(SseEmitter emitter, SseEventType eventType, SseEventData data) {
        try {
            sseEventSender.send(emitter, eventType, data);
            return true;
        } catch (IOException exception) {
            sseEventSender.completeWithError(emitter, exception);
            return false;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record StreamAttemptOutcome(boolean retryRequested) {
    }

    private ConversationItem toConversationItem(ChatConversation conversation) {
        return new ConversationItem(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getAgentKey(),
                conversation.getModelId(),
                conversation.getStatus()
        );
    }

    private MessageItem toMessageItem(ChatMessage message, List<AttachmentItem> attachments) {
        return new MessageItem(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getReasoningContent(),
                message.getStatus(),
                attachments
        );
    }
}
