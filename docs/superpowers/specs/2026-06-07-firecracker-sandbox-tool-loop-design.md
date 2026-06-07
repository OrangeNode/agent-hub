# Firecracker 沙箱与 Tool Loop 设计

## 目标

本阶段目标是交付一套可用于 Agent Runtime 的沙箱工具执行闭环。它包含独立的 Firecracker microVM 沙箱服务，也包含 Agent Hub 后端中的 Tool Loop 接入能力。

交付结果包括：

- 独立 `sandbox-service`，使用 Go 实现。
- `sandbox-service` 直接管理 Firecracker microVM 生命周期。
- 基于 `chat_conversation` 复用 sandbox session。
- 支持 warm pool，降低首次工具执行延迟。
- 支持 SSE 流式返回工具执行日志。
- 支持固定工作区、输入目录和产物目录。
- 支持产物同步到 MinIO，并由 Agent Hub 保存产物元数据。
- 支持 OpenAI Compatible 与 Anthropic 两类 Tool Calling 协议。
- Agent Runtime 支持 Tool Loop，并把工具事件转发到用户端聊天 SSE。

## 非目标

本阶段不做以下内容：

- 不做多机器沙箱调度。
- 不做 Kubernetes 集成。
- 不做 mTLS，第一版使用内网访问和静态内部 token。
- 不做复杂命令策略引擎。
- 不做 VM 暂停、快照恢复或完整工作目录快照。
- 不做浏览器自动化沙箱。
- 不做用户每次工具调用确认。
- 不做 sandbox-service 自有数据库。
- 不让沙箱直接调用模型 API，模型调用仍由 Agent Hub 后端完成。

## 总体架构

系统分为四层：

```text
user-web
  -> 展示聊天 SSE 中的模型输出和工具事件

Agent Hub backend
  -> Agent Runtime / Tool Loop
  -> LLM Tool Calling 协议适配
  -> sandbox-service 内部客户端
  -> sandbox_session / sandbox_execution / sandbox_artifact 元数据入库

Go sandbox-service
  -> session 管理
  -> Warm VM Pool
  -> Firecracker 生命周期管理
  -> SSE 执行事件
  -> 产物同步调度
  -> 空闲 5 分钟回收

Firecracker microVM
  -> minimal rootfs
  -> sandbox-agent
  -> /workspace
  -> /workspace/input
  -> /workspace/artifacts
```

`sandbox-service` 独立部署在具备 Firecracker 和 KVM 能力的 Linux 服务器上。Agent Hub 后端通过内部 HTTP 调用它，并使用静态 token 鉴权。

`sandbox-service` 只维护运行态，不建数据库。运行态包括 microVM 进程、Firecracker socket、tap 设备、临时工作目录、`lastActiveAt` 等。服务重启后，旧 session 直接视为不可用，由 Agent Hub 下次调用时重新创建，并恢复已同步产物。

Agent Hub 保存业务态，包括会话与沙箱 session 的绑定、工具执行记录、产物元数据和下载权限。文件内容保存到 MinIO，数据库只保存文件描述信息。

## 会话生命周期

一个 `chat_conversation` 绑定一个 sandbox session。同一个聊天会话内的多轮工具调用复用同一个 microVM 和 `/workspace`。

会话第一次需要工具时：

1. Agent Hub 根据 `conversationId` 请求 sandbox-service 创建或获取 session。
2. sandbox-service 优先从 warm pool 取一个预热 microVM。
3. sandbox-service 将 microVM 绑定到该 conversation 对应的 sandbox session。
4. Agent Hub 把该 conversation 已同步产物列表传给 sandbox-service。
5. sandbox-service 将历史产物恢复到 `/workspace/artifacts`。
6. session 进入 active 状态。

会话空闲回收：

1. active session 超过 5 分钟无工具执行。
2. sandbox-service 关闭 Firecracker microVM。
3. sandbox-service 清理 socket、tap、临时目录等运行态。
4. Agent Hub 保留业务记录和已同步产物。
5. 下次同一 conversation 再执行工具时，重新创建 sandbox session，并恢复历史产物。

状态分层：

