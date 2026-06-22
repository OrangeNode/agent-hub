# 聊天 SSE 协议 v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将聊天流式链路固化为 `chat.sse.v1`，补齐协议握手、扩展错误事件、停止生成、断线恢复和消息状态一致性。

**Architecture:** 后端在 `agent-hub-sse` 定义协议事件模型，在 `agent-hub-chat` 负责发送 `meta`、扩展 `error`、暴露停止接口并返回历史错误原因。前端在 `user-web/src/api/chat.ts` 解析新增事件和扩展字段，在 `ChatView.vue` 中把 `message_completed` 作为成功信号，把 `done` 作为连接收尾信号，并通过停止接口让服务端确认 `STOPPED`。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis Plus、JUnit 5、Mockito、AssertJ、Vue 3、TypeScript、Vitest。

---

## File Map

- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`
- Modify: `backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageItem.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/controller/ChatController.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/src/views/ChatView.vue`
- Modify: `user-web/tests/chat-stream-tool-events.spec.ts`
- Modify: `user-web/tests/chat-stop-generation.spec.ts`

执行前注意：当前工作树已有与本计划无关的未提交改动。实现时先用 `superpowers:using-git-worktrees` 建隔离 worktree，或确认这些改动不会被覆盖。

## Task 1: 后端 SSE 协议模型

**Files:**
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`
- Modify: `backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java`

- [ ] **Step 1: 写失败测试，覆盖 meta 事件名和扩展错误字段**

在 `SseEventSenderTest.java` 增加测试：

```java
@Test
void eventTypeNamesIncludeChatProtocolMeta() {
    assertThat(SseEventType.META.eventName()).isEqualTo("meta");
}

@Test
void metaAndErrorDataCarryChatProtocolFields() {
    SseEventData.Meta meta = new SseEventData.Meta(
            "chat.sse.v1",
            100L,
            200L,
            "chatmsg_200_20260622_000001",
            false
    );
    assertThat(meta.protocol()).isEqualTo("chat.sse.v1");
    assertThat(meta.conversationId()).isEqualTo(100L);
    assertThat(meta.messageId()).isEqualTo(200L);
    assertThat(meta.streamId()).startsWith("chatmsg_200_");
    assertThat(meta.resumable()).isFalse();

    SseEventData.Error error = SseEventData.Error.failed(
            "LLM_STREAM_FAILED",
            "模型调用失败",
            200L,
            "LLM",
            false
    );
    assertThat(error.code()).isEqualTo("LLM_STREAM_FAILED");
    assertThat(error.message()).isEqualTo("模型调用失败");
    assertThat(error.messageId()).isEqualTo(200L);
    assertThat(error.fatal()).isTrue();
    assertThat(error.recoverable()).isFalse();
    assertThat(error.source()).isEqualTo("LLM");
    assertThat(error.status()).isEqualTo("FAILED");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd backend
mvn -pl agent-hub-sse -Dtest=SseEventSenderTest test
```

Expected: FAIL，原因是 `SseEventType.META`、`SseEventData.Meta` 或 `SseEventData.Error.failed(...)` 尚不存在。

- [ ] **Step 3: 实现 SSE 事件模型**

修改 `SseEventType.java`：

```java
public enum SseEventType {
    META("meta"),
    CONVERSATION("conversation"),
    MESSAGE("message"),
    REASONING_DELTA("reasoning_delta"),
    ANSWER_DELTA("answer_delta"),
    TOOL_CALL("tool_call"),
    TOOL_OUTPUT("tool_output"),
    TOOL_RESULT("tool_result"),
    MESSAGE_COMPLETED("message_completed"),
    ERROR("error"),
    DONE("done");
```

修改 `SseEventData.java` 的 sealed permits 列表并增加记录类型：

```java
public sealed interface SseEventData permits SseEventData.Meta,
        SseEventData.Conversation,
        SseEventData.Message,
        SseEventData.Delta,
        SseEventData.ToolCall,
        SseEventData.ToolOutput,
        SseEventData.ToolResult,
        SseEventData.MessageCompleted,
        SseEventData.Error,
        SseEventData.Done {

    record Meta(
            String protocol,
            Long conversationId,
            Long messageId,
            String streamId,
            boolean resumable
    ) implements SseEventData {
    }
```

