# AI 供应商、模型与基础聊天闭环设计

## 目标

本阶段目标是先打通模型供应能力和用户端基础聊天闭环，不实现完整 Agent Runtime、MCP、Skill 和 Tool Loop。

交付范围包括：

- 管理端维护 AI 供应商。
- 管理端维护 AI 模型。
- 后端兼容 OpenAI 协议和 Anthropic 协议。
- 用户端加载后端可用模型。
- 用户端发起流式聊天。
- 聊天会话和消息必须落库。
- 新会话标题由用户第一次消息总结生成，不超过 15 个中文字符。
- SSE 能力独立成后端模块，统一处理流式事件。
- 支持基础思考内容和回答内容的流式输出。

## 非目标

- 不实现完整 Agent Runtime。
- 不实现 MCP、Skill 管理和 Tool Loop。
- 不实现文件解析、知识库检索和多模态上传处理。
- 不实现复杂模型调度、费用统计和限流策略。
- 不把 SSE 流式事件纳入普通 REST `ApiResponse` 包装。

## 后端模块

当前后端已有模块：

- `agent-hub-common`
- `agent-hub-security`
- `agent-hub-system`
- `agent-hub-server`

本阶段新增模块：

- `agent-hub-ai`
- `agent-hub-chat`
- `agent-hub-sse`

### agent-hub-ai

负责 AI 供应商、模型和 LLM 协议适配。

主要职责：

- 供应商管理。
- 模型管理。
- 用户端可用模型查询。
- OpenAI 协议客户端。
- Anthropic 协议客户端。
- LLM Client 工厂。
- 将不同协议的流式响应统一转换为内部 `LlmStreamChunk`。

### agent-hub-chat

负责会话、消息和聊天编排。

主要职责：

- 会话创建、查询、删除。
- 消息保存和查询。
- 新会话标题生成。
- 根据用户请求选择模型。
- 调用 `agent-hub-ai` 发起模型流式请求。
- 调用 `agent-hub-sse` 推送流式事件。
- 在流结束或失败时更新助手消息状态。

### agent-hub-sse

负责统一 SSE 事件协议和发送能力。

主要职责：

- SSE 事件类型定义。
- SSE 事件数据 DTO。
- SSE 发送器。
- 流内错误事件。
- 完成事件。

该模块不直接访问数据库，不直接调用 LLM，也不承载聊天业务规则。

### agent-hub-server

负责应用启动和基础装配。

主要职责：

- Spring Boot 启动。
- Spring Security 装配。
- 全局异常处理。
- Flyway 迁移。
- 装配 `ai`、`chat`、`sse` 模块。

## 数据库设计

新增迁移建议命名为 `V2__init_ai_chat.sql`。

### ai_provider

AI 供应商表。

字段：

- `id`：主键。
- `code`：供应商唯一编码，例如 `openai`、`anthropic`、`deepseek`。
- `name`：供应商展示名称。
- `provider_type`：供应商类型，枚举值为 `OPENAI`、`ANTHROPIC`、`DEEPSEEK`、`QWEN`、`CUSTOM`。
- `protocol_type`：协议类型，枚举值为 `OPENAI_CHAT_COMPLETIONS`、`OPENAI_RESPONSES`、`ANTHROPIC_MESSAGES`、`OPENAI_COMPATIBLE`、`CUSTOM`。
- `base_url`：API 基础地址。
- `api_key_cipher`：API Key 密文或加密后内容。
- `auth_type`：鉴权方式，枚举值为 `BEARER`、`X_API_KEY`、`ANTHROPIC_API_KEY`、`CUSTOM_HEADER`。
- `api_version`：协议版本，例如 Anthropic 的 `2023-06-01`。
- `extra_headers`：JSON 字符串，额外请求头。
- `extra_body`：JSON 字符串，额外请求参数。
- `enabled`：是否启用。
- `sort`：排序。
- `created_by`、`created_at`、`updated_by`、`updated_at`：审计字段。

约束：

