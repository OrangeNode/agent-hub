# 聊天 SSE 协议 v1 设计

## 背景

当前用户端聊天已经通过 SSE 接收 `conversation`、`message`、`reasoning_delta`、`answer_delta`、`tool_call`、`tool_output`、`tool_result`、`message_completed`、`error` 和 `done` 等事件。后端会先创建会话和助手消息，再异步调用 LLM、Tool Registry、沙箱和 Skill 同步链路，最后把助手消息落成 `COMPLETED`、`FAILED` 或 `STOPPED`。

这条链路已经可用，但协议仍停留在代码约定层面。前后端目前主要靠事件名和字段默契协作，一旦后续增加移动端、多窗口、继续生成、消息重试、运行中任务取消或事件回放，容易出现以下问题：

- 后端调整事件字段后，前端不一定编译报错，但聊天过程可能静默损坏。
- 用户点击“中止生成”后，前端本地状态、服务端落库状态和刷新后的历史状态可能短暂不一致。
- 网络断开、浏览器关闭、SSE 发送失败和模型失败容易被混为同一种失败。
- `done` 容易被误解为生成成功，但实际它只代表 SSE 连接结束。
- 流内错误只有 `code/message`，前端难以判断是否可恢复、是否需要刷新历史消息、是否允许重试。

因此本设计将聊天流式协议固化为 `chat.sse.v1`，作为前后端稳定契约。

## 目标

- 定义聊天 SSE 事件协议版本、事件名、事件含义、发送顺序和兼容策略。
- 定义统一错误事件模型，明确普通 REST `ApiResponse` 与 SSE 流内错误的边界。
- 定义停止生成的客户端行为、服务端确认和最终状态。
- 定义断线、失败后的最小恢复策略。
- 固化助手消息状态机，避免 `STREAMING`、`COMPLETED`、`FAILED`、`STOPPED` 被混用。
- 给出后端、前端、接口、数据返回和测试的最小实现边界。

## 非目标

- v1 不实现完整事件持久化和按 `sequence` 回放 SSE 事件。
- v1 不要求恢复已经断开的 SSE 流本身，只恢复消息最终状态和已落库内容。
- v1 不强制立即取消底层 LLM HTTP 请求，但必须让用户主动停止后的消息最终不落成 `COMPLETED`。
- v1 不改变普通 REST 接口统一 `ApiResponse` 规范。
- v1 不重新设计 Tool Loop、Skill 同步、沙箱产物协议，只规范它们在聊天 SSE 中的事件边界。

## 协议定位

协议名固定为：

```text
chat.sse.v1
```

服务端通过 SSE 向前端发送事件。SSE 本身不使用普通 REST `ApiResponse` 包装；请求创建、停止生成、历史消息查询等普通 HTTP 接口继续使用 `ApiResponse`。

客户端必须遵守以下兼容规则：

- 识别 `protocol = chat.sse.v1`。
- 忽略未知事件。
- 忽略已知事件中的未知字段。
- 判断行为时优先使用结构化字段，例如 `code`、`status`、`recoverable`，不依赖中文文案匹配。
- 刷新后以服务端历史消息状态为准。

服务端必须遵守以下兼容规则：

- v1 内不删除已有事件名和已有字段。
- 可向事件中追加字段，但不能改变字段原有语义。
- 需要破坏兼容时新增协议版本，例如 `chat.sse.v2`。

## 事件分类

v1 事件分为四类。

生命周期事件：

```text
meta
conversation
message
message_completed
done
```

内容事件：

```text
reasoning_delta
answer_delta
```

过程事件：

```text
tool_call
tool_output
tool_result
```

异常事件：

```text
error
```

推荐发送顺序：

```text
meta
conversation
message
reasoning_delta / answer_delta / tool_call / tool_output / tool_result ...
message_completed | error
done
```

关键语义：

- `meta` 是协议握手事件，必须尽量作为第一条事件发送。
- `conversation` 表示本轮所属会话已经确定。
- `message` 表示服务端助手消息已经创建，后续事件默认归属该消息。
- `reasoning_delta` 和 `answer_delta` 只追加流式内容，不决定最终状态。
- `tool_call`、`tool_output`、`tool_result` 只描述工具过程，不决定助手消息最终状态。
- `message_completed` 表示助手消息成功完成。
- `error` 表示本轮助手消息失败。
- `done` 只表示 SSE 连接正常收尾，不代表生成成功。

## meta 事件

`meta` 用于协议握手和后续扩展。

示例：

