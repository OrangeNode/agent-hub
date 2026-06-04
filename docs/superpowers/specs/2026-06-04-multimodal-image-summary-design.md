# 图片多模态与会话摘要设计

## 目标

本阶段目标是在现有纯文本聊天链路上增加图片多模态能力，并建立贴近生产环境的会话上下文预算与摘要机制。

交付范围：

- 用户端支持上传图片并随消息发送给支持图片输入的模型。
- 图片通过 MinIO 存储，前端使用后端签发的预签名 URL 直传。
- 图片只保留 7 天，业务层和 MinIO lifecycle 双重控制过期。
- 同一会话支持默认复用最近未过期图片，也支持用户手动选择历史未过期图片。
- 模型管理保留现有 `capabilityType`，新增“支持图片输入”开关。
- 会话上下文达到模型上下文长度 80% 时自动更新会话摘要。
- 第一版上下文管理使用 token 预算、输出预留、安全余量和图片 token 估算，不使用简单字符数粗略裁剪。

## 非目标

- 不支持文档类附件解析，文档类能力后续接入 sandbox runtime 后再设计。
- 不实现音频、视频、GIF 抽帧、转码或转写。
- 不实现跨会话长期记忆。
- 不实现向量检索、知识库、RAG 或自动长期记忆。
- 不把图片对象设置为公开读，所有读写都通过短期预签名 URL。

## 方案取舍

### 方案一：最小图片多模态

只实现图片上传、存储、消息关联和视觉模型调用。该方案最快，但长会话仍然会被上下文长度限制卡住，不适合复杂任务。

### 方案二：图片多模态 + 会话摘要

实现图片上传、存储、历史图片复用，并在上下文达到 80% 时自动摘要旧消息。该方案覆盖本阶段核心需求，范围可控，也能为后续 Agent Runtime、工具调用和 sandbox runtime 留出上下文管理底座。

### 方案三：图片多模态 + 会话摘要 + 长期记忆

额外实现跨会话长期记忆、记忆召回、管理和删除。该方案更完整，但需要额外处理权限、去重、隐私、人工确认和记忆 CRUD，本阶段暂不采用。

推荐采用方案二。

## 后端模块

新增 `agent-hub-storage` 模块，负责：

- MinIO/S3 兼容对象存储配置。
- 对象存储客户端。
- 预签名上传 URL。
- 预签名读取 URL。
- 附件记录管理。
- 附件状态校验。
- 临时附件清理任务。
- bucket lifecycle 初始化所需配置说明。

`agent-hub-chat` 只通过 `agent-hub-storage` 的服务接口使用附件能力，不直接操作 MinIO。

`agent-hub-ai` 负责把文本和图片引用转换为不同供应商协议的多模态请求格式。

## 数据库设计

### chat_attachment

聊天附件表，第一版只存图片。

```text
id                   BIGINT PRIMARY KEY
user_id              BIGINT NOT NULL
conversation_id      BIGINT
message_id           BIGINT
object_key           VARCHAR(512) NOT NULL
bucket_name          VARCHAR(128) NOT NULL
original_filename    VARCHAR(255) NOT NULL
content_type         VARCHAR(128) NOT NULL
file_size            BIGINT NOT NULL
file_ext             VARCHAR(16) NOT NULL
attachment_type      VARCHAR(32) NOT NULL
status               VARCHAR(32) NOT NULL
expires_at           TIMESTAMPTZ NOT NULL
created_at           TIMESTAMPTZ NOT NULL
updated_at           TIMESTAMPTZ NOT NULL
confirmed_at         TIMESTAMPTZ
deleted_at           TIMESTAMPTZ
```

字段规则：

- `attachment_type` 第一版固定为 `IMAGE`。
- `status` 可取 `PENDING`、`READY`、`EXPIRED`、`DELETED`。
- `expires_at` 默认为 `created_at + 7 天`。
- `conversation_id` 和 `message_id` 在图片真正随消息发送后绑定。
- 未绑定 `message_id` 的附件视为临时附件。