- `code` 唯一。
- 删除供应商前必须校验是否存在关联模型。
- 列表和详情接口不返回 API Key 明文，只返回 `hasApiKey`。

### ai_model

AI 模型表。

字段：

- `id`：主键。
- `provider_id`：所属供应商。
- `model_code`：实际调用模型名，例如 `gpt-4o`、`claude-3-5-sonnet-latest`。
- `display_name`：展示名称。
- `capability_type`：能力类型，枚举值为 `CHAT`、`REASONING`、`MULTIMODAL`。
- `stream_supported`：是否支持流式输出。
- `reasoning_supported`：是否支持思考内容。
- `context_window`：上下文长度。
- `max_output_tokens`：最大输出 token。
- `default_model`：是否默认模型。
- `enabled`：是否启用。
- `sort`：排序。
- `created_by`、`created_at`、`updated_by`、`updated_at`：审计字段。

约束：

- 同一供应商下 `model_code` 唯一。
- 同一时间最多一个启用的默认模型。更新默认模型时，应取消其他模型的默认标记。
- 删除模型前必须校验是否存在聊天会话或消息引用。

### chat_conversation

聊天会话表。

字段：

- `id`：主键。
- `user_id`：用户 ID。
- `title`：会话标题，不超过 15 个中文字符。
- `agent_key`：预留 Agent 标识，本阶段可为空或通用 Agent。
- `model_id`：当前会话默认模型。
- `provider_id`：当前会话默认供应商。
- `status`：会话状态，枚举值为 `ACTIVE`、`DELETED`。
- `created_at`、`updated_at`：时间字段。

约束：

- 用户只能查询和删除自己的会话。
- 删除会话采用逻辑删除，将 `status` 更新为 `DELETED`。

### chat_message

聊天消息表。

字段：

- `id`：主键。
- `conversation_id`：会话 ID。
- `user_id`：用户 ID。
- `role`：消息角色，枚举值为 `USER`、`ASSISTANT`、`SYSTEM`。
- `content`：回答正文或用户消息正文。
- `reasoning_content`：思考内容。
- `provider_id`：实际使用供应商。
- `model_id`：实际使用模型。
- `status`：消息状态，枚举值为 `STREAMING`、`COMPLETED`、`FAILED`。
- `error_message`：错误信息。
- `created_at`：创建时间。
- `completed_at`：完成时间。

约束：

- 消息查询必须按当前登录用户和会话归属校验。
- 流式过程中助手消息先以 `STREAMING` 状态入库，流结束后更新为 `COMPLETED`，异常时更新为 `FAILED`。

## 管理端接口

所有普通 REST 接口继续使用统一 `ApiResponse`。

### 供应商管理

接口：

```text
GET    /api/ai/providers
POST   /api/ai/providers
PUT    /api/ai/providers/{providerId}
DELETE /api/ai/providers/{providerId}
```

创建请求：

```json
{
  "code": "anthropic",
  "name": "Anthropic",
  "providerType": "ANTHROPIC",
  "protocolType": "ANTHROPIC_MESSAGES",
  "baseUrl": "https://api.anthropic.com",
  "apiKey": "sk-xxx",
  "authType": "ANTHROPIC_API_KEY",
  "apiVersion": "2023-06-01",
  "extraHeaders": {},
  "extraBody": {},
  "enabled": true,
  "sort": 1
}
```

列表返回：

```json
{
  "providerId": 1,
  "code": "anthropic",
  "name": "Anthropic",
  "providerType": "ANTHROPIC",
  "protocolType": "ANTHROPIC_MESSAGES",
  "baseUrl": "https://api.anthropic.com",
  "authType": "ANTHROPIC_API_KEY",
  "apiVersion": "2023-06-01",
  "hasApiKey": true,
  "enabled": true,
  "sort": 1
}
```

更新规则：

- `apiKey` 为空表示保留原 API Key。
- `apiKey` 有值表示覆盖保存。
- 禁用供应商后，其下模型不在用户端可用模型接口中返回。

