# MCP 工具注册与运行时接入设计

## 背景

当前聊天工具调用链路已经支持内置 sandbox 工具。`ChatServiceImpl` 直接维护 `SANDBOX_TOOLS` 静态工具定义，并在收到 LLM tool call 后通过 `switch toolName` 调用 `SandboxToolService`。这个结构能支撑内置工具，但继续接入外部 MCP 工具时会让 ChatService 同时承担工具注册、工具分发、sandbox 执行、MCP 连接和错误处理等职责。

下一阶段需要把工具能力抽成统一运行层。内置 sandbox 工具和外部 MCP 工具都通过同一套 `ToolProvider` 契约暴露给 LLM，由 `ToolRegistry` 聚合工具定义并分发执行。这样 ChatService 只负责对话流、SSE 事件、消息持久化和 tool loop 编排，不关心工具来源。

## 规范基准

MCP 首版按官方 2025-11-25 规范设计：

- Streamable HTTP transport: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- Tools: https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- Resources: https://modelcontextprotocol.io/specification/2025-11-25/server/resources
- Prompts: https://modelcontextprotocol.io/specification/2025-11-25/server/prompts
- Sampling: https://modelcontextprotocol.io/specification/2025-11-25/client/sampling
- Tasks: https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks

首版只实现远程 Streamable HTTP MCP Server 的 tools/list 和 tools/call。旧 HTTP+SSE transport 不作为主目标；如果远端响应 `text/event-stream`，MCP Client 需要能读取 SSE 中最终的 JSON-RPC result 或 error。

## 目标

- 新增统一工具运行层，抽出 `ToolProvider`、`ToolRegistry` 和工具执行上下文。
- 将内置 sandbox 工具迁移为 `BuiltinSandboxToolProvider`，保持现有工具能力和前端体验不退化。
- 支持管理端配置远程 MCP Server，测试连接、同步工具、启停服务和启停工具。
- 支持 MCP Streamable HTTP 的 initialize、notifications/initialized、tools/list、tools/call。
- ChatService 通过 ToolRegistry 获取工具定义并执行工具调用，不再直接维护 sandbox 工具列表和工具分发 switch。
- 保存 MCP 工具调用日志，便于排查外部服务调用、超时、认证和协议错误。
- 前端管理端新增 MCP 服务页面，沿用当前 AI Provider 的表格、弹窗、测试按钮和中文反馈风格。

## 非目标

- 首版不支持 stdio MCP Server，不托管本地外部进程。
- 首版不实现 MCP resources、prompts、sampling、tasks、elicitation、roots。
- 首版不做 OAuth 授权流，只支持 NONE、BEARER、API_KEY_HEADER 等简单认证。
- 首版不做 Agent 级工具授权表，启用的工具先全局可见。
- 首版不把内置 sandbox 工具写入 MCP 数据表。
- 首版不完整保存大参数、大结果或敏感内容到调用日志，只保存截断摘要。

## MCP 能力说明

### Tools

Tools 是模型可主动发现和调用的动作能力，例如查询数据库、调用第三方 API、创建工单或访问企业系统。本期只接入 tools，因为它直接对应现有 LLM tool call loop。

### Resources

Resources 是外部服务提供的可读取上下文，例如文档、数据库 schema 或仓库文件。它们更像上下文来源，不是首版工具执行主路径。

### Prompts

Prompts 是外部 MCP Server 提供的可复用提示词模板或快捷指令，和 Agent Hub 自身的 Agent system prompt 不是一回事。首版不接入，避免和现有 Agent 配置混在一起。

### Sampling

Sampling 允许 MCP Server 反向请求宿主客户端调用 LLM。它涉及模型费用、上下文泄露、审批和审计，权限边界比 tools 更敏感，首版不开放。

### Tasks

Tasks 面向长任务和异步结果，例如批量分析、导出大报告或长时间爬取。官方规范中该能力仍适合作为后续增强。Agent Hub 当前已有 sandbox 流式工具过程，首版不叠加任务状态机。

## 方案选择

已确认采用方案 B：统一 Tool Registry + MCP Client 模块。

### 备选方案

- 方案 A：直接在 ChatService 中接 MCP。实现最快，但会继续扩大 ChatService 职责，后续接 Agent 工具授权和更多工具来源时会变得难维护。
- 方案 B：抽统一工具层，sandbox 和 MCP 都是 ToolProvider。改造量适中，边界清楚，后续可自然扩展 Agent 授权、工具审计和更多工具来源。
- 方案 C：一次性做完整 MCP 子系统。架构完整但首版过重，会把 resources、prompts、sampling、tasks 等暂不需要的能力一起拖进交付范围。

