package com.agenthub.chat.service;

import com.agenthub.agent.service.AgentService;
import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.chat.artifact.service.ChatArtifactService;
import com.agenthub.chat.context.ConversationContextBuilder;
import com.agenthub.chat.conversation.mapper.ChatConversationMapper;
import com.agenthub.chat.conversation.service.ConversationService;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.message.support.AssistantMessageStore;
import com.agenthub.chat.run.ChatRunExecutor;
import com.agenthub.chat.run.ChatRunProperties;
import com.agenthub.chat.run.ChatRunRegistry;
import com.agenthub.chat.run.SkillSandboxSynchronizer;
import com.agenthub.chat.service.impl.ChatServiceImpl;
import com.agenthub.chat.service.support.ChatPreparationService;
import com.agenthub.chat.task.runtime.AgentRunRouter;
import com.agenthub.chat.task.runtime.AgentTaskPlanner;
import com.agenthub.chat.task.runtime.ChatTaskProgressTracker;
import com.agenthub.chat.task.service.AgentTaskService;
import com.agenthub.chat.title.ConversationTitleGenerator;
import com.agenthub.chat.title.ConversationTitleService;
import com.agenthub.sandbox.client.SandboxClient;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.skill.service.SkillRuntimeService;
import com.agenthub.sse.SseEventSender;
import com.agenthub.storage.attachment.service.AttachmentService;
import com.agenthub.storage.object.ObjectStorageClient;
import com.agenthub.tool.runtime.ToolRegistry;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 按测试关心的依赖组装聊天服务的全部协作组件，未传入的可选能力保持为 null。
 */
final class ChatServiceTestFactory {

    private ChatServiceTestFactory() {
    }

    static ChatServiceImpl create(
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
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, null, contextBuilder, llmClientFactory, streamExecutor);
    }

    static ChatServiceImpl create(
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
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor, null, null);
    }

    static ChatServiceImpl create(
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
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor,
                sandboxToolService, toolRegistry, null);
    }

    static ChatServiceImpl create(
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
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor,
                sandboxToolService, toolRegistry, skillRuntimeService, null, null, null);
    }

    static ChatServiceImpl create(
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
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor,
                sandboxToolService, toolRegistry, skillRuntimeService, sandboxSessionService, sandboxClient,
                objectStorageClient, null, null, null);
    }

    static ChatServiceImpl create(
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
            ObjectStorageClient objectStorageClient,
            AgentTaskService taskService,
            AgentRunRouter runRouter,
            AgentTaskPlanner taskPlanner
    ) {
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor, sandboxToolService,
                toolRegistry, skillRuntimeService, sandboxSessionService, sandboxClient, objectStorageClient,
                taskService, runRouter, taskPlanner, new ChatRunProperties());
    }

    static ChatServiceImpl create(
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
            ObjectStorageClient objectStorageClient,
            AgentTaskService taskService,
            AgentRunRouter runRouter,
            AgentTaskPlanner taskPlanner,
            ChatRunProperties runProperties
    ) {
        return create(conversationMapper, messageMapper, modelService, titleService, transactionTemplate, sseEventSender,
                attachmentService, agentService, contextBuilder, llmClientFactory, streamExecutor, sandboxToolService,
                toolRegistry, skillRuntimeService, sandboxSessionService, sandboxClient, objectStorageClient,
                taskService, runRouter, taskPlanner, runProperties, new ChatRunRegistry(runProperties));
    }

    static ChatServiceImpl create(
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
            ObjectStorageClient objectStorageClient,
            AgentTaskService taskService,
            AgentRunRouter runRouter,
            AgentTaskPlanner taskPlanner,
            ChatRunProperties runProperties,
            ChatRunRegistry runRegistry
    ) {
        AssistantMessageStore messageStore = new AssistantMessageStore(messageMapper);
        ConversationService conversationService = new ConversationService(conversationMapper, messageMapper,
                attachmentService);
        ChatTaskProgressTracker taskTracker = new ChatTaskProgressTracker(taskService, taskPlanner, llmClientFactory);
        ChatRunExecutor runExecutor = new ChatRunExecutor(
                llmClientFactory,
                toolRegistry,
                messageStore,
                taskTracker,
                new SkillSandboxSynchronizer(sandboxSessionService, sandboxClient, objectStorageClient),
                runProperties
        );
        ChatPreparationService preparationService = new ChatPreparationService(
                conversationService,
                messageStore,
                modelService,
                agentService,
                skillRuntimeService,
                attachmentService,
                contextBuilder,
                new ConversationTitleGenerator(titleService, llmClientFactory),
                transactionTemplate
        );
        return new ChatServiceImpl(
                conversationService,
                new ChatArtifactService(sandboxToolService),
                preparationService,
                messageStore,
                runRegistry,
                runExecutor,
                taskTracker,
                runRouter,
                sseEventSender,
                runProperties,
                streamExecutor
        );
    }
}
