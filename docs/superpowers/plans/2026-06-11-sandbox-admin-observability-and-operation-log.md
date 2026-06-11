# Sandbox Admin Observability And Operation Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 sandbox 运维可观测工作台和通用操作日志能力，支持查看资源消耗、识别残留资源、执行高危运维操作并完整审计。

**Architecture:** Go `sandbox-service` 暴露内部 admin API 采集和操作宿主机运行态；Java 后端聚合 Go 运行态与数据库业务态，并负责权限、确认校验和操作日志；admin-web 提供运维面板和操作日志查询页面。

**Tech Stack:** Go 1.x、Firecracker、Spring Boot 4、MyBatis-Plus、Flyway、Spring Security、Spring AOP、Vue 3、Pinia、Naive UI、Vitest。

---

## Scope Check

该规格覆盖 Go 运行态、Java 聚合、通用操作日志和 admin-web 四个子系统。它们共享同一个用户目标，并且强控制操作必须依赖操作日志审计，所以采用一个总计划分阶段执行。每个任务都可以独立测试和提交，执行时不要跳过前置任务。

## File Structure

### 后端操作日志

- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/annotation/OperationLog.java`  
  定义可配置操作日志注解。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/entity/OperationLogRecord.java`  
  映射 `operation_log` 表。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/mapper/OperationLogMapper.java`  
  MyBatis-Plus mapper。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/dto/OperationLogItem.java`  
  管理端列表 DTO。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/command/OperationLogQuery.java`  
  查询条件。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/service/OperationLogService.java`  
  记录和查询接口。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/service/impl/OperationLogServiceImpl.java`  
  入库、分页和条件查询实现。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/aspect/OperationLogAspect.java`  
  AOP 环绕记录成功和失败。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/support/OperationLogSanitizer.java`  
  参数/结果脱敏和截断。
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/controller/OperationLogController.java`  
  管理端查询接口。
- Modify: `backend/agent-hub-system/pom.xml`  
  增加 AOP、Jackson、MyBatis annotation 所需依赖。
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/AgentHubApplication.java`  
  增加 operation log mapper scan。
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V9__init_operation_log_and_sandbox_admin.sql`  
  创建操作日志表，初始化 sandbox 运维和操作日志菜单权限。
- Test: `backend/agent-hub-system/src/test/java/com/agenthub/system/operationlog/OperationLogSanitizerTest.java`
- Test: `backend/agent-hub-system/src/test/java/com/agenthub/system/operationlog/OperationLogAspectTest.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/system/OperationLogMigrationSmokeTest.java`

### Go `sandbox-service`

- Create: `sandbox-service/internal/host/runtime_snapshot.go`  
  session、VM、warm pool 只读快照。
- Create: `sandbox-service/internal/host/resource_scanner.go`  
  扫描 Firecracker 进程、tap 和 runDir。
- Create: `sandbox-service/internal/host/admin_api.go`  
  Go 内部 admin API。
- Create: `sandbox-service/internal/host/idle_reaper.go`  
  后台 idle reaper。
- Modify: `sandbox-service/internal/host/firecracker.go`  
  暴露 VM 元数据、路径安全辅助、停止结果。
- Modify: `sandbox-service/internal/host/session_manager.go`  
  增加 snapshot、close session、reap result。
- Modify: `sandbox-service/internal/host/warm_pool.go`  
  增加 snapshot、stop all、rebuild。
- Modify: `sandbox-service/cmd/sandbox-service/main.go`  
  注册 admin API，启动 idle reaper。
- Test: `sandbox-service/internal/host/runtime_snapshot_test.go`
- Test: `sandbox-service/internal/host/resource_scanner_test.go`
- Test: `sandbox-service/internal/host/admin_api_test.go`
- Test: `sandbox-service/internal/host/idle_reaper_test.go`

### Java sandbox admin 聚合

- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/client/SandboxAdminClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/client/HttpSandboxAdminClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/dto/*.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/service/SandboxAdminService.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/service/impl/SandboxAdminServiceImpl.java`
- Create: `backend/agent-hub-server/src/main/java/com/agenthub/server/sandbox/SandboxAdminController.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/session/mapper/SandboxSessionMapper.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/execution/mapper/SandboxExecutionMapper.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/artifact/mapper/SandboxArtifactMapper.java`
- Test: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/admin/SandboxAdminServiceImplTest.java`
- Test: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/admin/HttpSandboxAdminClientTest.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxAdminControllerTest.java`

### admin-web

- Create: `admin-web/src/types/sandbox-admin.ts`
- Create: `admin-web/src/types/operation-log.ts`
- Create: `admin-web/src/api/sandbox-admin.ts`
- Create: `admin-web/src/api/operation-log.ts`
- Create: `admin-web/src/views/SandboxMonitorView.vue`
- Create: `admin-web/src/views/OperationLogsView.vue`
- Create: `admin-web/src/views/sandbox-admin.helpers.ts`
- Create: `admin-web/src/views/operation-log.helpers.ts`
- Modify: `admin-web/src/router/route-registry.ts`
- Modify: `admin-web/src/stores/menu.helpers.ts`
- Modify: `admin-web/src/views/menu-icon-registry.ts`
- Test: `admin-web/tests/sandbox-admin.spec.ts`
- Test: `admin-web/tests/operation-log.spec.ts`
- Test: `admin-web/tests/router.spec.ts`
- Test: `admin-web/tests/menu.spec.ts`

---

### Task 1: Operation Log Schema, Annotation, Sanitizer

**Files:**
- Modify: `backend/agent-hub-system/pom.xml`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/annotation/OperationLog.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/support/OperationLogSanitizer.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/entity/OperationLogRecord.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/mapper/OperationLogMapper.java`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/AgentHubApplication.java`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V9__init_operation_log_and_sandbox_admin.sql`
- Test: `backend/agent-hub-system/src/test/java/com/agenthub/system/operationlog/OperationLogSanitizerTest.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/system/OperationLogMigrationSmokeTest.java`

- [ ] **Step 1: Write sanitizer failing tests**

Create `OperationLogSanitizerTest`:

```java
package com.agenthub.system.operationlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.system.operationlog.support.OperationLogSanitizer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationLogSanitizerTest {

    @Test
    void shouldMaskSensitiveFieldsCaseInsensitively() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.of(
                "username", "admin",
                "password", "123456",
                "Authorization", "Bearer token",
                "nested", Map.of("secret", "abc")
        ), 4000, new String[]{"token"});

        assertThat(json).contains("\"username\":\"admin\"");
        assertThat(json).contains("\"password\":\"******\"");
        assertThat(json).contains("\"Authorization\":\"******\"");
        assertThat(json).contains("\"secret\":\"******\"");
        assertThat(json).doesNotContain("123456");
        assertThat(json).doesNotContain("Bearer token");
        assertThat(json).doesNotContain("abc");
    }

    @Test
    void shouldTruncateLongJson() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.of("command", "x".repeat(100)), 40, new String[0]);

        assertThat(json.length()).isLessThanOrEqualTo(40);
        assertThat(json).endsWith("...");
    }
}
```

- [ ] **Step 2: Run sanitizer test to verify it fails**

Run:

```bash
cd backend
mvn -pl agent-hub-system -Dtest=OperationLogSanitizerTest test
```

Expected: FAIL because `OperationLogSanitizer` does not exist.

- [ ] **Step 3: Add operation log dependencies**

Modify `backend/agent-hub-system/pom.xml` dependencies:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

- [ ] **Step 4: Create `@OperationLog` annotation**

Create:

```java
package com.agenthub.system.operationlog.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    String module();

    String action();

    String target() default "";

    String targetType() default "";

    String operationType() default "MANAGE";

    boolean recordParams() default true;

    boolean recordResult() default false;

    boolean requireReason() default false;

    int maxLength() default 4000;

    String[] sensitiveFields() default {};
}
```

- [ ] **Step 5: Create sanitizer implementation**

Create `OperationLogSanitizer` with these public methods:

```java
public String toSanitizedJson(Object value, int maxLength, String[] extraSensitiveFields)
public String truncate(String value, int maxLength)
```

Implementation requirements:

- Use Jackson `ObjectMapper`.
- Recursively sanitize `Map`, `Collection`, array, POJO converted with `valueToTree`.
- Default sensitive field names: `password`, `token`, `secret`, `authorization`, `internalToken`, `content`, `fileContent`, `stdout`, `stderr`.
- Match sensitive field names case-insensitively.
- Return `null` for `null` input.
- Return `"\"<unserializable>\""` if Jackson serialization fails.

- [ ] **Step 6: Run sanitizer test to verify it passes**

Run:

```bash
cd backend
mvn -pl agent-hub-system -Dtest=OperationLogSanitizerTest test
```

Expected: PASS.

- [ ] **Step 7: Create operation log entity and mapper**

Create `OperationLogRecord` mapped to `operation_log` with fields:

```java
Long id;
String traceId;
Long userId;
String username;
String module;
String action;
String targetType;
String targetId;
String operationType;
String reason;
String requestMethod;
String requestUri;
String clientIp;
String userAgent;
String paramsJson;
String resultJson;
String status;
String errorCode;
String errorMessage;
Long durationMs;
OffsetDateTime createdAt;
```

Use `@TableName("operation_log")` and `@TableId(type = IdType.AUTO)`.

Create `OperationLogMapper extends BaseMapper<OperationLogRecord>` with `@Mapper`.

- [ ] **Step 8: Add mapper scan**

Modify `AgentHubApplication`:

```java
"com.agenthub.system.operationlog.mapper",
```

Add it next to the other `com.agenthub.system.*.mapper` entries.

- [ ] **Step 9: Write migration smoke test**

Create `OperationLogMigrationSmokeTest` that:

- Starts H2 like existing migration tests.
- Asserts `operation_log` table exists.
- Asserts indexes `idx_operation_log_created_at` and `idx_operation_log_user_created_at` exist.
- Asserts menus `sandbox`, `sandboxMonitor`, `operationLogs` exist.
- Asserts ADMIN role is bound to `sandbox:monitor:view`, `sandbox:runtime:kill`, `operation-log:view`.
- Asserts migration script does not contain `INSERT INTO sys_menu (id` or `RESTART WITH`.

- [ ] **Step 10: Create migration**

Create `V9__init_operation_log_and_sandbox_admin.sql`:

```sql
CREATE TABLE operation_log (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    trace_id VARCHAR(64),
    user_id BIGINT,
    username VARCHAR(64),
    module VARCHAR(64) NOT NULL,
    action VARCHAR(128) NOT NULL,
    target_type VARCHAR(64),
    target_id VARCHAR(256),
    operation_type VARCHAR(64) NOT NULL DEFAULT 'MANAGE',
    reason VARCHAR(512),
    request_method VARCHAR(16),
    request_uri VARCHAR(512),
    client_ip VARCHAR(64),
    user_agent VARCHAR(512),
    params_json TEXT,
    result_json TEXT,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    error_message TEXT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_operation_log_created_at ON operation_log (created_at DESC);
CREATE INDEX idx_operation_log_user_created_at ON operation_log (user_id, created_at DESC);
CREATE INDEX idx_operation_log_module_action ON operation_log (module, action);
```

Then insert menus using dynamic parent lookup, following `V6__init_agent_runtime.sql` style:

- Root menu `sandbox`: title `Sandbox 管理`, path `/sandbox`, icon `cloud`.
- Child menu `sandboxMonitor`: title `Sandbox 运维`, path `/sandbox/monitor`, permission `sandbox:monitor:view`.
- Child under system root `operationLogs`: title `操作日志`, path `/system/operation-logs`, permission `operation-log:view`.
- Button permission rows can use `menu_type = 'BUTTON'`, `visible = FALSE`, and permissions listed in the spec.
- Bind all inserted rows to ADMIN with `INSERT INTO sys_role_menu ... SELECT`.

- [ ] **Step 11: Run migration smoke test**

Run:

```bash
cd backend
mvn -pl agent-hub-server -Dtest=OperationLogMigrationSmokeTest test
```

Expected: PASS.

- [ ] **Step 12: Commit task 1**

```bash
git add backend/agent-hub-system/pom.xml backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog backend/agent-hub-system/src/test/java/com/agenthub/system/operationlog backend/agent-hub-server/src/main/java/com/agenthub/server/AgentHubApplication.java backend/agent-hub-server/src/main/resources/db/migration/V9__init_operation_log_and_sandbox_admin.sql backend/agent-hub-server/src/test/java/com/agenthub/server/system/OperationLogMigrationSmokeTest.java
git commit -m "新增操作日志表与注解基础"
```

---

### Task 2: Operation Log AOP, Service, Query API

**Files:**
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/dto/OperationLogItem.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/command/OperationLogQuery.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/service/OperationLogService.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/service/impl/OperationLogServiceImpl.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/aspect/OperationLogAspect.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog/controller/OperationLogController.java`
- Test: `backend/agent-hub-system/src/test/java/com/agenthub/system/operationlog/OperationLogAspectTest.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/system/OperationLogControllerTest.java`

- [ ] **Step 1: Write aspect failing tests**

Create a Spring test with a test service method:

```java
@OperationLog(module = "测试模块", action = "成功操作", target = "#targetId", recordParams = true, recordResult = true)
public Map<String, Object> success(String targetId, String password) {
    return Map.of("ok", true, "token", "secret-token");
}

@OperationLog(module = "测试模块", action = "失败操作", target = "#targetId", recordParams = true)
public void failure(String targetId) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "失败原因");
}
```

Assertions:

- Success inserts one row with `status = SUCCESS`, `targetId = "vm-1"`, masked password and token.
- Failure inserts one row with `status = FAILED`, `errorCode = BAD_REQUEST.code()` and message `失败原因`.

- [ ] **Step 2: Run aspect test to verify it fails**

Run:

```bash
cd backend
mvn -pl agent-hub-system -Dtest=OperationLogAspectTest test
```

Expected: FAIL because service/aspect do not exist.

- [ ] **Step 3: Implement operation log service**

`OperationLogService` methods:

```java
void record(OperationLogRecord record);
List<OperationLogItem> list(OperationLogQuery query);
```

`OperationLogQuery` fields:

```java
OffsetDateTime startAt;
OffsetDateTime endAt;
String username;
String module;
String action;
String status;
String targetId;
String keyword;
Integer limit;
```

Use MyBatis-Plus `LambdaQueryWrapper`, default limit 100, max limit 500, order by `createdAt desc, id desc`.

- [ ] **Step 4: Implement aspect**

`OperationLogAspect` requirements:

- `@Around("@annotation(operationLog)")`.
- Extract current user from `SecurityContextHolder`; if principal is `LoginUser`, use `userId()` and `username()`.
- Extract request info with `RequestContextHolder`.
- Resolve `target` with SpEL using method parameter names.
- Extract `reason` from any method argument with a `reason()` accessor or `getReason()` getter.
- If `requireReason = true` and reason is blank, throw `BusinessException(ErrorCode.BAD_REQUEST, "操作原因不能为空")` before invoking target method.
- On success insert `SUCCESS`.
- On `BusinessException`, insert `FAILED` with `exception.errorCode().code()` and `exception.getMessage()`, then rethrow.
- On other `RuntimeException`, insert `FAILED` with `ErrorCode.INTERNAL_ERROR.code()`, then rethrow.
- Always record `durationMs`.

- [ ] **Step 5: Implement operation log controller**

`OperationLogController`:

```java
@RestController
@RequestMapping("/api/system/operation-logs")
@PreAuthorize("hasAuthority('operation-log:view')")
public class OperationLogController {
    @GetMapping
    public ApiResponse<List<OperationLogItem>> list(...)
}
```

Query params: `startAt`, `endAt`, `username`, `module`, `action`, `status`, `targetId`, `keyword`, `limit`.

- [ ] **Step 6: Write controller test**

Use MockMvc test style from `SystemControllerTest`:

- Authorized user with `operation-log:view` returns `code = 200`.
- User without permission receives 403.

- [ ] **Step 7: Run task 2 backend tests**

Run:

```bash
cd backend
mvn -pl agent-hub-system,agent-hub-server -am -Dtest=OperationLogAspectTest,OperationLogControllerTest test
```

Expected: PASS.

- [ ] **Step 8: Commit task 2**

```bash
git add backend/agent-hub-system/src/main/java/com/agenthub/system/operationlog backend/agent-hub-system/src/test/java/com/agenthub/system/operationlog backend/agent-hub-server/src/test/java/com/agenthub/server/system/OperationLogControllerTest.java
git commit -m "实现注解式操作日志记录与查询"
```

---

### Task 3: Go Read-Only Runtime Snapshot And Resource Scanner

**Files:**
- Create: `sandbox-service/internal/host/runtime_snapshot.go`
- Create: `sandbox-service/internal/host/resource_scanner.go`
- Modify: `sandbox-service/internal/host/firecracker.go`
- Modify: `sandbox-service/internal/host/session_manager.go`
- Modify: `sandbox-service/internal/host/warm_pool.go`
- Test: `sandbox-service/internal/host/runtime_snapshot_test.go`
- Test: `sandbox-service/internal/host/resource_scanner_test.go`

- [ ] **Step 1: Write runtime snapshot tests**

Tests to add:

```go
func TestSessionManagerSnapshotIncludesIdleAndActiveUses(t *testing.T)
func TestSessionManagerSnapshotDoesNotExposeMutableSessionPointers(t *testing.T)
func TestWarmPoolSnapshotReportsPooledVMs(t *testing.T)
```

Assertions:

- Snapshot returns `SandboxSessionID`, `ConversationID`, `VMID`, `AgentURL`, `LastActiveAt`, `ActiveUses`, `IdleSeconds`.
- During `BeginUse`, `ActiveUses` is 1 and session is not considered idle.
- Modifying returned snapshot does not change manager internals.

- [ ] **Step 2: Run snapshot tests to verify they fail**

Run:

```bash
cd sandbox-service
go test ./internal/host -run 'TestSessionManagerSnapshot|TestWarmPoolSnapshot'
```

Expected: FAIL because snapshot types do not exist.

- [ ] **Step 3: Add VM metadata interface**

In `firecracker.go`, add:

```go
type VMMetadata struct {
    ID       string `json:"vmId"`
    AgentURL string `json:"agentUrl"`
    RunDir   string `json:"runDir,omitempty"`
    TapName  string `json:"tapName,omitempty"`
    PID      int    `json:"pid,omitempty"`
}

type MetadataProvider interface {
    Metadata() VMMetadata
}
```

Implement `Metadata()` on `FirecrackerVM` and `staticAgentVM`.

- [ ] **Step 4: Add session manager snapshot**

Create `runtime_snapshot.go` with:

```go
type RuntimeSessionSnapshot struct {
    SandboxSessionID   string    `json:"sandboxSessionId"`
    ConversationID     string    `json:"conversationId"`
    VMID               string    `json:"vmId"`
    AgentURL           string    `json:"agentUrl"`
    LastActiveAt       time.Time `json:"lastActiveAt"`
    ActiveUses         int       `json:"activeUses"`
    IdleSeconds        int64     `json:"idleSeconds"`
    IdleTimeoutSeconds int64     `json:"idleTimeoutSeconds"`
    RunDir             string    `json:"runDir,omitempty"`
    TapName            string    `json:"tapName,omitempty"`
    FirecrackerPID     int       `json:"firecrackerPid,omitempty"`
    RuntimeStatus      string    `json:"runtimeStatus"`
}
```

Add:

```go
func (m *SessionManager) Snapshot(now time.Time) []RuntimeSessionSnapshot
```

Status rules:

- `ACTIVE` when activeUses > 0 or idleSeconds < timeout.
- `IDLE` when idleSeconds >= timeout and activeUses == 0.

- [ ] **Step 5: Add warm pool snapshot**

Add:

```go
type WarmPoolSnapshot struct {
    TargetSize int          `json:"targetSize"`
    Size       int          `json:"size"`
    Starting   int          `json:"starting"`
    VMs        []VMMetadata `json:"vms"`
}

func (p *WarmPool) Snapshot() WarmPoolSnapshot
```

- [ ] **Step 6: Run runtime snapshot tests**

Run:

```bash
cd sandbox-service
go test ./internal/host -run 'TestSessionManagerSnapshot|TestWarmPoolSnapshot'
```

Expected: PASS.

- [ ] **Step 7: Write resource scanner tests**

Create temp runDir with:

- `fc-live/firecracker.socket`
- `fc-orphan/rootfs.ext4`
- `not-vm/readme.txt`

Use a fake process list and fake tap list injected into scanner. Assertions:

- `fc-orphan` is classified as `ORPHAN_RUN_DIR`.
- Firecracker process whose socket points at `fc-live/firecracker.socket` is bound to `fc-live`.
- Tap without VM metadata is classified as `ORPHAN_TAP`.

- [ ] **Step 8: Implement resource scanner**

Create `ResourceScanner`:

```go
type ResourceScanner struct {
    RunDir        string
    TapNamePrefix string
    ProcessLister func() ([]ProcessInfo, error)
    TapLister     func() ([]TapInfo, error)
}

func (s ResourceScanner) Scan(sessions []RuntimeSessionSnapshot, warmPool WarmPoolSnapshot) (ResourceSnapshot, error)
```

Default process lister can read `ps -eo pid=,args=`. Default tap lister can read `ip -o link show`. Tests should use injected listers.

Path safety:

- Only directories whose base name starts with `fc-` are considered VM runDirs.
- Resolve absolute runDir before scanning.

- [ ] **Step 9: Run resource scanner tests**

Run:

```bash
cd sandbox-service
go test ./internal/host -run 'TestResourceScanner'
```

Expected: PASS.

- [ ] **Step 10: Run host package tests**

Run:

```bash
cd sandbox-service
go test ./internal/host
```

Expected: PASS.

- [ ] **Step 11: Commit task 3**

```bash
git add sandbox-service/internal/host
git commit -m "新增 sandbox 运行态快照与资源扫描"
```

---

### Task 4: Go Admin API, Idle Reaper, Control Operations

**Files:**
- Create: `sandbox-service/internal/host/admin_api.go`
- Create: `sandbox-service/internal/host/idle_reaper.go`
- Modify: `sandbox-service/internal/host/session_manager.go`
- Modify: `sandbox-service/internal/host/warm_pool.go`
- Modify: `sandbox-service/cmd/sandbox-service/main.go`
- Test: `sandbox-service/internal/host/admin_api_test.go`
- Test: `sandbox-service/internal/host/idle_reaper_test.go`

- [ ] **Step 1: Write admin API tests**

Tests:

```go
func TestAdminStatusRequiresInternalToken(t *testing.T)
func TestAdminStatusReturnsRuntimeAndResourceSummary(t *testing.T)
func TestCleanupOrphansDryRunDoesNotDelete(t *testing.T)
func TestClearRunDirRejectsUnsafePath(t *testing.T)
```

Use `httptest` and temp dirs. Expected failures before implementation.

- [ ] **Step 2: Write idle reaper tests**

Tests:

```go
func TestIdleReaperStopsIdleSessions(t *testing.T)
func TestIdleReaperSkipsActiveSessions(t *testing.T)
```

Use fake clock and fake VM stop counter.

- [ ] **Step 3: Run Go tests to verify they fail**

Run:

```bash
cd sandbox-service
go test ./internal/host -run 'TestAdmin|TestCleanup|TestClearRunDir|TestIdleReaper'
```

Expected: FAIL because admin API and idle reaper do not exist.

- [ ] **Step 4: Add session close and reap result**

In `SessionManager`, add:

```go
type ReapResult struct {
    ClosedSessions []string `json:"closedSessions"`
    StoppedVMs     []string `json:"stoppedVms"`
    Errors         []string `json:"errors"`
}

func (m *SessionManager) Close(sessionID string) ReapResult
func (m *SessionManager) ReapIdleWithResult(now time.Time) ReapResult
```

Keep existing `ReapIdle(now time.Time) error` as compatibility wrapper.

- [ ] **Step 5: Add warm pool stop and rebuild**

In `WarmPool`, add:

```go
func (p *WarmPool) StopAll() error
func (p *WarmPool) Rebuild() error
```

`Rebuild()` calls `StopAll()` then `Fill()`.

- [ ] **Step 6: Implement idle reaper**

Create:

```go
type IdleReaper struct {
    Manager  *SessionManager
    Interval time.Duration
    StopCh   chan struct{}
}

func NewIdleReaper(manager *SessionManager, interval time.Duration) *IdleReaper
func (r *IdleReaper) Start()
func (r *IdleReaper) Stop()
```

Default interval in main should be `min(idleTimeout/2, time.Minute)`, lower bound 10 seconds.

- [ ] **Step 7: Implement admin API**

Create:

```go
type AdminServer struct {
    Manager *SessionManager
    WarmPool *WarmPool
    Scanner ResourceScanner
    LogFile string
    StartedAt time.Time
    Config AdminConfig
}

func (s *AdminServer) Register(mux *http.ServeMux)
```

Routes:

- `GET /internal/admin/sandbox/status`
- `GET /internal/admin/sandbox/sessions`
- `GET /internal/admin/sandbox/resources`
- `POST /internal/admin/sandbox/reap-idle`
- `POST /internal/admin/sandbox/sessions/{sessionId}/close`
- `POST /internal/admin/sandbox/vms/{vmId}/kill`
- `POST /internal/admin/sandbox/resources/cleanup-orphans`
- `POST /internal/admin/sandbox/run-dir/clear`
- `POST /internal/admin/sandbox/warm-pool/rebuild`
- `GET /internal/admin/sandbox/logs/tail`

Use existing auth middleware by registering these routes on the same mux returned to `auth.RequireToken`.

- [ ] **Step 8: Implement high-risk operations safely**

Rules:

- `cleanup-orphans` supports `{"dryRun":true}` and returns planned deletions.
- `run-dir/clear` resolves absolute runDir and only deletes children under that directory.
- `vms/{vmId}/kill` first tries known session/warm pool VM metadata, then falls back to scanned orphan process.
- Tap deletion only accepts names starting with configured tap prefix.
- Return per-resource results, not only a boolean.

- [ ] **Step 9: Wire admin API and reaper in main**

Modify `main.go`:

- Keep `warmPool := host.NewWarmPool(...)`.
- Start `warmPool.Fill()` once on startup when warm pool size > 0.
- Build `AdminServer` with manager, warmPool, scanner, log file and config.
- Register admin routes before `ListenAndServe`.
- Start idle reaper after manager creation.

- [ ] **Step 10: Run Go admin tests**

Run:

```bash
cd sandbox-service
go test ./internal/host
go test ./...
```

Expected: PASS.

- [ ] **Step 11: Commit task 4**

```bash
git add sandbox-service/internal/host sandbox-service/cmd/sandbox-service/main.go
git commit -m "实现 sandbox-service 管理接口与 idle 回收"
```

---

### Task 5: Java Sandbox Admin Client And Aggregation API

**Files:**
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/client/SandboxAdminClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/client/HttpSandboxAdminClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/dto/SandboxAdminDtos.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/service/SandboxAdminService.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin/service/impl/SandboxAdminServiceImpl.java`
- Create: `backend/agent-hub-server/src/main/java/com/agenthub/server/sandbox/SandboxAdminController.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/session/mapper/SandboxSessionMapper.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/execution/mapper/SandboxExecutionMapper.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/artifact/mapper/SandboxArtifactMapper.java`
- Test: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/admin/HttpSandboxAdminClientTest.java`
- Test: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/admin/SandboxAdminServiceImplTest.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxAdminControllerTest.java`

- [ ] **Step 1: Write HTTP admin client tests**

Use `HttpServer` or `MockWebServer` alternative already available in JDK with `com.sun.net.httpserver.HttpServer`.

Test:

- Client sends `Authorization: Bearer <token>`.
- `status()` parses `serviceHealthy`, `activeSessions`, `risks`.
- Non-2xx throws `BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱运行态失败")`.

- [ ] **Step 2: Run client test to verify it fails**

Run:

```bash
cd backend
mvn -pl agent-hub-sandbox -Dtest=HttpSandboxAdminClientTest test
```

Expected: FAIL.

- [ ] **Step 3: Create DTO records**

Create records in `admin/dto`, one record per file if `SandboxAdminDtos.java` becomes too large:

```java
public record SandboxOverview(...){}
public record SandboxRuntimeStatus(...){}
public record SandboxRuntimeSession(...){}
public record SandboxResourceSnapshot(...){}
public record SandboxRisk(String level, String code, String title, String message, String targetType, String targetId, String recommendedAction){}
public record SandboxControlRequest(String reason, String confirmText, boolean dryRun){}
public record SandboxControlResult(boolean success, List<String> affected, List<String> errors){}
public record SandboxAdminSessionItem(...){}
public record SandboxAdminExecutionItem(...){}
public record SandboxAdminArtifactItem(...){}
```

Use Java records for DTOs, matching existing style.

- [ ] **Step 4: Implement `SandboxAdminClient`**

Methods:

```java
SandboxRuntimeStatus status();
List<SandboxRuntimeSession> sessions();
SandboxResourceSnapshot resources();
SandboxControlResult reapIdle();
SandboxControlResult closeSession(String sessionId);
SandboxControlResult killVm(String vmId);
SandboxControlResult cleanupOrphans(boolean dryRun);
SandboxControlResult clearRunDir();
SandboxControlResult rebuildWarmPool();
String tailLogs(int lines);
```

HTTP implementation mirrors `HttpSandboxClient`: use `SandboxProperties.getBaseUrl()` and `getInternalToken()`.

- [ ] **Step 5: Add mapper query methods**

Add explicit select methods:

`SandboxSessionMapper`:

```java
List<SandboxSession> selectAdminSessions(@Param("status") String status, @Param("conversationId") Long conversationId, @Param("sandboxSessionId") String sandboxSessionId);
void markClosedBySandboxSessionId(@Param("sandboxSessionId") String sandboxSessionId, @Param("closedAt") OffsetDateTime closedAt);
```

`SandboxExecutionMapper`:

```java
List<SandboxExecution> selectAdminExecutions(@Param("conversationId") Long conversationId, @Param("sandboxSessionDbId") Long sandboxSessionDbId, @Param("status") String status);
```

`SandboxArtifactMapper`:

```java
List<SandboxArtifact> selectAdminArtifacts(@Param("conversationId") Long conversationId, @Param("sandboxSessionDbId") Long sandboxSessionDbId);
```

Use `@Select` and `@Update` annotations, ordering newest first.

- [ ] **Step 6: Implement aggregation service**

`SandboxAdminService` methods:

```java
SandboxOverview overview();
List<SandboxAdminSessionItem> sessions(...filters...);
List<SandboxAdminExecutionItem> executions(...filters...);
List<SandboxAdminArtifactItem> artifacts(...filters...);
SandboxResourceSnapshot resources();
SandboxControlResult reapIdle();
SandboxControlResult closeSession(String sessionId);
SandboxControlResult killVm(String vmId, SandboxControlRequest request);
SandboxControlResult cleanupOrphans(SandboxControlRequest request);
SandboxControlResult clearRunDir(SandboxControlRequest request);
SandboxControlResult rebuildWarmPool(SandboxControlRequest request);
String tailLogs(int lines);
```

Risk aggregation:

- Return `SERVICE_UNAVAILABLE` CRITICAL if client call fails.
- Add `ORPHAN_PROCESS`, `ORPHAN_RUN_DIR`, `ORPHAN_TAP`, `IDLE_TIMEOUT`, `DB_SESSION_MISSING_RUNTIME`, `RUN_DIR_TOO_LARGE` based on Go status and DB data.

High-risk confirmation validation:

- `killVm`: confirm text must equal VM ID.
- `cleanupOrphans`: confirm text must equal `清理残留资源`.
- `clearRunDir`: confirm text must equal `清空运行目录`.
- `rebuildWarmPool`: confirm text must equal `重建预热池`.
- For these operations `reason` must be nonblank.

- [ ] **Step 7: Write aggregation service tests**

Test:

- DB READY session missing from Go runtime creates WARN risk.
- `closeSession` calls client and marks DB row `CLOSED` with `closedAt`.
- `killVm` rejects wrong confirm text.
- `tailLogs(1000)` clamps to max 500.

- [ ] **Step 8: Implement controller with permissions and operation logs**

`SandboxAdminController` in server module:

```java
@RestController
@RequestMapping("/api/sandbox/admin")
@PreAuthorize("hasAuthority('sandbox:monitor:view')")
public class SandboxAdminController { ... }
```

Annotate high-risk methods:

```java
@OperationLog(module = "Sandbox运维", action = "强杀VM", target = "#vmId", targetType = "VM", recordParams = true, recordResult = true, requireReason = true)
@PostMapping("/vms/{vmId}/kill")
@PreAuthorize("hasAuthority('sandbox:runtime:kill')")
```

Use `ApiResponse.success(...)` for all responses.

- [ ] **Step 9: Write controller tests**

Test:

- `/api/sandbox/admin/overview` requires `sandbox:monitor:view`.
- `/api/sandbox/admin/vms/vm-1/kill` requires `sandbox:runtime:kill`.
- Successful kill returns `code = 200`.
- Missing permission returns 403.

- [ ] **Step 10: Run Java sandbox admin tests**

Run:

```bash
cd backend
mvn -pl agent-hub-sandbox,agent-hub-server -am -Dtest=HttpSandboxAdminClientTest,SandboxAdminServiceImplTest,SandboxAdminControllerTest test
```

Expected: PASS.

- [ ] **Step 11: Commit task 5**

```bash
git add backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/admin backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/session/mapper/SandboxSessionMapper.java backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/execution/mapper/SandboxExecutionMapper.java backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/artifact/mapper/SandboxArtifactMapper.java backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/admin backend/agent-hub-server/src/main/java/com/agenthub/server/sandbox/SandboxAdminController.java backend/agent-hub-server/src/test/java/com/agenthub/server/sandbox/SandboxAdminControllerTest.java
git commit -m "实现 sandbox 管理聚合接口"
```

---

### Task 6: Admin-Web Sandbox Monitor Read-Only UI

**Files:**
- Create: `admin-web/src/types/sandbox-admin.ts`
- Create: `admin-web/src/api/sandbox-admin.ts`
- Create: `admin-web/src/views/sandbox-admin.helpers.ts`
- Create: `admin-web/src/views/SandboxMonitorView.vue`
- Modify: `admin-web/src/router/route-registry.ts`
- Modify: `admin-web/src/stores/menu.helpers.ts`
- Modify: `admin-web/src/views/menu-icon-registry.ts`
- Test: `admin-web/tests/sandbox-admin.spec.ts`
- Test: `admin-web/tests/router.spec.ts`
- Test: `admin-web/tests/menu.spec.ts`

- [ ] **Step 1: Write helper tests**

Create tests for:

```ts
buildRiskTagType('CRITICAL') === 'error'
buildRiskTagType('WARN') === 'warning'
formatBytes(1073741824) === '1.00 GB'
requiresStrongConfirm('CLEAR_RUN_DIR') === true
```

- [ ] **Step 2: Run helper test to verify it fails**

Run:

```bash
cd admin-web
npm run test -- sandbox-admin.spec.ts
```

Expected: FAIL because helper file does not exist.

- [ ] **Step 3: Create types and API helpers**

`types/sandbox-admin.ts`:

```ts
export interface SandboxRisk {
  level: 'INFO' | 'WARN' | 'CRITICAL'
  code: string
  title: string
  message: string
  targetType?: string
  targetId?: string
  recommendedAction?: string
}

export interface SandboxOverview {
  serviceHealthy: boolean
  activeSessions: number
  dbOpenSessions: number
  runningExecutions: number
  recentFailedExecutions: number
  firecrackerProcesses: number
  tapDevices: number
  runDirBytes: number
  orphanResources: number
  risks: SandboxRisk[]
}
```

Add interfaces for session, execution, artifact, resource and control request/result.

`api/sandbox-admin.ts` functions:

- `fetchSandboxOverview`
- `fetchSandboxSessions`
- `fetchSandboxExecutions`
- `fetchSandboxArtifacts`
- `fetchSandboxResources`
- `reapSandboxIdle`
- `closeSandboxSession`
- `killSandboxVm`
- `cleanupSandboxOrphans`
- `clearSandboxRunDir`
- `rebuildSandboxWarmPool`
- `fetchSandboxLogTail`

- [ ] **Step 4: Implement helpers**

`sandbox-admin.helpers.ts`:

```ts
export function buildRiskTagType(level: string) {
  if (level === 'CRITICAL') return 'error'
  if (level === 'WARN') return 'warning'
  return 'info'
}
```

Add `formatBytes`, `formatDurationSeconds`, `requiresStrongConfirm`, `expectedConfirmText`.

- [ ] **Step 5: Add routes and menu section support**

Add route definition:

```ts
{
  path: '/sandbox/monitor',
  name: 'sandbox-monitor',
  title: 'Sandbox 运维',
  icon: 'cloud',
  permission: 'sandbox:monitor:view',
  component: () => import('../views/SandboxMonitorView.vue')
}
```

In `menu.helpers.ts`, add section title:

```ts
sandbox: 'Sandbox 管理'
```

Ensure `filterMenusByProfile` treats `/sandbox` like `/system`, `/ai`, `/agent`.

- [ ] **Step 6: Create read-only monitor page**

`SandboxMonitorView.vue` layout:

- Header with title, refresh button, auto-refresh switch, interval select.
- Metric grid for overview.
- Risk list.
- Tabs: `概览`, `会话`, `执行`, `资源`, `日志尾部`.
- Tables use Naive UI `n-data-table`.
- Logs tab only loads on button click.
- Use `onMounted` to load overview/sessions/executions/resources.
- Use `window.setInterval` when auto-refresh is enabled; clear interval on unmount.

- [ ] **Step 7: Update router tests**

Add expectations:

```ts
expect(routeNames).toContain('sandbox-monitor')
```

- [ ] **Step 8: Run admin-web tests**

Run:

```bash
cd admin-web
npm run test -- sandbox-admin.spec.ts router.spec.ts menu.spec.ts
npm run type-check
```

Expected: PASS.

- [ ] **Step 9: Commit task 6**

```bash
git add admin-web/src/types/sandbox-admin.ts admin-web/src/api/sandbox-admin.ts admin-web/src/views/sandbox-admin.helpers.ts admin-web/src/views/SandboxMonitorView.vue admin-web/src/router/route-registry.ts admin-web/src/stores/menu.helpers.ts admin-web/src/views/menu-icon-registry.ts admin-web/tests/sandbox-admin.spec.ts admin-web/tests/router.spec.ts admin-web/tests/menu.spec.ts
git commit -m "新增 sandbox 运维只读面板"
```

---

### Task 7: Admin-Web High-Risk Operations And Operation Logs UI

**Files:**
- Create: `admin-web/src/types/operation-log.ts`
- Create: `admin-web/src/api/operation-log.ts`
- Create: `admin-web/src/views/operation-log.helpers.ts`
- Create: `admin-web/src/views/OperationLogsView.vue`
- Modify: `admin-web/src/views/SandboxMonitorView.vue`
- Modify: `admin-web/src/router/route-registry.ts`
- Test: `admin-web/tests/operation-log.spec.ts`
- Test: `admin-web/tests/sandbox-admin.spec.ts`
- Test: `admin-web/tests/router.spec.ts`

- [ ] **Step 1: Write operation log helper tests**

Test:

```ts
expect(buildOperationStatusTagType('SUCCESS')).toBe('success')
expect(buildOperationStatusTagType('FAILED')).toBe('error')
expect(normalizeLogLimit(0)).toBe(100)
expect(normalizeLogLimit(999)).toBe(500)
```

- [ ] **Step 2: Run operation log test to verify it fails**

Run:

```bash
cd admin-web
npm run test -- operation-log.spec.ts
```

Expected: FAIL.

- [ ] **Step 3: Create operation log API and types**

`operation-log.ts`:

```ts
export interface OperationLogItem {
  id: number
  traceId?: string | null
  userId?: number | null
  username?: string | null
  module: string
  action: string
  targetType?: string | null
  targetId?: string | null
  operationType: string
  reason?: string | null
  requestMethod?: string | null
  requestUri?: string | null
  clientIp?: string | null
  userAgent?: string | null
  paramsJson?: string | null
  resultJson?: string | null
  status: 'SUCCESS' | 'FAILED'
  errorCode?: string | null
  errorMessage?: string | null
  durationMs: number
  createdAt: string
}
```

API:

```ts
export async function fetchOperationLogs(params: OperationLogQuery): Promise<OperationLogItem[]>
```

- [ ] **Step 4: Add high-risk operation modal**

In `SandboxMonitorView.vue`:

- Add `operationModal` state with `action`, `targetId`, `confirmText`, `reason`, `loading`.
- Strong confirm actions require exact text from helper.
- Submit calls corresponding API helper.
- On success, show `message.success` and reload overview/resources/sessions.
- On error, show backend error message.

- [ ] **Step 5: Create operation logs page**

`OperationLogsView.vue`:

- Header and filters: time range, username, module, action, status, targetId, keyword.
- Data table columns: time, user, module, action, target, status, duration, IP, reason, error.
- Detail drawer with request path, method, User-Agent, params JSON, result JSON, error details, traceId.
- Load data on mount and when clicking search.

- [ ] **Step 6: Add route**

Route:

```ts
{
  path: '/system/operation-logs',
  name: 'system-operation-logs',
  title: '操作日志',
  icon: 'history',
  permission: 'operation-log:view',
  component: () => import('../views/OperationLogsView.vue')
}
```

- [ ] **Step 7: Run UI tests and type check**

Run:

```bash
cd admin-web
npm run test -- operation-log.spec.ts sandbox-admin.spec.ts router.spec.ts
npm run type-check
```

Expected: PASS.

- [ ] **Step 8: Commit task 7**

```bash
git add admin-web/src/types/operation-log.ts admin-web/src/api/operation-log.ts admin-web/src/views/operation-log.helpers.ts admin-web/src/views/OperationLogsView.vue admin-web/src/views/SandboxMonitorView.vue admin-web/src/router/route-registry.ts admin-web/tests/operation-log.spec.ts admin-web/tests/sandbox-admin.spec.ts admin-web/tests/router.spec.ts
git commit -m "接入 sandbox 高危操作与操作日志页面"
```

---

### Task 8: End-To-End Verification And Documentation Updates

**Files:**
- Modify: `sandbox-service/README.md`
- Modify: `docs/superpowers/plans/2026-06-11-sandbox-admin-observability-and-operation-log.md`
- Create: `docs/superpowers/plans/2026-06-11-sandbox-admin-observability-and-operation-log-verification.md`

- [x] **Step 1: Update sandbox-service README**

Add a section `管理接口与资源回收`:

- Explain internal admin API path.
- Explain idle reaper behavior.
- Explain `SANDBOX_IDLE_TIMEOUT`, `SANDBOX_WARM_POOL_SIZE`, runDir cleanup.
- Document that high-risk operations must go through Java admin API in normal deployment.

- [x] **Step 2: Create verification notes**

Create verification doc containing exact commands and results:

```bash
cd sandbox-service
go test ./...

cd backend
mvn -pl agent-hub-system,agent-hub-sandbox,agent-hub-server -am test

cd admin-web
npm run test
npm run type-check
npm run build
```

Record whether each passed. If a command fails because of local environment, record the exact failure and the next action.

- [x] **Step 3: Run Go verification**

Run:

```bash
cd sandbox-service
go test ./...
```

Expected: PASS.

- [x] **Step 4: Run backend verification**

Run:

```bash
cd backend
mvn -pl agent-hub-system,agent-hub-sandbox,agent-hub-server -am test
```

Expected: PASS.

- [x] **Step 5: Run admin-web verification**

Run:

```bash
cd admin-web
npm run test
npm run type-check
npm run build
```

Expected: PASS.

- [x] **Step 6: Review git diff**

Run:

```bash
git status --short
git diff --stat
```

Expected:

- Only files from this plan changed.
- No generated build output is staged.
- No unrelated user changes are modified.

- [ ] **Step 7: Commit verification docs**

```bash
git add sandbox-service/README.md docs/superpowers/plans/2026-06-11-sandbox-admin-observability-and-operation-log.md docs/superpowers/plans/2026-06-11-sandbox-admin-observability-and-operation-log-verification.md
git commit -m "补充 sandbox 运维验证记录"
```

---

## Final Verification Checklist

- [ ] `cd sandbox-service && go test ./...`
- [ ] `cd backend && mvn -pl agent-hub-system,agent-hub-sandbox,agent-hub-server -am test`
- [ ] `cd admin-web && npm run test`
- [ ] `cd admin-web && npm run type-check`
- [ ] `cd admin-web && npm run build`
- [ ] Manually open admin-web and confirm menu contains `Sandbox 运维` and `操作日志`.
- [ ] Trigger dry-run cleanup and confirm operation log row is created.
- [ ] Trigger wrong high-risk confirmation text and confirm backend returns validation error without calling Go control API.
- [ ] Confirm `operation_log.params_json` masks password/token/secret/content/stdout/stderr.

## Self-Review Notes

- Spec coverage: operation log module is covered by Tasks 1-2 and Task 7; Go runtime/admin API is covered by Tasks 3-4; Java aggregation is covered by Task 5; admin-web monitor and operation log pages are covered by Tasks 6-7; verification and docs are covered by Task 8.
- Type consistency: DTO names use `SandboxOverview`, `SandboxRisk`, `SandboxControlRequest`, `SandboxControlResult`, `OperationLogItem`; frontend uses matching TypeScript interfaces.
- Execution ordering: Task 1 must precede Task 2 and Task 5 because operation log annotation and migration are prerequisites; Task 3 must precede Task 4; Task 4 should precede Task 5 for real Go admin endpoints, while Task 5 tests can mock `SandboxAdminClient`.