## 总体架构

```text
ChatService
  -> ToolRegistry
    -> BuiltinSandboxToolProvider
      -> SandboxSessionService
      -> SandboxToolService
    -> McpToolProvider
      -> McpServerMapper / McpToolMapper
      -> McpClient
```

### 模块边界

新增后端模块 `agent-hub-tool`，而不是只叫 `agent-hub-mcp`。该模块承接统一工具运行层，MCP 只是工具来源之一。

```text
agent-hub-tool
  依赖:
    agent-hub-common
    agent-hub-ai
    agent-hub-sandbox

  提供:
    ToolProvider
    ToolRegistry
    ToolExecutionContext
    ToolExecutionRequest
    ToolExecutionResult
    ToolEventSink
    BuiltinSandboxToolProvider
    McpToolProvider
    McpClient
    McpServerService
    MCP Controller / DTO / Entity / Mapper

agent-hub-chat
  依赖:
    agent-hub-tool

  改造:
    使用 toolRegistry.listTools(context) 构造 LlmStreamRequest
    使用 toolRegistry.execute(request, context) 执行 tool call
    保留 ChatService 对消息、SSE、会话标题、附件和 artifact 展示相关职责

agent-hub-sandbox
  继续提供:
    SandboxSessionService
    SandboxToolService
    sandbox artifact 读取和持久化能力

agent-hub-server
  提供:
    Flyway migration
    Spring Boot 装配入口
```

ChatService 的 artifact 列表、预览和下载接口可以在首版继续直接使用 sandbox 服务；本设计只替换 LLM tool call 执行链路。后续如需把 artifact 也抽象为统一工具产物，再单独设计。

## 工具命名

所有工具对 LLM 暴露时必须带命名空间。

```text
sandbox.exec
sandbox.read_file
sandbox.write_file
sandbox.list_files
sandbox.upload_file

mcp.github.search_repositories
mcp.crm.query_customer
mcp.database.query
```

MCP 工具解析规则：

```text
mcp.{serverKey}.{remoteToolName}
```

解析时按前两个点切分。远端工具名允许包含点，例如：

```text
mcp.github.repo.search
prefix = mcp
serverKey = github
remoteToolName = repo.search
```

`serverKey` 创建后不允许修改，避免历史工具名、日志和 Agent 授权出现漂移。

## 核心接口

```java
public interface ToolProvider {

    List<LlmToolDefinition> listTools(ToolExecutionContext context);

    boolean supports(String toolName);

    ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context);
}
```

```java
public record ToolExecutionContext(
        Long userId,
        Long conversationId,
        Long assistantMessageId,
        String agentKey,
        ToolEventSink eventSink
) {
}
```

```java
public record ToolExecutionRequest(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
) {
}
```

```java
public record ToolExecutionResult(
        String status,
        String content
) {
    public static ToolExecutionResult completed(String content) {
        return new ToolExecutionResult("completed", content);
    }

    public static ToolExecutionResult failed(String content) {
        return new ToolExecutionResult("failed", content);
    }
}
```

```java
public interface ToolEventSink {
    void output(String toolCallId, String toolName, String stream, String content);
}
```

`ToolEventSink` 让 provider 不直接依赖 `SseEmitter`。ChatService 将 sink 输出转换为 `SseEventType.TOOL_OUTPUT` 和 `ProcessEventRecorder.appendToolOutput`。

## 数据表设计

### mcp_server

```sql
CREATE TABLE mcp_server (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    server_key VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    endpoint_url VARCHAR(512) NOT NULL,
    transport_type VARCHAR(32) NOT NULL DEFAULT 'STREAMABLE_HTTP',
    protocol_version VARCHAR(32) NOT NULL DEFAULT '2025-11-25',
    auth_type VARCHAR(64) NOT NULL DEFAULT 'NONE',
    auth_token_cipher TEXT,
    extra_headers TEXT,
    connect_timeout_seconds INT NOT NULL DEFAULT 10,
    request_timeout_seconds INT NOT NULL DEFAULT 60,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync_status VARCHAR(32),
    last_sync_message TEXT,
    last_synced_at TIMESTAMPTZ,
    sort INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mcp_server_key UNIQUE (server_key)
);
```

字段说明：