将原 `Error` record 替换为兼容扩展结构：

```java
    record Error(
            String code,
            String message,
            Long messageId,
            boolean fatal,
            boolean recoverable,
            String source,
            String status
    ) implements SseEventData {

        public Error(String code, String message) {
            this(code, message, null, true, false, "SERVER", "FAILED");
        }

        public static Error failed(String code, String message, Long messageId, String source, boolean recoverable) {
            return new Error(code, message, messageId, true, recoverable, source, "FAILED");
        }
    }
```

- [ ] **Step 4: 运行 SSE 模块测试**

Run:

```powershell
cd backend
mvn -pl agent-hub-sse -Dtest=SseEventSenderTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/agent-hub-sse
git commit -m "固化聊天 SSE 协议事件模型"
```

## Task 2: 后端聊天流发送 meta 和扩展 error

**Files:**
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: 写失败测试，正常流应先发送 meta**

修改 `shouldStreamNewConversationAndPersistMessagesAndEvents` 中的事件断言：

```java
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
SseEventData.Meta meta = sender.eventDataOf(SseEventData.Meta.class);
assertThat(meta.protocol()).isEqualTo("chat.sse.v1");
assertThat(meta.conversationId()).isEqualTo(100L);
assertThat(meta.messageId()).isEqualTo(2L);
assertThat(meta.streamId()).startsWith("chatmsg_2_");
assertThat(meta.resumable()).isFalse();
```

同时更新其它 `containsExactly` 事件断言，把初始事件顺序改为 `META`、`CONVERSATION`、`MESSAGE`。

- [ ] **Step 2: 写失败测试，LLM 错误应发扩展 error**

在 `shouldKeepReasoningAndAnswerContentWhenLlmReturnsErrorChunk` 的尾部增加断言：

```java
SseEventData.Error error = sender.eventDataOf(SseEventData.Error.class);
assertThat(error.code()).isEqualTo("LLM_STREAM_FAILED");
assertThat(error.message()).isEqualTo("模型返回错误");
assertThat(error.messageId()).isEqualTo(failedAssistantMessage.getId());
assertThat(error.fatal()).isTrue();
assertThat(error.recoverable()).isFalse();
assertThat(error.source()).isEqualTo("LLM");
assertThat(error.status()).isEqualTo("FAILED");
```

- [ ] **Step 3: 运行测试确认失败**

Run:

```powershell
cd backend
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: FAIL，原因是聊天流未发送 `META`，错误事件仍缺扩展字段或 `messageId`。

- [ ] **Step 4: 实现 meta 发送**

在 `ChatServiceImpl` 增加常量和 streamId 方法：

```java
private static final String CHAT_SSE_PROTOCOL = "chat.sse.v1";

private String streamId(Long messageId) {
    return "chatmsg_" + messageId + "_" + OffsetDateTime.now()
            .toString()
            .replace("-", "")
            .replace(":", "")
            .replace(".", "")
            .replace("+", "")
            .replace("Z", "");
}
```

在 `stream(...)` 中创建 `SseEmitter` 后，先发送 `META`：

```java
String streamId = streamId(preparedChat.assistantMessage().getId());
boolean connected = send(emitter, SseEventType.META,
        new SseEventData.Meta(
                CHAT_SSE_PROTOCOL,
                preparedChat.conversation().getId(),
                preparedChat.assistantMessage().getId(),
                streamId,
                false
        ))
        && send(emitter, SseEventType.CONVERSATION,
        new SseEventData.Conversation(preparedChat.conversation().getId(), preparedChat.conversation().getTitle()))
        && send(emitter, SseEventType.MESSAGE,
        new SseEventData.Message(preparedChat.assistantMessage().getId(), ROLE_ASSISTANT));