### 模型管理

接口：

```text
GET    /api/ai/models
POST   /api/ai/models
PUT    /api/ai/models/{modelId}
DELETE /api/ai/models/{modelId}
GET    /api/ai/models/available
```

创建请求：

```json
{
  "providerId": 1,
  "modelCode": "claude-3-5-sonnet-latest",
  "displayName": "Claude 3.5 Sonnet",
  "capabilityType": "CHAT",
  "streamSupported": true,
  "reasoningSupported": false,
  "contextWindow": 200000,
  "maxOutputTokens": 4096,
  "defaultModel": true,
  "enabled": true,
  "sort": 1
}
```

列表返回：

```json
{
  "modelId": 1,
  "providerId": 1,
  "providerCode": "anthropic",
  "providerName": "Anthropic",
  "modelCode": "claude-3-5-sonnet-latest",
  "displayName": "Claude 3.5 Sonnet",
  "capabilityType": "CHAT",
  "streamSupported": true,
  "reasoningSupported": false,
  "contextWindow": 200000,
  "maxOutputTokens": 4096,
  "defaultModel": true,
  "enabled": true,
  "sort": 1
}
```

`/api/ai/models/available` 只返回启用供应商下的启用模型，供用户端模型下拉使用。

## 用户端聊天接口

### 流式聊天

接口：

```text
GET /api/chat/stream
```

查询参数：

- `conversationId`：可选，为空表示新建会话。
- `modelId`：可选，为空使用默认模型。
- `agentKey`：可选，本阶段只记录和预留。
- `prompt`：必填，用户输入。
- `token`：临时兼容 EventSource 鉴权。

鉴权说明：

- 由于浏览器原生 `EventSource` 不方便设置 `Authorization` 头，本阶段临时允许 `token` 查询参数。
- 后续可以切换为 `fetch` 读取流、cookie 鉴权或前端 SSE polyfill，以移除 URL token。

### 会话接口

接口：

```text
GET    /api/chat/conversations
GET    /api/chat/conversations/{conversationId}/messages
DELETE /api/chat/conversations/{conversationId}
```

后续可按需增加：

```text
PUT /api/chat/conversations/{conversationId}
```

用于手动重命名会话，本阶段不实现。

## SSE 事件协议

SSE 事件不使用普通 `ApiResponse` 包装。

事件类型：

- `conversation`：新会话创建或会话信息确认。
- `message`：助手消息创建。
- `reasoning_delta`：思考内容增量。
- `answer_delta`：回答内容增量。
- `message_completed`：助手消息完成。
- `error`：流内错误。
- `done`：整个流结束。

示例：

```text
event: conversation
data: {"conversationId":1,"title":"模型管理设计"}

event: message
data: {"messageId":20,"role":"assistant"}

event: reasoning_delta
data: {"content":"正在分析用户想要的模块边界..."}

event: answer_delta
data: {"content":"本阶段建议先实现供应商管理、模型管理..."}

event: message_completed
data: {"messageId":20,"status":"COMPLETED"}

event: done
data: {}
```

错误事件：

```text
event: error
data: {"code":"LLM_STREAM_FAILED","message":"模型响应失败，请稍后重试"}
```

## OpenAI 和 Anthropic 协议兼容

`agent-hub-ai` 提供统一抽象：

- `LlmClient`
- `LlmClientFactory`
- `LlmStreamRequest`
- `LlmStreamChunk`
- `LlmMessage`

客户端实现：

- `OpenAiChatCompletionsClient`
- `OpenAiResponsesClient`
- `OpenAiCompatibleClient`
- `AnthropicMessagesClient`

其中 `OpenAiResponsesClient` 本阶段可以先预留接口和枚举，不阻塞基础对话闭环。

统一流式片段类型：

- `REASONING_DELTA`
- `ANSWER_DELTA`
- `USAGE`
- `DONE`
- `ERROR`

协议映射：

