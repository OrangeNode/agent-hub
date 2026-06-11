# Sandbox 运维可观测与操作日志设计

## 背景

`sandbox-service` 负责在宿主机上管理 Firecracker microVM。每个 VM 会占用 Firecracker 进程、tap 设备、`run/fc-*` 运行目录、rootfs 副本、socket 和日志文件。当前实现中，VM 停止时会清理对应 runDir 和 tap，但 idle 回收主要在创建或获取新 session 时顺手触发，没有稳定的后台定时回收。服务异常退出、机器重启或未通过停止脚本关闭时，可能留下残留目录、残留 tap 或孤儿 Firecracker 进程。

管理端需要提供完整的 sandbox 运维工作台，用于观察服务器资源消耗、定位运行态和数据库状态不一致、执行必要的高危控制操作，并记录可审计的操作日志。该能力不只服务 sandbox，操作日志应作为后台通用能力，支持通过注解记录指定方法的操作过程。

## 目标

- 管理端展示 sandbox 实时运行态、数据库业务态、执行记录、产物记录和宿主机资源扫描结果。
- 支持自动轮询和资源风险提示，优先发现 VM、runDir、tap、idle session、运行中执行等资源异常。
- 支持强控制操作：关闭 session、触发 idle 回收、强杀 VM、清理残留资源、清空 runDir、重建 warm pool。
- 高危操作必须分级确认，要求填写操作原因，并写入操作日志。
- 新增通用 `@OperationLog` 注解和操作日志模块，所有带注解的方法都记录，记录策略由注解配置。
- admin-web 只访问 Java 后端；Java 负责鉴权、审计和聚合；Go `sandbox-service` 负责宿主机运行态采集和实际资源操作。
- 操作日志永久保留，第一版不做自动清理。

## 非目标

- 第一版不引入 WebSocket 或 SSE 实时推送，使用 5-10 秒自动轮询。
- 第一版不做操作日志归档、冷热分层或自动清理。
- 第一版不让 admin-web 直接访问 `sandbox-service` 内部 token 或内部 API。
- 第一版不把完整文件内容、token、secret、密码、完整 stdout/stderr 写入操作日志。

## 概念关系

### Session

`sandbox session` 表示聊天会话与沙箱运行态的绑定。

- Go 内存态 session：由 `sandbox-service` 保存，包含 `sandboxSessionId`、`conversationId`、VM、`agentURL`、`lastActiveAt`、`activeUses`。
- Java 数据库态 session：由 `sandbox_session` 表保存，包含用户、会话、sandbox session ID、状态、创建时间、最后活跃时间、关闭时间。

Go 服务重启后，Go 内存态 session 会丢失；数据库记录仍存在，但不能证明真实 VM 仍可用。

### Firecracker VM

Firecracker VM 是真实消耗服务器资源的 microVM。它通常对应：

- 一个 Firecracker 进程。
- 一个 VM ID，例如 `fc-1781076983151075207`。
- 一个 guest agent 地址。
- 一个 tap 网络设备。
- 一个宿主机 runDir。

当前 VM ID 由 `time.Now().UnixNano()` 生成，因此 `fc-*` 名称可反推出创建时间。

### runDir

runDir 是 VM 的宿主机运行目录，不等同于 session。

```text
sandbox-service/run/fc-1781076983151075207/
  rootfs.ext4
  firecracker.socket
  firecracker.log
  firecracker.stdout.log
  firecracker.stderr.log
```

理论生命周期与 VM 一致：VM 停止时删除 runDir。异常退出时 runDir 可能残留。

### Resource

面板中的 resource 指宿主机真实资源扫描结果，包括：

- `fc-*` runDir。
- Firecracker 进程。
- tap 设备。
- rootfs 副本大小。
- socket 和日志文件。
- runDir 总磁盘占用。

通过 resource 视角可以发现：

