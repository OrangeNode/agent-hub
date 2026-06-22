package com.agenthub.chat.service.impl;

import com.agenthub.agent.dto.AgentRuntimeConfig;
import com.agenthub.agent.service.AgentService;
import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.llm.tool.LlmToolCall;
import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.chat.artifact.dto.ChatArtifactContent;
import com.agenthub.chat.artifact.dto.ChatArtifactDownload;
import com.agenthub.chat.artifact.dto.ChatArtifactItem;
import com.agenthub.chat.context.ConversationContext;
import com.agenthub.chat.context.ConversationContextBuilder;
import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.conversation.entity.ChatConversation;
import com.agenthub.chat.conversation.mapper.ChatConversationMapper;
import com.agenthub.chat.message.dto.MessageItem;
import com.agenthub.chat.message.dto.MessageProcessEvent;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.service.ChatService;
import com.agenthub.chat.title.ConversationTitleService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventSender;
import com.agenthub.sse.SseEventType;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.client.dto.SkillZipSyncRequest;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.skill.service.SkillRuntimeContext;
import com.agenthub.skill.service.SkillRuntimePackage;
import com.agenthub.skill.service.SkillRuntimeService;
import com.agenthub.storage.attachment.dto.AttachmentItem;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.service.AttachmentService;
import com.agenthub.storage.object.ObjectStorageClient;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String ERROR_SOURCE_LLM = "LLM";
    private static final String ERROR_SOURCE_TOOL = "TOOL";
    private static final String ERROR_SOURCE_SERVER = "SERVER";
    private static final String LLM_STREAM_FAILED = "LLM_STREAM_FAILED";
    private static final String SSE_CONNECTION_CLOSED = "SSE 连接已断开";
    private static final int TITLE_MAX_OUTPUT_TOKENS = 64;
    private static final int DEFAULT_IMAGE_TOKEN_ESTIMATE = 1500;
    private static final int TOOL_CALL_MAX_COUNT = 100;
    private static final ObjectMapper PROCESS_EVENT_OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<MessageProcessEvent>> PROCESS_EVENT_LIST_TYPE = new TypeReference<>() {
    };
    private static final String SANDBOX_PATH_RULES = """
            沙箱文件规则：
            - 工具路径必须位于允许目录：/workspace 或 /tmp；不要访问其他绝对路径。
            - 用户上传或导入的文件位于 /workspace/input，请从这里读取输入材料。
            - 需要交付给用户的输出文件必须写入 /workspace/artifacts，例如 /workspace/artifacts/作文.txt。
            - /tmp 仅用于构建、编译、缓存等临时工作目录，例如 /tmp/docx-work；最终交付文件不要留在 /tmp。
            - 用户要求下载代码、input、output 或其他文件时，优先调用 sandbox.write_file 逐个写入 /workspace/artifacts，文件名要清晰可识别。
            - sandbox.exec 可以用于复杂任务编排、脚本执行、重定向、管道和 here-doc；所有需要用户下载的最终文件仍应写入 /workspace/artifacts。
            - 如果工具参数使用相对路径，服务端会按工具类型统一归一到 /workspace/input 或 /workspace/artifacts；不要尝试使用 ..、/etc、/root 等越界路径。
            """;
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
    private final SandboxToolService sandboxToolService;
    private final ToolRegistry toolRegistry;
    private final SkillRuntimeService skillRuntimeService;
    private final SandboxSessionService sandboxSessionService;
    private final SandboxClient sandboxClient;
    private final ObjectStorageClient objectStorageClient;

    @Autowired
    public ChatServiceImpl(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            ConversationTitleService titleService,
            TransactionTemplate transactionTemplate,
            AttachmentService attachmentService,
            AgentService agentService,
            ConversationContextBuilder contextBuilder,
            SandboxToolService sandboxToolService,
            ToolRegistry toolRegistry,
            SkillRuntimeService skillRuntimeService,
            SandboxSessionService sandboxSessionService,
            SandboxClient sandboxClient,
            ObjectStorageClient objectStorageClient
    ) {
        this(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, new SseEventSender(),
                attachmentService, agentService, contextBuilder, LlmClientFactory::create, defaultStreamExecutor(),
                sandboxToolService, toolRegistry, skillRuntimeService,
                sandboxSessionService, sandboxClient, objectStorageClient);
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
                attachmentService, null, contextBuilder, llmClientFactory, streamExecutor, null, null, null);
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
        this(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor, null, null, null);
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
            Executor streamExecutor,
            SandboxToolService sandboxToolService,
            ToolRegistry toolRegistry
    ) {
        this(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor,
                sandboxToolService, toolRegistry, null);
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
            Executor streamExecutor,
            SandboxToolService sandboxToolService,
            ToolRegistry toolRegistry,
            SkillRuntimeService skillRuntimeService
    ) {
        this(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor,
                sandboxToolService, toolRegistry, skillRuntimeService, null, null, null);
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
            Executor streamExecutor,
            SandboxToolService sandboxToolService,
            ToolRegistry toolRegistry,
            SkillRuntimeService skillRuntimeService,
            SandboxSessionService sandboxSessionService,
            SandboxClient sandboxClient,
            ObjectStorageClient objectStorageClient
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
        this.sandboxToolService = sandboxToolService;
        this.toolRegistry = toolRegistry;
        this.skillRuntimeService = skillRuntimeService;
        this.sandboxSessionService = sandboxSessionService;
        this.sandboxClient = sandboxClient;
        this.objectStorageClient = objectStorageClient;
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
            List<Long> reuseAttachmentIds,
            List<Long> skillIds
    ) {
        String normalizedPrompt = normalizePrompt(prompt);
        requireChatInput(normalizedPrompt, attachmentIds, reuseAttachmentIds);
        ChatConversation existingConversation = conversationId == null ? null : requireActiveConversation(userId, conversationId);
        String effectiveAgentKey = resolveEffectiveAgentKey(existingConversation, agentKey);
        AgentRuntimeConfig agentRuntime = resolveAgentRuntime(effectiveAgentKey);
        effectiveAgentKey = agentRuntime == null ? effectiveAgentKey : agentRuntime.agentKey();
        AiModelRuntimeConfig runtimeConfig = requireRuntimeConfig(modelId, agentRuntime);
        ChatPreparation preparation = prepareChat(
                userId,
                existingConversation,
                effectiveAgentKey,
                normalizedPrompt,
                runtimeConfig,
                agentRuntime,
                attachmentIds,
                reuseAttachmentIds,
                skillIds
        );
        PreparedChat preparedChat = transactionTemplate.execute(status -> persistChatRecords(preparation));
        if (preparedChat == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "聊天记录准备失败");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Long assistantMessageId = preparedChat.assistantMessage().getId();
        String streamId = "chatmsg_" + assistantMessageId + "_" + System.currentTimeMillis();
        boolean connected = send(emitter, SseEventType.META,
                new SseEventData.Meta("chat.sse.v1", preparedChat.conversation().getId(), assistantMessageId,
                        streamId, false))
                && send(emitter, SseEventType.CONVERSATION,
                new SseEventData.Conversation(preparedChat.conversation().getId(), preparedChat.conversation().getTitle()))
                && send(emitter, SseEventType.MESSAGE,
                new SseEventData.Message(assistantMessageId, ROLE_ASSISTANT));
        if (connected) {
            streamExecutor.execute(() -> streamLlm(emitter, preparedChat.conversation().getUserId(),
                    preparedChat.conversation().getId(), runtimeConfig, preparedChat.context().messages(),
                    preparedChat.assistantMessage(), preparedChat.conversation().getAgentKey(), thinkingEnabled,
                    preparedChat.skillContext()));
        } else {
            // 初始事件发送失败说明客户端已断开，直接标记助手消息失败。
            failAssistantMessage(preparedChat.assistantMessage(), new StringBuilder(), new StringBuilder(), SSE_CONNECTION_CLOSED);
            sseEventSender.complete(emitter);
        }
        return emitter;
    }

    private ChatPreparation prepareChat(
            Long userId,
            ChatConversation existingConversation,
            String agentKey,
            String normalizedPrompt,
            AiModelRuntimeConfig runtimeConfig,
            AgentRuntimeConfig agentRuntime,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds,
            List<Long> selectedSkillIds
    ) {
        OffsetDateTime currentMessageCreatedAt = OffsetDateTime.now();
        ChatConversation conversation = resolveConversation(userId, existingConversation, agentKey, runtimeConfig, normalizedPrompt);
        int imageTokenEstimate = runtimeConfig.imageTokenEstimate() == null
                ? DEFAULT_IMAGE_TOKEN_ESTIMATE
                : runtimeConfig.imageTokenEstimate();
        Long resolvedConversationId = existingConversation == null ? null : conversation.getId();
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
        SkillRuntimeContext skillContext = buildSkillRuntimeContext(agentRuntime, selectedSkillIds);
        ConversationContext context = contextBuilder.build(userId, resolvedConversationId, runtimeConfig, normalizedPrompt,
                images, combinedSystemPrompt(agentRuntime, skillContext), currentMessageCreatedAt);
        return new ChatPreparation(
                userId,
                existingConversation == null,
                conversation,
                normalizedPrompt,
                runtimeConfig,
                attachmentIds,
                images,
                context,
                skillContext,
                currentMessageCreatedAt
        );
    }

    private PreparedChat persistChatRecords(ChatPreparation preparation) {
        ChatConversation conversation = preparation.conversation();
        if (preparation.newConversation()) {
            conversationMapper.insert(conversation);
        }
        ChatMessage userMessage = saveUserMessage(preparation.userId(), conversation.getId(),
                preparation.normalizedPrompt(), preparation.runtimeConfig(), preparation.currentMessageCreatedAt());
        attachmentService.bindToMessage(preparation.userId(), conversation.getId(), userMessage.getId(),
                preparation.attachmentIds());
        ChatMessage assistantMessage = saveAssistantMessage(preparation.userId(), conversation.getId(),
                preparation.runtimeConfig());
        touchConversation(conversation);
        return new PreparedChat(conversation, userMessage, assistantMessage, preparation.images(), preparation.context(),
                preparation.skillContext());
    }

    private static Executor defaultStreamExecutor() {
        return command -> Thread.ofVirtual().name("chat-stream-").start(command);
    }

    private record PreparedChat(
            ChatConversation conversation,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<AttachmentReference> images,
            ConversationContext context,
            SkillRuntimeContext skillContext
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
            ConversationContext context,
            SkillRuntimeContext skillContext,
            OffsetDateTime currentMessageCreatedAt
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

    @Override
    public void stopGeneration(Long userId, Long messageId) {
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null || !Objects.equals(message.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        if (!ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只能中止助手生成消息");
        }
        if (!STATUS_STREAMING.equals(message.getStatus())) {
            return;
        }
        message.setStatus(STATUS_STOPPED);
        message.setErrorMessage("用户已中止生成");
        message.setCompletedAt(OffsetDateTime.now());
        messageMapper.updateById(message);
    }

    @Override
    public List<ChatArtifactItem> listArtifacts(Long userId, Long conversationId) {
        requireActiveConversation(userId, conversationId);
        if (sandboxToolService == null) {
            return List.of();
        }
        return sandboxToolService.artifacts(userId, conversationId, true).stream()
                .map(this::toArtifactItem)
                .toList();
    }

    @Override
    public ChatArtifactContent previewArtifact(Long userId, Long conversationId, Long artifactId, int maxBytes) {
        requireActiveConversation(userId, conversationId);
        int previewLimit = Math.min(Math.max(maxBytes, 1), 256 * 1024);
        SandboxArtifactItem artifact = requireArtifactMetadata(userId, conversationId, artifactId);
        byte[] bytes = sandboxToolService.readArtifact(userId, conversationId, artifactId);
        boolean previewable = isPreviewableText(artifact);
        if (!previewable) {
            return new ChatArtifactContent(
                    artifact.artifactId(),
                    artifact.fileName(),
                    artifact.relativePath(),
                    artifact.contentType(),
                    artifact.sizeBytes(),
                    false,
                    bytes.length > previewLimit,
                    ""
            );
        }
        int length = Math.min(bytes.length, previewLimit);
        return new ChatArtifactContent(
                artifact.artifactId(),
                artifact.fileName(),
                artifact.relativePath(),
                artifact.contentType(),
                artifact.sizeBytes(),
                true,
                bytes.length > previewLimit,
                new String(bytes, 0, length, StandardCharsets.UTF_8)
        );
    }

    @Override
    public ChatArtifactDownload downloadArtifact(Long userId, Long conversationId, Long artifactId) {
        requireActiveConversation(userId, conversationId);
        SandboxArtifactItem artifact = requireArtifactMetadata(userId, conversationId, artifactId);
        return new ChatArtifactDownload(
                artifact.fileName(),
                defaultContentType(artifact.contentType()),
                sandboxToolService.readArtifact(userId, conversationId, artifactId)
        );
    }

    @Override
    public ChatArtifactDownload downloadAllArtifacts(Long userId, Long conversationId) {
        requireActiveConversation(userId, conversationId);
        if (sandboxToolService == null) {
            return new ChatArtifactDownload("全部文件.zip", "application/zip", new byte[0]);
        }
        List<SandboxArtifactItem> artifacts = sandboxToolService.artifacts(userId, conversationId, true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            HashSet<String> usedNames = new HashSet<>();
            for (SandboxArtifactItem artifact : artifacts) {
                String entryName = uniqueZipEntryName(safeZipEntryName(artifact), usedNames);
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(sandboxToolService.readArtifact(userId, conversationId, artifact.artifactId()));
                zip.closeEntry();
            }
            zip.finish();
            return new ChatArtifactDownload("全部文件.zip", "application/zip", output.toByteArray());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件打包失败");
        }
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

    private String systemPrompt(AgentRuntimeConfig agentRuntime) {
        return agentRuntime == null ? null : agentRuntime.systemPrompt();
    }

    private SkillRuntimeContext buildSkillRuntimeContext(AgentRuntimeConfig agentRuntime, List<Long> selectedSkillIds) {
        if (skillRuntimeService == null) {
            return new SkillRuntimeContext("", List.of());
        }
        Long agentId = agentRuntime == null ? null : agentRuntime.agentId();
        return skillRuntimeService.buildRuntimeContext(agentId, selectedSkillIds);
    }

    private String combinedSystemPrompt(AgentRuntimeConfig agentRuntime, SkillRuntimeContext skillContext) {
        String agentPrompt = systemPrompt(agentRuntime);
        String skillPrompt = skillContext == null ? "" : skillContext.prompt();
        if (!hasText(agentPrompt)) {
            return skillPrompt;
        }
        if (!hasText(skillPrompt)) {
            return agentPrompt;
        }
        return agentPrompt.strip() + "\n\n" + skillPrompt.strip();
    }

    /**
     * 根据 conversationId 决定创建新会话还是续写已有会话。
     */
    private ChatConversation resolveConversation(
            Long userId,
            ChatConversation existingConversation,
            String agentKey,
            AiModelRuntimeConfig runtimeConfig,
            String prompt
    ) {
        if (existingConversation == null) {
            return createConversation(userId, agentKey, runtimeConfig, prompt);
        }
        return existingConversation;
    }

    private String resolveEffectiveAgentKey(ChatConversation existingConversation, String requestedAgentKey) {
        String normalizedRequestedAgentKey = normalizeAgentKey(requestedAgentKey);
        if (existingConversation == null) {
            return normalizedRequestedAgentKey;
        }

        String boundAgentKey = normalizeAgentKey(existingConversation.getAgentKey());
        if (!hasText(normalizedRequestedAgentKey)) {
            return boundAgentKey;
        }
        if (!hasText(boundAgentKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已有会话不能切换 Agent");
        }
        if (!boundAgentKey.equals(normalizedRequestedAgentKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话已绑定其他 Agent");
        }
        return boundAgentKey;
    }

    private String normalizeAgentKey(String agentKey) {
        return hasText(agentKey) ? agentKey.strip() : null;
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
            AiModelRuntimeConfig runtimeConfig,
            OffsetDateTime createdAt
    ) {
        OffsetDateTime now = createdAt == null ? OffsetDateTime.now() : createdAt;
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
            Long userId,
            Long conversationId,
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            ChatMessage assistantMessage,
            String agentKey,
            boolean thinkingEnabled,
            SkillRuntimeContext skillContext
    ) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        ProcessEventRecorder processRecorder = new ProcessEventRecorder();
        List<Long> skillIds = skillContext == null ? List.of() : skillContext.skillIds();
        AtomicBoolean finished = new AtomicBoolean(false);
        String errorSource = ERROR_SOURCE_SERVER;
        ToolExecutionContext toolContext = new ToolExecutionContext(
                userId,
                conversationId,
                assistantMessage.getId(),
                agentKey,
                skillIds,
                (toolCallId, toolName, stream, output) -> {
                    String safeStream = hasText(stream) ? stream : "stdout";
                    String safeOutput = defaultString(output);
                    processRecorder.appendToolOutput(toolCallId, toolName, safeStream, safeOutput);
                    if (!send(emitter, SseEventType.TOOL_OUTPUT,
                            new SseEventData.ToolOutput(toolCallId, safeStream, safeOutput))
                            && finished.compareAndSet(false, true)) {
                        stopAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED,
                                processRecorder);
                    }
                }
        );
        // 只有用户开启思考且模型声明支持时，才向前端透出推理片段。
        boolean reasoningRequested = thinkingEnabled && runtimeConfig.reasoningSupported();
        List<LlmToolDefinition> availableTools = List.of();
        List<LlmMessage> loopMessages = new ArrayList<>(messages);
        try {
            syncSkillsToSandbox(userId, conversationId, skillContext);
            errorSource = ERROR_SOURCE_TOOL;
            availableTools = listTools(toolContext);
            errorSource = ERROR_SOURCE_LLM;
            LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
            loopMessages = withSandboxPathRules(messages, availableTools);
            int toolCallCount = 0;
            while (!finished.get()) {
                errorSource = ERROR_SOURCE_LLM;
                StreamAttemptOutcome outcome = streamOnce(client, runtimeConfig, loopMessages, reasoningRequested,
                        availableTools, emitter, assistantMessage, content, reasoningContent, processRecorder, finished);
                if (outcome.retryRequested() && !finished.get()) {
                    StreamAttemptOutcome retryOutcome = streamOnce(client, runtimeConfig, compactMessagesForRetry(loopMessages),
                            reasoningRequested, availableTools, emitter, assistantMessage, content, reasoningContent,
                            processRecorder, finished);
                    if (retryOutcome.retryRequested() && finished.compareAndSet(false, true)) {
                        failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent,
                                CONTEXT_LENGTH_RETRY_FAILED_MESSAGE, processRecorder);
                    }
                    outcome = retryOutcome;
                }
                if (finished.get() || outcome.toolCalls().isEmpty()) {
                    return;
                }
                errorSource = ERROR_SOURCE_TOOL;
                if (availableTools.isEmpty()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工具服务不可用");
                }
                if (toolCallCount + outcome.toolCalls().size() > TOOL_CALL_MAX_COUNT) {
                    failAssistantMessageAndSendToolError(emitter, assistantMessage, content, reasoningContent,
                            "工具调用次数超过上限", processRecorder);
                    return;
                }
                toolCallCount += outcome.toolCalls().size();
                loopMessages.add(LlmMessage.assistantToolCalls(outcome.toolCalls()));
                for (LlmToolCall toolCall : outcome.toolCalls()) {
                    if (finished.get()) {
                        return;
                    }
                    String result = executeToolCall(emitter, toolCall, processRecorder, toolContext,
                            assistantMessage, content, reasoningContent, finished);
                    if (finished.get()) {
                        return;
                    }
                    loopMessages.add(LlmMessage.tool(toolCall.id(), toolCall.name(), result));
                }
            }
            if (finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendToolError(emitter, assistantMessage, content, reasoningContent,
                        "工具调用轮次超过上限", processRecorder);
            }
        } catch (Exception exception) {
            if (isRetryableBeforeDelta(content, reasoningContent) && isContextLengthError(exception.getMessage())) {
                retryAfterContextLengthException(emitter, runtimeConfig, loopMessages, assistantMessage, reasoningRequested,
                        availableTools, content, reasoningContent, processRecorder, finished, exception);
            } else if (finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent,
                        exception.getMessage(), errorSource, processRecorder);
            }
        } finally {
            send(emitter, SseEventType.DONE, new SseEventData.Done());
            sseEventSender.complete(emitter);
        }
    }

    private void syncSkillsToSandbox(Long userId, Long conversationId, SkillRuntimeContext skillContext) {
        if (skillContext == null || skillContext.packages().isEmpty()) {
            return;
        }
        skillContext.packages().forEach(this::requireSyncableSkillPackage);
        if (sandboxSessionService == null || sandboxClient == null || objectStorageClient == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "沙箱同步服务不可用");
        }
        String sandboxSessionId = sandboxSessionService.ensureSession(userId, conversationId);
        List<SkillZipSyncRequest.Skill> skills = skillContext.packages().stream()
                .map(skill -> new SkillZipSyncRequest.Skill(
                        skill.skillId(),
                        skill.skillKey(),
                        skill.name(),
                        objectStorageClient.presignGet(skill.bucketName(), skill.objectKey(), Duration.ofMinutes(10)),
                        resolveSkillZipSha256(skill),
                        "/workspace/skills/" + skill.skillKey()
                ))
                .toList();
        sandboxClient.syncSkillZips(sandboxSessionId, new SkillZipSyncRequest(skills));
    }

    private void requireSyncableSkillPackage(SkillRuntimePackage skill) {
        if (skill == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Skill 包元数据缺失，无法同步到沙箱");
        }
        if (!hasText(skill.skillKey())) {
            throw missingSkillPackageField(skill, "skillKey");
        }
        if (!hasText(skill.bucketName())) {
            throw missingSkillPackageField(skill, "bucketName");
        }
        if (!hasText(skill.objectKey())) {
            throw missingSkillPackageField(skill, "objectKey");
        }
    }

    private String resolveSkillZipSha256(SkillRuntimePackage skill) {
        if (hasText(skill.zipSha256())) {
            return skill.zipSha256().strip();
        }
        byte[] zipBytes = objectStorageClient.readBytes(skill.bucketName(), skill.objectKey());
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Skill " + skillDisplayName(skill) + " 原始 zip 为空，无法同步到沙箱");
        }
        return sha256Hex(zipBytes);
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BusinessException missingSkillPackageField(SkillRuntimePackage skill, String fieldName) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR,
                "Skill " + skillDisplayName(skill) + " 缺少 " + fieldName + "，无法同步到沙箱");
    }

    private String skillDisplayName(SkillRuntimePackage skill) {
        String display = hasText(skill.skillKey()) ? skill.skillKey() : skill.name();
        String id = skill.skillId() == null ? "未知" : String.valueOf(skill.skillId());
        return hasText(display) ? id + "(" + display.strip() + ")" : id;
    }

    private LlmStreamRequest buildRequest(
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            boolean reasoningRequested,
            List<LlmToolDefinition> availableTools
    ) {
        return new LlmStreamRequest(
                runtimeConfig.baseUrl(),
                runtimeConfig.apiKeyCipher(),
                runtimeConfig.apiVersion(),
                runtimeConfig.modelCode(),
                messages,
                runtimeConfig.maxOutputTokens(),
                reasoningRequested,
                availableTools
        );
    }

    private StreamAttemptOutcome streamOnce(
            LlmClient client,
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            boolean reasoningRequested,
            List<LlmToolDefinition> availableTools,
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            ProcessEventRecorder processRecorder,
            AtomicBoolean finished
    ) {
        AtomicBoolean retryRequested = new AtomicBoolean(false);
        AtomicBoolean sawDelta = new AtomicBoolean(false);
        List<LlmToolCall> toolCalls = new ArrayList<>();
        client.stream(buildRequest(runtimeConfig, messages, reasoningRequested, availableTools), chunk -> {
            if (retryRequested.get()) {
                return;
            }
            if (chunk.type() == LlmStreamChunkType.TOOL_CALL) {
                if (finished.get()) {
                    return;
                }
                if (!send(emitter, SseEventType.TOOL_CALL,
                        new SseEventData.ToolCall(chunk.toolCall().id(), chunk.toolCall().name()))
                        && finished.compareAndSet(false, true)) {
                    stopAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED,
                            processRecorder);
                    return;
                }
                toolCalls.add(chunk.toolCall());
                processRecorder.startTool(chunk.toolCall().id(), chunk.toolCall().name());
                return;
            }
            if (chunk.type() == LlmStreamChunkType.DONE && !toolCalls.isEmpty()) {
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
            handleChunk(emitter, assistantMessage, content, reasoningContent, processRecorder,
                    finished, reasoningRequested, chunk);
        });
        return new StreamAttemptOutcome(retryRequested.get(), toolCalls);
    }

    private List<LlmToolDefinition> listTools(ToolExecutionContext toolContext) {
        if (toolRegistry == null) {
            return List.of();
        }
        return toolRegistry.listTools(toolContext);
    }

    private List<LlmMessage> withSandboxPathRules(List<LlmMessage> messages, List<LlmToolDefinition> availableTools) {
        List<LlmMessage> resolvedMessages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        if (!hasSandboxTools(availableTools)) {
            return resolvedMessages;
        }
        String rules = SANDBOX_PATH_RULES.strip();
        for (int index = 0; index < resolvedMessages.size(); index++) {
            LlmMessage message = resolvedMessages.get(index);
            if (!"system".equals(message.role())) {
                continue;
            }
            String content = hasText(message.content()) ? message.content().strip() + "\n\n" + rules : rules;
            resolvedMessages.set(index, new LlmMessage(
                    message.role(),
                    content,
                    message.parts(),
                    message.toolCallId(),
                    message.toolName(),
                    message.toolCalls()
            ));
            return resolvedMessages;
        }
        resolvedMessages.addFirst(new LlmMessage("system", rules));
        return resolvedMessages;
    }

    private boolean hasSandboxTools(List<LlmToolDefinition> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return false;
        }
        return availableTools.stream()
                .filter(Objects::nonNull)
                .map(LlmToolDefinition::name)
                .filter(Objects::nonNull)
                .anyMatch(name -> name.startsWith("sandbox."));
    }

    private String executeToolCall(
            SseEmitter emitter,
            LlmToolCall toolCall,
            ProcessEventRecorder processRecorder,
            ToolExecutionContext toolContext,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            AtomicBoolean finished
    ) {
        ToolExecutionResult result;
        try {
            result = toolRegistry.execute(
                    new ToolExecutionRequest(toolCall.id(), toolCall.name(), toolCall.arguments()),
                    toolContext
            );
        } catch (RuntimeException exception) {
            String message = hasText(exception.getMessage()) ? exception.getMessage() : "工具调用失败";
            result = ToolExecutionResult.failed(message);
            processRecorder.completeTool(toolCall.id(), toolCall.name(), result.status(), result.content());
            if (!send(emitter, SseEventType.TOOL_RESULT,
                    new SseEventData.ToolResult(toolCall.id(), toolCall.name(), result.status(), result.content()))
                    && finished.compareAndSet(false, true)) {
                stopAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED,
                        processRecorder);
            }
            throw exception;
        }
        processRecorder.completeTool(toolCall.id(), toolCall.name(), result.status(), result.content());
        if (!send(emitter, SseEventType.TOOL_RESULT,
                new SseEventData.ToolResult(toolCall.id(), toolCall.name(), result.status(), result.content()))
                && finished.compareAndSet(false, true)) {
            stopAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED,
                    processRecorder);
        }
        return result.content();
    }
    private void retryAfterContextLengthException(
            SseEmitter emitter,
            AiModelRuntimeConfig runtimeConfig,
            List<LlmMessage> messages,
            ChatMessage assistantMessage,
            boolean reasoningRequested,
            List<LlmToolDefinition> availableTools,
            StringBuilder content,
            StringBuilder reasoningContent,
            ProcessEventRecorder processRecorder,
            AtomicBoolean finished,
            Exception originalException
    ) {
        try {
            LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
            StreamAttemptOutcome retryOutcome = streamOnce(client, runtimeConfig, compactMessagesForRetry(messages),
                    reasoningRequested, availableTools, emitter, assistantMessage, content, reasoningContent,
                    processRecorder, finished);
            if (retryOutcome.retryRequested() && finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent,
                        CONTEXT_LENGTH_RETRY_FAILED_MESSAGE, processRecorder);
            }
        } catch (Exception retryException) {
            if (finished.compareAndSet(false, true)) {
                String message = isContextLengthError(retryException.getMessage())
                        ? CONTEXT_LENGTH_RETRY_FAILED_MESSAGE
                        : defaultString(retryException.getMessage());
                if (message.isBlank()) {
                    message = originalException.getMessage();
                }
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, message,
                        processRecorder);
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
            ProcessEventRecorder processRecorder,
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
            processRecorder.appendReasoning(delta);
            assistantMessage.setReasoningContent(reasoningContent.toString());
            if (!send(emitter, SseEventType.REASONING_DELTA, new SseEventData.Delta(delta))
                    && finished.compareAndSet(false, true)) {
                stopAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED, processRecorder);
            }
        } else if (type == LlmStreamChunkType.ANSWER_DELTA) {
            content.append(delta);
            assistantMessage.setContent(content.toString());
            if (!send(emitter, SseEventType.ANSWER_DELTA, new SseEventData.Delta(delta))
                    && finished.compareAndSet(false, true)) {
                stopAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED, processRecorder);
            }
        } else if (type == LlmStreamChunkType.ERROR) {
            if (finished.compareAndSet(false, true)) {
                failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent,
                        chunk.errorMessage(), processRecorder);
            }
        } else if (type == LlmStreamChunkType.DONE) {
            if (finished.compareAndSet(false, true)) {
                completeAssistantMessage(emitter, assistantMessage, content, reasoningContent, processRecorder);
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
            StringBuilder reasoningContent,
            ProcessEventRecorder processRecorder
    ) {
        assistantMessage.setContent(content.toString());
        assistantMessage.setReasoningContent(reasoningContent.toString());
        assistantMessage.setProcessEvents(serializeProcessEvents(processRecorder.events()));
        assistantMessage.setStatus(STATUS_COMPLETED);
        assistantMessage.setCompletedAt(OffsetDateTime.now());
        messageMapper.updateById(assistantMessage);
        if (!send(emitter, SseEventType.MESSAGE_COMPLETED,
                new SseEventData.MessageCompleted(assistantMessage.getId(), STATUS_COMPLETED))) {
            failAssistantMessage(assistantMessage, content, reasoningContent, SSE_CONNECTION_CLOSED, processRecorder);
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
        failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, errorMessage,
                new ProcessEventRecorder());
    }

    private void failAssistantMessageAndSendError(
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String errorMessage,
            ProcessEventRecorder processRecorder
    ) {
        failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, errorMessage,
                ERROR_SOURCE_LLM, processRecorder);
    }

    private void failAssistantMessageAndSendToolError(
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String errorMessage,
            ProcessEventRecorder processRecorder
    ) {
        failAssistantMessageAndSendError(emitter, assistantMessage, content, reasoningContent, errorMessage,
                ERROR_SOURCE_TOOL, processRecorder);
    }

    private void failAssistantMessageAndSendError(
            SseEmitter emitter,
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String errorMessage,
            String source,
            ProcessEventRecorder processRecorder
    ) {
        String resolvedMessage = failAssistantMessage(assistantMessage, content, reasoningContent, errorMessage,
                processRecorder);
        send(emitter, SseEventType.ERROR, SseEventData.Error.failed(LLM_STREAM_FAILED, resolvedMessage,
                assistantMessage.getId(), source, isContextLengthError(resolvedMessage)));
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
        return failAssistantMessage(assistantMessage, content, reasoningContent, errorMessage, new ProcessEventRecorder());
    }

    private void stopAssistantMessage(
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String reason,
            ProcessEventRecorder processRecorder
    ) {
        assistantMessage.setContent(content.toString());
        assistantMessage.setReasoningContent(reasoningContent.toString());
        assistantMessage.setProcessEvents(serializeProcessEvents(processRecorder.events()));
        assistantMessage.setStatus(STATUS_STOPPED);
        assistantMessage.setErrorMessage(reason == null || reason.isBlank() ? SSE_CONNECTION_CLOSED : reason);
        assistantMessage.setCompletedAt(OffsetDateTime.now());
        messageMapper.updateById(assistantMessage);
    }

    private String failAssistantMessage(
            ChatMessage assistantMessage,
            StringBuilder content,
            StringBuilder reasoningContent,
            String errorMessage,
            ProcessEventRecorder processRecorder
    ) {
        String resolvedMessage = errorMessage == null || errorMessage.isBlank() ? "模型调用失败" : errorMessage;
        assistantMessage.setContent(content.toString());
        assistantMessage.setReasoningContent(reasoningContent.toString());
        assistantMessage.setProcessEvents(serializeProcessEvents(processRecorder.events()));
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

    private SandboxArtifactItem requireArtifactMetadata(Long userId, Long conversationId, Long artifactId) {
        if (sandboxToolService == null || artifactId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return sandboxToolService.artifacts(userId, conversationId, false).stream()
                .filter(item -> artifactId.equals(item.artifactId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));
    }

    private ChatArtifactItem toArtifactItem(SandboxArtifactItem item) {
        return new ChatArtifactItem(
                item.artifactId(),
                item.fileName(),
                item.relativePath(),
                defaultContentType(item.contentType()),
                item.sizeBytes(),
                item.sha256(),
                item.version()
        );
    }

    private boolean isPreviewableText(SandboxArtifactItem artifact) {
        String contentType = defaultString(artifact.contentType()).toLowerCase(Locale.ROOT);
        if (contentType.startsWith("text/")
                || contentType.contains("json")
                || contentType.contains("xml")
                || contentType.contains("yaml")) {
            return true;
        }
        String name = defaultString(artifact.fileName()).toLowerCase(Locale.ROOT);
        return name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".py")
                || name.endsWith(".java")
                || name.endsWith(".js")
                || name.endsWith(".ts")
                || name.endsWith(".json")
                || name.endsWith(".csv")
                || name.endsWith(".xml")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".html")
                || name.endsWith(".css");
    }

    private String defaultContentType(String contentType) {
        return hasText(contentType) ? contentType : "application/octet-stream";
    }

    private String safeZipEntryName(SandboxArtifactItem artifact) {
        String value = hasText(artifact.relativePath()) ? artifact.relativePath() : artifact.fileName();
        if (!hasText(value)) {
            value = "file-" + artifact.artifactId();
        }
        String normalized = value.replace('\\', '/').replaceAll("^/+", "");
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? "file-" + artifact.artifactId() : String.join("/", segments);
    }

    private String uniqueZipEntryName(String name, HashSet<String> usedNames) {
        if (usedNames.add(name)) {
            return name;
        }
        int slashIndex = name.lastIndexOf('/');
        String directory = slashIndex >= 0 ? name.substring(0, slashIndex + 1) : "";
        String fileName = slashIndex >= 0 ? name.substring(slashIndex + 1) : name;
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String suffix = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        int index = 2;
        while (true) {
            String candidate = directory + base + "-" + index + suffix;
            if (usedNames.add(candidate)) {
                return candidate;
            }
            index++;
        }
    }

    private String serializeProcessEvents(List<MessageProcessEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        try {
            return PROCESS_EVENT_OBJECT_MAPPER.writeValueAsString(events);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "消息过程事件序列化失败");
        }
    }

    private List<MessageProcessEvent> deserializeProcessEvents(String value) {
        if (!hasText(value)) {
            return List.of();
        }
        try {
            List<MessageProcessEvent> events = PROCESS_EVENT_OBJECT_MAPPER.readValue(value, PROCESS_EVENT_LIST_TYPE);
            if (events == null) {
                return List.of();
            }
            return events.stream()
                    .filter(Objects::nonNull)
                    .map(this::normalizeProcessEvent)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private MessageProcessEvent normalizeProcessEvent(MessageProcessEvent event) {
        if ("reasoning".equals(event.type())) {
            return new MessageProcessEvent("reasoning", defaultString(event.content()), null);
        }
        if (!"tool".equals(event.type()) || event.tool() == null) {
            return null;
        }
        MessageProcessEvent.ToolProcess tool = event.tool();
        return new MessageProcessEvent("tool", null, new MessageProcessEvent.ToolProcess(
                defaultString(tool.toolCallId()),
                defaultString(tool.name()).isBlank() ? "工具调用" : tool.name(),
                defaultString(tool.status()).isBlank() ? "running" : tool.status(),
                defaultString(tool.stdout()),
                defaultString(tool.stderr()),
                defaultString(tool.result())
        ));
    }

    private static class ProcessEventRecorder {

        private final List<MessageProcessEvent> events = new ArrayList<>();
        private final Map<String, Integer> toolIndexes = new HashMap<>();

        private List<MessageProcessEvent> events() {
            return List.copyOf(events);
        }

        private void appendReasoning(String content) {
            if (content == null || content.isEmpty()) {
                return;
            }
            if (!events.isEmpty() && "reasoning".equals(events.getLast().type())) {
                MessageProcessEvent last = events.getLast();
                events.set(events.size() - 1,
                        new MessageProcessEvent("reasoning", last.content() + content, null));
                return;
            }
            events.add(new MessageProcessEvent("reasoning", content, null));
        }

        private void startTool(String toolCallId, String name) {
            updateTool(toolCallId, name, tool -> new MessageProcessEvent.ToolProcess(
                    tool.toolCallId(),
                    resolveToolName(name, tool.name()),
                    "running",
                    tool.stdout(),
                    tool.stderr(),
                    tool.result()
            ));
        }

        private void appendToolOutput(String toolCallId, String name, String stream, String content) {
            updateTool(toolCallId, name, tool -> new MessageProcessEvent.ToolProcess(
                    tool.toolCallId(),
                    resolveToolName(name, tool.name()),
                    tool.status(),
                    "stderr".equals(stream) ? tool.stdout() : tool.stdout() + defaultStaticString(content),
                    "stderr".equals(stream) ? tool.stderr() + defaultStaticString(content) : tool.stderr(),
                    tool.result()
            ));
        }

        private void completeTool(String toolCallId, String name, String status, String result) {
            updateTool(toolCallId, name, tool -> new MessageProcessEvent.ToolProcess(
                    tool.toolCallId(),
                    resolveToolName(name, tool.name()),
                    defaultStaticString(status).isBlank() ? "completed" : status,
                    tool.stdout(),
                    tool.stderr(),
                    defaultStaticString(result)
            ));
        }

        private void updateTool(
                String toolCallId,
                String name,
                Function<MessageProcessEvent.ToolProcess, MessageProcessEvent.ToolProcess> updater
        ) {
            String resolvedToolCallId = defaultStaticString(toolCallId);
            Integer index = toolIndexes.get(resolvedToolCallId);
            MessageProcessEvent.ToolProcess current;
            if (index == null) {
                current = new MessageProcessEvent.ToolProcess(
                        resolvedToolCallId,
                        resolveToolName(name, ""),
                        "running",
                        "",
                        "",
                        ""
                );
                events.add(new MessageProcessEvent("tool", null, current));
                index = events.size() - 1;
                toolIndexes.put(resolvedToolCallId, index);
            } else {
                current = events.get(index).tool();
            }
            events.set(index, new MessageProcessEvent("tool", null, updater.apply(current)));
        }

        private String resolveToolName(String preferred, String fallback) {
            String value = defaultStaticString(preferred);
            if (!value.isBlank()) {
                return value;
            }
            value = defaultStaticString(fallback);
            return value.isBlank() ? "工具调用" : value;
        }

        private static String defaultStaticString(String value) {
            return value == null ? "" : value;
        }
    }

    private record StreamAttemptOutcome(boolean retryRequested, List<LlmToolCall> toolCalls) {
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
                message.getErrorMessage(),
                attachments,
                deserializeProcessEvents(message.getProcessEvents())
        );
    }
}