- `server_key`：MCP 工具命名空间的一部分，例如 `github`。
- `endpoint_url`：Streamable HTTP MCP endpoint，例如 `https://example.com/mcp`。
- `auth_type`：首版支持 `NONE`、`BEARER`、`API_KEY_HEADER`。
- `auth_token_cipher`：沿用 AI Provider 的密钥预留加密思路，前端只展示 `hasAuthToken`。
- `extra_headers`：JSON 字符串，用于企业网关、租户头和特殊认证头。
- `last_sync_*`：展示最近一次 tools/list 同步结果。

### mcp_tool

```sql
CREATE TABLE mcp_tool (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    server_id BIGINT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    description TEXT,
    input_schema TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mcp_tool_server_name UNIQUE (server_id, tool_name),
    CONSTRAINT fk_mcp_tool_server FOREIGN KEY (server_id) REFERENCES mcp_server (id)
);
```

`mcp_tool` 保存远端 `tools/list` 的元数据快照。远端消失的工具首版不自动删除，只通过 `last_seen_at` 判断陈旧；管理员可以手动停用。

### mcp_tool_call_log

```sql
CREATE TABLE mcp_tool_call_log (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    server_id BIGINT NOT NULL,
    tool_id BIGINT,
    conversation_id BIGINT,
    user_id BIGINT,
    message_id BIGINT,
    tool_call_id VARCHAR(128),
    exposed_tool_name VARCHAR(256) NOT NULL,
    remote_tool_name VARCHAR(128) NOT NULL,
    arguments_excerpt TEXT,
    result_excerpt TEXT,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    duration_ms BIGINT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_mcp_call_server FOREIGN KEY (server_id) REFERENCES mcp_server (id),
    CONSTRAINT fk_mcp_call_tool FOREIGN KEY (tool_id) REFERENCES mcp_tool (id),
    CONSTRAINT fk_mcp_call_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_mcp_call_message FOREIGN KEY (message_id) REFERENCES chat_message (id)
);
```

调用日志只保存摘要，默认截断参数和结果到 8KB，避免日志表膨胀和敏感信息扩散。

### 索引

```sql
CREATE INDEX idx_mcp_server_enabled_sort
    ON mcp_server (enabled, sort, id);

CREATE INDEX idx_mcp_tool_server_enabled
    ON mcp_tool (server_id, enabled);

CREATE INDEX idx_mcp_call_conversation_started
    ON mcp_tool_call_log (conversation_id, started_at);

CREATE INDEX idx_mcp_call_server_started
    ON mcp_tool_call_log (server_id, started_at);
```

### 后续授权表

首版不新增 Agent 级工具绑定表。后续统一做：

```text
agent_tool_binding
  agent_id
  tool_name
  provider_type
  provider_ref_id
  enabled
```

这张表应同时覆盖 `sandbox.*` 和 `mcp.*`，不为 MCP 单独做一套授权体系。

## MCP Client 行为

### HTTP 请求

MCP Client 对 Streamable HTTP endpoint 发送 JSON-RPC 请求。

基础 Header：

```text
Accept: application/json, text/event-stream
Content-Type: application/json
MCP-Protocol-Version: 2025-11-25
Authorization: Bearer xxx
```

`extra_headers` 会合并进请求头，但必须过滤 `Host`、`Content-Length`、`Transfer-Encoding` 等危险或由 HTTP Client 管理的头。

### initialize

测试连接、同步工具和运行时调用都需要先确保 MCP session 可用。

```text
1. POST initialize
2. 解析 protocolVersion、capabilities、serverInfo
3. 如果响应头包含 MCP-Session-Id，则缓存 sessionId
4. 发送 notifications/initialized
5. 校验 capabilities.tools 存在
```

MCP session 可以先用内存缓存，按 `serverId` 维护。遇到 404 或 session 失效时重新 initialize。首版不把 sessionId 持久化到数据库。

### tools/list

同步工具时调用 `tools/list`，支持分页 cursor：

```text
1. initialize
2. tools/list cursor=null
3. 如果响应 nextCursor 不为空，继续请求下一页
4. upsert mcp_tool
5. 更新 mcp_server.last_sync_status/message/synced_at
```

`inputSchema` 必须是 JSON Object，超过 64KB 的 schema 拒绝同步该工具，并把原因写入同步消息。

### tools/call

执行工具时：

```text
1. 解析 exposedToolName 得到 serverKey 和 remoteToolName
2. 查询 enabled server 和 enabled tool
3. 获取或初始化 MCP session
4. POST tools/call
5. 解析 application/json 或 text/event-stream
6. 将 MCP content 数组转换为字符串结果
7. 写 mcp_tool_call_log
```