- 有 DB session，但 Go 没有 session。
- 有 Go session，但没有 Firecracker 进程。
- 有 Firecracker 进程，但没有 Go session。
- 有 `fc-*` 目录，但没有进程。
- 有 tap，但没有 VM 或 session。

### Idle

idle 表示 session 多久没有被使用。

- 创建或使用 session 时更新 `lastActiveAt`。
- `SANDBOX_IDLE_TIMEOUT` 默认 5 分钟。
- 超过 idle timeout 且没有 active use 的 session 应被回收。
- 回收动作是停止 VM，并删除 tap 和 runDir。

设计中需要补充后台 idle reaper ticker，让回收不依赖新请求触发。

### Warm Pool VM

warm pool VM 是预热但尚未绑定 conversation/session 的 VM。它已经消耗 Firecracker 进程、runDir、tap 和 sandbox-agent 资源，但不应有 `sandbox_session` 数据库记录。当前代码已有 `WarmPool` 抽象，但启动流程未主动 `Fill()`，idle 回收也未把 VM 回收到池中。第一版面板需要显式展示 warm pool 状态，并提供重建 warm pool 操作。

## 总体架构

```text
admin-web
  -> Java /api/sandbox/admin/**
    -> 查询 sandbox_session / sandbox_execution / sandbox_artifact
    -> 调用 Go /internal/admin/sandbox/**
      -> 读取或操作 Firecracker、tap、runDir、warm pool
  -> Java 写 operation_log
```

### Go `sandbox-service`

Go 服务是运行态控制面，负责：

- 采集服务健康、启动时间、配置摘要、VM 模式、warm pool、session、Firecracker 进程、tap、runDir、磁盘占用。
- 识别残留资源和资源风险。
- 执行真实宿主机操作：idle 回收、关闭 session、强杀 VM、清理残留、清空 runDir、重建 warm pool。
- 暴露内部 admin API，继续使用内部 token 鉴权，只允许 Java 后端调用。

### Java 后端

Java 后端是管理聚合与审计面，负责：

- 对 admin-web 提供统一 REST API。
- 通过 Spring Security 做权限校验。
- 聚合 Go 实时运行态和数据库业务态。
- 执行危险操作参数校验和确认文本校验。
- 调用 Go 内部 admin API。
- 写入操作日志。
- 使用项目统一响应模型和统一异常处理。

### admin-web

管理端是运维工作台，负责：

- 展示 sandbox overview、风险提示、session、execution、artifact、resource、日志尾部。
- 自动轮询状态，默认 10 秒。
- 对高危操作提供分级确认、确认文本和操作原因输入。
- 展示操作日志列表和详情。

## Go Admin API 设计

Go 新增内部接口路径：

`/internal/admin/sandbox/**`

接口继续复用内部 token 鉴权。

### `GET /internal/admin/sandbox/status`

返回服务级状态：

- 服务健康、启动时间、当前时间。
- VM 模式、idle timeout、max active session、warm pool 配置。
- Go runtime active session 数、warm pool VM 数。
- Firecracker 进程数、tap 数、runDir 占用。
- 残留资源数量。
- 风险列表。

### `GET /internal/admin/sandbox/sessions`

返回 Go 内存态 session 快照：

- `sandboxSessionId`
- `conversationId`
- `vmId`
- `agentURL`
- `lastActiveAt`
- `activeUses`
- `idleSeconds`
- `idleTimeoutSeconds`
- `runDir`
- `tapName`
- `firecrackerPid`
- `runtimeStatus`

### `GET /internal/admin/sandbox/resources`

返回宿主机资源扫描结果：

- `fc-*` runDir、大小、创建时间、是否绑定 VM、是否有对应进程。
- Firecracker 进程、pid、api socket、vmId、runDir。
- tap 设备、名称、是否绑定 VM。
- orphan 资源列表。
- 扫描时间。

### `POST /internal/admin/sandbox/reap-idle`

主动触发 idle 回收，返回：

- 被回收 session/VM 列表。
- 未回收原因。
- 错误列表。