```

- [ ] **Step 5: 实现扩展 error 发送**

将 `failAssistantMessageAndSendError(...)` 中的发送改成：

```java
send(emitter, SseEventType.ERROR, SseEventData.Error.failed(
        LLM_STREAM_FAILED,
        resolvedMessage,
        assistantMessage.getId(),
        "LLM",
        isContextLengthError(resolvedMessage)
));
```

在工具循环上限或工具不可恢复失败分支仍可先复用 `LLM_STREAM_FAILED`，后续单独细化 `TOOL_LOOP_FAILED`。

- [ ] **Step 6: 运行 ChatService 测试**

Run:

```powershell
cd backend
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/agent-hub-chat backend/agent-hub-sse
git commit -m "发送聊天 SSE 协议握手事件"
```

## Task 3: 历史消息错误原因与停止接口

**Files:**
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageItem.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/controller/ChatController.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: 写失败测试，历史消息返回 errorMessage**

在 `shouldListMessagesWithAttachments` 中给助手消息设置错误原因，并增加断言：

```java
assistantMessage.setStatus("FAILED");
assistantMessage.setErrorMessage("模型调用失败");
```

```java
assertThat(messages.get(1).status()).isEqualTo("FAILED");
assertThat(messages.get(1).errorMessage()).isEqualTo("模型调用失败");
```

- [ ] **Step 2: 写失败测试，stopGeneration 停止本人 STREAMING 助手消息**

在 `ChatServiceImplTest` 增加测试：

```java
@Test
void shouldStopStreamingAssistantMessageOwnedByUser() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    AiModelService modelService = mock(AiModelService.class);
    ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
            new RecordingLlmClient(List.of(LlmStreamChunk.done())));
    ChatMessage assistant = message(200L, 100L, 7L, "ASSISTANT", "部分回答");
    assistant.setStatus("STREAMING");
    when(messageMapper.selectById(200L)).thenReturn(assistant);

    service.stopGeneration(7L, 200L);

    ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
    verify(messageMapper).updateById(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("STOPPED");
    assertThat(captor.getValue().getErrorMessage()).isEqualTo("用户已中止生成");
    assertThat(captor.getValue().getCompletedAt()).isNotNull();
}
```

- [ ] **Step 3: 写失败测试，stopGeneration 对终态消息幂等**

```java
@Test
void shouldTreatStoppingFinishedAssistantMessageAsIdempotent() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    AiModelService modelService = mock(AiModelService.class);
    ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
            new RecordingLlmClient(List.of(LlmStreamChunk.done())));
    ChatMessage assistant = message(200L, 100L, 7L, "ASSISTANT", "完整回答");
    assistant.setStatus("COMPLETED");
    when(messageMapper.selectById(200L)).thenReturn(assistant);

    service.stopGeneration(7L, 200L);

    verify(messageMapper, never()).updateById(any(ChatMessage.class));
}
```

- [ ] **Step 4: 写失败测试，stopGeneration 拒绝越权或非助手消息**

```java
@Test
void shouldRejectStoppingMessageNotOwnedByUser() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    AiModelService modelService = mock(AiModelService.class);
    ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
            new RecordingLlmClient(List.of(LlmStreamChunk.done())));
    ChatMessage assistant = message(200L, 100L, 9L, "ASSISTANT", "部分回答");
    assistant.setStatus("STREAMING");
    when(messageMapper.selectById(200L)).thenReturn(assistant);

    assertThatThrownBy(() -> service.stopGeneration(7L, 200L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("消息不存在")
            .extracting("errorCode")
            .isEqualTo(ErrorCode.NOT_FOUND);
}

@Test
void shouldRejectStoppingUserMessage() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    AiModelService modelService = mock(AiModelService.class);
    ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, new RecordingSseEventSender(),
            new RecordingLlmClient(List.of(LlmStreamChunk.done())));
    ChatMessage userMessage = message(201L, 100L, 7L, "USER", "问题");
    userMessage.setStatus("COMPLETED");
    when(messageMapper.selectById(201L)).thenReturn(userMessage);

    assertThatThrownBy(() -> service.stopGeneration(7L, 201L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("只能中止助手生成消息")
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
}
```

- [ ] **Step 5: 运行测试确认失败**

Run:

```powershell
cd backend
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: FAIL，原因是 `MessageItem.errorMessage()` 和 `ChatService.stopGeneration(...)` 不存在。

- [ ] **Step 6: 实现 MessageItem errorMessage**

修改 `MessageItem.java`：

```java
public record MessageItem(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        String reasoningContent,
        String status,
        String errorMessage,
        List<AttachmentItem> attachments,
        List<MessageProcessEvent> processEvents
) {
}
```

修改 `toMessageItem(...)`：

```java
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
```

- [ ] **Step 7: 实现 ChatService.stopGeneration**

在 `ChatService.java` 增加：

```java
/**
 * 中止当前用户自己的流式助手消息。
 */
void stopGeneration(Long userId, Long messageId);
```

在 `ChatServiceImpl.java` 增加：

```java
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
```

- [ ] **Step 8: 实现 ChatController 停止接口**

在 `ChatController.java` 增加：

```java
@PostMapping("/messages/{messageId}/stop")
public ApiResponse<Void> stopGeneration(
        Authentication authentication,
        @PathVariable Long messageId
) {
    LoginUser loginUser = (LoginUser) authentication.getPrincipal();
    chatService.stopGeneration(loginUser.userId(), messageId);
    return ApiResponse.success();
}
```

- [ ] **Step 9: 运行 Chat 测试**

Run:

```powershell
cd backend
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: PASS。

- [ ] **Step 10: 提交**

```powershell
git add backend/agent-hub-chat
git commit -m "补齐聊天停止生成状态接口"
```

## Task 4: 前端 SSE parser 支持 meta 和扩展 error

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/tests/chat-stream-tool-events.spec.ts`

- [ ] **Step 1: 写失败测试，openChatStream 分发 meta 和扩展 error**

在 `chat-stream-tool-events.spec.ts` 增加测试：

```ts
it('应该分发协议 meta 和扩展错误事件', async () => {
  const events: unknown[] = []
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    body: sseBody(
      'event: meta\ndata: {"protocol":"chat.sse.v1","conversationId":100,"messageId":200,"streamId":"chatmsg_200_20260622_000001","resumable":false}\n\n',
      'event: error\ndata: {"code":"LLM_STREAM_FAILED","message":"模型调用失败","messageId":200,"fatal":true,"recoverable":false,"source":"LLM","status":"FAILED"}\n\n',
      'event: done\ndata: {}\n\n'
    )
  }))

  await new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => reject(new Error('等待协议事件超时')), 1000)
    openChatStream(
      { prompt: '你好', token: 'token' },
      {
        onMeta: (data) => events.push({ type: 'meta', ...data }),
        onError: (data) => events.push({ type: 'error', ...data }),
        onDone: () => {
          window.clearTimeout(timeout)
          resolve()
        },
        onTransportError: reject
      }
    )
  })

  expect(events).toEqual([
    {
      type: 'meta',
      protocol: 'chat.sse.v1',
      conversationId: 100,
      messageId: 200,
      streamId: 'chatmsg_200_20260622_000001',
      resumable: false
    },
    {
      type: 'error',
      code: 'LLM_STREAM_FAILED',
      message: '模型调用失败',
      messageId: 200,
      fatal: true,
      recoverable: false,
      source: 'LLM',
      status: 'FAILED'
    }
  ])
  vi.unstubAllGlobals()
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd user-web
npm test -- chat-stream-tool-events.spec.ts
```

Expected: FAIL，原因是 `onMeta` 类型和 `meta` 分发不存在。

- [ ] **Step 3: 实现前端类型和分发**

修改 `ChatMessageRecord`：

```ts
export interface ChatMessageRecord {
  messageId: number
  conversationId: number
  role: 'USER' | 'ASSISTANT' | 'user' | 'assistant'
  content: string
  reasoningContent?: string | null
  status: string
  errorMessage?: string | null
  attachments?: ChatAttachmentRecord[]
  processEvents?: ChatProcessEventRecord[] | null
}
```

新增事件类型：

```ts
export interface ChatStreamMetaRecord {
  protocol: 'chat.sse.v1' | string
  conversationId: number
  messageId: number
  streamId: string
  resumable: boolean
}

export interface ChatStreamErrorRecord {
  code: string
  message: string
  messageId?: number | null
  fatal?: boolean
  recoverable?: boolean
  source?: string
  status?: string
}
```

修改 `ChatStreamHandlers`：

```ts
export interface ChatStreamHandlers {
  onMeta?: (data: ChatStreamMetaRecord) => void
  onConversation?: (data: { conversationId: number; title: string }) => void
  onMessage?: (data: { messageId: number; role: string }) => void
  onReasoningDelta?: (data: { content: string }) => void
  onAnswerDelta?: (data: { content: string }) => void
  onToolCall?: (data: { toolCallId: string; name: string }) => void
  onToolOutput?: (data: { toolCallId: string; stream: string; content: string }) => void
  onToolResult?: (data: { toolCallId: string; name: string; status: string; content: string }) => void
  onMessageCompleted?: (data: { messageId: number; status: string }) => void
  onError?: (data: ChatStreamErrorRecord) => void
  onDone?: () => void
  onTransportError?: (error: unknown) => void
}
```

修改 `dispatchSseEvent(...)`：

```ts
case 'meta':
  dispatchJson(event.data, handlers.onMeta)
  break
```

- [ ] **Step 4: 运行前端 parser 测试**

Run:

```powershell
cd user-web
npm test -- chat-stream-tool-events.spec.ts
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add user-web/src/api/chat.ts user-web/tests/chat-stream-tool-events.spec.ts
git commit -m "支持聊天 SSE 协议握手解析"
```

## Task 5: 前端停止生成和断线恢复语义

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/src/views/ChatView.vue`
- Modify: `user-web/tests/chat-stop-generation.spec.ts`

- [ ] **Step 1: 写失败测试，API 暴露 stopGeneration**

在 `chat-stop-generation.spec.ts` 增加源代码断言：

```ts
it('中止生成应调用服务端停止接口', () => {
  expect(normalizedChatViewSource).toContain('stopGeneration(currentAssistantMessageId)')
  expect(normalizedChatViewSource).toContain("await http.post(`/chat/messages/${messageId}/stop`)")
})
```

- [ ] **Step 2: 写失败测试，transport error 不直接标记失败**

修改现有流式错误处理测试，将 transport error 断言改为：

```ts
expect(normalizedChatViewSource.slice(onTransportErrorIndex))
  .not.toContain('markAssistantFailed(findAssistantMessage(currentAssistantMessageId))')
expect(normalizedChatViewSource.slice(onTransportErrorIndex))
  .toContain('void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)')
```

- [ ] **Step 3: 写失败测试，失败空消息优先展示 errorMessage**

将 `AssistantDisplayMessageLike` 测试补充：

```ts
expect(assistantDisplayContent({
  status: 'FAILED',
  content: '',
  errorMessage: '模型调用失败'
})).toBe('模型调用失败')
```

- [ ] **Step 4: 运行测试确认失败**

Run:

```powershell
cd user-web
npm test -- chat-stop-generation.spec.ts
```

Expected: FAIL，原因是 stop API、errorMessage 展示和 transport error 行为尚未调整。

- [ ] **Step 5: 实现 stopGeneration API**

在 `user-web/src/api/chat.ts` 增加：

```ts
export async function stopGeneration(messageId: number) {
  await http.post(`/chat/messages/${messageId}/stop`)
}
```

- [ ] **Step 6: 调整失败展示 helper**

在 `ChatView.vue` 中扩展接口和 helper：

```ts
export interface AssistantDisplayMessageLike {
  content?: string | null
  status?: string | null
  errorMessage?: string | null
}

export function assistantDisplayContent(messageItem: AssistantDisplayMessageLike) {
  if (messageItem.content) {
    return messageItem.content
  }

  if (messageItem.status === 'FAILED') {
    return messageItem.errorMessage || '生成失败'
  }

  return messageItem.status === 'STOPPED' ? '已中止生成' : '正在生成回复...'
}
```

确认 `ChatMessageItem` 本地类型包含：

```ts
errorMessage?: string | null
```

在历史消息映射处把 `record.errorMessage` 写入本地消息。

- [ ] **Step 7: 调整 stopCurrentStream**

从 `chat.ts` import：

```ts
import { stopGeneration } from '../api/chat'
```

将停止逻辑改成：

```ts
async function requestStopGeneration(messageId: number) {
  if (messageId <= 0) {
    return
  }
  try {
    await stopGeneration(messageId)
  } catch (error) {
    message.warning(error instanceof Error ? error.message : '中止生成状态同步失败')
  }
}

function stopCurrentStream() {
  const assistantMessage = findStoppableAssistantMessage(currentMessages.value)
  const messageId = assistantMessage?.id
  markCurrentAssistantStopped()
  if (typeof messageId === 'number') {
    void requestStopGeneration(messageId)
  }
  closeStream()
  void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
  void refreshActiveArtifacts()
  void loadConversations()
}
```

- [ ] **Step 8: 调整 onError 和 onTransportError**

`onError` 中保留失败标记，并写入错误原因：

```ts
onError: (data) => {
  const assistantMessage = findAssistantMessage(data.messageId ?? currentAssistantMessageId)
  markAssistantFailed(assistantMessage)
  if (assistantMessage) {
    assistantMessage.errorMessage = data.message ?? '流式响应失败'
  }
  message.error(data.message ?? '流式响应失败')
  closeStream()
  void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
  void refreshActiveArtifacts()
}
```

`onTransportError` 不再永久标记失败：

```ts
onTransportError: () => {
  closeStream()
  void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
  void refreshActiveArtifacts()
}
```

- [ ] **Step 9: 运行停止生成测试**

Run:

```powershell
cd user-web
npm test -- chat-stop-generation.spec.ts
```

Expected: PASS。

- [ ] **Step 10: 提交**

```powershell
git add user-web/src/api/chat.ts user-web/src/views/ChatView.vue user-web/tests/chat-stop-generation.spec.ts
git commit -m "调整聊天停止生成与断线恢复语义"
```

## Task 6: 端到端回归验证

**Files:**
- Verify only.

- [ ] **Step 1: 运行后端相关模块测试**

Run:

```powershell
cd backend
mvn -pl agent-hub-sse,agent-hub-chat -am test
```

Expected: PASS，`agent-hub-sse` 和 `agent-hub-chat` 相关测试全部通过。

- [ ] **Step 2: 运行前端单元测试**

Run:

```powershell
cd user-web
npm test -- chat-stream-tool-events.spec.ts chat-stop-generation.spec.ts
```

Expected: PASS。

- [ ] **Step 3: 运行用户端类型检查**

Run:

```powershell
cd user-web
npm run type-check
```

Expected: PASS。

- [ ] **Step 4: 检查协议文档和实现一致性**

Run:

```powershell
rg -n "chat.sse.v1|SseEventType.META|event: meta|stopGeneration|errorMessage|message_completed|done" backend user-web docs/superpowers/specs/2026-06-22-chat-sse-protocol-v1-design.md
```

Expected: 输出包含后端 meta 事件、前端 meta parser、停止生成接口、历史错误原因和规格文档。

- [ ] **Step 5: 查看变更边界**

Run:

```powershell
git status --short
git diff --stat
```

Expected: 只包含本计划列出的后端和前端文件。

- [ ] **Step 6: 提交最终验证记录**

```powershell
git add backend/agent-hub-sse backend/agent-hub-chat user-web/src/api/chat.ts user-web/src/views/ChatView.vue user-web/tests/chat-stream-tool-events.spec.ts user-web/tests/chat-stop-generation.spec.ts
git commit -m "完成聊天 SSE 协议 v1 落地"
```

如果前面已经按任务分多次提交，此步骤不再创建重复提交，只在最终状态仍有未提交实现文件时执行。

## Self-Review Notes

- 规格覆盖：本计划覆盖 `meta` 协议握手、扩展错误事件、停止生成接口、断线刷新恢复、消息状态机、历史 `errorMessage` 和前后端测试。
- 范围控制：v1 不新增事件持久化表，不实现 `afterSequence` 回放，不重构 Tool Loop 或沙箱产物协议。
- 类型一致性：后端错误字段为 `messageId/fatal/recoverable/source/status`；前端 `ChatStreamErrorRecord` 使用同名字段；历史消息 `errorMessage` 在 Java DTO 和 TypeScript record 中同名。