- 临时状态：`/workspace` 中未同步内容，只在当前 microVM 生命周期内存在。
- 持久状态：`/workspace/artifacts` 中已同步产物，保存到 MinIO，重建 session 时恢复。

## 资源与池化

第一版默认资源：

```text
vCPU: 1
内存: 1GB
磁盘: 5GB
单次命令超时: 60 秒
stdout 捕获上限: 1MB
stderr 捕获上限: 1MB
空闲回收: 5 分钟
网络: 默认禁公网，只允许内部白名单
```

VM 池默认配置：

```text
warmPoolSize: 2
maxActiveSessions: 5
```

所有资源和池化参数必须可配置，生产服务器可通过环境变量调整：

```text
SANDBOX_WARM_POOL_SIZE=2
SANDBOX_MAX_ACTIVE_SESSIONS=5
SANDBOX_IDLE_TIMEOUT=5m
SANDBOX_VM_CPU=1
SANDBOX_VM_MEMORY_MB=1024
SANDBOX_VM_DISK_GB=5
SANDBOX_EXEC_TIMEOUT_SECONDS=60
SANDBOX_STDOUT_MAX_BYTES=1048576
SANDBOX_STDERR_MAX_BYTES=1048576
```

第一版只维护 minimal 镜像的 warm pool，不做多镜像调度。

## 镜像与工作区

第一版使用 minimal rootfs，预装：

- `bash`
- `python3`
- `pip`
- `node`
- `npm`
- `git`
- `curl`
- `zip`
- `unzip`
- `tar`
- `jq`
- 基础 CA 证书和时区配置

暂不预装 JDK、Maven、浏览器、Playwright 和数据库客户端。

固定路径约定：

```text
/workspace              工作区
/workspace/input        用户上传文件导入目录
/workspace/artifacts    产物导出目录
```

microVM 内工具执行必须使用非 root 用户。guest-agent 应固定工作目录、PATH 和环境变量白名单，避免继承宿主或启动脚本中的敏感环境变量。

## 网络策略

microVM 默认不能访问公网，只允许访问内部白名单。

第一版建议白名单包括：

- Agent Hub 后端内部地址。
- MinIO 或对象存储内部地址。
- 公司内部 npm、PyPI、Maven 代理镜像源。
- 其他明确允许的内部服务。

模型供应商 API 不加入沙箱白名单。模型调用仍由 Agent Hub 后端的 LLM Client 完成。

## sandbox-service 内部接口

所有接口要求请求头：

```text
Authorization: Bearer <internal-token>
```

### 创建 Session

```text
POST /internal/sandbox/sessions
```

请求：

```json
{
  "conversationId": 88,
  "image": "minimal",
  "resources": {
    "cpu": 1,
    "memoryMb": 1024,
    "diskGb": 5
  },
  "restoreArtifacts": [
    {
      "relativePath": "report.csv",
      "storageBucket": "agent-hub-sandbox-artifacts",
      "storageKey": "sandbox-artifacts/88/exec_001/sha256/report.csv",
      "sha256": "9d8f..."
    }
  ]
}
```

响应：

```json
{
  "sandboxSessionId": "sbx_abc",
  "status": "ACTIVE"
}
```

### 查询和关闭 Session

```text
GET /internal/sandbox/sessions/{sessionId}
DELETE /internal/sandbox/sessions/{sessionId}
```

### 流式执行命令

```text
POST /internal/sandbox/sessions/{sessionId}/exec/stream
```

请求：

```json
{
  "executionId": "exec_001",
  "command": "python3 main.py",
  "workingDir": "/workspace",
  "timeoutSeconds": 60
}
```

SSE 事件：

```text
started
stdout_delta
stderr_delta
artifact_synced
completed
error
```

`completed` 示例：

```json
{
  "executionId": "exec_001",
  "exitCode": 0,
  "durationMs": 1200,
  "stdoutTruncated": false,
  "stderrTruncated": false
}
```

### 文件导入

```text
POST /internal/sandbox/sessions/{sessionId}/files/import
```

请求：