### `POST /internal/admin/sandbox/sessions/{sessionId}/close`

关闭指定 session：

- 从 Go session map 中移除。
- 停止 VM。
- 删除 runDir 和 tap。
- 返回关闭结果。

### `POST /internal/admin/sandbox/vms/{vmId}/kill`

强杀指定 Firecracker VM：

- 支持按 Go session 绑定 VM 或扫描到的 orphan VM 执行。
- 优先调用已有 `Stop()`，必要时 kill 进程。
- 返回进程、tap、runDir 的处理结果。

### `POST /internal/admin/sandbox/resources/cleanup-orphans`

清理残留资源：

- 支持 `dryRun`。
- 默认 dry-run 可预览将删除的 runDir 和 tap。
- 非 dry-run 只允许清理配置 runDir 下的 `fc-*` 目录和匹配 tap 前缀的孤儿 tap。
- 禁止越界路径。

### `POST /internal/admin/sandbox/run-dir/clear`

清空整个 runDir：

- 最高危操作。
- 先停止相关 Firecracker 进程和 tap，再删除 runDir 下内容。
- 必须由 Java 层完成确认文本和原因校验后调用。

### `POST /internal/admin/sandbox/warm-pool/rebuild`

重建 warm pool：

- 停止当前 warm pool VM。
- 清理对应资源。
- 按配置重新 Fill warm pool。

### `GET /internal/admin/sandbox/logs/tail?lines=200`

返回 sandbox-service 日志尾部：

- 最大行数限制。
- Go 侧限制读取文件大小，避免大日志造成压力。

### Go 内部改造

- `SessionManager` 增加只读快照方法，包含 session、activeUses、idle 状态。
- `FirecrackerVM` 暴露只读元数据：VM ID、runDir、tapName、pid、agentURL。
- 增加资源扫描器，扫描 runDir、Firecracker 进程和 tap。
- 增加后台 idle reaper ticker。
- warm pool 增加快照、停止全部、重建能力。
- 所有清理操作必须校验路径位于配置 runDir 下。

## Java 后端 API 设计

Java 新增管理接口路径：

`/api/sandbox/admin/**`

### `GET /api/sandbox/admin/overview`

聚合 Go 实时状态和 DB 统计，返回：

- sandbox-service 健康状态。
- active session 数。
- DB open session 数。
- 运行中执行数。
- 最近失败执行数。
- Firecracker 进程数。
- tap 数。
- runDir 占用。
- 残留资源数。
- 风险列表。

### `GET /api/sandbox/admin/sessions`

合并 DB session 和 Go runtime session，支持过滤：

- 状态。
- 用户。
- conversationId。
- sandboxSessionId。
- 是否有运行态。
- 是否疑似孤儿。

### `GET /api/sandbox/admin/executions`

查询执行记录：

- 命令、工作目录、状态、退出码、耗时。
- stdout/stderr 摘要。
- 开始和结束时间。
- 关联 session/conversation。

### `GET /api/sandbox/admin/artifacts`

查询产物记录：

- 文件名、路径、大小、版本、hash。
- 存储 bucket/key。
- 创建时间。

### `GET /api/sandbox/admin/resources`

透传并整理 Go 资源扫描结果，给前端资源页使用。

### 控制操作接口

- `POST /api/sandbox/admin/reap-idle`
- `POST /api/sandbox/admin/sessions/{sessionId}/close`
- `POST /api/sandbox/admin/vms/{vmId}/kill`
- `POST /api/sandbox/admin/resources/cleanup-orphans`
- `POST /api/sandbox/admin/run-dir/clear`
- `POST /api/sandbox/admin/warm-pool/rebuild`
- `GET /api/sandbox/admin/logs/tail`

高危操作请求体统一包含：

- `reason`：操作原因。
- `confirmText`：确认文本。
- `dryRun`：仅适用于清理类操作。

## 权限设计

新增权限：