状态语义：

- `PENDING`：后端已创建附件记录并签发上传 URL，前端尚未确认上传完成。
- `READY`：MinIO 对象存在，允许发送聊天或被历史图片复用。
- `EXPIRED`：超过 7 天自然过期，不允许访问，提示 `图片已过期`。
- `DELETED`：临时附件清理或用户主动移除后进入该状态。

索引建议：

```text
idx_chat_attachment_user_status_created_at(user_id, status, created_at)
idx_chat_attachment_conversation_message(conversation_id, message_id)
idx_chat_attachment_expires_at(expires_at)
idx_chat_attachment_status_created_at(status, created_at)
```

### chat_conversation_summary

会话摘要表，只服务当前会话，不跨会话召回。

```text
id                       BIGINT PRIMARY KEY
conversation_id          BIGINT NOT NULL
user_id                  BIGINT NOT NULL
summary                  TEXT NOT NULL
summarized_message_id    BIGINT
token_estimate           INT
created_at               TIMESTAMPTZ NOT NULL
updated_at               TIMESTAMPTZ NOT NULL
```

字段规则：

- `summary` 保存当前会话已压缩的历史上下文。
- `summarized_message_id` 表示摘要已经覆盖到哪条消息。
- `token_estimate` 保存摘要本身的估算 token，便于后续预算计算。

索引建议：

```text
uk_chat_conversation_summary_conversation(conversation_id)
idx_chat_conversation_summary_user(user_id)
```

### ai_model 调整

保留现有 `capability_type`，新增：

```text
image_input_supported BOOLEAN NOT NULL DEFAULT FALSE
image_token_estimate INT
```

规则：

- `image_input_supported` 表示模型是否支持图片输入。
- `image_token_estimate` 表示单张图片的默认 token 预算；为空时使用系统默认值。
- 管理端模型新增/编辑页增加“支持图片输入”开关。

## 上传链路

### 申请预签名上传 URL

```text
POST /api/chat/attachments/image-upload-url
```

请求：

```json
{
  "filename": "cat.png",
  "contentType": "image/png",
  "fileSize": 1234567
}
```

后端处理：

- 校验登录用户。
- 校验扩展名和 MIME 类型。
- 校验单张图片不超过 `20MB`。
- 生成不可猜测的 `object_key`。
- 创建 `PENDING` 附件记录。
- 返回 MinIO 预签名 PUT URL。

返回：

```json
{
  "attachmentId": 1,
  "uploadUrl": "http://...",
  "method": "PUT",
  "headers": {
    "Content-Type": "image/png"
  },
  "expiresAt": "2026-06-11T10:00:00+08:00"
}
```

### 前端直传 MinIO

前端使用返回的 `uploadUrl` 直接 PUT 图片到 MinIO。上传中展示进度，上传未完成时发送按钮置灰。

### 确认上传完成

```text
POST /api/chat/attachments/{attachmentId}/confirm
```

后端处理：

- 校验附件属于当前用户。
- 校验状态为 `PENDING`。
- 查询 MinIO 对象是否存在。
- 校验对象大小和 content type 与申请记录一致。
- 更新状态为 `READY`，写入 `confirmed_at`。

## 图片限制

- 支持类型：`jpg`、`jpeg`、`png`、`webp`、`gif`。
- 单张最大：`20MB`。
- 单条消息最多：`8 张`。
- GIF 第一版只作为图片传给模型，不抽帧、不转码、不做视频理解。

错误提示：

```text
当前模型不支持图片输入
图片不能超过 20MB
单条消息最多上传 8 张图片
仅支持 jpg、jpeg、png、webp、gif 图片
图片上传失败，请重试
图片仍在上传中
图片已过期
当前输入内容过长，已超过模型上下文限制，请缩短后重试
```

