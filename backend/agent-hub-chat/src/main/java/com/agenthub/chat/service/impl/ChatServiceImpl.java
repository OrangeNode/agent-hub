package com.agenthub.chat.service.impl;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.service.AiModelService;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AiModelService modelService;
    private final ConversationTitleService titleService;
    private final SseEventSender sseEventSender;
    private final Function<String, LlmClient> llmClientFactory;
    private final Executor streamExecutor;

    @Autowired
    public ChatServiceImpl(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            ConversationTitleService titleService
    ) {
        this(conversationMapper, messageMapper, modelService, titleService, new SseEventSender(),
                LlmClientFactory::create, defaultStreamExecutor());
    }

    public ChatServiceImpl(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            ConversationTitleService titleService,
            SseEventSender sseEventSender,
            Function<String, LlmClient> llmClientFactory,
            Executor streamExecutor
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.modelService = modelService;
        this.titleService = titleService;
        this.sseEventSender = sseEventSender;
        this.llmClientFactory = llmClientFactory;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 创建 SSE 通道并异步执行模型流式调用，调用方可立即拿到响应连接。
     */
    @Override
    public SseEmitter stream(Long userId, Long conversationId, Long modelId, String agentKey, String prompt, boolean thinkingEnabled) {
        String normalizedPrompt = requirePrompt(prompt);
        AiModelRuntimeConfig runtimeConfig = requireRuntimeConfig(modelId);
        ChatConversation conversation = resolveConversation(userId, conversationId, agentKey, runtimeConfig, normalizedPrompt);
        saveUserMessage(userId, conversation.getId(), normalizedPrompt, runtimeConfig);
        ChatMessage assistantMessage = saveAssistantMessage(userId, conversation.getId(), runtimeConfig);
        touchConversation(conversation);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        boolean connected = send(emitter, SseEventType.CONVERSATION,
                new SseEventData.Conversation(conversation.getId(), conversation.getTitle()))
                && send(emitter, SseEventType.MESSAGE, new SseEventData.Message(assistantMessage.getId(), ROLE_ASSISTANT));
        if (connected) {
            streamExecutor.execute(() -> streamLlm(emitter, runtimeConfig, normalizedPrompt, assistantMessage, thinkingEnabled));
        } else {
            // 初始事件发送失败说明客户端已断开，直接标记助手消息失败。
            failAssistantMessage(assistantMessage, new StringBuilder(), new StringBuilder(), SSE_CONNECTION_CLOSED);
            sseEventSender.complete(emitter);
        }
        return emitter;
    }

    private static Executor defaultStreamExecutor() {
        return command -> Thread.ofVirtual().name("chat-stream-").start(command);
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
        return messageMapper.selectList(wrapper).stream()
                .map(this::toMessageItem)
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

    private String requirePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }
        return prompt;
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能为空");
        }
        return title.strip();
    }

    private AiModelRuntimeConfig requireRuntimeConfig(Long modelId) {
        if (modelId == null) {
            return modelService.requireDefaultRuntimeConfig();
        }
        return modelService.requireAvailableRuntimeConfig(modelId);
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
        conversationMapper.insert(conversation);
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
                switch (chunk.type()) {
                    case ANSWER_DELTA -> title.append(defaultString(chunk.content()));
                    case ERROR -> title.setLength(0);
                    case REASONING_DELTA, DONE, USAGE -> {
                    }
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
            String prompt,
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
            client.stream(buildRequest(runtimeConfig, prompt, reasoningRequested), chunk ->
                    handleChunk(emitter, assistantMessage, content, reasoningContent, finished, reasoningRequested, chunk));
        } catch (Exception exception) {
            if (finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, exception.getMessage());
            }
        } finally {
            send(emitter, SseEventType.DONE, new SseEventData.Done());
            sseEventSender.complete(emitter);
        }
    }

    private LlmStreamRequest buildRequest(AiModelRuntimeConfig runtimeConfig, String prompt, boolean reasoningRequested) {
        return new LlmStreamRequest(
                runtimeConfig.baseUrl(),
                runtimeConfig.apiKeyCipher(),
                runtimeConfig.apiVersion(),
                runtimeConfig.modelCode(),
                List.of(new LlmMessage("user", prompt)),
                runtimeConfig.maxOutputTokens(),
                reasoningRequested
        );
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
        switch (chunk.type()) {
            case REASONING_DELTA -> {
                if (!reasoningRequested) {
                    return;
                }
                reasoningContent.append(defaultString(chunk.content()));
                assistantMessage.setReasoningContent(reasoningContent.toString());
                if (!send(emitter, SseEventType.REASONING_DELTA, new SseEventData.Delta(defaultString(chunk.content())))
                        && finished.compareAndSet(false, true)) {
                    failAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED);
                }
            }
            case ANSWER_DELTA -> {
                content.append(defaultString(chunk.content()));
                assistantMessage.setContent(content.toString());
                if (!send(emitter, SseEventType.ANSWER_DELTA, new SseEventData.Delta(defaultString(chunk.content())))
                        && finished.compareAndSet(false, true)) {
                    failAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED);
                }
            }
            case ERROR -> {
                if (finished.compareAndSet(false, true)) {
                    failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, chunk.errorMessage());
                }
            }
            case DONE -> {
                if (finished.compareAndSet(false, true)) {
                    completeAssistantMessage(emitter, assistantMessage, content, reasoningContent);
                }
            }
            case USAGE -> {
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

    private ConversationItem toConversationItem(ChatConversation conversation) {
        return new ConversationItem(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getAgentKey(),
                conversation.getModelId(),
                conversation.getStatus()
        );
    }

    private MessageItem toMessageItem(ChatMessage message) {
        return new MessageItem(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getReasoningContent(),
                message.getStatus()
        );
    }
}