```json
{
  "sourceUrl": "http://minio-internal/...",
  "targetPath": "/workspace/input/data.csv",
  "sha256": "..."
}
```

`sourceUrl` 由 Agent Hub 在校验附件权限后生成。sandbox-service 不接受模型直接提供的外部 URL。

### 文件读写

```text
GET /internal/sandbox/sessions/{sessionId}/files
GET /internal/sandbox/sessions/{sessionId}/files/content
PUT /internal/sandbox/sessions/{sessionId}/files/content
GET /internal/sandbox/sessions/{sessionId}/artifacts
```

这些接口只允许访问 `/workspace` 下的路径。

## Tool Loop 工具定义

模型可见工具名不带 `sandbox_` 前缀：

```text
exec
list_files
read_file
write_file
upload_file
artifacts
```

### exec

执行命令，实时返回 stdout/stderr。

参数：

```json
{
  "command": "python3 main.py",
  "workingDir": "/workspace",
  "timeoutSeconds": 60
}
```

限制：

- `workingDir` 必须在 `/workspace`。
- `command` 不能为空。
- `command` 长度不能超过配置上限。
- `timeoutSeconds` 不能超过配置上限。
- 命令必须通过简单安全校验。
- Agent Hub 和 sandbox-service 都必须执行同一套校验。

### list_files

列出工作区文件。

参数：

```json
{
  "path": "/workspace",
  "recursive": false
}
```

限制：

- `path` 必须在 `/workspace`。
- 递归列表需要限制最大文件数。

### read_file

读取文本文件。

参数：

```json
{
  "path": "/workspace/artifacts/report.csv",
  "maxBytes": 65536
}
```

限制：

- `path` 必须在 `/workspace`。
- 默认最大读取 64KB。
- 超过上限返回截断标记。
- 第一版只读取文本文件。

### write_file

写入文本文件。

参数：

```json
{
  "path": "/workspace/main.py",
  "content": "print('hello')",
  "append": false
}
```

限制：

- `path` 必须在 `/workspace`。
- 单次写入大小有限制。
- 写入 `/workspace/artifacts` 后，在执行或同步阶段触发产物同步。

### upload_file

把用户已上传且已授权的附件导入工作区。

参数：

```json
{
  "attachmentId": 123,
  "targetPath": "/workspace/input/data.csv"
}
```

限制：

- `attachmentId` 必须属于当前用户或当前会话。
- `targetPath` 必须在 `/workspace/input`。
- Agent Hub 负责鉴权并生成内部下载地址。

### artifacts

查询当前 conversation 已同步产物。

参数：

```json
{
  "latestOnly": true
}
```

返回产物名称、路径、大小、版本、MIME 类型和下载引用。

## 命令安全校验

第一版采用简单有效的命令校验，不做复杂规则引擎。

校验规则：

1. `workingDir` 必须在 `/workspace`。
2. `command` 不能为空。
3. `command` 长度不能超过配置上限。
4. `timeoutSeconds` 不能超过配置上限。
5. 禁止明显危险命令关键词。
6. 禁止明显危险路径模式。
7. Agent Hub 和 sandbox-service 都执行校验。
8. microVM 内使用非 root 用户执行。

危险关键词：

```text
shutdown
reboot
poweroff
halt
mount
umount
modprobe
insmod
sysctl
sudo
su
systemctl
service
```

危险路径模式：

```text
rm -rf /
rm -rf /*
/etc
/proc
/sys
/dev
/root
```

`curl`、`wget`、`git`、`npm`、`pip` 不在第一版命令黑名单内，由 microVM 网络白名单兜底。

后续可扩展结构化命令：

```json
{
  "program": "python3",
  "args": ["main.py"]
}
```

第一版仍允许命令字符串，但必须经过上述校验。

## Tool Calling 协议适配

Agent Hub 内部新增统一抽象：

```text
ToolDefinition
ToolCall
ToolResult
ToolLoopState
```

OpenAI Compatible：

- 请求中通过 `tools` 传入函数工具定义。
- 模型返回 `tool_calls`。
- 后端解析 `function.name` 和 `function.arguments`。
- 工具执行完成后，以 `role=tool` 和 `tool_call_id` 把结果回传模型。

