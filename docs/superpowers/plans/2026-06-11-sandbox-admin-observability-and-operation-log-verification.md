# Sandbox 运维可观测与操作日志验证记录

日期：2026-06-11

分支：`codex/sandbox-admin-observability`

## 自动验证

### Go sandbox-service

命令：

```bash
cd sandbox-service
GOCACHE=D:\project\Agent-Hub\.gocache go test ./...
```

结果：通过。

摘要：

- `cmd/sandbox-service`、`internal/host`、`internal/config`、`internal/auth`、`internal/agent` 等包均通过。
- 测试后已确认 `.gocache` 位于仓库目录内，并清理该临时目录。

### Java 后端

命令：

```bash
cd backend
mvn -pl agent-hub-system,agent-hub-sandbox,agent-hub-server -am test
```

结果：通过。

摘要：

- Reactor 总体 `BUILD SUCCESS`。
- 覆盖 `agent-hub-system` 操作日志、`agent-hub-sandbox` 聚合服务、`agent-hub-server` sandbox/operation-log controller 和迁移 smoke test。
- 输出中存在 Mockito 动态 agent 警告，以及附件清理失败续跑测试的预期 WARN 日志；均未导致测试失败。

### admin-web

命令：

```bash
cd admin-web
npm run test
npm run type-check
npm run build
```

结果：通过。

摘要：

- `vitest run`：12 个测试文件、38 个测试通过。
- `vue-tsc --noEmit`：通过。
- `vite build`：通过。
- build 输出存在单个入口 chunk 超过 500 kB 的 Vite 警告；当前未影响构建结果。

## 人工验证建议

需要联通真实后端、登录态和 sandbox-service 后再执行：

- 登录 admin-web，确认菜单包含 `Sandbox 运维` 和 `操作日志`。
- 打开 `/sandbox/monitor`，确认概览、会话、执行、资源和日志尾部区域可以加载。
- 对 `清理残留资源` 执行 dry-run，确认不会实际删除资源，并在 `操作日志` 中看到记录。
- 对 `终止 VM` 输入错误确认文本，确认前端不可提交或后端拒绝，不会调用 Go 控制 API。
- 检查 `operation_log.params_json` 和 `operation_log.result_json` 中 password、token、secret、content、stdout、stderr 等字段已脱敏。

## 本地限制

本次自动验证环境是 Windows 工作区，没有真实 Firecracker/KVM 运行态和管理端登录态，因此未执行真实 VM 终止、tap 删除、runDir 清空等破坏性操作。