## 聊天接口调整

```text
POST /api/chat/stream
```

请求新增：

```json
{
  "prompt": "分析这张图",
  "conversationId": 1,
  "modelId": 2,
  "agentKey": "default",
  "thinkingEnabled": true,
  "attachmentIds": [1, 2],
  "reuseAttachmentIds": [8]
}
```

字段规则：

- `attachmentIds` 表示本次新上传并随消息发送的图片。
- `reuseAttachmentIds` 表示用户手动选择复用的历史图片。
- 如果两者都为空，后端自动查找同会话最近一轮未过期图片作为上下文图片。
- 最终图片数量不得超过 8 张。

后端校验：

- 附件必须属于当前用户。
- 附件必须为 `READY`。
- 附件不能过期。
- 新附件可以尚未绑定会话，但发送成功后必须绑定当前会话和用户消息。
- 复用附件必须属于当前会话。
- 当前模型 `image_input_supported=false` 时，如果请求包含图片，返回 `当前模型不支持图片输入`。

发送成功后：

- 用户消息入库。
- 本次新附件绑定到 `conversation_id` 和用户消息 `message_id`。
- 助手消息按现有流式逻辑入库和更新状态。

## 历史图片访问与复用

历史消息接口返回附件列表。

未过期图片返回短期展示 URL：

```json
{
  "attachmentId": 1,
  "filename": "cat.png",
  "contentType": "image/png",
  "status": "READY",
  "expired": false,
  "previewUrl": "http://..."
}
```

过期图片不返回 URL：

```json
{
  "attachmentId": 1,
  "status": "EXPIRED",
  "expired": true,
  "message": "图片已过期"
}
```

默认复用规则：

- 如果本次没有上传图片，也没有手动选择历史图片，后端查找同会话最近一轮用户消息中的未过期图片。
- 找到后作为本轮图片上下文传给模型。
- 如果最近图片已过期，不复用并在历史展示中提示 `图片已过期`。

手动复用规则：

- 前端提供历史图片选择入口。
- 只展示未过期图片。
- 用户选择后通过 `reuseAttachmentIds` 发送。

## 模型调用设计

`agent-hub-chat` 组装聊天请求时，调用 `agent-hub-storage` 为每张图片生成短期预签名读取 URL。

`agent-hub-ai` 将内部消息结构扩展为多 part：

```text
text part
image_url part
```

OpenAI Chat Completions 兼容协议：

```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "分析这张图"
    },
    {
      "type": "image_url",
      "image_url": {
        "url": "https://..."
      }
    }
  ]
}
```

Anthropic Messages 协议第一版可预留结构，但如果只拿到 URL，需确认供应商是否支持 URL 图片输入。若供应商要求 base64，则该供应商的图片输入暂不开放，或后续扩展 base64 发送模式。

重要部署前提：

- 云模型必须能访问图片预签名读取 URL。
- `storage.public-endpoint` 必须配置为模型服务可访问的地址。
- 本地 `127.0.0.1:9000` 只适合本地前端上传和后端访问，不保证云模型可访问。

## 会话上下文预算与摘要

新增 `ConversationContextBuilder`，负责构建每次模型调用的上下文。

### 预算公式

```text
availableInputTokens = contextWindow - reservedOutputTokens - safetyMargin
summaryTriggerTokens = contextWindow * 0.8
```

配置建议：

```text
summaryTriggerRatio = 0.8
safetyMarginTokens = 1024
defaultImageTokenEstimate = 1500
recentMessageMaxCount = 20
recentTurnMinCount = 2
```

规则：

- `contextWindow` 来自模型配置。
- `reservedOutputTokens` 优先使用请求输出上限，其次使用模型 `maxOutputTokens`，最后使用默认值。
- 文本消息使用 token 估算器计算。
- 图片使用模型配置的 `image_token_estimate`，为空时使用系统默认值。
- 系统提示词、摘要、历史消息、当前用户消息、图片预算都纳入统一计算。