Anthropic：

- 请求中通过顶层 `tools` 传入工具定义。
- 模型返回 `tool_use` 内容块。
- 后端解析 `id`、`name`、`input`。
- 工具执行完成后，在下一条 `user` 消息中放入 `tool_result` 内容块。
- `tool_result` 必须紧跟对应的 `tool_use` 消息。

Agent Runtime 只处理内部 `ToolCall` 和 `ToolResult`，协议细节由 `agent-hub-ai` 的 LLM Client 适配层负责。

## 聊天 SSE 事件

现有聊天 SSE 保留模型输出事件。本阶段新增工具事件：

```text
tool_started
tool_stdout_delta
tool_stderr_delta
tool_artifact
tool_completed
tool_error
```

这些事件不使用普通 REST `ApiResponse` 包装，沿用聊天流内事件模型。

`tool_started` 示例：

```json
{
  "executionId": "exec_001",
  "toolName": "exec",
  "summary": "执行命令：python3 main.py"
}
```

`tool_artifact` 示例：

```json
{
  "executionId": "exec_001",
  "artifactId": 123,
  "fileName": "report.csv",
  "relativePath": "report.csv",
  "sizeBytes": 20480
}
```

## 数据库设计

Agent Hub 后端新增迁移脚本，创建以下表。

### sandbox_session

记录 conversation 与 sandbox session 的业务绑定。

字段：

- `id`：主键。
- `conversation_id`：聊天会话 ID。
- `sandbox_session_id`：sandbox-service 返回的 session ID。
- `status`：`ACTIVE`、`EXPIRED`、`CLOSED`、`FAILED`。
- `image`：镜像名称，第一版为 `minimal`。
- `created_at`：创建时间。
- `last_active_at`：最后活跃时间。
- `closed_at`：关闭时间。

### sandbox_execution

记录一次工具执行。

字段：

- `id`：主键。
- `conversation_id`：聊天会话 ID。
- `sandbox_session_id`：sandbox session ID。
- `tool_name`：工具名。
- `command`：`exec` 命令，其他工具为空。
- `status`：`RUNNING`、`COMPLETED`、`FAILED`、`TIMEOUT`、`COMPLETED_WITH_ARTIFACT_ERROR`。
- `exit_code`：命令退出码。
- `stdout_excerpt`：stdout 摘要。
- `stderr_excerpt`：stderr 摘要。
- `started_at`：开始时间。
- `completed_at`：完成时间。
- `duration_ms`：耗时。
- `error_code`：错误码。
- `error_message`：中文错误信息。

### sandbox_artifact

记录同步到 MinIO 的产物元数据。

字段：

- `id`：主键。
- `conversation_id`：聊天会话 ID。
- `execution_id`：工具执行 ID。
- `sandbox_session_id`：sandbox session ID。
- `file_name`：文件名。
- `relative_path`：相对 `/workspace/artifacts` 的路径。
- `storage_bucket`：MinIO bucket。
- `storage_key`：MinIO object key。
- `content_type`：MIME 类型。
- `size_bytes`：文件大小。
- `sha256`：内容哈希。
- `version`：同一路径的版本号。
- `created_at`：创建时间。

同一 conversation 下使用 `relative_path + sha256` 去重。同一路径内容变化时生成新版本。

## 产物同步

产物目录固定为：

```text
/workspace/artifacts
```

同步流程：

1. 工具执行结束后，guest-agent 扫描 `/workspace/artifacts`。
2. 生成文件清单：`relativePath`、`size`、`mtime`、`sha256`。
3. sandbox-service 与已同步清单对比。
4. 新文件或内容变化文件上传 MinIO。
5. sandbox-service 发送 `artifact_synced` SSE 事件。
6. Agent Hub 保存 `sandbox_artifact` 元数据。
7. Agent Hub 通过聊天 SSE 发送 `tool_artifact`。

MinIO key 建议格式：

```text
sandbox-artifacts/{conversationId}/{executionId}/{sha256}/{filename}
```

文件内容不覆盖，方便追溯每次执行的产物。

## 错误码