- `sandbox:monitor:view`：查看 sandbox 运维面板。
- `sandbox:runtime:reap`：触发 idle 回收。
- `sandbox:session:close`：关闭指定 session。
- `sandbox:runtime:cleanup`：清理残留资源。
- `sandbox:runtime:kill`：强杀 VM、清空 runDir、重建 warm pool。
- `operation-log:view`：查看操作日志。

菜单结构：

- `Sandbox 管理`
  - `Sandbox 运维`
- `系统管理`
  - `操作日志`

默认管理员角色授予上述菜单和权限。

## 操作日志模块设计

操作日志作为通用后台能力，放在 `agent-hub-system`，便于复用现有 RBAC 和管理端结构。

### 注解

```java
@OperationLog(
    module = "Sandbox运维",
    action = "强杀VM",
    target = "#vmId",
    recordParams = true,
    recordResult = true,
    requireReason = true,
    maxLength = 4000,
    sensitiveFields = {"token", "password", "secret", "authorization", "content"}
)
```

所有带 `@OperationLog` 的方法都记录，是否记录参数和结果由注解控制。默认记录参数摘要、自动脱敏、自动截断。

### AOP 行为

环绕执行带注解的方法：

- 方法开始前读取当前登录用户、IP、User-Agent、请求路径、HTTP 方法。
- 解析 `target` 表达式得到目标 ID。
- 序列化参数摘要，按敏感字段脱敏，按长度截断。
- 成功时记录状态 `SUCCESS`、耗时、结果摘要。
- 失败时记录状态 `FAILED`、异常类型、错误码、错误消息、耗时。
- 高危操作如果注解要求原因，则校验 `reason` 非空。

### 脱敏与截断

默认敏感字段包括：

- `password`
- `token`
- `secret`
- `authorization`
- `internalToken`
- `content`
- `fileContent`
- `stdout`
- `stderr`

字段名大小写不敏感，命中后写入 `******`。长文本按 `maxLength` 截断。文件内容、完整 stdout/stderr、token 和 secret 不允许原样入库。

### 表结构

新增 `operation_log`：

- `id`
- `trace_id`
- `user_id`
- `username`
- `module`
- `action`
- `target_type`
- `target_id`
- `operation_type`
- `reason`
- `request_method`
- `request_uri`
- `client_ip`
- `user_agent`
- `params_json`
- `result_json`
- `status`
- `error_code`
- `error_message`
- `duration_ms`
- `created_at`

日志永久保留，不自动删除。

## 管理端页面设计

### Sandbox 运维

路由：`/sandbox/monitor`

页面使用 tabs：

- 概览
- 风险提示
- 会话
- 执行
- 资源
- 日志尾部

默认 10 秒轮询 overview、sessions、resources 和 executions 摘要。日志尾部不自动轮询，避免压力和日志噪音。

#### 概览

展示：

- sandbox-service 健康状态。
- active session。
- DB open session。
- Firecracker 进程。
- tap 数。
- runDir 占用。
- 残留资源。
- 运行中执行。
- 最近失败执行。

#### 风险提示

展示风险等级、标题、说明、目标和建议动作。风险判断由 Java 后端聚合后返回，前端只负责展示。

#### 会话

表格字段：

- 用户。
- conversationId。
- sandboxSessionId。
- DB 状态。
- 运行态状态。
- VM ID。
- lastActiveAt。
- idle 时长。
- activeUses。
- agentURL。
- 创建时间。
- 关闭时间。

操作：

- 关闭 session。
- 查看执行。
- 查看产物。

#### 执行

表格字段：

- 命令。
- 工作目录。
- 状态。
- 退出码。
- 耗时。
- stdout/stderr 摘要。
- 开始和结束时间。

支持按 conversationId、sessionId、状态、时间范围筛选。

#### 资源

展示 Firecracker 进程、`fc-*` runDir、tap 设备、目录大小、是否绑定 session、是否疑似残留。

操作：

- dry-run 清理残留。
- 执行清理。
- 强杀 VM。
- 清空 runDir。
- 重建 warm pool。