### 首次输入超限

如果首次发送时：

```text
系统提示词 + 当前用户消息 + 当前图片预算 > availableInputTokens
```

直接拦截并提示：

```text
当前输入内容过长，已超过模型上下文限制，请缩短后重试
```

不自动截断当前用户消息。

### 历史导致超限

如果完整历史上下文估算达到：

```text
fullHistoryEstimate >= contextWindow * 0.8
```

触发摘要更新。

摘要输入：

```text
旧摘要 + summarized_message_id 之后、且不属于最近保留窗口的消息
```

摘要输出要求：

- 保留用户目标。
- 保留已确认约束。
- 保留关键决策。
- 保留未完成事项。
- 保留和当前任务相关的错误、阻塞点和结论。
- 不保存无意义闲聊。
- 不跨会话保存。

摘要成功后：

- 更新 `chat_conversation_summary.summary`。
- 更新 `summarized_message_id`。
- 更新 `token_estimate`。

### 摘要后的消息选择

模型输入顺序：

```text
1. system prompt
2. conversation summary
3. selected recent messages
4. current user message
5. current/reused image references
```

最近消息选择规则：

- 从最新历史消息开始倒序选择。
- 优先保留 `summarized_message_id` 之后的新消息。
- 最多保留 `20` 条。
- 至少尝试保留最近 `2` 轮用户/助手对话。
- 如果预算不足，逐步减少最近消息数量。
- 如果只保留最低窗口仍超限，提示用户缩短当前输入或切换更大上下文模型。

### 模型调用失败兜底

如果模型返回上下文超限错误：

- 捕获错误。
- 减少历史窗口后重试一次。
- 仍失败则返回中文错误提示，建议用户缩短输入或切换大上下文模型。

## 前端交互设计

聊天输入区保留 `+` 按钮。

点击 `+` 后菜单向上展开，第一版只有：

```text
上传照片
```

当当前模型不支持图片输入时：

- `上传照片` 禁用。
- 提示 `当前模型不支持图片输入`。

上传预览：

- 图片上传后显示在输入框上方预览区。
- 1 张图片时显示较大的缩略图。
- 多张图片时显示小缩略图横向排列。
- 缩略图右上角显示圆形 `×`，点击可移除。
- 点击缩略图打开大图预览。
- 上传中显示进度遮罩或圆形进度。
- 上传失败显示失败状态，可移除后重新上传。
- 所有图片上传并确认成功后才允许发送消息。
- 输入框高度随图片预览区撑开，底部保留 `+`、输入框、Thinking、麦克风和发送按钮。

发送按钮规则：

- 无文本且无图片时置灰。
- 图片上传中置灰。
- 图片确认失败置灰或要求用户移除失败图片。
- 当前模型不支持图片但待发送区有图片时置灰并提示。

## Docker 与 MinIO

`docker-compose.yml` 增加：

- `minio`
- `minio-init`

MinIO 默认端口：

```text
9000  API
9001  Console
```

新增：

```text
docker/env/minio.env
```

配置项：

```text
MINIO_ROOT_USER=agenthub
MINIO_ROOT_PASSWORD=agenthub123
MINIO_BUCKET_CHAT_IMAGES=agent-hub-chat-images
MINIO_IMAGE_EXPIRE_DAYS=7
```

`minio-init` 职责：

- 等待 MinIO 启动。
- 创建图片 bucket。
- 配置 bucket lifecycle，7 天后自动清理对象。
- bucket 默认不公开读。

后端配置：

```yaml
agent-hub:
  storage:
    endpoint: http://127.0.0.1:9000
    public-endpoint: http://127.0.0.1:9000
    access-key: agenthub
    secret-key: agenthub123
    bucket: agent-hub-chat-images
    image-expire-days: 7
    presigned-upload-ttl-minutes: 10
    presigned-read-ttl-minutes: 10
    pending-cleanup-minutes: 60
    unused-ready-cleanup-hours: 24
```

