package com.agenthub.chat.service.impl;

import static com.agenthub.chat.message.ChatMessageStatus.ROLE_ASSISTANT;
import static com.agenthub.chat.message.ChatMessageStatus.STOPPED;
import static com.agenthub.chat.message.ChatMessageStatus.STREAMING;

import com.agenthub.chat.artifact.dto.ChatArtifactContent;
import com.agenthub.chat.artifact.dto.ChatArtifactDownload;
import com.agenthub.chat.artifact.dto.ChatArtifactItem;
import com.agenthub.chat.artifact.service.ChatArtifactService;
import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.conversation.service.ConversationService;
import com.agenthub.chat.message.dto.MessageItem;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.support.AssistantMessageStore;
import com.agenthub.chat.message.support.AssistantReply;
import com.agenthub.chat.run.ChatRunConfiguration;
import com.agenthub.chat.run.ChatRunExecutor;
import com.agenthub.chat.run.ChatRunHandle;
import com.agenthub.chat.run.ChatRunProperties;
import com.agenthub.chat.run.ChatRunRegistry;
import com.agenthub.chat.run.ChatRunRequest;
import com.agenthub.chat.run.RunCancelReason;
import com.agenthub.chat.run.RunEventSink;
import com.agenthub.chat.service.ChatService;
import com.agenthub.chat.service.support.ChatPreparationService;
import com.agenthub.chat.service.support.ChatPreparationService.ChatTurnInput;
import com.agenthub.chat.service.support.ChatPreparationService.PreparedChat;
import com.agenthub.chat.task.runtime.AgentRunDecision;
import com.agenthub.chat.task.runtime.AgentRunInput;
import com.agenthub.chat.task.runtime.AgentRunRouter;
import com.agenthub.chat.task.runtime.ChatTaskProgressTracker;
import com.agenthub.chat.task.runtime.PlanPolicy;
import com.agenthub.chat.task.runtime.RunMode;
import com.agenthub.chat.task.runtime.TaskRunContext;
import com.agenthub.chat.task.runtime.ToolPolicy;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventSender;
import com.agenthub.sse.SseEventType;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天应用服务门面：负责编排准备、运行和查询，具体逻辑由各协作组件实现。
 */
@Service
public class ChatServiceImpl implements ChatService {

    /**
     * SSE 连接比运行多保留一分钟，确保超时终止时的错误事件仍能送达前端。
     */
    private static final long SSE_TIMEOUT_MARGIN_MILLIS = 60_000L;

    private final ConversationService conversationService;
    private final ChatArtifactService artifactService;
    private final ChatPreparationService preparationService;
    private final AssistantMessageStore messageStore;
    private final ChatRunRegistry runRegistry;
    private final ChatRunExecutor runExecutor;
    private final ChatTaskProgressTracker taskTracker;
    private final AgentRunRouter runRouter;
    private final SseEventSender sseEventSender;
    private final ChatRunProperties runProperties;
    private final Executor runThreadExecutor;

    @Autowired
    public ChatServiceImpl(
            ConversationService conversationService,
            ChatArtifactService artifactService,
            ChatPreparationService preparationService,
            AssistantMessageStore messageStore,
            ChatRunRegistry runRegistry,
            ChatRunExecutor runExecutor,
            ChatTaskProgressTracker taskTracker,
            AgentRunRouter runRouter,
            SseEventSender sseEventSender,
            ChatRunProperties runProperties
    ) {
        this(conversationService, artifactService, preparationService, messageStore, runRegistry, runExecutor,
                taskTracker, runRouter, sseEventSender, runProperties, ChatRunConfiguration.virtualThreadExecutor());
    }

    public ChatServiceImpl(
            ConversationService conversationService,
            ChatArtifactService artifactService,
            ChatPreparationService preparationService,
            AssistantMessageStore messageStore,
            ChatRunRegistry runRegistry,
            ChatRunExecutor runExecutor,
            ChatTaskProgressTracker taskTracker,
            AgentRunRouter runRouter,
            SseEventSender sseEventSender,
            ChatRunProperties runProperties,
            Executor runThreadExecutor
    ) {
        this.conversationService = conversationService;
        this.artifactService = artifactService;
        this.preparationService = preparationService;
        this.messageStore = messageStore;
        this.runRegistry = runRegistry;
        this.runExecutor = runExecutor;
        this.taskTracker = taskTracker;
        this.runRouter = runRouter;
        this.sseEventSender = sseEventSender;
        this.runProperties = runProperties;
        this.runThreadExecutor = runThreadExecutor;
    }

