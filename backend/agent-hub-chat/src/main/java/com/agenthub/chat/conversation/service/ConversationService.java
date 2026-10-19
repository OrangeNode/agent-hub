package com.agenthub.chat.conversation.service;

import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.conversation.entity.ChatConversation;
import com.agenthub.chat.conversation.mapper.ChatConversationMapper;
import com.agenthub.chat.message.dto.MessageItem;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.message.support.ProcessEventCodec;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentItem;
import com.agenthub.storage.attachment.service.AttachmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话与消息的查询和维护，所有操作都先校验会话归属。
 */
@Service
public class ConversationService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";
    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 50;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AttachmentService attachmentService;

    public ConversationService(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AttachmentService attachmentService
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.attachmentService = attachmentService;
    }

    public PageResult<ConversationItem> listConversations(Long userId, int limit, int offset) {
        int safeLimit = limit <= 0 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
        int safeOffset = Math.max(0, offset);
        long total = conversationMapper.selectCount(activeConversationQuery(userId));
        LambdaQueryWrapper<ChatConversation> wrapper = activeConversationQuery(userId)
                .orderByDesc(ChatConversation::getUpdatedAt)
                .orderByDesc(ChatConversation::getId)
                .last("LIMIT " + safeLimit + " OFFSET " + safeOffset);
        List<ConversationItem> items = conversationMapper.selectList(wrapper).stream()
                .map(ConversationService::toConversationItem)
                .toList();
        return PageResult.of(items, total, safeLimit, safeOffset);
    }

    public List<MessageItem> listMessages(Long userId, Long conversationId) {
        requireActive(userId, conversationId);
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
    @Transactional
    public void delete(Long userId, Long conversationId) {
        ChatConversation conversation = requireActive(userId, conversationId);
        conversation.setStatus(STATUS_DELETED);
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    /**
     * 更新会话标题，并刷新会话更新时间用于列表排序。
     */
    @Transactional
    public void rename(Long userId, Long conversationId, String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能为空");
        }
        ChatConversation conversation = requireActive(userId, conversationId);
        conversation.setTitle(title.strip());
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    public ChatConversation requireActive(Long userId, Long conversationId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || !userId.equals(conversation.getUserId())
                || !STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    public ChatConversation newConversation(Long userId, String agentKey, AiModelRuntimeConfig runtimeConfig, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setAgentKey(agentKey);
        conversation.setModelId(runtimeConfig.modelId());
        conversation.setProviderId(runtimeConfig.providerId());
        conversation.setStatus(STATUS_ACTIVE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        return conversation;
    }

    public void insert(ChatConversation conversation) {
        conversationMapper.insert(conversation);
    }

    public void touch(ChatConversation conversation) {
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    private LambdaQueryWrapper<ChatConversation> activeConversationQuery(Long userId) {
        return new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getUserId, userId)
                .eq(ChatConversation::getStatus, STATUS_ACTIVE);
    }

    private static ConversationItem toConversationItem(ChatConversation conversation) {
        return new ConversationItem(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getAgentKey(),
                conversation.getModelId(),
                conversation.getStatus(),
                conversation.getUpdatedAt()
        );
    }

    private static MessageItem toMessageItem(ChatMessage message, List<AttachmentItem> attachments) {
        return new MessageItem(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getReasoningContent(),
                message.getStatus(),
                message.getErrorMessage(),
                attachments,
                ProcessEventCodec.deserialize(message.getProcessEvents())
        );
    }
}
