package com.agenthub.chat.message.support;

import static com.agenthub.chat.message.ChatMessageStatus.COMPLETED;
import static com.agenthub.chat.message.ChatMessageStatus.FAILED;
import static com.agenthub.chat.message.ChatMessageStatus.ROLE_ASSISTANT;
import static com.agenthub.chat.message.ChatMessageStatus.ROLE_USER;
import static com.agenthub.chat.message.ChatMessageStatus.STOPPED;
import static com.agenthub.chat.message.ChatMessageStatus.STREAMING;

import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 聊天消息的状态流转。所有终态更新都带 STREAMING 条件，
 * 依靠数据库条件更新保证并发的中止请求和生成线程不会互相覆盖。
 */
@Component
public class AssistantMessageStore {

    public static final String USER_STOPPED_GENERATION = "用户已中止生成";
    private static final String DEFAULT_FAILURE_MESSAGE = "模型调用失败";
    private static final int ORPHAN_SWEEP_BATCH_SIZE = 100;

    private final ChatMessageMapper messageMapper;

    public AssistantMessageStore(ChatMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    public ChatMessage saveUserMessage(
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
        message.setStatus(COMPLETED);
        message.setCreatedAt(now);
        message.setCompletedAt(now);
        messageMapper.insert(message);
        return message;
    }

    public ChatMessage saveAssistantMessage(Long userId, Long conversationId, AiModelRuntimeConfig runtimeConfig) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(ROLE_ASSISTANT);
        message.setContent("");
        message.setReasoningContent("");
        message.setProviderId(runtimeConfig.providerId());
        message.setModelId(runtimeConfig.modelId());
        message.setStatus(STREAMING);
        message.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    /**
     * 返回 false 表示消息已被用户中止，此时只回填已生成内容。
     */
    public boolean markCompleted(AssistantReply reply) {
        ChatMessage message = applySnapshot(reply);
        message.setStatus(COMPLETED);
        message.setCompletedAt(OffsetDateTime.now());
        if (!updateStreaming(message)) {
            backfillStopped(reply);
            return false;
        }
        return true;
    }

    public FailureResult markFailed(AssistantReply reply, String errorMessage) {
        String resolvedMessage = errorMessage == null || errorMessage.isBlank() ? DEFAULT_FAILURE_MESSAGE : errorMessage;
        ChatMessage message = applySnapshot(reply);
        message.setStatus(FAILED);
        message.setErrorMessage(resolvedMessage);
        message.setCompletedAt(OffsetDateTime.now());
        boolean updated = updateStreaming(message);
        if (!updated) {
            backfillStopped(reply);
        }
        return new FailureResult(resolvedMessage, updated);
    }

    public void markStopped(AssistantReply reply, String reason) {
        ChatMessage message = applySnapshot(reply);
        message.setStatus(STOPPED);
        message.setErrorMessage(reason);
        message.setCompletedAt(OffsetDateTime.now());
        if (!updateStreaming(message)) {
            backfillStopped(reply);
        }
    }

    /**
     * 用户中止只改状态；生成线程感知到后会把已生成内容回填到 STOPPED 消息上。
     */
    public void requestStop(Long messageId, Long userId) {
        ChatMessage update = new ChatMessage();
        update.setStatus(STOPPED);
        update.setErrorMessage(USER_STOPPED_GENERATION);
        update.setCompletedAt(OffsetDateTime.now());
        messageMapper.update(update, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, messageId)
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .in(ChatMessage::getStatus, STREAMING, STOPPED));
    }

    /**
     * 供生成线程轮询：中止请求可能落在其他实例上，只能通过数据库状态感知。
     */
    public boolean isStopRequested(Long messageId) {
        ChatMessage current = messageMapper.selectById(messageId);
        return current != null && STOPPED.equals(current.getStatus());
    }

    public ChatMessage findById(Long messageId) {
        return messageMapper.selectById(messageId);
    }

    /**
     * 查找创建时间早于 cutoff 仍处于 STREAMING 的助手消息，这些消息的生成线程已不存在。
     */
    public List<ChatMessage> findStaleStreaming(OffsetDateTime cutoff) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .eq(ChatMessage::getStatus, STREAMING)
                .lt(ChatMessage::getCreatedAt, cutoff)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT " + ORPHAN_SWEEP_BATCH_SIZE));
    }

    /**
     * 不依赖内存状态直接把 STREAMING 消息标记失败，用于孤儿回收和停机兜底。
     */
    public boolean failIfStreaming(Long messageId, String errorMessage) {
        ChatMessage update = new ChatMessage();
        update.setStatus(FAILED);
        update.setErrorMessage(errorMessage);
        update.setCompletedAt(OffsetDateTime.now());
        return messageMapper.update(update, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, messageId)
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .eq(ChatMessage::getStatus, STREAMING)) > 0;
    }

    private ChatMessage applySnapshot(AssistantReply reply) {
        ChatMessage message = reply.message();
        message.setContent(reply.content());
        message.setReasoningContent(reply.reasoningContent());
        message.setProcessEvents(ProcessEventCodec.serialize(reply.processRecorder().events()));
        return message;
    }

    private boolean updateStreaming(ChatMessage message) {
        return messageMapper.update(message, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, message.getId())
                .eq(ChatMessage::getUserId, message.getUserId())
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .eq(ChatMessage::getStatus, STREAMING)) > 0;
    }

    private void backfillStopped(AssistantReply reply) {
        ChatMessage message = reply.message();
        ChatMessage current = messageMapper.selectById(message.getId());
        if (current == null || !STOPPED.equals(current.getStatus())) {
            return;
        }
        ChatMessage update = new ChatMessage();
        update.setContent(reply.content());
        update.setReasoningContent(reply.reasoningContent());
        update.setProcessEvents(ProcessEventCodec.serialize(reply.processRecorder().events()));
        update.setStatus(STOPPED);
        update.setCompletedAt(OffsetDateTime.now());
        messageMapper.update(update, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, message.getId())
                .eq(ChatMessage::getUserId, message.getUserId())
                .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                .eq(ChatMessage::getStatus, STOPPED));
    }

    public record FailureResult(String message, boolean updated) {
    }
}