```text
event: meta
data: {
  "protocol": "chat.sse.v1",
  "conversationId": 123,
  "messageId": 456,
  "streamId": "chatmsg_456_20260622_000001",
  "resumable": false
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `protocol` | string | 是 | 固定为 `chat.sse.v1`。 |
| `conversationId` | number | 是 | 会话 ID。 |
| `messageId` | number | 是 | 助手消息 ID。 |
| `streamId` | string | 是 | 本轮 SSE 流标识，用于日志和后续恢复扩展。 |
| `resumable` | boolean | 是 | v1 固定为 `false`，表示不支持从中间事件续接。 |

v1 最小实现中，`meta` 可以在 `conversation` 和 `message` 之后短期兼容发送，但目标顺序是第一条。前端在未收到 `meta` 时应继续兼容当前事件流，避免旧后端不可用。

## 业务事件

### conversation

表示本轮所属会话。

```json
{
  "conversationId": 123,
  "title": "新会话标题"
}
```

### message

表示服务端已创建助手消息。

```json
{
  "messageId": 456,
  "role": "ASSISTANT"
}
```

### reasoning_delta

表示推理内容增量。只有用户开启思考且模型支持推理内容时，服务端才应发送。

```json
{
  "content": "推理片段"
}
```

### answer_delta

表示回答正文增量。

```json
{
  "content": "回答片段"
}
```

### tool_call

表示模型请求调用工具。

```json
{
  "toolCallId": "call_1",
  "name": "sandbox.exec"
}
```

### tool_output

表示工具运行中的输出。

```json
{
  "toolCallId": "call_1",
  "stream": "stdout",
  "content": "运行输出"
}
```

`stream` 可取值：

```text
stdout
stderr
```

未知值前端按普通输出展示。

### tool_result

表示工具调用完成。

```json
{
  "toolCallId": "call_1",
  "name": "sandbox.exec",
  "status": "completed",
  "content": "工具结果"
}
```

`status` 使用工具运行时已有状态。前端不应把工具失败直接等同于助手消息失败，因为 Tool Loop 可能把工具失败结果喂回 LLM 继续处理。

### message_completed

表示助手消息成功完成。

```json
{
  "messageId": 456,
  "status": "COMPLETED"
}
```

收到该事件后，前端可将当前助手消息标记为 `COMPLETED`，但仍建议在 `done` 后刷新历史消息，以服务端落库内容为准。

### done

表示 SSE 连接结束。

```json
{}
```

`done` 不改变消息状态。前端收到 `done` 后应关闭本地流状态，并刷新当前会话消息、产物和会话列表。

## 错误事件

错误事件名固定为 `error`。

推荐结构：

```json
{
  "code": "LLM_STREAM_FAILED",
  "message": "模型调用失败",
  "messageId": 456,
  "fatal": true,
  "recoverable": false,
  "source": "LLM",
  "status": "FAILED"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | string | 是 | 机器可读错误码。 |
| `message` | string | 是 | 可展示的中文错误消息。 |
| `messageId` | number | 否 | 关联助手消息 ID。 |
| `fatal` | boolean | 是 | 是否导致本轮消息终止。v1 流内错误通常为 `true`。 |
| `recoverable` | boolean | 是 | 前端是否可提示用户重试或调整输入。 |
| `source` | string | 是 | 错误来源。 |
| `status` | string | 是 | 对应消息最终状态，通常为 `FAILED`。 |

错误码建议：

| code | source | recoverable | 说明 |
| --- | --- | --- | --- |
| `LLM_STREAM_FAILED` | `LLM` | false | 模型流失败或模型返回错误。 |
| `TOOL_LOOP_FAILED` | `TOOL` | false | 工具循环出现不可恢复失败。 |
| `CONTEXT_LENGTH_EXCEEDED` | `LLM` | true | 上下文超限，可提示缩短输入或切换模型。 |
| `CLIENT_DISCONNECTED` | `SSE` | true | 服务端检测到客户端断开，通常只落库，不一定能发给客户端。 |
| `GENERATION_STOPPED` | `CLIENT` | true | 用户主动停止。主动停止不要求发送 `error`。 |
| `PROTOCOL_UNSUPPORTED` | `SERVER` | false | 协议版本不兼容。 |

v1 首轮实现可兼容现有 `Error(code, message)` 结构，但后续应补齐 `messageId`、`fatal`、`recoverable`、`source` 和 `status`。

## 停止生成

停止生成按两阶段设计。

第一阶段：客户端请求停止。

- 用户点击“中止生成”。
- 前端立即中止当前 SSE 读取。
- 前端将当前助手消息临时标记为 `STOPPING` 或 `STOPPED`，提供即时反馈。
- 前端调用停止接口通知服务端落库。

第二阶段：服务端确认停止。

- 服务端校验消息归属。
- 如果助手消息仍为 `STREAMING`，更新为 `STOPPED`。
- 保存已生成正文、推理内容和过程事件。
- `errorMessage` 写入“用户已中止生成”。
- 如果消息已经是终态，停止接口幂等返回成功。

建议新增接口：

```text
POST /api/chat/messages/{messageId}/stop
```

响应：

```json
{
  "code": "SUCCESS",
  "message": "成功",
  "data": null
}
```

接口规则：

- 只能停止当前用户自己的助手消息。
- 只能从 `STREAMING` 转为 `STOPPED`。
- `COMPLETED`、`FAILED`、`STOPPED` 幂等返回成功，不再改变状态。
- 找不到消息或归属不正确时返回统一业务异常。
- v1 不强制真正取消底层 LLM HTTP 请求，但服务端必须避免已停止消息后续被覆盖成 `COMPLETED`。

前端停止行为：

- 如果已经拿到真实 `messageId`，立即调用停止接口并中止 SSE。
- 如果还未拿到真实 `messageId`，先中止 SSE，随后刷新会话消息。
- 刷新后以服务端消息状态为准。

停止生成与失败的边界：

- 用户主动停止使用 `STOPPED`，不使用 `FAILED`。
- 网络断开导致服务端无法继续交付，也使用 `STOPPED`，`errorMessage` 写“SSE 连接已断开”。
- 模型、工具、服务端异常使用 `FAILED`。

## 断线和失败恢复

v1 不恢复 SSE 流本身，而恢复消息状态和已落库内容。

前端处理规则：

- 收到 `error`：将当前助手消息标记为 `FAILED`，展示 `message`，关闭流并刷新当前会话消息。
- 收到 `done`：关闭流，刷新当前会话消息、文件产物和会话列表。
- 发生 `transport error`：不要永久判定为 `FAILED`，先显示“连接已断开，正在同步状态”，然后刷新当前会话消息。
- 刷新后以服务端 `status` 为准。
- 如果刷新后仍为 `STREAMING`，显示“生成状态确认中”，短窗口轮询 1 到 2 次。
- 超过短窗口仍为 `STREAMING` 时，提示用户刷新或中止生成。

服务端处理规则：

- 正常完成：落库 `COMPLETED`，发送 `message_completed` 和 `done`。
- 模型失败：落库 `FAILED`，发送 `error` 和 `done`。
- 工具循环不可恢复失败：落库 `FAILED`，发送 `error` 和 `done`。
- 用户停止：落库 `STOPPED`，不要求发送 `error`。
- SSE 发送失败：落库 `STOPPED`，保存已生成内容和过程事件。

历史消息接口需要返回错误原因。当前 `MessageItem` 未返回 `errorMessage`，v1 应补充：

```json
{
  "messageId": 456,
  "status": "FAILED",
  "errorMessage": "模型调用失败"
}
```

否则前端刷新后只能知道失败，无法展示原因。

## 消息状态机

消息状态固定为：

```text
STREAMING
COMPLETED
FAILED
STOPPED
```

合法迁移：

```text
STREAMING -> COMPLETED
STREAMING -> FAILED
STREAMING -> STOPPED
```

终态：

```text
COMPLETED
FAILED
STOPPED
```

规则：

- `USER` 消息创建后直接为 `COMPLETED`。
- `ASSISTANT` 消息创建后为 `STREAMING`。
- `COMPLETED` 表示模型回答正常结束。
- `FAILED` 表示系统失败，必须尽量写入 `errorMessage`。
- `STOPPED` 表示用户主动停止，或连接断开导致服务端无法继续向用户交付。
- 终态之间不互相转换。
- 前端可使用临时 UI 状态 `STOPPING`、`RECONNECTING`，但不写入数据库。

状态与事件关系：

| 事件或动作 | 消息状态影响 |
| --- | --- |
| `message` | 前端绑定助手消息，服务端状态仍为 `STREAMING`。 |
| `reasoning_delta` | 不改变状态。 |
| `answer_delta` | 不改变状态。 |
| `tool_call` / `tool_output` / `tool_result` | 不改变状态。 |
| `message_completed` | 转为 `COMPLETED`。 |
| `error` | 转为 `FAILED`。 |
| 停止接口成功 | 转为 `STOPPED`。 |
| SSE 发送失败 | 转为 `STOPPED`。 |
| `done` | 不改变状态，只关闭流。 |

最重要的约束是：`done` 不是成功事件。前端判断成功必须看 `message_completed` 或历史消息状态。

## 后端改造边界

### agent-hub-sse

- 新增 `SseEventType.META`。
- 新增 `SseEventData.Meta`。
- 扩展 `SseEventData.Error` 字段，同时保留兼容构造或工厂方法。
- `SseEventSender` 继续只负责发送，不承载聊天业务规则。

### agent-hub-chat

- `ChatServiceImpl.stream` 在准备好会话和助手消息后发送 `meta`。
- 生成 `streamId`，建议由 `messageId`、日期和短随机串组成。
- 失败时发送扩展后的 `error` 事件。
- 停止生成接口落在 `ChatController`，业务逻辑落在 `ChatService`。
- 停止逻辑必须校验用户归属和消息角色。
- `MessageItem` 增加 `errorMessage` 字段。
- 当前 `send` 失败后标记 `STOPPED` 的行为保留。

### 数据库

v1 不要求新增表。现有 `chat_message.error_message` 可支撑失败和停止原因。

后续如果要支持事件回放，可新增 `chat_stream_event`：

```text
id
stream_id
conversation_id
message_id
sequence
event_name
event_data
created_at
```

该表不属于 v1 必做范围。

## 前端改造边界

- `openChatStream` 新增 `onMeta` handler。
- SSE 分发兼容 `meta` 事件。
- 错误 handler 支持扩展字段。
- 点击中止生成时，优先调用 `POST /api/chat/messages/{messageId}/stop`，然后中止 SSE。
- `onTransportError` 不直接永久标记 `FAILED`，而是刷新历史消息后采用服务端状态。
- `ChatMessageRecord` 增加 `errorMessage`。
- 历史消息渲染失败状态时优先展示 `errorMessage`。
- 保留当前 `toolCallId` 内部关联逻辑，不在 UI 暴露协议 ID。

## 版本演进预留

v1 预留但不强制实现以下字段：

```json
{
  "protocol": "chat.sse.v1",
  "streamId": "chatmsg_456_20260622_000001",
  "sequence": 12,
  "messageId": 456
}
```

后续可升级为：

```text
GET /api/chat/messages/{messageId}/events?afterSequence=12
```

升级到事件回放后，`resumable` 可改为 `true`。在此之前，前端必须把断线恢复理解为“刷新消息状态”，不是“续接流”。

## 测试策略

后端测试：

- `SseEventType` 包含 `meta`。
- `meta` 事件字段包含 `protocol`、`conversationId`、`messageId`、`streamId`、`resumable`。
- 正常流事件顺序包含 `meta`、`conversation`、`message`、`answer_delta`、`message_completed`、`done`。
- LLM 错误时消息落库 `FAILED`，`error` 事件包含结构化字段，最后发送 `done`。
- SSE 发送失败时助手消息落库 `STOPPED`。
- 停止接口只能停止本人助手消息。
- 停止接口对终态消息幂等。
- 历史消息接口返回 `errorMessage`。

前端测试：

- `openChatStream` 能分发 `meta`。
- `done` 不会把消息标记为成功。
- 收到 `message_completed` 才标记 `COMPLETED`。
- 收到 `error` 标记 `FAILED` 并刷新历史消息。
- `transport error` 触发刷新，不直接永久失败。
- 生成中点击发送按钮调用停止接口并中止 SSE。
- 历史失败消息展示 `errorMessage`。

回归测试：

- 现有 `tool_call`、`tool_output`、`tool_result` 分发不退化。
- 现有思考过程折叠、工具过程展示、文件产物刷新不退化。
- 现有 SSE 无 `meta` 时，前端仍能兼容旧事件流。

## 实施顺序建议

1. 在 `agent-hub-sse` 增加 `meta` 和扩展错误事件。
2. 在 `agent-hub-chat` 发送 `meta`，补充扩展错误事件。
3. 在历史消息 DTO 中返回 `errorMessage`。
4. 增加停止生成 REST 接口和服务端状态迁移。
5. 调整前端 SSE parser 和 ChatView 状态处理。
6. 补充后端、前端测试。

## 验收标准

- 聊天 SSE 首轮事件能识别为 `chat.sse.v1`。
- 正常生成只在 `message_completed` 后进入 `COMPLETED`。
- 模型失败会落库 `FAILED`，前端能展示失败原因。
- 用户点击中止后，刷新历史消息仍显示 `STOPPED`。
- 网络断开后，前端刷新历史消息并以服务端状态为准。
- `done` 不再被任何代码当成成功信号。
- 工具过程事件展示不退化。
- 新协议字段向后兼容，旧事件流不会让前端崩溃。
