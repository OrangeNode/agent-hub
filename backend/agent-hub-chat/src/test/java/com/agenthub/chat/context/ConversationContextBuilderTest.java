package com.agenthub.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmContentPart;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.mapper.ChatMessageMapper;
import com.agenthub.chat.summary.entity.ChatConversationSummary;
import com.agenthub.chat.summary.mapper.ChatConversationSummaryMapper;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.attachment.dto.AttachmentItem;
import com.agenthub.storage.attachment.dto.AttachmentReference;
import com.agenthub.storage.attachment.service.AttachmentService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationContextBuilderTest {

    @Test
    void shouldRejectCurrentInputWhenItExceedsAvailableBudget() {
        ConversationContextBuilder builder = builder(List.of(), null, summaryClient("摘要"));

        assertThatThrownBy(() -> builder.build(7L, 100L, runtimeConfig(2_000, 500), "a".repeat(5_000), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前输入内容过长，已超过模型上下文限制，请缩短后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectEvenShortInputWhenAvailableBudgetIsNotPositive() {
        ConversationContextBuilder builder = builder(List.of(), null, summaryClient("摘要"));

        assertThatThrownBy(() -> builder.build(7L, 100L, runtimeConfig(1_000, 100), "hi", List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前输入内容过长，已超过模型上下文限制，请缩短后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldTriggerSummaryWhenFullHistoryReachesEightyPercent() {
        ChatConversationSummaryMapper summaryMapper = mock(ChatConversationSummaryMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(messageMapper.selectList(any())).thenReturn(history(10, "a".repeat(800)));
        RecordingLlmClient llmClient = summaryClient("压缩后的中文摘要");
        ConversationContextBuilder builder = new ConversationContextBuilder(
                messageMapper,
                summaryMapper,
                attachmentService,
                new SimpleTokenEstimator(),
                protocolType -> llmClient
        );

        ConversationContext result = builder.build(7L, 100L, runtimeConfig(2_000, 100), "继续", List.of());

        assertThat(result.summaryUpdated()).isTrue();
        ArgumentCaptor<ChatConversationSummary> summaryCaptor = ArgumentCaptor.forClass(ChatConversationSummary.class);
        verify(summaryMapper).insert(summaryCaptor.capture());
        ChatConversationSummary inserted = summaryCaptor.getValue();
        assertThat(inserted.getConversationId()).isEqualTo(100L);
        assertThat(inserted.getUserId()).isEqualTo(7L);
        assertThat(inserted.getSummary()).isEqualTo("压缩后的中文摘要");
        assertThat(inserted.getSummarizedMessageId()).isEqualTo(6L);
        assertThat(inserted.getTokenEstimate()).isPositive();
        assertThat(llmClient.lastRequest.messages()).hasSize(1);
        assertThat(llmClient.lastRequest.messages().getFirst().content())
                .contains("请把以下会话历史压缩为后续对话可用的中文摘要")
                .contains("不要编造历史中不存在的信息");
    }

    @Test
    void shouldKeepRecentMessagesWithinBudget() {
        List<ChatMessage> history = history(30, "a".repeat(400));
        ConversationContextBuilder builder = builder(history, null, summaryClient("摘要"));

        ConversationContext result = builder.build(7L, 100L, runtimeConfig(10_000, 1_000), "当前问题", List.of());

        List<LlmMessage> historyMessages = result.messages().subList(0, result.messages().size() - 1);
        assertThat(historyMessages).hasSizeLessThanOrEqualTo(20);
        assertThat(historyMessages).extracting(LlmMessage::content)
                .doesNotContain("message-1 " + "a".repeat(400));
        assertThat(result.messages().getLast().role()).isEqualTo("user");
        assertThat(result.messages().getLast().content()).isEqualTo("当前问题");
        assertThat(result.inputTokenEstimate()).isLessThanOrEqualTo(10_000 - 1_000 - 1_024);
    }

    @Test
    void shouldIncludeAvailableHistoryImagesAsMessageParts() {
        ChatMessage historyMessage = message(1L, "USER", "analyze historical image");
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.listMessageAttachments(7L, List.of(1L))).thenReturn(List.of(
                attachment(10L, 1L, false, "https://example.com/history.png"),
                attachment(11L, 1L, true, "https://example.com/expired.png"),
                attachment(12L, 1L, false, " ")
        ));
        ConversationContextBuilder builder = builder(
                List.of(historyMessage),
                null,
                summaryClient("summary"),
                attachmentService
        );

        ConversationContext result = builder.build(7L, 100L, runtimeConfig(10_000, 1_000), "continue", List.of());

        LlmMessage historyLlmMessage = result.messages().getFirst();
        assertThat(historyLlmMessage.role()).isEqualTo("user");
        assertThat(historyLlmMessage.content()).isNull();
        assertThat(historyLlmMessage.parts()).containsExactly(
                LlmContentPart.text("analyze historical image"),
                LlmContentPart.imageUrl("https://example.com/history.png")
        );
        assertThat(result.inputTokenEstimate()).isGreaterThan(1_500);
    }

    private ConversationContextBuilder builder(
            List<ChatMessage> history,
            ChatConversationSummary summary,
            LlmClient llmClient
    ) {
        return builder(history, summary, llmClient, mock(AttachmentService.class));
    }

    private ConversationContextBuilder builder(
            List<ChatMessage> history,
            ChatConversationSummary summary,
            LlmClient llmClient,
            AttachmentService attachmentService
    ) {
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatConversationSummaryMapper summaryMapper = mock(ChatConversationSummaryMapper.class);
        when(messageMapper.selectList(any())).thenReturn(history);
        when(summaryMapper.selectOne(any())).thenReturn(summary);
        return new ConversationContextBuilder(
                messageMapper,
                summaryMapper,
                attachmentService,
                new SimpleTokenEstimator(),
                protocolType -> llmClient
        );
    }

    private List<ChatMessage> history(int count, String content) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add(message((long) i, i % 2 == 0 ? "ASSISTANT" : "USER", "message-" + i + " " + content));
        }
        return messages;
    }

    private ChatMessage message(Long id, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setConversationId(100L);
        message.setUserId(7L);
        message.setRole(role);
        message.setContent(content);
        message.setStatus("COMPLETED");
        return message;
    }

    private AttachmentItem attachment(Long attachmentId, Long messageId, boolean expired, String previewUrl) {
        return new AttachmentItem(
                attachmentId,
                "image.png",
                "image/png",
                "READY",
                expired,
                previewUrl,
                null,
                messageId
        );
    }

    private RecordingLlmClient summaryClient(String summary) {
        return new RecordingLlmClient(summary);
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
                "测试模型",
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

    private static class RecordingLlmClient implements LlmClient {

        private final String summary;
        private LlmStreamRequest lastRequest;

        private RecordingLlmClient(String summary) {
            this.summary = summary;
        }

        @Override
        public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
            this.lastRequest = request;
            chunkConsumer.accept(LlmStreamChunk.answer(summary));
            chunkConsumer.accept(LlmStreamChunk.done());
        }
    }
}