#### 日志尾部

默认读取 200 行，最大行数由后端限制。读取日志尾部属于高风险查询，记录操作日志。

### 操作日志

路由：`/system/operation-logs`

筛选项：

- 时间范围。
- 操作人。
- 模块。
- 动作。
- 状态。
- 目标 ID。
- 关键词。

表格字段：

- 时间。
- 操作人。
- 模块。
- 动作。
- 目标。
- 状态。
- 耗时。
- IP。
- 原因。
- 错误消息。

详情抽屉展示：

- 请求路径。
- HTTP 方法。
- User-Agent。
- 参数 JSON 摘要。
- 结果 JSON 摘要。
- 异常类型、错误码、错误信息。
- traceId。

## 高危操作交互

- 关闭单个 session：普通确认，原因可选。
- idle 回收：普通确认，原因可选。
- 清理残留资源：必须输入 `清理残留资源`，必须填写原因。
- 强杀 VM：必须输入 VM ID，必须填写原因。
- 清空 runDir：必须输入 `清空运行目录`，必须填写原因。
- 重建 warm pool：必须输入 `重建预热池`，必须填写原因。

所有高危操作进入操作日志。

## 状态与风险规则

### 业务状态

来自 DB：

- `READY`
- `RUNNING`
- `CLOSED`
- `EXPIRED`
- `UNKNOWN`

### 运行态状态

来自 Go 和宿主机扫描：

- `ACTIVE`
- `IDLE`
- `CLOSING`
- `MISSING_PROCESS`
- `ORPHAN_PROCESS`
- `ORPHAN_RUN_DIR`
- `ORPHAN_TAP`
- `SERVICE_UNAVAILABLE`

### 风险规则

第一版内置：

- Go 服务不可达：`CRITICAL`。
- Firecracker 进程数超过 `SANDBOX_MAX_ACTIVE_SESSIONS + SANDBOX_WARM_POOL_SIZE`：`CRITICAL`。
- 存在孤儿 Firecracker 进程：`CRITICAL`。
- Go session 有 VM 但 agent health 失败：`CRITICAL`。
- 存在 idle 超时 session：`WARN`。
- 存在残留 `fc-*` runDir：`WARN`。
- 存在残留 tap：`WARN`。
- DB `READY` session 在 Go 中不存在：`WARN`。
- runDir 占用超过阈值：`WARN`，默认阈值 10GB，可配置。
- 运行中执行超过 timeout 仍未结束：按超时倍数标记 `WARN` 或 `CRITICAL`。

风险返回结构：

- `level`
- `code`
- `title`
- `message`
- `targetType`
- `targetId`
- `recommendedAction`

## 测试验证

### Go

- `SessionManager` 快照测试。
- idle reaper 测试。
- 资源扫描测试。
- admin API 鉴权测试。
- dry-run 测试。
- 清理操作路径安全测试。
- warm pool 重建测试。

### Java

- `@OperationLog` AOP 成功、失败、异常、耗时测试。
- 脱敏与截断测试。
- sandbox admin 聚合测试。
- 权限 403 测试。
- 高危操作确认文本和原因校验测试。
- DB migration smoke test。

### admin-web

- 路由和菜单测试。
- API helper 测试。
- 页面 loading、失败、空数据测试。
- 风险提示展示测试。
- 高危操作弹窗校验测试。
- 自动轮询启停测试。

## 实施顺序

1. 先实现操作日志模块和菜单权限，保证后续高危操作有审计底座。
2. 实现 Go 运行态快照、资源扫描、idle reaper 和只读 admin API。
3. 实现 Java sandbox admin 只读聚合 API。
4. 实现 admin-web sandbox 运维只读页面。
5. 实现 Go 高危操作 API 和 Java 代理操作 API。
6. 为高危操作接入 `@OperationLog` 和确认校验。
7. 实现操作日志页面。
8. 完成测试、构建和联调验证。