- OpenAI Chat Completions：读取 `choices[].delta.content`，兼容 `reasoning_content` 扩展字段。
- OpenAI Compatible：沿用 Chat Completions 格式，兼容 DeepSeek、Qwen、代理网关。
- Anthropic Messages：读取 `content_block_delta`，将 `text_delta` 映射为 `ANSWER_DELTA`，将 `thinking_delta` 或兼容字段映射为 `REASONING_DELTA`。

## 会话标题规则

新会话创建时：

1. 保存用户第一条消息。
2. 调用标题生成逻辑。
3. 标题必须不超过 15 个中文字符。
4. 如果模型总结失败、返回空、返回超长或发生异常，则使用用户消息前 15 个可见字符作为兜底标题。
5. 标题保存到 `chat_conversation.title`。
6. 标题通过 SSE `conversation` 事件返回给前端。

标题生成可以先使用默认模型。后续如果需要更稳定的成本和速度，可以增加“标题模型”配置。

## 管理端页面

`admin-web` 新增菜单：

- AI 管理
  - 供应商管理
  - 模型管理

新增路由：

- `/ai/providers`
- `/ai/models`

建议权限：

- `ai:provider:list`
- `ai:provider:create`
- `ai:provider:update`
- `ai:provider:delete`
- `ai:model:list`
- `ai:model:create`
- `ai:model:update`
- `ai:model:delete`

页面沿用现有系统管理页模式：

- 查询区。
- 表格区。
- 新增和编辑弹窗。
- 删除确认。
- 启用状态开关。

## 用户端对接

`user-web` 调整：

- 模型下拉请求 `/api/ai/models/available`，不再使用硬编码模型列表。
- 发送消息时传递 `modelId`、`conversationId`、`prompt`。
- 新会话第一次发送时不传 `conversationId`。
- 收到 `conversation` 事件后保存真实 `conversationId` 和标题。
- 收到 `message` 事件后记录助手消息 ID。
- 收到 `reasoning_delta` 后更新思考内容。
- 收到 `answer_delta` 后更新回答正文。
- 收到 `message_completed` 后更新消息状态。
- 收到 `error` 后展示流内错误，并保留已生成内容。
- 最近会话请求后端会话列表。
- 切换会话时请求后端消息列表。

## 错误处理

普通 REST 接口继续使用统一响应和全局异常处理。

流式聊天错误通过 SSE `error` 事件返回。常见错误码包括：

- `MODEL_NOT_FOUND`：模型不存在。
- `MODEL_DISABLED`：模型不可用。
- `PROVIDER_DISABLED`：供应商不可用。
- `LLM_STREAM_FAILED`：模型流式响应失败。
- `CONVERSATION_NOT_FOUND`：会话不存在或无权访问。
- `CHAT_MESSAGE_SAVE_FAILED`：消息保存失败。

当流式过程中发生错误时：

- 助手消息状态更新为 `FAILED`。
- 已收到的思考内容和回答内容保留入库。
- 向前端发送 `error` 事件。
- 最后发送 `done` 事件结束连接。

## 验证标准

后端：

- Maven 多模块结构包含 `ai`、`chat`、`sse`。
- Flyway 能创建供应商、模型、会话和消息表。
- 供应商 CRUD 可用。
- 模型 CRUD 可用。
- 用户端可用模型接口只返回启用供应商下的启用模型。
- OpenAI Compatible 客户端可完成流式回答。
- Anthropic Messages 客户端可完成流式回答。
- 聊天接口能创建会话、生成标题、保存用户消息、保存助手消息。
- SSE 能输出思考增量、回答增量、完成和错误事件。

管理端：

- 能进入供应商管理页面。
- 能新增、编辑、删除、启停供应商。
- 能进入模型管理页面。
- 能新增、编辑、删除、启停模型。
- 能设置默认模型。

用户端：

- 模型下拉来自后端。
- 能创建新会话。
- 会话标题不超过 15 字。
- 能流式展示思考和回答。
- 会话列表来自后端。
- 切换会话后能加载历史消息。