## 清理任务

新增 `AttachmentCleanupJob`。

每 10 分钟执行：

- 清理 `PENDING` 且 `created_at < now - 1 hour` 的附件。
- 清理 `READY` 且 `message_id IS NULL` 且 `created_at < now - 24 hours` 的附件。
- 删除 MinIO 对象。
- 将附件状态标记为 `DELETED`。

每小时或每天执行：

- 将 `expires_at < now` 且仍为 `READY` 的附件标记为 `EXPIRED`。

物理删除：

- 临时未使用附件由后端主动删除对象。
- 已关联消息的 7 天过期附件主要由 MinIO lifecycle 清理对象。
- 业务层始终以数据库 `expires_at` 为准判断是否可访问。

## 安全与权限

- 所有附件接口必须登录。
- 用户只能访问自己的附件。
- 复用图片必须属于当前会话。
- object key 由后端生成，不接受前端自定义。
- 预签名上传 URL 有短 TTL。
- 预签名读取 URL 有短 TTL。
- bucket 不公开。
- 上传确认时校验对象存在、大小和 content type。
- 发送聊天时再次校验附件状态、归属、过期时间和数量。

## 测试设计

后端测试：

- 申请上传 URL 时校验类型、大小、登录用户。
- 申请上传 URL 后创建 `PENDING` 附件。
- 确认上传时校验附件归属、状态和 MinIO 对象存在。
- 确认成功后状态变为 `READY`。
- 聊天发送时校验附件数量不超过 8。
- 聊天发送时校验模型支持图片输入。
- 聊天发送时校验附件未过期。
- 聊天发送成功后绑定附件到会话和消息。
- 默认复用最近未过期图片。
- 手动复用历史图片。
- 过期图片不返回预签名 URL。
- 清理任务处理 `PENDING` 超时附件。
- 清理任务处理未关联消息的 `READY` 附件。
- 上下文达到 80% 时触发摘要。
- 当前用户输入本身超限时直接拒绝。
- 模型上下文超限失败时执行一次降级重试。

前端测试：

- 不支持图片模型时禁用 `上传照片`。
- `+` 菜单向上展开。
- 单张图片显示大缩略图。
- 多张图片显示小缩略图横向排列。
- 缩略图右上角 `×` 可移除图片。
- 点击缩略图打开大图预览。
- 上传中显示进度。
- 上传未完成时发送按钮置灰。
- 上传失败时显示失败状态。
- 超过 8 张图片时提示。
- 超过 20MB 时提示。
- 历史过期图片展示 `图片已过期` 并不可复用。

## 实施顺序建议

1. 增加 MinIO Docker Compose、env 和初始化 lifecycle。
2. 新增 `agent-hub-storage` 模块和附件表迁移。
3. 实现预签名上传、确认上传、预签名读取和清理任务。
4. 扩展模型表和管理端模型图片能力开关。
5. 扩展聊天消息附件关联和历史消息返回结构。
6. 扩展 LLM 内部消息结构和 OpenAI 兼容视觉输入。
7. 实现用户端上传照片菜单、上传进度、预览、移除和大图预览。
8. 实现历史图片默认复用和手动复用。
9. 实现 `ConversationContextBuilder`、token 估算和 80% 摘要更新。
10. 补齐后端和前端测试。

## 待用户配置后继续的点

实现阶段如果进入 MinIO 部署配置，需要先完成：

- 确认本地或服务器 MinIO 端口没有冲突。
- 启动 `docker compose up -d`。
- 确认 `minio-init` 已创建 bucket 并配置 7 天 lifecycle。
- 如果调用云模型读取图片，需要配置模型侧可访问的 `public-endpoint`。

在以上配置未确认前，不继续执行依赖真实 MinIO 的联调。