首版完整支持 text content。image、audio、resource link、embedded resource 和 structured content 先转换为中文说明或 JSON 摘要，后续再做富内容展示和 artifact 转存。

## 后端管理接口

```text
GET    /api/mcp/servers
POST   /api/mcp/servers
PUT    /api/mcp/servers/{serverId}
DELETE /api/mcp/servers/{serverId}

POST   /api/mcp/servers/{serverId}/test
POST   /api/mcp/servers/{serverId}/sync-tools

GET    /api/mcp/servers/{serverId}/tools
PUT    /api/mcp/tools/{toolId}/enabled

GET    /api/mcp/calls
```

权限建议：

```text
mcp:server:list
mcp:server:create
mcp:server:update
mcp:server:delete
mcp:server:test
mcp:tool:sync
mcp:tool:update
mcp:call:list
```

菜单建议先放在 AI 管理下：

```text
AI 管理
  - 供应商管理
  - 模型管理
  - MCP 服务
```

## ChatService 改造流程

当前流程：

```text
buildRequest -> SANDBOX_TOOLS
tool call -> executeToolCall -> switch sandbox tool
```

目标流程：

```text
1. 用户发送消息
2. ChatService 创建 ToolExecutionContext
3. ToolRegistry 聚合工具定义
   - BuiltinSandboxToolProvider 返回 sandbox.*
   - McpToolProvider 返回 enabled mcp.*
4. ChatService 构造 LlmStreamRequest
5. LLM 输出 tool call
6. ChatService 记录 TOOL_CALL SSE 和 process event
7. ToolRegistry 根据 toolName 选择 provider
8. provider 执行工具并返回 ToolExecutionResult
9. ChatService 发送 TOOL_RESULT SSE
10. ChatService 追加 LlmMessage.tool
11. LLM 继续下一轮
```

`TOOL_CALL_MAX_COUNT = 100` 继续生效，覆盖 sandbox 和 MCP 的总调用次数。

## 错误处理

### 配置错误

包括 server 不存在、server disabled、tool disabled、auth 配置非法、工具名无法解析。管理接口返回统一 `BusinessException`；聊天工具调用时，工具禁用和远端不可用返回 tool result，系统配置损坏才中断整轮。

### 连接错误

包括 endpoint 不可达、DNS 失败、TLS 失败、连接超时、读取超时。同步时写入 `last_sync_status=FAILED` 和失败消息。运行时写入调用日志，并返回工具失败结果给 LLM。

### 协议错误

包括 JSON-RPC error、响应 JSON 非法、SSE 未返回最终 result、capabilities 不支持 tools。同步和调用都记录具体错误摘要。普通远端业务错误不直接打断 ChatService，而是作为工具失败结果喂回 LLM。

## 安全边界

- 只有管理员可以配置 MCP Server。
- `auth_token_cipher` 不回传给前端，只返回 `hasAuthToken`。
- API Key 更新时留空表示保留旧密钥。
- `extra_headers` 必须是 JSON Object，并过滤危险头。
- `endpoint_url` 首版只允许 `http` 和 `https`，生产配置建议限制为 `https`。
- `input_schema` 最大 64KB。
- 调用参数和结果日志默认截断到 8KB。
- 单次工具调用使用 `request_timeout_seconds`，默认 60 秒。
- ToolRegistry 暴露给 LLM 的工具只包含 enabled server 和 enabled tool。
- 后续接入 Agent 级工具授权前，MCP 工具默认全局可见，需要管理员谨慎启用。

## 管理端设计

新增文件：

```text
admin-web/src/views/McpServersView.vue
admin-web/src/api/mcp.ts
admin-web/src/types/mcp.ts
admin-web/tests/mcp-servers.spec.ts
```

路由：

```text
/ai/mcp-servers
标题：MCP 服务
权限：mcp:server:list
```

页面结构沿用 `AiProvidersView.vue`：

```text
页面头部:
  标题：MCP 服务
  描述：维护外部 MCP Server、同步工具并控制启停状态。
  按钮：刷新 / 新增服务

筛选区:
  搜索 serverKey/name/endpointUrl
  按状态筛选
  按同步状态筛选

服务表格:
  服务标识
  名称
  Endpoint
  认证方式
  工具数
  最近同步状态
  启停状态
  排序
  操作
```

操作列：

