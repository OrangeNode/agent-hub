package com.agenthub.chat.service.support;

import com.agenthub.agent.dto.AgentRuntimeConfig;
import com.agenthub.agent.service.AgentService;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.chat.context.ConversationContext;
import com.agenthub.chat.context.ConversationContextBuilder;
import com.agenthub.chat.conversation.entity.ChatConversation;
import com.agenthub.chat.conversation.service.ConversationService;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.support.AssistantMessageStore;
import com.agenthub.chat.title.ConversationTitleGenerator;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.skill.service.SkillRuntimeContext;
import com.agenthub.skill.service.SkillRuntimeService;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.service.AttachmentService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在请求线程中完成一轮对话的全部准备：校验输入、解析 Agent 与模型、构建上下文、落库用户消息和助手占位消息。
 * 这里抛出的业务异常直接返回给调用方，不会进入 SSE 流。
 */
@Component
public class ChatPreparationService {

    private static final int DEFAULT_IMAGE_TOKEN_ESTIMATE = 1500;

    private final ConversationService conversationService;
    private final AssistantMessageStore messageStore;
    private final AiModelService modelService;
    private final AgentService agentService;
    private final SkillRuntimeService skillRuntimeService;
    private final AttachmentService attachmentService;
    private final ConversationContextBuilder contextBuilder;
    private final ConversationTitleGenerator titleGenerator;
    private final TransactionTemplate transactionTemplate;

    public ChatPreparationService(
            ConversationService conversationService,
            AssistantMessageStore messageStore,
            AiModelService modelService,
            AgentService agentService,
            SkillRuntimeService skillRuntimeService,
            AttachmentService attachmentService,
            ConversationContextBuilder contextBuilder,
            ConversationTitleGenerator titleGenerator,
            TransactionTemplate transactionTemplate
    ) {
        this.conversationService = conversationService;
        this.messageStore = messageStore;
        this.modelService = modelService;
        this.agentService = agentService;
        this.skillRuntimeService = skillRuntimeService;
        this.attachmentService = attachmentService;
        this.contextBuilder = contextBuilder;
        this.titleGenerator = titleGenerator;
        this.transactionTemplate = transactionTemplate;
    }

