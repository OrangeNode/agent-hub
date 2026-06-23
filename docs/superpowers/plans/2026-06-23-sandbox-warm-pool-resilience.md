# Sandbox 预热池韧性优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 sandbox warm pool 持续补位、失败自动重试，并在管理接口暴露关键观测字段。

**Architecture:** `WarmPool` 负责池容量维护、补位调度、失败退避和统计；`SessionManager` 继续只消费 VM，不回收已使用 VM 到池子。管理快照新增字段，Java DTO 和前端类型保持兼容。

**Tech Stack:** Go `sandbox-service`、Java Spring Boot DTO、Vue/TypeScript 管理端类型。

---

### Task 1: WarmPool 后台维护测试

**Files:**
- Modify: `sandbox-service/internal/host/warm_pool_test.go`
- Modify: `sandbox-service/internal/host/warm_pool.go`
- Modify: `sandbox-service/internal/host/runtime_snapshot.go`

- [ ] **Step 1: 写失败测试**

在 `warm_pool_test.go` 增加测试：

```go
func TestWarmPoolAcquireTriggersAsyncRefill(t *testing.T) {
    factory := &fakeVMFactory{}
    pool := NewWarmPool(factory, 1)
    if err := pool.Fill(); err != nil {
        t.Fatalf("Fill() error = %v", err)
    }

    vm, err := pool.Acquire()
    if err != nil {
        t.Fatalf("Acquire() error = %v", err)
    }
    if vm.ID() != "vm-1" {
        t.Fatalf("vm.ID() = %q", vm.ID())
    }

    waitUntil(t, 200*time.Millisecond, func() bool {
        return pool.Size() == 1 && factory.StartCount() == 2
    })
    snapshot := pool.Snapshot()
    if snapshot.HotAcquireCount != 1 {
        t.Fatalf("HotAcquireCount = %d", snapshot.HotAcquireCount)
    }
}
```

- [ ] **Step 2: 验证测试失败**

Run: `go test ./internal/host -run TestWarmPoolAcquireTriggersAsyncRefill -count=1`

Expected: FAIL，原因是 `Acquire()` 当前不会触发异步补位，且 `WarmPoolSnapshot` 没有 `HotAcquireCount`。

- [ ] **Step 3: 实现最小代码**

给 `WarmPool` 增加 `triggerFill()`，`Acquire()` 从池子拿到 VM 后调用它；`Snapshot()` 返回热启动计数。

- [ ] **Step 4: 验证测试通过**

Run: `go test ./internal/host -run TestWarmPoolAcquireTriggersAsyncRefill -count=1`

Expected: PASS。

### Task 2: 失败重试与状态快照

**Files:**
- Modify: `sandbox-service/internal/host/warm_pool_test.go`
- Modify: `sandbox-service/internal/host/warm_pool.go`
- Modify: `sandbox-service/internal/host/runtime_snapshot.go`

- [ ] **Step 1: 写失败测试**

增加测试覆盖第一次启动失败、下一次 tick 重试成功，并检查 `lastFillError`、`nextRetryAt`、`lastSuccessAt`。

- [ ] **Step 2: 验证测试失败**

Run: `go test ./internal/host -run TestWarmPoolMaintainerRetriesAfterFailure -count=1`

Expected: FAIL，原因是当前没有后台维护器和退避状态。

- [ ] **Step 3: 实现维护器**

新增 `StartMaintainer()`、`StopMaintainer()`、`SetRetryBackoffForTest()`；维护器按退避间隔重试 `Fill()`，成功后清空错误。

- [ ] **Step 4: 验证测试通过**

Run: `go test ./internal/host -run TestWarmPoolMaintainerRetriesAfterFailure -count=1`

Expected: PASS。

### Task 3: 服务启动接入维护器

**Files:**
- Modify: `sandbox-service/cmd/sandbox-service/main.go`
- Modify: `sandbox-service/internal/host/admin_api_test.go`

- [ ] **Step 1: 写或更新测试**

确认 admin status JSON 能返回新增 warm pool 字段，并保持旧字段不变。

- [ ] **Step 2: 实现接入**

将启动时的一次性 `go warmPool.Fill()` 改为 `warmPool.StartMaintainer()`，服务退出时 `defer warmPool.StopMaintainer()`。

- [ ] **Step 3: 验证**

Run: `go test ./internal/host ./cmd/sandbox-service -count=1`

Expected: PASS。

### Task 4: 产物恢复观测日志

**Files:**
- Modify: `sandbox-service/internal/host/api.go`
- Modify: `sandbox-service/internal/host/api_test.go`

- [ ] **Step 1: 写测试**

覆盖创建会话携带恢复产物时，仍会下载对象并写入 `/workspace/artifacts/<relativePath>`。

- [ ] **Step 2: 实现日志**

在 `restoreArtifacts` 开始、单文件失败、全部成功时写 `log.Printf`，包含 conversation、数量、路径和错误。

- [ ] **Step 3: 验证**

Run: `go test ./internal/host -run Restore -count=1`

Expected: PASS。

### Task 5: Java DTO 与前端类型兼容

**Files:**
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/dto/SandboxWarmPoolSnapshot.java`
- Modify: `admin-web/src/types/sandbox-admin.ts`

- [ ] **Step 1: 更新 DTO 和类型**

新增字段为可空或默认值字段，避免旧服务 JSON 反序列化失败。

- [ ] **Step 2: 验证**

Run: `mvn -pl agent-hub-sandbox -am test`

Expected: PASS。

Run: `npm test -- sandbox-admin`

Expected: PASS 或说明当前前端测试命令不可用。

### Task 6: 全量验证

**Files:**
- No code changes.

- [ ] **Step 1: Go 验证**

Run: `go test ./...`

Expected: PASS。

- [ ] **Step 2: Java 验证**

Run: `mvn -pl agent-hub-sandbox,agent-hub-server -am test`

Expected: PASS。

- [ ] **Step 3: 差异审查**

Run: `git diff --stat` and `git diff`

Expected: 差异只包含 warm pool、产物恢复观测和兼容类型更新。