    /**
     * 创建 SSE 通道并异步执行 Agent 运行，调用方可立即拿到响应连接。
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
        ChatRunHandle handle = runRegistry.reserve();
        PreparedChat prepared;
        try {
            prepared = preparationService.prepare(new ChatTurnInput(userId, conversationId, modelId, agentKey, prompt,
                    attachmentIds, reuseAttachmentIds, skillIds));
        } catch (RuntimeException exception) {
            runRegistry.release(handle);
            throw exception;
        }
        ChatMessage assistantMessage = prepared.assistantMessage();
        Long resolvedConversationId = prepared.conversation().getId();
        runRegistry.activate(handle, assistantMessage.getId());

        AgentRunDecision runDecision = routeRun(prepared.prompt());
        TaskRunContext taskRunContext = taskTracker.createIfNeeded(userId, resolvedConversationId,
                assistantMessage.getId(), prepared.prompt(), runDecision);

        SseEmitter emitter = new SseEmitter(runProperties.getMaxDuration().toMillis() + SSE_TIMEOUT_MARGIN_MILLIS);
        RunEventSink events = new RunEventSink(emitter, sseEventSender);
        String streamId = "chatmsg_" + assistantMessage.getId() + "_" + System.currentTimeMillis();
        boolean connected = events.send(SseEventType.META, new SseEventData.Meta("chat.sse.v1",
                resolvedConversationId, assistantMessage.getId(), streamId, false))
                && events.send(SseEventType.CONVERSATION,
                new SseEventData.Conversation(resolvedConversationId, prepared.conversation().getTitle()))
                && events.send(SseEventType.MESSAGE,
                new SseEventData.Message(assistantMessage.getId(), ROLE_ASSISTANT));
        if (!connected) {
            // 初始事件发送失败说明客户端已断开，直接标记助手消息停止。
            abortBeforeRun(handle, assistantMessage, taskRunContext, RunCancelReason.CLIENT_DISCONNECTED.message(), false);
            events.complete();
            return emitter;
        }
        emitter.onTimeout(() -> handle.cancel(RunCancelReason.TIMEOUT));
        emitter.onError(error -> handle.cancel(RunCancelReason.CLIENT_DISCONNECTED));

        ChatRunRequest request = new ChatRunRequest(
                prepared.conversation().getUserId(),
                resolvedConversationId,
                prepared.runtimeConfig(),
                prepared.context().messages(),
                assistantMessage,
                prepared.conversation().getAgentKey(),
                prepared.agentRuntime() == null ? null : prepared.agentRuntime().agentId(),
                thinkingEnabled,
                prepared.skillContext(),
                prepared.prompt(),
                taskRunContext,
                runDecision,
                !prepared.images().isEmpty()
        );
        try {
            runThreadExecutor.execute(() -> {
                try {
                    runExecutor.execute(request, handle, events);
                } finally {
                    runRegistry.release(handle);
                }
            });
        } catch (RejectedExecutionException exception) {
            abortBeforeRun(handle, assistantMessage, taskRunContext, "生成任务启动失败，请稍后重试", true);
            events.complete();
        }
        return emitter;
    }

    private void abortBeforeRun(
            ChatRunHandle handle,
            ChatMessage assistantMessage,
            TaskRunContext taskRunContext,
            String reason,
            boolean failure
    ) {
        try {
            AssistantReply reply = new AssistantReply(assistantMessage);
            if (failure) {
                messageStore.markFailed(reply, reason);
            } else {
                messageStore.markStopped(reply, reason);
            }
            taskTracker.stopQuietly(assistantMessage.getUserId(), assistantMessage.getId(), taskRunContext);
        } finally {
            runRegistry.release(handle);
        }
    }

    private AgentRunDecision routeRun(String prompt) {
        if (runRouter == null) {
            return new AgentRunDecision(RunMode.TOOL_ASSISTED, ToolPolicy.AUTO, PlanPolicy.NONE);
        }
        try {
            AgentRunDecision decision = runRouter.route(new AgentRunInput(prompt));
            if (decision == null || decision.runMode() == null) {
                return new AgentRunDecision(RunMode.DIRECT_CHAT, ToolPolicy.NONE, PlanPolicy.NONE);
            }
            return decision;
        } catch (RuntimeException exception) {
            return new AgentRunDecision(RunMode.DIRECT_CHAT, ToolPolicy.NONE, PlanPolicy.NONE);
        }
    }

    @Override
    public PageResult<ConversationItem> listConversations(Long userId, int limit, int offset) {
        return conversationService.listConversations(userId, limit, offset);
    }

    @Override
    public List<MessageItem> listMessages(Long userId, Long conversationId) {
        return conversationService.listMessages(userId, conversationId);
    }

    /**
     * 中止请求先写数据库状态，再通知本实例上的运行；运行落在其他实例时由其轮询数据库状态感知。
     */
    @Override
    public void stopGeneration(Long userId, Long messageId) {
        ChatMessage message = messageStore.findById(messageId);
        if (message == null || !Objects.equals(message.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        if (!ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只能中止助手生成消息");
        }
        if (!STREAMING.equals(message.getStatus()) && !STOPPED.equals(message.getStatus())) {
            return;
        }
        messageStore.requestStop(messageId, userId);
        runRegistry.cancel(messageId, RunCancelReason.USER_STOPPED);
        taskTracker.stopQuietly(userId, messageId);
    }

    @Override
    public List<ChatArtifactItem> listArtifacts(Long userId, Long conversationId) {
        conversationService.requireActive(userId, conversationId);
        return artifactService.list(userId, conversationId);
    }

    @Override
    public ChatArtifactContent previewArtifact(Long userId, Long conversationId, Long artifactId, int maxBytes) {
        conversationService.requireActive(userId, conversationId);
        return artifactService.preview(userId, conversationId, artifactId, maxBytes);
    }

    @Override
    public ChatArtifactDownload downloadArtifact(Long userId, Long conversationId, Long artifactId) {
        conversationService.requireActive(userId, conversationId);
        return artifactService.download(userId, conversationId, artifactId);
    }

    @Override
    public ChatArtifactDownload downloadAllArtifacts(Long userId, Long conversationId) {
        conversationService.requireActive(userId, conversationId);
        return artifactService.downloadAll(userId, conversationId);
    }

    @Override
    public void deleteConversation(Long userId, Long conversationId) {
        conversationService.delete(userId, conversationId);
    }

    @Override
    public void renameConversation(Long userId, Long conversationId, String title) {
        conversationService.rename(userId, conversationId, title);
    }
}
