package com.agenthub.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.agent.dto.AgentRuntimeConfig;
import com.agenthub.agent.service.AgentService;
import com.agenthub.ai.llm.LlmContentPart;
import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.llm.tool.LlmToolCall;
import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.chat.context.ConversationContextBuilder;
import com.agenthub.chat.context.SimpleTokenEstimator;
import com.agenthub.chat.conversation.entity.ChatConversation;
import com.agenthub.chat.conversation.mapper.ChatConversationMapper;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.service.impl.ChatServiceImpl;
import com.agenthub.chat.summary.mapper.ChatConversationSummaryMapper;
import com.agenthub.chat.title.ConversationTitleService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventSender;
import com.agenthub.sse.SseEventType;
import com.agenthub.sandbox.client.SandboxClient;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatServiceImplTest {

    @Test
    void shouldStreamNewConversationAndPersistMessagesAndEvents() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.reasoning("正在思考"),
                LlmStreamChunk.answer("你好"),
                LlmStreamChunk.answer("呀"),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run
        );
        when(modelService.requireDefaultRuntimeConfig()).thenReturn(runtimeConfig());
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        });
        List<ChatMessage> insertedMessageSnapshots = new ArrayList<>();
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SnapshottingSequentialMessageIdAnswer(
                insertedMessageSnapshots
        ));

        service.stream(7L, null, null, "agent-a", "你好，介绍一下自己", true, List.of(), List.of());

        ArgumentCaptor<ChatConversation> conversationCaptor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(conversationCaptor.capture());
        ChatConversation conversation = conversationCaptor.getValue();
        assertThat(conversation.getUserId()).isEqualTo(7L);
        assertThat(conversation.getTitle()).isEqualTo("你好呀");
        assertThat(conversation.getAgentKey()).isEqualTo("agent-a");
        assertThat(conversation.getModelId()).isEqualTo(10L);
        assertThat(conversation.getProviderId()).isEqualTo(20L);
        assertThat(conversation.getStatus()).isEqualTo("ACTIVE");

        verify(messageMapper, org.mockito.Mockito.times(2)).insert(any(ChatMessage.class));
        ChatMessage userMessage = insertedMessageSnapshots.get(0);
        ChatMessage assistantMessage = insertedMessageSnapshots.get(1);
        assertThat(userMessage.getRole()).isEqualTo("USER");
        assertThat(userMessage.getContent()).isEqualTo("你好，介绍一下自己");
        assertThat(userMessage.getStatus()).isEqualTo("COMPLETED");
        assertThat(userMessage.getCompletedAt()).isNotNull();
        assertThat(assistantMessage.getRole()).isEqualTo("ASSISTANT");
        assertThat(assistantMessage.getContent()).isEmpty();
        assertThat(assistantMessage.getReasoningContent()).isEmpty();
        assertThat(assistantMessage.getStatus()).isEqualTo("STREAMING");

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage completedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(completedAssistantMessage.getId()).isEqualTo(2L);
        assertThat(completedAssistantMessage.getContent()).isEqualTo("你好呀");
        assertThat(completedAssistantMessage.getReasoningContent()).isEqualTo("正在思考");
        assertThat(completedAssistantMessage.getStatus()).isEqualTo("COMPLETED");
        assertThat(completedAssistantMessage.getCompletedAt()).isNotNull();

        SseEventData.Meta meta = sender.eventDataOf(SseEventData.Meta.class);
        assertThat(meta.protocol()).isEqualTo("chat.sse.v1");
        assertThat(meta.conversationId()).isEqualTo(100L);
        assertThat(meta.messageId()).isEqualTo(2L);
        assertThat(meta.streamId()).startsWith("chatmsg_2_");
        assertThat(meta.resumable()).isFalse();
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.REASONING_DELTA,
                SseEventType.ANSWER_DELTA,
                SseEventType.ANSWER_DELTA,
                SseEventType.MESSAGE_COMPLETED,
                SseEventType.DONE
        );
        assertThat(sender.completed).isTrue();
        assertThat(llmClient.lastRequest.messages()).hasSize(1);
        assertTimedMessageContent(llmClient.lastRequest.messages().getFirst().content(), "你好，介绍一下自己");
        assertThat(llmClient.lastRequest.reasoningRequested()).isTrue();
    }

    @Test
    void shouldInjectAgentSystemPromptAndUseAgentDefaultModelWhenModelNotSelected() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        AgentService agentService = mock(AgentService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.answer("好的"),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                new RecordingSseEventSender(),
                attachmentService,
                agentService,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run
        );
        when(agentService.requireEnabledRuntimeConfig("official-writing")).thenReturn(new AgentRuntimeConfig(
                1L,
                "official-writing",
                "公文写作 Agent",
                "你是一个公文写作助手。",
                10L
        ));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        });
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, null, List.of(), List.of(), 1500)).thenReturn(List.of());

        service.stream(7L, null, null, "official-writing", "起草一份会议通知", false, List.of(), List.of());

        verify(modelService).requireAvailableRuntimeConfig(10L);
        assertThat(llmClient.lastRequest.messages()).hasSize(2);
        assertThat(llmClient.lastRequest.messages().get(0).role()).isEqualTo("system");
        assertThat(llmClient.lastRequest.messages().get(0).content()).isEqualTo("你是一个公文写作助手。");
        assertThat(llmClient.lastRequest.messages().get(1).role()).isEqualTo("user");
        assertTimedMessageContent(llmClient.lastRequest.messages().get(1).content(), "起草一份会议通知");
    }

    @Test
    void shouldInjectSelectedSkillsForDefaultAgentWithProgressiveDisclosure() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.answer("好的"),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                new RecordingSseEventSender(),
                attachmentService,
                null,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService
        );
        when(modelService.requireDefaultRuntimeConfig()).thenReturn(runtimeConfig());
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        });
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, null, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(null, List.of(10L, 11L))).thenReturn(new SkillRuntimeContext(
                "已选择以下 Skill。如需完整说明，调用 skill.read。\nSkill ID：10",
                List.of(10L, 11L)
        ));

        service.stream(7L, null, null, null, "按 Skill 帮我写", false, List.of(), List.of(), List.of(10L, 11L));

        assertThat(llmClient.lastRequest.messages()).hasSize(2);
        assertThat(llmClient.lastRequest.messages().get(0).role()).isEqualTo("system");
        assertThat(llmClient.lastRequest.messages().get(0).content())
                .contains("已选择以下 Skill")
                .contains("如需完整说明，调用 skill.read")
                .contains("Skill ID：10");
        assertTimedMessageContent(llmClient.lastRequest.messages().get(1).content(), "按 Skill 帮我写");
    }

    @Test
    void shouldSyncSelectedSkillZipsBeforeLlmStream() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SandboxSessionService sandboxSessionService = mock(SandboxSessionService.class);
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        LlmClient llmClient = mock(LlmClient.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                attachmentService,
                null,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService,
                sandboxSessionService,
                sandboxClient,
                objectStorageClient
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(null, List.of(6L))).thenReturn(skillContextWithDocxPackage());
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        when(objectStorageClient.presignGet("agent-hub", "skill-packages/docx.zip", Duration.ofMinutes(10)))
                .thenReturn("https://storage.example.com/skill-packages/docx.zip");

        service.stream(7L, 100L, 10L, null, "用 docx 帮我写", false, List.of(), List.of(), List.of(6L));

        InOrder inOrder = inOrder(sandboxSessionService, objectStorageClient, sandboxClient, llmClient);
        inOrder.verify(sandboxSessionService).ensureSession(7L, 100L);
        inOrder.verify(objectStorageClient).presignGet(
                eq("agent-hub"),
                eq("skill-packages/docx.zip"),
                eq(Duration.ofMinutes(10))
        );
        ArgumentCaptor<SkillZipSyncRequest> requestCaptor = ArgumentCaptor.forClass(SkillZipSyncRequest.class);
        inOrder.verify(sandboxClient).syncSkillZips(eq("sbx_1"), requestCaptor.capture());
        inOrder.verify(llmClient).stream(any(LlmStreamRequest.class), any());

        assertThat(requestCaptor.getValue().skills())
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.skillKey()).isEqualTo("docx");
                    assertThat(skill.zipSha256()).isEqualTo("a".repeat(64));
                    assertThat(skill.targetDir()).isEqualTo("/workspace/skills/docx");
                    assertThat(skill.zipDownloadUrl()).isEqualTo("https://storage.example.com/skill-packages/docx.zip");
                });
    }

    @Test
    void shouldComputeMissingSkillZipSha256FromLegacyStoredZip() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SandboxSessionService sandboxSessionService = mock(SandboxSessionService.class);
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        LlmClient llmClient = mock(LlmClient.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                attachmentService,
                null,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService,
                sandboxSessionService,
                sandboxClient,
                objectStorageClient
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(null, List.of(6L))).thenReturn(skillContextWithDocxPackage(" "));
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        when(objectStorageClient.readBytes("agent-hub", "skill-packages/docx.zip"))
                .thenReturn("legacy zip".getBytes(StandardCharsets.UTF_8));
        when(objectStorageClient.presignGet("agent-hub", "skill-packages/docx.zip", Duration.ofMinutes(10)))
                .thenReturn("https://storage.example.com/skill-packages/docx.zip");

        service.stream(7L, 100L, 10L, null, "用 docx 帮我写", false, List.of(), List.of(), List.of(6L));

        ArgumentCaptor<SkillZipSyncRequest> requestCaptor = ArgumentCaptor.forClass(SkillZipSyncRequest.class);
        verify(sandboxClient).syncSkillZips(eq("sbx_1"), requestCaptor.capture());
        verify(llmClient).stream(any(LlmStreamRequest.class), any());
        assertThat(requestCaptor.getValue().skills())
                .singleElement()
                .satisfies(skill -> assertThat(skill.zipSha256())
                        .isEqualTo("abc773af8db9f28b6d32588141bd347ba4bd903c6410ed7a2be377fb94db054a"));
    }

    @Test
    void shouldFailAssistantMessageAndSkipLlmWhenSkillZipSyncFails() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SandboxSessionService sandboxSessionService = mock(SandboxSessionService.class);
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        LlmClient llmClient = mock(LlmClient.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                attachmentService,
                null,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService,
                sandboxSessionService,
                sandboxClient,
                objectStorageClient
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(null, List.of(6L))).thenReturn(skillContextWithDocxPackage());
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        when(objectStorageClient.presignGet("agent-hub", "skill-packages/docx.zip", Duration.ofMinutes(10)))
                .thenReturn("https://storage.example.com/skill-packages/docx.zip");
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 包到沙箱失败"))
                .when(sandboxClient)
                .syncSkillZips(eq("sbx_1"), any(SkillZipSyncRequest.class));

        service.stream(7L, 100L, 10L, null, "用 docx 帮我写", false, List.of(), List.of(), List.of(6L));

        verify(llmClient, never()).stream(any(), any());
        verify(messageMapper).updateById(argThat((ChatMessage message) ->
                "FAILED".equals(message.getStatus())
                        && message.getErrorMessage() != null
                        && message.getErrorMessage().contains("同步 Skill 包到沙箱失败")
        ));
        assertThat(sender.eventTypes()).contains(SseEventType.ERROR, SseEventType.DONE);
        assertThat(sender.completed).isTrue();
    }

    @Test
    void shouldFailAssistantMessageAndSkipSandboxSyncWhenSkillPackageMetadataIsMissing() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SandboxSessionService sandboxSessionService = mock(SandboxSessionService.class);
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        LlmClient llmClient = mock(LlmClient.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                attachmentService,
                null,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService,
                sandboxSessionService,
                sandboxClient,
                objectStorageClient
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(null, List.of(6L)))
                .thenReturn(skillContextWithDocxPackage(" ", "a".repeat(64)));

        service.stream(7L, 100L, 10L, null, "用 docx 帮我写", false, List.of(), List.of(), List.of(6L));

        verify(sandboxClient, never()).syncSkillZips(any(), any());
        verify(llmClient, never()).stream(any(), any());
        verify(messageMapper).updateById(argThat((ChatMessage message) ->
                "FAILED".equals(message.getStatus())
                        && message.getErrorMessage() != null
                        && message.getErrorMessage().contains("Skill 6(docx)")
                        && message.getErrorMessage().contains("objectKey")
        ));
        assertThat(sender.eventTypes()).contains(SseEventType.ERROR, SseEventType.DONE);
        assertThat(sender.completed).isTrue();
    }

    @Test
    void shouldUseBoundSkillsForBusinessAgentAndIgnoreSelectedSkills() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        AgentService agentService = mock(AgentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.answer("好的"),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                new RecordingSseEventSender(),
                attachmentService,
                agentService,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService
        );
        when(agentService.requireEnabledRuntimeConfig("official-writing")).thenReturn(new AgentRuntimeConfig(
                1L,
                "official-writing",
                "公文写作 Agent",
                "你是一个公文写作助手。",
                10L
        ));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        });
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, null, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(1L, List.of(10L))).thenReturn(new SkillRuntimeContext(
                "已绑定以下 Skill。如需完整说明，调用 skill.read。\nSkill ID：11",
                List.of(11L)
        ));

        service.stream(7L, null, null, "official-writing", "起草会议通知", false, List.of(), List.of(), List.of(10L));

        assertThat(llmClient.lastRequest.messages()).hasSize(2);
        assertThat(llmClient.lastRequest.messages().get(0).content())
                .contains("你是一个公文写作助手。")
                .contains("已绑定以下 Skill")
                .contains("Skill ID：11")
                .doesNotContain("Skill ID：10");
        verify(skillRuntimeService).buildRuntimeContext(1L, List.of(10L));
    }

    @Test
    void shouldSyncBoundSkillZipsBeforeLlmStreamForBusinessAgent() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        AgentService agentService = mock(AgentService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SandboxSessionService sandboxSessionService = mock(SandboxSessionService.class);
        SandboxClient sandboxClient = mock(SandboxClient.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        LlmClient llmClient = mock(LlmClient.class);
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                new RecordingSseEventSender(),
                attachmentService,
                agentService,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run,
                null,
                null,
                skillRuntimeService,
                sandboxSessionService,
                sandboxClient,
                objectStorageClient
        );
        ChatConversation conversation = conversation(100L, 7L, "ACTIVE");
        conversation.setAgentKey("official-writing");
        when(conversationMapper.selectById(100L)).thenReturn(conversation);
        when(agentService.requireEnabledRuntimeConfig("official-writing")).thenReturn(new AgentRuntimeConfig(
                1L,
                "official-writing",
                "公文写作 Agent",
                "你是一个公文写作助手。",
                10L
        ));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500)).thenReturn(List.of());
        when(skillRuntimeService.buildRuntimeContext(1L, List.of(10L))).thenReturn(skillContextWithDocxPackage());
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        when(objectStorageClient.presignGet("agent-hub", "skill-packages/docx.zip", Duration.ofMinutes(10)))
                .thenReturn("https://storage.example.com/skill-packages/docx.zip");

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of(), List.of(10L));

        InOrder inOrder = inOrder(sandboxSessionService, objectStorageClient, sandboxClient, llmClient);
        inOrder.verify(sandboxSessionService).ensureSession(7L, 100L);
        inOrder.verify(objectStorageClient).presignGet(
                eq("agent-hub"),
                eq("skill-packages/docx.zip"),
                eq(Duration.ofMinutes(10))
        );
        inOrder.verify(sandboxClient).syncSkillZips(eq("sbx_1"), any(SkillZipSyncRequest.class));
        inOrder.verify(llmClient).stream(any(LlmStreamRequest.class), any());
        verify(skillRuntimeService).buildRuntimeContext(1L, List.of(10L));
    }

    @Test
    void shouldKeepExistingConversationAgentWhenRequestOmitsAgentKey() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        AgentService agentService = mock(AgentService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.answer("ok"),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                new RecordingSseEventSender(),
                attachmentService,
                agentService,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run
        );
        ChatConversation conversation = conversation(100L, 7L, "ACTIVE");
        conversation.setAgentKey("official-writing");
        when(conversationMapper.selectById(100L)).thenReturn(conversation);
        when(agentService.requireEnabledRuntimeConfig("official-writing")).thenReturn(new AgentRuntimeConfig(
                1L,
                "official-writing",
                "公文写作 Agent",
                "你是一个公文写作助手。",
                10L
        ));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500)).thenReturn(List.of());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        verify(agentService).requireEnabledRuntimeConfig("official-writing");
        assertThat(llmClient.lastRequest.messages()).hasSize(2);
        assertThat(llmClient.lastRequest.messages().get(0).role()).isEqualTo("system");
        assertThat(llmClient.lastRequest.messages().get(0).content()).isEqualTo("你是一个公文写作助手。");
        assertTimedMessageContent(llmClient.lastRequest.messages().get(1).content(), "继续");
    }

    @Test
    void shouldRejectDisabledAgentBeforePreparingChatRecords() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AgentService agentService = mock(AgentService.class);
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                new RecordingSseEventSender(),
                mock(AttachmentService.class),
                agentService,
                contextBuilder(messageMapper, mock(AttachmentService.class), new RecordingLlmClient(List.of())),
                protocolType -> new RecordingLlmClient(List.of()),
                Runnable::run
        );
        when(agentService.requireEnabledRuntimeConfig("disabled-agent"))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Agent 不可用"));

        assertThatThrownBy(() -> service.stream(7L, null, null, "disabled-agent", "测试", false, List.of(), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent 不可用")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(modelService, never()).requireDefaultRuntimeConfig();
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void shouldRejectExistingConversationOwnedByOtherUser() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 9L, "ACTIVE"));

        assertThatThrownBy(() -> service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void shouldRejectBlankPrompt() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));

        assertThatThrownBy(() -> service.stream(7L, null, 10L, null, "   ", false, List.of(), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("消息内容不能为空")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(modelService, never()).requireAvailableRuntimeConfig(any());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void shouldAllowImageOnlyMessage() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.done()));
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                attachmentService, llmClient);
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(1L), List.of(), 1500))
                .thenReturn(List.of(new AttachmentReference(
                        1L,
                        "cat.png",
                        "image/png",
                        "https://example.com/cat.png",
                        1500
                )));

        service.stream(7L, 100L, 10L, null, "   ", false, List.of(1L), List.of());

        assertThat(llmClient.lastRequest.messages()).hasSize(1);
        assertThat(llmClient.lastRequest.messages().getFirst().content()).isNull();
        assertThat(llmClient.lastRequest.messages().getFirst().parts()).hasSize(2);
        assertThat(llmClient.lastRequest.messages().getFirst().parts().get(0).text())
                .startsWith("[消息时间：")
                .contains("attachment_id=1")
                .contains("cat.png")
                .contains("sandbox.upload_file");
        assertThat(llmClient.lastRequest.messages().getFirst().parts().get(1))
                .isEqualTo(LlmContentPart.imageUrl("https://example.com/cat.png"));
        verify(attachmentService).bindToMessage(7L, 100L, 1L, List.of(1L));
    }

    @Test
    void shouldValidateConversationOwnershipBeforeListMessages() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 9L, "ACTIVE"));

        assertThatThrownBy(() -> service.listMessages(7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void shouldListMessagesWithAttachments() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                attachmentService, new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        ChatMessage userMessage = message(1L, 100L, 7L, "USER", "看图");
        ChatMessage assistantMessage = message(2L, 100L, 7L, "ASSISTANT", "这是一只猫");
        assistantMessage.setStatus("FAILED");
        assistantMessage.setErrorMessage("模型调用失败");
        assistantMessage.setProcessEvents("""
                [{"type":"tool","tool":{"toolCallId":"call_1","name":"exec","status":"completed","stdout":"ok","stderr":"","result":"stdout:\\nok\\nstderr:\\n"}}]
                """);
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage, assistantMessage));
        AttachmentItem attachment = new AttachmentItem(
                10L,
                "cat.png",
                "image/png",
                "READY",
                false,
                "https://example.com/preview.png",
                null,
                1L
        );
        when(attachmentService.listMessageAttachments(7L, List.of(1L, 2L))).thenReturn(List.of(attachment));

        List<com.agenthub.chat.message.dto.MessageItem> messages = service.listMessages(7L, 100L);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).attachments()).containsExactly(attachment);
        assertThat(messages.get(1).attachments()).isEmpty();
        assertThat(messages.get(1).status()).isEqualTo("FAILED");
        assertThat(messages.get(1).errorMessage()).isEqualTo("模型调用失败");
        assertThat(messages.get(1).processEvents()).hasSize(1);
        assertThat(messages.get(1).processEvents().getFirst().tool().name()).isEqualTo("exec");
        assertThat(messages.get(1).processEvents().getFirst().tool().stdout()).isEqualTo("ok");
    }

    @Test
    void shouldStopStreamingAssistantMessage() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        ChatMessage message = message(2L, 100L, 7L, "ASSISTANT", "部分回答");
        message.setStatus("STREAMING");
        when(messageMapper.selectById(2L)).thenReturn(message);

        service.stopGeneration(7L, 2L);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(messageCaptor.capture());
        ChatMessage stoppedMessage = messageCaptor.getValue();
        assertThat(stoppedMessage.getStatus()).isEqualTo("STOPPED");
        assertThat(stoppedMessage.getErrorMessage()).isEqualTo("用户已中止生成");
        assertThat(stoppedMessage.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldIgnoreStoppingCompletedAssistantMessage() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        ChatMessage message = message(2L, 100L, 7L, "ASSISTANT", "完整回答");
        message.setStatus("COMPLETED");
        when(messageMapper.selectById(2L)).thenReturn(message);

        service.stopGeneration(7L, 2L);

        verify(messageMapper, never()).updateById(any(ChatMessage.class));
    }

    @Test
    void shouldRejectStoppingOtherUserMessageAsNotFound() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        ChatMessage message = message(2L, 100L, 9L, "ASSISTANT", "其他用户回答");
        message.setStatus("STREAMING");
        when(messageMapper.selectById(2L)).thenReturn(message);

        assertThatThrownBy(() -> service.stopGeneration(7L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("消息不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(messageMapper, never()).updateById(any(ChatMessage.class));
    }

    @Test
    void shouldRejectStoppingUserMessage() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        ChatMessage message = message(1L, 100L, 7L, "USER", "用户问题");
        message.setStatus("COMPLETED");
        when(messageMapper.selectById(1L)).thenReturn(message);

        assertThatThrownBy(() -> service.stopGeneration(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("只能中止助手生成消息")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(messageMapper, never()).updateById(any(ChatMessage.class));
    }

    @Test
    void shouldMarkAssistantMessageFailedAndSendErrorAndDoneWhenLlmThrows() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        LlmClient throwingClient = (request, chunkConsumer) -> {
            throw new RuntimeException("网络异常");
        };
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                contextBuilder(messageMapper, mock(AttachmentService.class), throwingClient),
                protocolType -> throwingClient,
                Runnable::run
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", true, List.of(), List.of());

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage failedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(failedAssistantMessage.getStatus()).isEqualTo("FAILED");
        assertThat(failedAssistantMessage.getErrorMessage()).isEqualTo("网络异常");
        assertThat(failedAssistantMessage.getCompletedAt()).isNotNull();
        assertThat(sender.eventTypes()).contains(SseEventType.ERROR, SseEventType.DONE);
        assertThat(sender.completed).isTrue();
    }

    @Test
    void shouldKeepAssistantMessageFailedWhenLlmErrorChunkIsFollowedByDone() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender,
                new RecordingLlmClient(List.of(LlmStreamChunk.error("模型返回错误"), LlmStreamChunk.done())));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage failedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(failedAssistantMessage.getStatus()).isEqualTo("FAILED");
        assertThat(failedAssistantMessage.getErrorMessage()).isEqualTo("模型返回错误");
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.ERROR,
                SseEventType.DONE
        );
    }

    @Test
    void shouldKeepAnswerContentWhenLlmThrowsAfterAnswerDelta() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        LlmClient throwingClient = (request, chunkConsumer) -> {
            chunkConsumer.accept(LlmStreamChunk.answer("已经生成"));
            throw new RuntimeException("网络异常");
        };
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                contextBuilder(messageMapper, mock(AttachmentService.class), throwingClient),
                protocolType -> throwingClient,
                Runnable::run
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage failedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(failedAssistantMessage.getStatus()).isEqualTo("FAILED");
        assertThat(failedAssistantMessage.getContent()).isEqualTo("已经生成");
        assertThat(failedAssistantMessage.getErrorMessage()).isEqualTo("网络异常");
    }

    @Test
    void shouldStopAssistantMessageAndSkipToolExecutionWhenClientDisconnectsBeforeToolCallEvent() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender(SseEventType.TOOL_CALL);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_1",
                        "sandbox.exec",
                        Map.of("command", "python3 main.py")
                )),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(new LlmToolDefinition(
                "sandbox.exec",
                "执行 sandbox 命令",
                Map.of("type", "object")
        )));

        service.stream(7L, 100L, 10L, null, "生成文件", false, List.of(), List.of());

        verify(toolRegistry, never()).execute(any(), any());
        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage stoppedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(stoppedAssistantMessage.getStatus()).isEqualTo("STOPPED");
        assertThat(stoppedAssistantMessage.getErrorMessage()).isEqualTo("SSE 连接已断开");
        assertThat(stoppedAssistantMessage.getCompletedAt()).isNotNull();
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.TOOL_CALL,
                SseEventType.DONE
        );
        assertThat(sender.completed).isTrue();
    }

    @Test
    void shouldRetryOnceWithCompactMessagesWhenContextLengthErrorOccursBeforeDelta() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RetryingLlmClient llmClient = new RetryingLlmClient(List.of(
                List.of(LlmStreamChunk.error("context length exceeded")),
                List.of(LlmStreamChunk.answer("重试成功"), LlmStreamChunk.done())
        ));
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender, llmClient);
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.selectList(any())).thenReturn(history(6, "历史消息"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        assertThat(llmClient.requests).hasSize(2);
        assertThat(llmClient.requests.get(0).messages()).hasSizeGreaterThan(1);
        assertThat(llmClient.requests.get(1).messages()).hasSize(1);
        assertTimedMessageContent(llmClient.requests.get(1).messages().getFirst().content(), "继续");
        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        assertThat(updatedMessageCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(updatedMessageCaptor.getValue().getContent()).isEqualTo("重试成功");
    }

    @Test
    void shouldKeepReasoningAndAnswerContentWhenLlmReturnsErrorChunk() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender,
                new RecordingLlmClient(List.of(
                        LlmStreamChunk.reasoning("推理片段"),
                        LlmStreamChunk.answer("回答片段"),
                        LlmStreamChunk.error("模型返回错误")
                )));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", true, List.of(), List.of());

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage failedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(failedAssistantMessage.getStatus()).isEqualTo("FAILED");
        assertThat(failedAssistantMessage.getReasoningContent()).isEqualTo("推理片段");
        assertThat(failedAssistantMessage.getContent()).isEqualTo("回答片段");
        assertThat(failedAssistantMessage.getErrorMessage()).isEqualTo("模型返回错误");
        SseEventData.Error error = sender.eventDataOf(SseEventData.Error.class);
        assertThat(error.code()).isEqualTo("LLM_STREAM_FAILED");
        assertThat(error.message()).isEqualTo(failedAssistantMessage.getErrorMessage());
        assertThat(error.messageId()).isEqualTo(2L);
        assertThat(error.fatal()).isTrue();
        assertThat(error.recoverable()).isFalse();
        assertThat(error.source()).isEqualTo("LLM");
        assertThat(error.status()).isEqualTo("FAILED");
    }

    @Test
    void shouldReturnEmitterBeforeExecutingLlmTask() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ManualExecutor executor = new ManualExecutor();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.answer("异步回答"), LlmStreamChunk.done()));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                executor
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        SseEmitter emitter = service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        assertThat(emitter).isNotNull();
        assertThat(executor.captured).isNotNull();
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE
        );
        verify(messageMapper, never()).updateById(any(ChatMessage.class));

        executor.runCaptured();

        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.ANSWER_DELTA,
                SseEventType.MESSAGE_COMPLETED,
                SseEventType.DONE
        );
        verify(messageMapper).updateById(any(ChatMessage.class));
    }

    @Test
    void shouldNotRequestReasoningWhenThinkingDisabled() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.done()));
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                llmClient);
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        assertThat(llmClient.lastRequest.reasoningRequested()).isFalse();
    }

    @Test
    void shouldDiscardReasoningChunksWhenThinkingDisabled() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                LlmStreamChunk.reasoning("thinking"),
                LlmStreamChunk.answer("answer"),
                LlmStreamChunk.done()
        ));
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender, llmClient);
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "continue", false, List.of(), List.of());

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage completedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(completedAssistantMessage.getContent()).isEqualTo("answer");
        assertThat(completedAssistantMessage.getReasoningContent()).isEmpty();
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.ANSWER_DELTA,
                SseEventType.MESSAGE_COMPLETED,
                SseEventType.DONE
        );
    }

    @Test
    void shouldUseToolRegistryForToolDefinitionsAndExecution() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RetryingLlmClient llmClient = new RetryingLlmClient(List.of(
                List.of(LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_1",
                        "mcp.github.search_repositories",
                        java.util.Map.of("query", "agent hub")
                )), LlmStreamChunk.done()),
                List.of(LlmStreamChunk.answer("运行完成"), LlmStreamChunk.done())
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        ChatConversation conversation = conversation(100L, 7L, "ACTIVE");
        conversation.setAgentKey("official-writing");
        when(conversationMapper.selectById(100L)).thenReturn(conversation);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        LlmToolDefinition githubSearchTool = new LlmToolDefinition(
                "mcp.github.search_repositories",
                "搜索 GitHub 仓库",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")))
        );
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(githubSearchTool));
        when(toolRegistry.execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class)))
                .thenReturn(ToolExecutionResult.completed("repo: agent-hub"));

        service.stream(7L, 100L, 10L, null, "运行脚本", false, List.of(), List.of());

        assertThat(llmClient.requests).hasSize(2);
        assertThat(llmClient.requests.getFirst().tools()).containsExactly(githubSearchTool);
        assertThat(llmClient.requests.get(1).tools()).containsExactly(githubSearchTool);
        assertThat(llmClient.requests.get(1).messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.toolCallId()).isEqualTo("call_1");
            assertThat(message.content()).isEqualTo("repo: agent-hub");
        });
        ArgumentCaptor<ToolExecutionContext> listContextCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolRegistry).listTools(listContextCaptor.capture());
        assertThat(listContextCaptor.getValue().userId()).isEqualTo(7L);
        assertThat(listContextCaptor.getValue().conversationId()).isEqualTo(100L);
        assertThat(listContextCaptor.getValue().assistantMessageId()).isEqualTo(2L);
        assertThat(listContextCaptor.getValue().agentKey()).isEqualTo("official-writing");
        ArgumentCaptor<ToolExecutionRequest> requestCaptor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        ArgumentCaptor<ToolExecutionContext> contextCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolRegistry).execute(requestCaptor.capture(), contextCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new ToolExecutionRequest(
                "call_1",
                "mcp.github.search_repositories",
                Map.of("query", "agent hub")
        ));
        assertThat(contextCaptor.getValue().userId()).isEqualTo(7L);
        assertThat(contextCaptor.getValue().conversationId()).isEqualTo(100L);
        assertThat(contextCaptor.getValue().assistantMessageId()).isEqualTo(2L);
        assertThat(contextCaptor.getValue().agentKey()).isEqualTo("official-writing");
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.TOOL_CALL,
                SseEventType.TOOL_RESULT,
                SseEventType.ANSWER_DELTA,
                SseEventType.MESSAGE_COMPLETED,
                SseEventType.DONE
        );
        SseEventData.ToolResult toolResult = sender.eventDataOf(SseEventData.ToolResult.class);
        assertThat(toolResult.toolCallId()).isEqualTo("call_1");
        assertThat(toolResult.name()).isEqualTo("mcp.github.search_repositories");
        assertThat(toolResult.status()).isEqualTo("completed");
        assertThat(toolResult.content()).isEqualTo("repo: agent-hub");
        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        assertThat(updatedMessageCaptor.getValue().getProcessEvents())
                .contains("\"type\":\"tool\"", "\"name\":\"mcp.github.search_repositories\"",
                        "\"result\":\"repo: agent-hub\"");
    }

    @Test
    void shouldRecordFailedToolResultWhenRegistryExecutionThrows() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RetryingLlmClient llmClient = new RetryingLlmClient(List.of(
                List.of(LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_1",
                        "mcp.github.search_repositories",
                        Map.of("query", "agent hub")
                )), LlmStreamChunk.done())
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        ChatConversation conversation = conversation(100L, 7L, "ACTIVE");
        conversation.setAgentKey("official-writing");
        when(conversationMapper.selectById(100L)).thenReturn(conversation);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(new LlmToolDefinition(
                "mcp.github.search_repositories",
                "搜索 GitHub 仓库",
                Map.of("type", "object")
        )));
        when(toolRegistry.execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class)))
                .thenThrow(new RuntimeException("远程工具异常"));

        service.stream(7L, 100L, 10L, null, "搜索仓库", false, List.of(), List.of());

        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.META,
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.TOOL_CALL,
                SseEventType.TOOL_RESULT,
                SseEventType.ERROR,
                SseEventType.DONE
        );
        SseEventData.ToolResult toolResult = sender.eventDataOf(SseEventData.ToolResult.class);
        assertThat(toolResult.toolCallId()).isEqualTo("call_1");
        assertThat(toolResult.name()).isEqualTo("mcp.github.search_repositories");
        assertThat(toolResult.status()).isEqualTo("failed");
        assertThat(toolResult.content()).isEqualTo("远程工具异常");
        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage failedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(failedAssistantMessage.getStatus()).isEqualTo("FAILED");
        assertThat(failedAssistantMessage.getErrorMessage()).isEqualTo("远程工具异常");
        assertThat(failedAssistantMessage.getProcessEvents())
                .contains("\"status\":\"failed\"", "\"result\":\"远程工具异常\"");
    }

    @Test
    void shouldDefaultToolOutputStreamAndContentWhenRegistryEmitsNullOutput() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RetryingLlmClient llmClient = new RetryingLlmClient(List.of(
                List.of(LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_1",
                        "sandbox.exec",
                        Map.of("command", "python main.py")
                )), LlmStreamChunk.done()),
                List.of(LlmStreamChunk.answer("完成"), LlmStreamChunk.done())
        ));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(new LlmToolDefinition(
                "sandbox.exec",
                "执行命令",
                Map.of("type", "object")
        )));
        when(toolRegistry.execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class)))
                .thenAnswer(invocation -> {
                    ToolExecutionContext context = invocation.getArgument(1);
                    context.eventSink().output("call_1", "sandbox.exec", null, null);
                    return ToolExecutionResult.completed("ok");
                });

        service.stream(7L, 100L, 10L, null, "运行脚本", false, List.of(), List.of());

        SseEventData.ToolOutput toolOutput = sender.eventDataOf(SseEventData.ToolOutput.class);
        assertThat(toolOutput.toolCallId()).isEqualTo("call_1");
        assertThat(toolOutput.stream()).isEqualTo("stdout");
        assertThat(toolOutput.content()).isEmpty();
        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        assertThat(updatedMessageCaptor.getValue().getProcessEvents())
                .contains("\"stdout\":\"\"", "\"stderr\":\"\"");
    }

    @Test
    void shouldAllowOneHundredSandboxToolCallsBeforeCompleting() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        CountingToolLoopClient llmClient = new CountingToolLoopClient(100);
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(new LlmToolDefinition(
                "sandbox.list_files",
                "列出文件",
                Map.of("type", "object")
        )));
        when(toolRegistry.execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class)))
                .thenReturn(ToolExecutionResult.completed("file /workspace/artifacts/output.txt"));

        service.stream(7L, 100L, 10L, null, "生成一百步文件任务", false, List.of(), List.of());

        assertThat(llmClient.requests).hasSize(101);
        verify(toolRegistry, org.mockito.Mockito.times(100))
                .execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class));
        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        assertThat(updatedMessageCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(updatedMessageCaptor.getValue().getContent()).isEqualTo("全部工具调用完成");
    }

    @Test
    void shouldMarkToolCallLimitErrorSourceAsTool() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        CountingToolLoopClient llmClient = new CountingToolLoopClient(101);
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(new LlmToolDefinition(
                "sandbox.list_files",
                "列出文件",
                Map.of("type", "object")
        )));
        when(toolRegistry.execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class)))
                .thenReturn(ToolExecutionResult.completed("file /workspace/artifacts/output.txt"));

        service.stream(7L, 100L, 10L, null, "生成超长工具任务", false, List.of(), List.of());

        SseEventData.Error error = sender.eventDataOf(SseEventData.Error.class);
        assertThat(error.code()).isEqualTo("LLM_STREAM_FAILED");
        assertThat(error.source()).isEqualTo("TOOL");
        assertThat(error.messageId()).isEqualTo(2L);
        assertThat(error.status()).isEqualTo("FAILED");
        assertThat(sender.eventTypes()).contains(SseEventType.ERROR, SseEventType.DONE);
    }

    @Test
    void shouldIncludeSandboxPathRulesInSystemPromptWhenToolsAvailable() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        RecordingSseEventSender sender = new RecordingSseEventSender();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.done()));
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                mock(AttachmentService.class),
                null,
                contextBuilder(messageMapper, mock(AttachmentService.class), llmClient),
                protocolType -> llmClient,
                Runnable::run,
                mock(SandboxToolService.class),
                toolRegistry
        );
        when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(new LlmToolDefinition(
                "sandbox.exec",
                "执行命令",
                Map.of("type", "object")
        )));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
            ChatConversation conversation = invocation.getArgument(0);
            conversation.setId(100L);
            return 1;
        });
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, null, 10L, null,
                "写一个 python 快速排序算法，输入数据为 input.txt，输出结果为 output.txt，最后把代码和 input、output 给我下载",
                false, List.of(), List.of());

        assertThat(llmClient.lastRequest).isNotNull();
        assertThat(llmClient.lastRequest.messages().getFirst().role()).isEqualTo("system");
        assertThat(llmClient.lastRequest.messages().getFirst().content())
                .contains("/workspace/input", "/workspace/artifacts", "/tmp", "相对路径",
                        "sandbox.write_file", "下载", "sandbox.exec")
                .doesNotContain("调用 write_file", "- exec 可以");
    }

    @Test
    void shouldRenameOwnedConversation() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));

        service.renameConversation(7L, 100L, "  新标题  ");

        ArgumentCaptor<ChatConversation> conversationCaptor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).updateById(conversationCaptor.capture());
        ChatConversation updatedConversation = conversationCaptor.getValue();
        assertThat(updatedConversation.getId()).isEqualTo(100L);
        assertThat(updatedConversation.getTitle()).isEqualTo("新标题");
        assertThat(updatedConversation.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldRejectImagesWhenModelDoesNotSupportImageInput() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                attachmentService, new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig(false, null));
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(1L), List.of(), 1500))
                .thenReturn(List.of(new AttachmentReference(
                        1L,
                        "cat.png",
                        "image/png",
                        "https://example.com/cat.png",
                        1500
                )));

        assertThatThrownBy(() -> service.stream(7L, 100L, 10L, null, "看图", false, List.of(1L), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前模型不支持图片输入")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void shouldBindReadyAttachmentsToUserMessage() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                attachmentService, new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(1L, 2L), List.of(), 1500))
                .thenReturn(List.of());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(1L, 2L), List.of());

        verify(attachmentService).bindToMessage(7L, 100L, 1L, List.of(1L, 2L));
    }

    @Test
    void shouldSendImagePartsToLlmClient() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.done()));
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender,
                attachmentService, llmClient);
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(1L), List.of(), 1500))
                .thenReturn(List.of(new AttachmentReference(
                        1L,
                        "cat.png",
                        "image/png",
                        "https://example.com/cat.png",
                        1500
                )));

        service.stream(7L, 100L, 10L, null, "分析图片", false, List.of(1L), List.of());

        assertThat(llmClient.lastRequest.messages()).hasSize(1);
        assertThat(llmClient.lastRequest.messages().getFirst().content()).isNull();
        assertThat(llmClient.lastRequest.messages().getFirst().parts()).hasSize(2);
        assertThat(llmClient.lastRequest.messages().getFirst().parts().get(0).text())
                .startsWith("[消息时间：")
                .contains("分析图片")
                .contains("attachment_id=1")
                .contains("cat.png")
                .contains("sandbox.upload_file");
        assertThat(llmClient.lastRequest.messages().getFirst().parts().get(1))
                .isEqualTo(LlmContentPart.imageUrl("https://example.com/cat.png"));
    }

    @Test
    void shouldCommitPreparedRecordsBeforeSendingSseEvents() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        List<String> events = new ArrayList<>();
        RecordingSseEventSender sender = new RecordingSseEventSender(events);
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(events),
                sender,
                mock(AttachmentService.class),
                contextBuilder(messageMapper, mock(AttachmentService.class), new RecordingLlmClient(List.of(LlmStreamChunk.done()))),
                protocolType -> new RecordingLlmClient(List.of(LlmStreamChunk.done())),
                Runnable::run
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        assertThat(events).contains("tx:commit", "sse:META", "sse:CONVERSATION", "sse:MESSAGE");
        List<String> sseEvents = events.stream()
                .filter(event -> event.startsWith("sse:"))
                .toList();
        assertThat(sseEvents.getFirst()).isEqualTo("sse:META");
        assertThat(events.indexOf("tx:commit")).isLessThan(events.indexOf("sse:META"));
        assertThat(events.indexOf("sse:META")).isLessThan(events.indexOf("sse:CONVERSATION"));
        assertThat(events.indexOf("sse:CONVERSATION")).isLessThan(events.indexOf("sse:MESSAGE"));
    }

    @Test
    void shouldBuildSummaryContextOutsidePreparationTransaction() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatConversationSummaryMapper summaryMapper = mock(ChatConversationSummaryMapper.class);
        List<String> events = new ArrayList<>();
        RecordingSseEventSender sender = new RecordingSseEventSender(events);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.answer("summary"), LlmStreamChunk.done()), events);
        ManualExecutor executor = new ManualExecutor();
        ChatServiceImpl service = new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(events),
                sender,
                attachmentService,
                new ConversationContextBuilder(
                        messageMapper,
                        summaryMapper,
                        attachmentService,
                        new SimpleTokenEstimator(),
                        protocolType -> llmClient
                ),
                protocolType -> llmClient,
                executor
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig(2_000, 100));
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.selectList(any())).thenReturn(history(10, "a".repeat(800)));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500))
                .thenReturn(List.of());

        service.stream(7L, 100L, 10L, null, "continue", false, List.of(), List.of());

        assertThat(events).contains("llm:stream", "tx:begin", "tx:commit", "sse:CONVERSATION");
        assertThat(events.indexOf("llm:stream")).isLessThan(events.indexOf("tx:begin"));
        assertThat(executor.captured).isNotNull();
    }

    @Test
    void shouldNotStartLlmOrSaveAssistantWhenBindingAttachmentFails() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(LlmStreamChunk.done()));
        RecordingSseEventSender sender = new RecordingSseEventSender();
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender,
                attachmentService, llmClient);
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(1L), List.of(), 1500))
                .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "图片上传失败，请重试"))
                .when(attachmentService)
                .bindToMessage(7L, 100L, 1L, List.of(1L));

        assertThatThrownBy(() -> service.stream(7L, 100L, 10L, null, "继续", false, List.of(1L), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片上传失败，请重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(messageMapper, org.mockito.Mockito.times(1)).insert(any(ChatMessage.class));
        assertThat(llmClient.lastRequest).isNull();
        assertThat(sender.eventTypes).isEmpty();
    }

    @Test
    void shouldResolveRecentReusableImagesWithoutLatestMessageLookup() {
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AiModelService modelService = mock(AiModelService.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
                attachmentService, new RecordingLlmClient(List.of(LlmStreamChunk.done())));
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
        when(attachmentService.resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500))
                .thenReturn(List.of());

        service.stream(7L, 100L, 10L, null, "继续", false, List.of(), List.of());

        verify(messageMapper, never()).selectOne(any());
        verify(attachmentService).resolveImagesForChat(7L, 100L, List.of(), List.of(), 1500);
    }

    private SkillRuntimeContext skillContextWithDocxPackage() {
        return skillContextWithDocxPackage("a".repeat(64));
    }

    private SkillRuntimeContext skillContextWithDocxPackage(String zipSha256) {
        return skillContextWithDocxPackage("skill-packages/docx.zip", zipSha256);
    }

    private SkillRuntimeContext skillContextWithDocxPackage(String objectKey, String zipSha256) {
        return new SkillRuntimeContext(
                "已选择以下 Skill。\nsandbox 路径：/workspace/skills/docx",
                List.of(6L),
                List.of(new SkillRuntimePackage(
                        6L,
                        "docx",
                        "DOCX 文档",
                        "agent-hub",
                        objectKey,
                        zipSha256
                ))
        );
    }

    private ChatServiceImpl service(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            RecordingSseEventSender sender,
            LlmClient llmClient
    ) {
        return service(conversationMapper, messageMapper, modelService, sender, mock(AttachmentService.class), llmClient);
    }

    private ChatServiceImpl service(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            RecordingSseEventSender sender,
            AttachmentService attachmentService,
            LlmClient llmClient
    ) {
        return new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                transactionTemplate(),
                sender,
                attachmentService,
                contextBuilder(messageMapper, attachmentService, llmClient),
                protocolType -> llmClient,
                Runnable::run
        );
    }

    private ConversationContextBuilder contextBuilder(
            ChatMessageMapper messageMapper,
            AttachmentService attachmentService,
            LlmClient llmClient
    ) {
        return new ConversationContextBuilder(
                messageMapper,
                mock(ChatConversationSummaryMapper.class),
                attachmentService,
                new SimpleTokenEstimator(),
                protocolType -> llmClient
        );
    }

    private void assertTimedMessageContent(String actual, String expectedContent) {
        assertThat(actual)
                .startsWith("[消息时间：")
                .endsWith("\n" + expectedContent);
    }

    private void assertOnlyTimedMessageContent(String actual) {
        assertThat(actual)
                .startsWith("[消息时间：")
                .doesNotContain("\n");
    }

    private TransactionTemplate transactionTemplate() {
        return transactionTemplate(null);
    }

    private TransactionTemplate transactionTemplate(List<String> events) {
        return new TransactionTemplate(new RecordingTransactionManager(events));
    }

    private ChatConversation conversation(Long id, Long userId, String status) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setTitle("旧会话");
        conversation.setModelId(10L);
        conversation.setProviderId(20L);
        conversation.setStatus(status);
        return conversation;
    }

    private ChatMessage message(Long id, Long conversationId, Long userId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setReasoningContent("");
        message.setStatus("COMPLETED");
        return message;
    }

    private List<ChatMessage> history(int count, String content) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add(message((long) i, 100L, 7L, i % 2 == 0 ? "ASSISTANT" : "USER",
                    "message-" + i + " " + content));
        }
        return messages;
    }

    private AiModelRuntimeConfig runtimeConfig() {
        return runtimeConfig(true, null);
    }

    private AiModelRuntimeConfig runtimeConfig(Integer contextWindow, Integer maxOutputTokens) {
        return new AiModelRuntimeConfig(
                10L,
                20L,
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.example.com",
                "sk-cipher",
                "BEARER",
                "v1",
                "{}",
                "{}",
                "gpt-test",
                "娴嬭瘯妯″瀷",
                true,
                true,
                true,
                null,
                contextWindow,
                maxOutputTokens,
                true,
                true
        );
    }

    private AiModelRuntimeConfig runtimeConfig(boolean imageInputSupported, Integer imageTokenEstimate) {
        return new AiModelRuntimeConfig(
                10L,
                20L,
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.example.com",
                "sk-cipher",
                "BEARER",
                "v1",
                "{}",
                "{}",
                "gpt-test",
                "测试模型",
                true,
                true,
                imageInputSupported,
                imageTokenEstimate,
                128000,
                4096,
                true,
                true
        );
    }

    private static class SequentialMessageIdAnswer implements org.mockito.stubbing.Answer<Integer> {

        private long nextId = 1L;

        @Override
        public Integer answer(org.mockito.invocation.InvocationOnMock invocation) {
            ChatMessage message = invocation.getArgument(0);
            message.setId(nextId++);
            return 1;
        }
    }

    private static class SnapshottingSequentialMessageIdAnswer implements org.mockito.stubbing.Answer<Integer> {

        private final List<ChatMessage> snapshots;
        private long nextId = 1L;

        private SnapshottingSequentialMessageIdAnswer(List<ChatMessage> snapshots) {
            this.snapshots = snapshots;
        }

        @Override
        public Integer answer(org.mockito.invocation.InvocationOnMock invocation) {
            ChatMessage message = invocation.getArgument(0);
            message.setId(nextId++);
            snapshots.add(copy(message));
            return 1;
        }

        private ChatMessage copy(ChatMessage source) {
            ChatMessage copied = new ChatMessage();
            copied.setId(source.getId());
            copied.setConversationId(source.getConversationId());
            copied.setUserId(source.getUserId());
            copied.setRole(source.getRole());
            copied.setContent(source.getContent());
            copied.setReasoningContent(source.getReasoningContent());
            copied.setProcessEvents(source.getProcessEvents());
            copied.setProviderId(source.getProviderId());
            copied.setModelId(source.getModelId());
            copied.setStatus(source.getStatus());
            copied.setErrorMessage(source.getErrorMessage());
            copied.setCreatedAt(source.getCreatedAt());
            copied.setCompletedAt(source.getCompletedAt());
            return copied;
        }
    }

    private static class RecordingLlmClient implements LlmClient {

        private final List<LlmStreamChunk> chunks;
        private final List<String> orderedEvents;
        private com.agenthub.ai.llm.LlmStreamRequest lastRequest;

        private RecordingLlmClient(List<LlmStreamChunk> chunks) {
            this(chunks, null);
        }

        private RecordingLlmClient(List<LlmStreamChunk> chunks, List<String> orderedEvents) {
            this.chunks = chunks;
            this.orderedEvents = orderedEvents;
        }

        @Override
        public void stream(com.agenthub.ai.llm.LlmStreamRequest request, java.util.function.Consumer<LlmStreamChunk> chunkConsumer) {
            this.lastRequest = request;
            if (orderedEvents != null) {
                orderedEvents.add("llm:stream");
            }
            chunks.forEach(chunkConsumer);
        }
    }

    private static class RetryingLlmClient implements LlmClient {

        private final List<List<LlmStreamChunk>> attempts;
        private final List<com.agenthub.ai.llm.LlmStreamRequest> requests = new ArrayList<>();

        private RetryingLlmClient(List<List<LlmStreamChunk>> attempts) {
            this.attempts = attempts;
        }

        @Override
        public void stream(com.agenthub.ai.llm.LlmStreamRequest request, java.util.function.Consumer<LlmStreamChunk> chunkConsumer) {
            requests.add(request);
            List<LlmStreamChunk> chunks = attempts.get(Math.min(requests.size() - 1, attempts.size() - 1));
            chunks.forEach(chunkConsumer);
        }
    }

    private static class CountingToolLoopClient implements LlmClient {

        private final int toolCallCount;
        private final List<com.agenthub.ai.llm.LlmStreamRequest> requests = new ArrayList<>();

        private CountingToolLoopClient(int toolCallCount) {
            this.toolCallCount = toolCallCount;
        }

        @Override
        public void stream(com.agenthub.ai.llm.LlmStreamRequest request, java.util.function.Consumer<LlmStreamChunk> chunkConsumer) {
            requests.add(request);
            int index = requests.size();
            if (index <= toolCallCount) {
                chunkConsumer.accept(LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_" + index,
                        "sandbox.list_files",
                        Map.of("path", "/workspace/artifacts", "recursive", true)
                )));
                chunkConsumer.accept(LlmStreamChunk.done());
                return;
            }
            chunkConsumer.accept(LlmStreamChunk.answer("全部工具调用完成"));
            chunkConsumer.accept(LlmStreamChunk.done());
        }
    }

    private static class RecordingSseEventSender extends SseEventSender {

        private final List<SseEventType> eventTypes = new ArrayList<>();
        private final List<SseEventData> eventData = new ArrayList<>();
        private final List<String> orderedEvents;
        private final SseEventType failingEventType;
        private boolean completed;

        private RecordingSseEventSender() {
            this(null, null);
        }

        private RecordingSseEventSender(SseEventType failingEventType) {
            this(null, failingEventType);
        }

        private RecordingSseEventSender(List<String> orderedEvents) {
            this(orderedEvents, null);
        }

        private RecordingSseEventSender(List<String> orderedEvents, SseEventType failingEventType) {
            this.orderedEvents = orderedEvents;
            this.failingEventType = failingEventType;
        }

        @Override
        public void send(SseEmitter emitter, SseEventType eventType, SseEventData data) throws IOException {
            eventTypes.add(eventType);
            eventData.add(data);
            if (orderedEvents != null) {
                orderedEvents.add("sse:" + eventType.name());
            }
            if (eventType == failingEventType) {
                throw new IOException("client disconnected");
            }
        }

        @Override
        public void complete(SseEmitter emitter) {
            completed = true;
        }

        private List<SseEventType> eventTypes() {
            return eventTypes;
        }

        private <T extends SseEventData> T eventDataOf(Class<T> type) {
            return eventData.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<String> orderedEvents;

        private RecordingTransactionManager(List<String> orderedEvents) {
            this.orderedEvents = orderedEvents;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            if (orderedEvents != null) {
                orderedEvents.add("tx:begin");
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (orderedEvents != null) {
                orderedEvents.add("tx:commit");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            if (orderedEvents != null) {
                orderedEvents.add("tx:rollback");
            }
        }
    }

    private static class ManualExecutor implements Executor {

        private Runnable captured;

        @Override
        public void execute(Runnable command) {
            this.captured = command;
        }

        private void runCaptured() {
            captured.run();
        }
    }
}