错误码常量必须带中文说明或中文默认消息，符合项目记忆要求。

第一版错误码：

```text
SESSION_NOT_FOUND：沙箱会话不存在或已过期。
SESSION_CAPACITY_EXCEEDED：当前沙箱会话数量已达到上限。
VM_START_FAILED：沙箱虚拟机启动失败。
COMMAND_DENIED：命令未通过安全校验。
COMMAND_TIMEOUT：命令执行超时。
COMMAND_FAILED：命令执行失败。
FILE_NOT_FOUND：文件不存在。
FILE_TOO_LARGE：文件大小超过限制。
PATH_DENIED：路径不在允许访问范围内。
ARTIFACT_SYNC_FAILED：产物同步失败。
TOOL_CALL_INVALID：模型返回的工具调用参数无效。
TOOL_LOOP_LIMIT_EXCEEDED：工具调用轮次超过上限。
INTERNAL_ERROR：沙箱服务内部错误。
```

普通 REST 接口继续使用统一 `ApiResponse`。聊天流内错误通过 `tool_error` SSE 事件返回。

产物同步失败不等同于命令失败。命令执行成功但产物同步失败时，execution 状态记录为 `COMPLETED_WITH_ARTIFACT_ERROR`，并发送 `tool_error` 提醒用户或 Agent。

## 测试与验证

### sandbox-service

- `CommandPolicy` 单元测试覆盖危险命令、危险路径、空命令、超时和工作目录限制。
- session 池测试覆盖 warm pool 补池、`maxActiveSessions=5`、空闲 5 分钟回收。
- 文件工具测试覆盖 `list_files`、`read_file`、`write_file`、`upload_file`。
- 产物测试覆盖 `/workspace/artifacts` 扫描、sha256 diff、重复文件去重、同路径新版本。
- Firecracker 集成测试覆盖启动 microVM、执行命令、SSE 输出、关闭回收。

### Agent Hub 后端

- Tool Calling 适配测试覆盖 OpenAI Compatible `tool_calls`。
- Tool Calling 适配测试覆盖 Anthropic `tool_use` 和 `tool_result`。
- Tool Loop 测试覆盖模型请求工具、执行工具、结果回传模型、继续回答。
- sandbox client 测试覆盖 SSE 事件解析、错误事件、断线处理。
- 数据库迁移测试覆盖 `sandbox_session`、`sandbox_execution`、`sandbox_artifact`。
- 聊天 SSE 测试覆盖 `tool_started`、`tool_stdout_delta`、`tool_stderr_delta`、`tool_artifact`、`tool_completed`、`tool_error`。

### 端到端

验证链路：

```text
用户上传文件
-> 模型调用 upload_file
-> 模型调用 exec 处理文件
-> 产物写入 /workspace/artifacts
-> sandbox-service 同步 MinIO
-> Agent Hub 入库 artifact 元数据
-> 用户端聊天流看到工具日志和产物
-> 模型基于工具结果给最终回答
```

## 实施顺序

1. 创建 `sandbox-service` Go 项目骨架、配置、健康检查和内部 token 鉴权。
2. 制作 minimal rootfs 和 `sandbox-agent`。
3. 实现 Firecracker session 创建、销毁、warm pool 和空闲回收。
4. 实现 `exec` SSE、命令安全校验和非 root 执行。
5. 实现文件工具和产物同步。
6. Agent Hub 新增 sandbox 表、sandbox client 和产物元数据保存。
7. 扩展 LLM Client，支持 OpenAI Compatible 与 Anthropic Tool Calling。
8. 实现 Agent Runtime Tool Loop。
9. 扩展聊天 SSE 和用户端工具事件展示。
10. 补充端到端验证与部署文档。

## 参考资料

- OpenAI Function Calling 文档：`https://platform.openai.com/docs/guides/function-calling?api-mode=chat`
- Anthropic Tool Use 文档：`https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/overview`
- Firecracker 项目：`https://github.com/firecracker-microvm/firecracker`
- AWS Firecracker 介绍：`https://aws.amazon.com/blogs/opensource/firecracker-open-source-secure-fast-microvm-serverless/`