    public PreparedChat prepare(ChatTurnInput input) {
        String prompt = input.prompt() == null ? "" : input.prompt().strip();
        requireChatInput(prompt, input.attachmentIds(), input.reuseAttachmentIds());
        ChatConversation existingConversation = input.conversationId() == null
                ? null
                : conversationService.requireActive(input.userId(), input.conversationId());
        String effectiveAgentKey = resolveEffectiveAgentKey(existingConversation, input.agentKey());
        AgentRuntimeConfig agentRuntime = resolveAgentRuntime(effectiveAgentKey);
        effectiveAgentKey = agentRuntime == null ? effectiveAgentKey : agentRuntime.agentKey();
        AiModelRuntimeConfig runtimeConfig = requireRuntimeConfig(input.modelId(), agentRuntime);
        if (runtimeConfig != null && !runtimeConfig.streamSupported()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前模型不支持流式聊天");
        }

        OffsetDateTime currentMessageCreatedAt = OffsetDateTime.now();
        ChatConversation conversation = existingConversation != null
                ? existingConversation
                : conversationService.newConversation(input.userId(), effectiveAgentKey, runtimeConfig,
                titleGenerator.generate(runtimeConfig, prompt));
        Long resolvedConversationId = existingConversation == null ? null : conversation.getId();
        int imageTokenEstimate = runtimeConfig.imageTokenEstimate() == null
                ? DEFAULT_IMAGE_TOKEN_ESTIMATE
                : runtimeConfig.imageTokenEstimate();
        List<AttachmentReference> images = attachmentService.resolveImagesForChat(
                input.userId(),
                resolvedConversationId,
                input.attachmentIds(),
                input.reuseAttachmentIds(),
                imageTokenEstimate
        );
        if (hasImageReferences(images) && !runtimeConfig.imageInputSupported()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前模型不支持图片输入");
        }
        SkillRuntimeContext skillContext = buildSkillRuntimeContext(agentRuntime, input.skillIds());
        ConversationContext context = contextBuilder.build(input.userId(), resolvedConversationId, runtimeConfig, prompt,
                images, combinedSystemPrompt(agentRuntime, skillContext), currentMessageCreatedAt);

        boolean newConversation = existingConversation == null;
        PersistedMessages persisted = transactionTemplate.execute(status -> {
            if (newConversation) {
                conversationService.insert(conversation);
            }
            ChatMessage userMessage = messageStore.saveUserMessage(input.userId(), conversation.getId(), prompt,
                    runtimeConfig, currentMessageCreatedAt);
            attachmentService.bindToMessage(input.userId(), conversation.getId(), userMessage.getId(),
                    input.attachmentIds());
            ChatMessage assistantMessage = messageStore.saveAssistantMessage(input.userId(), conversation.getId(),
                    runtimeConfig);
            conversationService.touch(conversation);
            return new PersistedMessages(userMessage, assistantMessage);
        });
        if (persisted == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "聊天记录准备失败");
        }
        return new PreparedChat(conversation, persisted.userMessage(), persisted.assistantMessage(), prompt,
                runtimeConfig, agentRuntime, images, context, skillContext);
    }

    private void requireChatInput(String prompt, List<Long> attachmentIds, List<Long> reuseAttachmentIds) {
        if (prompt.isBlank() && !hasIds(attachmentIds) && !hasIds(reuseAttachmentIds)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }
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

    /**
     * 已有会话固定绑定创建时的 Agent，不允许中途切换。
     */
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

    private SkillRuntimeContext buildSkillRuntimeContext(AgentRuntimeConfig agentRuntime, List<Long> selectedSkillIds) {
        if (skillRuntimeService == null) {
            return new SkillRuntimeContext("", List.of());
        }
        Long agentId = agentRuntime == null ? null : agentRuntime.agentId();
        return skillRuntimeService.buildRuntimeContext(agentId, selectedSkillIds);
    }

    private String combinedSystemPrompt(AgentRuntimeConfig agentRuntime, SkillRuntimeContext skillContext) {
        String agentPrompt = agentRuntime == null ? null : agentRuntime.systemPrompt();
        String skillPrompt = skillContext == null ? "" : skillContext.prompt();
        if (!hasText(agentPrompt)) {
            return skillPrompt;
        }
        if (!hasText(skillPrompt)) {
            return agentPrompt;
        }
        return agentPrompt.strip() + "\n\n" + skillPrompt.strip();
    }

    private static boolean hasImageReferences(List<AttachmentReference> attachments) {
        return attachments != null && attachments.stream().anyMatch(attachment ->
                attachment != null
                        && hasText(attachment.contentType())
                        && attachment.contentType().toLowerCase(Locale.ROOT).startsWith("image/")
                        && hasText(attachment.readUrl()));
    }

    private static String normalizeAgentKey(String agentKey) {
        return hasText(agentKey) ? agentKey.strip() : null;
    }

    private static boolean hasIds(List<Long> ids) {
        return ids != null && ids.stream().anyMatch(Objects::nonNull);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PersistedMessages(ChatMessage userMessage, ChatMessage assistantMessage) {
    }

    public record ChatTurnInput(
            Long userId,
            Long conversationId,
            Long modelId,
            String agentKey,
            String prompt,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds,
            List<Long> skillIds
    ) {
    }

    public record PreparedChat(
            ChatConversation conversation,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            String prompt,
            AiModelRuntimeConfig runtimeConfig,
            AgentRuntimeConfig agentRuntime,
            List<AttachmentReference> images,
            ConversationContext context,
            SkillRuntimeContext skillContext
    ) {
    }
}
