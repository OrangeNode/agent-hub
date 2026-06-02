package com.agenthub.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.chat.conversation.entity.ChatConversation;
import com.agenthub.chat.conversation.mapper.ChatConversationMapper;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.service.impl.ChatServiceImpl;
import com.agenthub.chat.title.ConversationTitleService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventSender;
import com.agenthub.sse.SseEventType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
                sender,
                protocolType -> llmClient
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

        service.stream(7L, null, null, "agent-a", "你好，介绍一下自己");

        ArgumentCaptor<ChatConversation> conversationCaptor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(conversationCaptor.capture());
        ChatConversation conversation = conversationCaptor.getValue();
        assertThat(conversation.getUserId()).isEqualTo(7L);
        assertThat(conversation.getTitle()).isEqualTo("你好，介绍一下自己");
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

        assertThat(sender.eventTypes()).containsExactly(
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
        assertThat(llmClient.lastRequest.messages().getFirst().content()).isEqualTo("你好，介绍一下自己");
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

        assertThatThrownBy(() -> service.stream(7L, 100L, 10L, null, "继续"))
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

        assertThatThrownBy(() -> service.stream(7L, null, 10L, null, "   "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("消息内容不能为空")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(modelService, never()).requireAvailableRuntimeConfig(any());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
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
                sender,
                protocolType -> throwingClient
        );
        when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
        when(conversationMapper.selectById(100L)).thenReturn(conversation(100L, 7L, "ACTIVE"));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());

        service.stream(7L, 100L, 10L, null, "继续");

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

        service.stream(7L, 100L, 10L, null, "继续");

        ArgumentCaptor<ChatMessage> updatedMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(updatedMessageCaptor.capture());
        ChatMessage failedAssistantMessage = updatedMessageCaptor.getValue();
        assertThat(failedAssistantMessage.getStatus()).isEqualTo("FAILED");
        assertThat(failedAssistantMessage.getErrorMessage()).isEqualTo("模型返回错误");
        assertThat(sender.eventTypes()).containsExactly(
                SseEventType.CONVERSATION,
                SseEventType.MESSAGE,
                SseEventType.ERROR,
                SseEventType.DONE
        );
    }

    private ChatServiceImpl service(
            ChatConversationMapper conversationMapper,
            ChatMessageMapper messageMapper,
            AiModelService modelService,
            RecordingSseEventSender sender,
            LlmClient llmClient
    ) {
        return new ChatServiceImpl(
                conversationMapper,
                messageMapper,
                modelService,
                new ConversationTitleService(),
                sender,
                protocolType -> llmClient
        );
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

    private AiModelRuntimeConfig runtimeConfig() {
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
        private com.agenthub.ai.llm.LlmStreamRequest lastRequest;

        private RecordingLlmClient(List<LlmStreamChunk> chunks) {
            this.chunks = chunks;
        }

        @Override
        public void stream(com.agenthub.ai.llm.LlmStreamRequest request, java.util.function.Consumer<LlmStreamChunk> chunkConsumer) {
            this.lastRequest = request;
            chunks.forEach(chunkConsumer);
        }
    }

    private static class RecordingSseEventSender extends SseEventSender {

        private final List<SseEventType> eventTypes = new ArrayList<>();
        private final List<SseEventData> eventData = new ArrayList<>();
        private boolean completed;

        @Override
        public void send(SseEmitter emitter, SseEventType eventType, SseEventData data) throws IOException {
            eventTypes.add(eventType);
            eventData.add(data);
        }

        @Override
        public void complete(SseEmitter emitter) {
            completed = true;
        }

        private List<SseEventType> eventTypes() {
            return eventTypes;
        }
    }
}
