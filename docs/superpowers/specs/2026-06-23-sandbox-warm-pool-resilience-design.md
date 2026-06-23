# Sandbox 预热池韧性优化设计

## 背景

当前 `sandbox-service` 已有 warm pool，但它只在服务启动时调用一次 `Fill()`。如果启动预热失败，或者预热 VM 被一次会话消耗，池子不会自动补位，后续用户请求会退化为 Firecracker 冷启动。冷启动会经过 rootfs 复制、tap 网络创建、Firecracker API 配置和 guest agent 健康检查，体感耗时明显。

## 目标

- warm pool 在服务运行期间持续接近目标容量。
- 获取预热 VM 后异步补位，不阻塞当前用户请求。
- 预热失败后自动退避重试，不让一次失败永久关闭预热能力。
- 会话结束后销毁使用过的 VM，避免 `/workspace` 文件状态跨用户或跨会话串线。
- 管理接口能看到 warm pool 的最后补位时间、失败原因、下一次重试时间和冷/热启动计数。
- 新 VM 恢复已有产物时输出可观察日志，便于定位对象存储或校验失败。

## 非目标

- 不复用已经承载过会话的 VM。
- 不恢复 `/workspace/work` 或 `/tmp` 中间工作文件。
- 不改变现有 `sandbox_artifact` 的恢复语义：仍按 conversation 维度恢复每个路径的最新产物。

## 设计

`WarmPool` 保留同步 `Fill()` 能力，并新增后台维护能力。服务启动后调用 `StartMaintainer()`，维护协程会立即尝试补齐池子；失败时记录错误并按退避间隔安排下一次重试。`Acquire()` 如果命中池子，立刻返回 VM 并异步触发补位；如果池子为空，则记录一次 cold start 并同步启动 VM。

会话生命周期继续由 `SessionManager` 管理。会话 idle 回收、手动关闭、重复创建竞争失败时仍调用 `Stop()`，不调用 `WarmPool.Release()`。这样保证已使用 VM 不进入预热池。

管理快照 `WarmPoolSnapshot` 增加观测字段：`lastFillAt`、`lastSuccessAt`、`lastFillError`、`nextRetryAt`、`hotAcquireCount`、`coldStartCount`。Java DTO 和前端类型同步新增可选字段，旧服务返回旧 JSON 时仍保持兼容。

产物恢复保持现有创建会话时携带 `artifacts` 的协议。Go 服务在恢复开始、成功和失败时写日志，记录 conversation、产物数量、路径和错误原因。

## 测试策略

- Go 单测覆盖 `Acquire()` 命中预热 VM 后会异步补位。
- Go 单测覆盖预热失败会记录错误，下一次维护 tick 能重试成功。
- Go 单测覆盖快照字段能反映 hot/cold 计数和补位状态。
- Go 单测覆盖恢复产物时调用 downloader 和 guest 写入路径保持 `/workspace/artifacts/...`。
- 运行 `go test ./internal/host/...` 和相关 Java/前端类型检查。