```text
测试
同步工具
工具列表
编辑
删除
```

编辑弹窗字段：

```text
服务标识 serverKey，创建后不可改
服务名称 name
描述 description
Endpoint URL
传输方式 transportType，首版固定 STREAMABLE_HTTP
协议版本 protocolVersion，默认 2025-11-25
认证方式 authType: NONE / BEARER / API_KEY_HEADER
认证 Token
扩展请求头 extraHeaders
连接超时 connectTimeoutSeconds
请求超时 requestTimeoutSeconds
启用状态 enabled
排序 sort
```

工具列表弹窗：

```text
远端工具名
暴露名称 mcp.{serverKey}.{toolName}
描述
启停状态
最近同步时间
操作：启用 / 停用
```

调用日志首版可做同页抽屉或简单列表，字段包括时间、服务、工具、会话 ID、用户 ID、状态、耗时、错误摘要、参数摘要和结果摘要。

## 测试计划

### 后端单测

```text
McpClientTest
  - initialize 请求格式正确
  - tools/list 解析 application/json
  - tools/list 支持分页 cursor
  - tools/call 解析 application/json
  - tools/call 解析 text/event-stream
  - JSON-RPC error 转成可读错误
  - MCP-Session-Id 缓存并随请求发送

McpServerServiceTest
  - 创建时校验 serverKey 唯一
  - 更新时 token 留空保留旧密钥
  - testConnection 调用 initialize 并校验 tools capability
  - syncTools upsert 新旧工具
  - syncTools 失败时写入 last_sync_status/message

McpToolProviderTest
  - listTools 生成 mcp.{serverKey}.{toolName}
  - supports 正确解析命名空间
  - execute 映射远端 toolName
  - disabled server/tool 不暴露也不可执行
  - MCP 调用失败写入 call log 并返回 failed result

BuiltinSandboxToolProviderTest
  - listTools 暴露 sandbox.*
  - execute sandbox.exec/read/write/list/upload
  - exec 流式 output 通过 ToolEventSink 输出

ToolRegistryTest
  - 聚合多个 provider 工具
  - 按 toolName 分发
  - 不支持工具返回中文错误
```

### ChatService 回归测试

```text
- buildRequest 使用 toolRegistry.listTools
- tool call 使用 toolRegistry.execute
- 工具结果继续加入 LlmMessage.tool
- sandbox 工具流式输出和 process events 不退化
- MCP 工具失败作为 tool result 返回
- 工具调用次数上限覆盖全部工具来源
```

### 迁移 Smoke Test

```text
McpMigrationSmokeTest
  - mcp_server / mcp_tool / mcp_tool_call_log 表存在
  - MCP 菜单和权限初始化成功
```

### 前端测试

```text
mcp-servers.spec.ts
  - mcp API 路径正确
  - 路由注册 /ai/mcp-servers
  - 页面包含测试、同步工具、工具列表操作
  - token 输入使用 password
  - 工具暴露名包含 mcp.{serverKey}.{toolName}
```

## 实施顺序

1. 新增 `agent-hub-tool` 模块和 ToolProvider / ToolRegistry 骨架。
2. 迁移 sandbox tools 到 `BuiltinSandboxToolProvider`。
3. 改造 ChatService 使用 ToolRegistry，保持现有 sandbox tool loop 行为。
4. 新增 MCP 表、实体、Mapper、Service、Controller 和 migration。
5. 实现 McpClient 的 initialize、tools/list、tools/call。
6. 实现 McpToolProvider 并接入 ToolRegistry。
7. 新增 admin-web MCP 服务页面、API、类型、路由和菜单。
8. 补齐单测、迁移 smoke test 和前端源代码测试。

## 验收标准

- 管理员可以新增、编辑、删除、启停 MCP Server。
- 管理员可以测试 MCP Server 连接，并看到成功或失败中文提示。
- 管理员可以同步 MCP tools，并在管理端看到工具列表和启停状态。
- 启用的 MCP 工具会以 `mcp.{serverKey}.{toolName}` 形式暴露给 LLM。
- 内置 sandbox 工具会以 `sandbox.*` 形式暴露给 LLM。
- ChatService 不再直接维护 `SANDBOX_TOOLS` 和工具执行 switch。
- LLM 可以在一轮对话中调用 sandbox 工具和 MCP 工具，结果都回填到 tool loop。
- MCP 调用成功或失败都会写入调用日志。
- 现有 sandbox 文件产物、流式 stdout/stderr、文件面板体验不退化。
