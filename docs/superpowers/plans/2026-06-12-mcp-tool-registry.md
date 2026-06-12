# MCP Tool Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建统一 Tool Registry，把内置 sandbox 工具和远程 MCP Streamable HTTP 工具接入同一条 Chat tool loop。

**Architecture:** 新增 `agent-hub-tool` 后端模块，提供 `ToolProvider`、`ToolRegistry`、`BuiltinSandboxToolProvider`、`McpToolProvider`、`McpClient` 和 MCP 管理服务。`agent-hub-chat` 只依赖 ToolRegistry 获取工具定义和执行工具调用，不再直接维护 sandbox 工具列表或工具分发逻辑。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis Plus、Flyway、java.net.http.HttpClient、JUnit 5、Mockito、AssertJ、Vue 3、Naive UI、Vitest。

---

## Scope Check

本计划覆盖一个完整但有边界的首版：

- 统一工具运行层。
- 内置 sandbox 工具迁移。
- MCP Streamable HTTP 配置、连接测试、工具同步、工具调用。
- 管理端 MCP 服务页面。
- 不实现 stdio、resources、prompts、sampling、tasks、OAuth。
- 不实现 Agent 级工具授权表。

## File Map

### 后端新增

- `backend/agent-hub-tool/pom.xml`：工具模块 Maven 配置。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolProvider.java`：工具来源统一接口。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolRegistry.java`：聚合工具定义并分发工具调用。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java`：工具执行上下文。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionRequest.java`：工具调用请求。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionResult.java`：工具调用结果。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolEventSink.java`：工具过程输出回调。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/sandbox/BuiltinSandboxToolProvider.java`：内置 sandbox 工具 provider。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/**`：MCP 实体、Mapper、DTO、Service、Client、Provider、Controller。
- `backend/agent-hub-server/src/main/resources/db/migration/V11__init_mcp_tool_registry.sql`：MCP 表和菜单权限迁移。
- `backend/agent-hub-server/src/test/java/com/agenthub/server/mcp/McpMigrationSmokeTest.java`：迁移冒烟测试。

### 后端修改

- `backend/pom.xml`：加入 `agent-hub-tool` module。
- `backend/agent-hub-chat/pom.xml`：依赖 `agent-hub-tool`，移除直接依赖 sandbox 的运行时工具职责。
- `backend/agent-hub-server/pom.xml`：依赖 `agent-hub-tool`，保证 controller、mapper、service 被装配。
- `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`：改用 ToolRegistry。
- `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`：补充 registry 调用回归。

### 前端新增

- `admin-web/src/api/mcp.ts`：MCP 管理 API。
- `admin-web/src/types/mcp.ts`：MCP 管理端类型。
- `admin-web/src/views/McpServersView.vue`：MCP 服务管理页面。
- `admin-web/tests/mcp-servers.spec.ts`：MCP 前端源代码测试。

### 前端修改

- `admin-web/src/router/route-registry.ts`：注册 `/ai/mcp-servers`。
- `admin-web/src/stores/menu.helpers.ts`：不修改；MCP 服务挂在现有 AI 管理目录下，当前 `/ai` 目录过滤逻辑已有子菜单即可展示。

---

### Task 1: 创建 agent-hub-tool 模块和核心 ToolRegistry

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-hub-tool/pom.xml`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolProvider.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolRegistry.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionRequest.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionResult.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolEventSink.java`
- Test: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/runtime/ToolRegistryTest.java`

- [ ] **Step 1: 写 ToolRegistry 失败测试**

Create `backend/agent-hub-tool/src/test/java/com/agenthub/tool/runtime/ToolRegistryTest.java`:

```java
package com.agenthub.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void shouldAggregateToolsFromAllProviders() {
        ToolProvider first = mock(ToolProvider.class);
        ToolProvider second = mock(ToolProvider.class);
        ToolExecutionContext context = context();
        when(first.listTools(context)).thenReturn(List.of(tool("sandbox.exec")));
        when(second.listTools(context)).thenReturn(List.of(tool("mcp.github.search_repositories")));

        ToolRegistry registry = new ToolRegistry(List.of(first, second));

        assertThat(registry.listTools(context))
                .extracting(LlmToolDefinition::name)
                .containsExactly("sandbox.exec", "mcp.github.search_repositories");
    }

    @Test
    void shouldDispatchExecutionToSupportingProvider() {
        ToolProvider first = mock(ToolProvider.class);
        ToolProvider second = mock(ToolProvider.class);
        ToolExecutionContext context = context();
        ToolExecutionRequest request = new ToolExecutionRequest("call_1", "mcp.github.search_repositories", Map.of());
        when(first.supports("mcp.github.search_repositories")).thenReturn(false);
        when(second.supports("mcp.github.search_repositories")).thenReturn(true);
        when(second.execute(request, context)).thenReturn(ToolExecutionResult.completed("ok"));

        ToolRegistry registry = new ToolRegistry(List.of(first, second));

        assertThat(registry.execute(request, context).content()).isEqualTo("ok");
    }

    @Test
    void shouldReturnFailedResultWhenToolUnsupported() {
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolExecutionResult result = registry.execute(
                new ToolExecutionRequest("call_1", "mcp.missing.tool", Map.of()),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("不支持的工具: mcp.missing.tool");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, 100L, 200L, "official-writing", ToolEventSink.noop());
    }

    private LlmToolDefinition tool(String name) {
        return new LlmToolDefinition(name, "测试工具", Map.of("type", "object"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-tool -am test
```

Expected: FAIL，原因是 `agent-hub-tool` 模块、`ToolRegistry` 和相关类型不存在。

- [ ] **Step 3: 新增 Maven module**

Modify `backend/pom.xml` modules:

```xml
<module>agent-hub-tool</module>
```

建议插入在 `agent-hub-sandbox` 后、`agent-hub-storage` 前。

Create `backend/agent-hub-tool/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.agenthub</groupId>
        <artifactId>backend</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>agent-hub-tool</artifactId>
    <name>agent-hub-tool</name>

    <dependencies>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-ai</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-sandbox</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-core</artifactId>
            <version>3.5.16</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: 新增运行时核心类型**

Create `ToolProvider.java`:

```java
package com.agenthub.tool.runtime;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;

public interface ToolProvider {

    List<LlmToolDefinition> listTools(ToolExecutionContext context);

    boolean supports(String toolName);

    ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context);
}
```

Create `ToolExecutionContext.java`:

```java
package com.agenthub.tool.runtime;

public record ToolExecutionContext(
        Long userId,
        Long conversationId,
        Long assistantMessageId,
        String agentKey,
        ToolEventSink eventSink
) {
    public ToolExecutionContext {
        if (eventSink == null) {
            eventSink = ToolEventSink.noop();
        }
    }
}
```

Create `ToolExecutionRequest.java`:

```java
package com.agenthub.tool.runtime;

import java.util.Map;

public record ToolExecutionRequest(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
) {
    public ToolExecutionRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
```

Create `ToolExecutionResult.java`:

```java
package com.agenthub.tool.runtime;

public record ToolExecutionResult(
        String status,
        String content
) {
    public static ToolExecutionResult completed(String content) {
        return new ToolExecutionResult("completed", content == null ? "" : content);
    }

    public static ToolExecutionResult failed(String content) {
        return new ToolExecutionResult("failed", content == null ? "" : content);
    }
}
```

Create `ToolEventSink.java`:

```java
package com.agenthub.tool.runtime;

@FunctionalInterface
public interface ToolEventSink {

    void output(String toolCallId, String toolName, String stream, String content);

    static ToolEventSink noop() {
        return (toolCallId, toolName, stream, content) -> {
        };
    }
}
```

Create `ToolRegistry.java`:

```java
package com.agenthub.tool.runtime;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {

    private final List<ToolProvider> providers;

    public ToolRegistry(List<ToolProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        return providers.stream()
                .flatMap(provider -> provider.listTools(context).stream())
                .toList();
    }

    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        return providers.stream()
                .filter(provider -> provider.supports(request.toolName()))
                .findFirst()
                .map(provider -> provider.execute(request, context))
                .orElseGet(() -> ToolExecutionResult.failed("不支持的工具: " + request.toolName()));
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run:

```powershell
mvn -pl agent-hub-tool -am test
```

Expected: PASS，`ToolRegistryTest` 3 个测试通过。

- [ ] **Step 6: 提交**

```powershell
git add backend/pom.xml backend/agent-hub-tool
git commit -m "新增统一工具注册模块"
```

---

### Task 2: 迁移内置 sandbox 工具到 BuiltinSandboxToolProvider

**Files:**
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/sandbox/BuiltinSandboxToolProvider.java`
- Test: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/sandbox/BuiltinSandboxToolProviderTest.java`

- [ ] **Step 1: 写 sandbox provider 失败测试**

Create `BuiltinSandboxToolProviderTest.java`:

```java
package com.agenthub.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltinSandboxToolProviderTest {

    @Test
    void shouldExposeNamespacedSandboxTools() {
        BuiltinSandboxToolProvider provider = provider(mock(SandboxSessionService.class), mock(SandboxToolService.class));

        assertThat(provider.listTools(context()))
                .extracting(LlmToolDefinition::name)
                .containsExactly(
                        "sandbox.exec",
                        "sandbox.read_file",
                        "sandbox.write_file",
                        "sandbox.list_files",
                        "sandbox.upload_file"
                );
    }

    @Test
    void shouldExecuteNamespacedReadFile() {
        SandboxSessionService sessionService = mock(SandboxSessionService.class);
        SandboxToolService toolService = mock(SandboxToolService.class);
        when(sessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        when(toolService.readFile(7L, 100L, "sbx_1", "/workspace/input/a.txt", 16))
                .thenReturn("hello");
        BuiltinSandboxToolProvider provider = provider(sessionService, toolService);

        ToolExecutionResult result = provider.execute(new ToolExecutionRequest(
                "call_1",
                "sandbox.read_file",
                Map.of("path", "/workspace/input/a.txt", "max_bytes", 16)
        ), context());

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void shouldForwardExecOutputToSinkAndReturnCombinedOutput() {
        SandboxSessionService sessionService = mock(SandboxSessionService.class);
        SandboxToolService toolService = mock(SandboxToolService.class);
        when(sessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<SandboxEvent> consumer =
                    invocation.getArgument(6, java.util.function.Consumer.class);
            consumer.accept(new SandboxEvent("stdout_delta", "ok", null, null));
            consumer.accept(new SandboxEvent("stderr_delta", "warn", null, null));
            return null;
        }).when(toolService).exec(eq(7L), eq(100L), eq("sbx_1"), eq("python main.py"),
                eq("/workspace"), eq(60), any());
        List<String> events = new ArrayList<>();
        ToolExecutionContext context = new ToolExecutionContext(7L, 100L, 200L, "official-writing",
                (toolCallId, toolName, stream, content) -> events.add(stream + ":" + content));
        BuiltinSandboxToolProvider provider = provider(sessionService, toolService);

        ToolExecutionResult result = provider.execute(new ToolExecutionRequest(
                "call_1",
                "sandbox.exec",
                Map.of("command", "python main.py")
        ), context);

        assertThat(events).containsExactly("stdout:ok", "stderr:warn");
        assertThat(result.content()).isEqualTo("stdout:\nok\nstderr:\nwarn");
    }

    @Test
    void shouldFormatListFilesResult() {
        SandboxSessionService sessionService = mock(SandboxSessionService.class);
        SandboxToolService toolService = mock(SandboxToolService.class);
        when(sessionService.ensureSession(7L, 100L)).thenReturn("sbx_1");
        when(toolService.listFiles(7L, 100L, "sbx_1", "/workspace", false))
                .thenReturn(List.of(new SandboxFileItem("/workspace/a.txt", "a.txt", false, 3L, null)));
        BuiltinSandboxToolProvider provider = provider(sessionService, toolService);

        ToolExecutionResult result = provider.execute(new ToolExecutionRequest(
                "call_1",
                "sandbox.list_files",
                Map.of("path", "/workspace")
        ), context());

        assertThat(result.content()).contains("a.txt").contains("3");
    }

    @Test
    void shouldReturnFailedResultWhenSandboxThrows() {
        SandboxSessionService sessionService = mock(SandboxSessionService.class);
        SandboxToolService toolService = mock(SandboxToolService.class);
        when(sessionService.ensureSession(7L, 100L)).thenThrow(new RuntimeException("sandbox unavailable"));
        BuiltinSandboxToolProvider provider = provider(sessionService, toolService);

        ToolExecutionResult result = provider.execute(new ToolExecutionRequest(
                "call_1",
                "sandbox.read_file",
                Map.of("path", "/workspace/input/a.txt")
        ), context());

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("sandbox unavailable");
    }

    private BuiltinSandboxToolProvider provider(SandboxSessionService sessionService, SandboxToolService toolService) {
        return new BuiltinSandboxToolProvider(sessionService, toolService);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, 100L, 200L, "official-writing", (id, name, stream, content) -> {
        });
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-tool -am test
```

Expected: FAIL，原因是 `BuiltinSandboxToolProvider` 不存在。

- [ ] **Step 3: 实现 BuiltinSandboxToolProvider**

Create `BuiltinSandboxToolProvider.java` with these public behaviors:

```java
package com.agenthub.tool.sandbox;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolProvider;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BuiltinSandboxToolProvider implements ToolProvider {

    private static final String PREFIX = "sandbox.";
    private static final int DEFAULT_TOOL_EXEC_TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_TOOL_WORKING_DIR = "/workspace";
    private static final String DEFAULT_TOOL_OUTPUT_PATH = "/workspace/artifacts/output.txt";
    private static final String DEFAULT_TOOL_UPLOAD_PATH = "/workspace/input/upload.bin";

    private static final List<LlmToolDefinition> TOOLS = List.of(
            new LlmToolDefinition("sandbox.exec", "在工作区执行白名单命令",
                    Map.of("type", "object", "properties", Map.of(
                            "command", Map.of("type", "string"),
                            "working_dir", Map.of("type", "string"),
                            "timeout_seconds", Map.of("type", "integer")
                    ), "required", List.of("command"))),
            new LlmToolDefinition("sandbox.read_file", "读取 /workspace 内文件内容",
                    Map.of("type", "object", "properties", Map.of(
                            "path", Map.of("type", "string"),
                            "max_bytes", Map.of("type", "integer")
                    ), "required", List.of("path"))),
            new LlmToolDefinition("sandbox.write_file", "写入交付给用户的输出文件，路径必须位于 /workspace/artifacts",
                    Map.of("type", "object", "properties", Map.of(
                            "path", Map.of("type", "string"),
                            "content", Map.of("type", "string"),
                            "append", Map.of("type", "boolean")
                    ), "required", List.of("path", "content"))),
            new LlmToolDefinition("sandbox.list_files", "列出 /workspace 内文件",
                    Map.of("type", "object", "properties", Map.of(
                            "path", Map.of("type", "string"),
                            "recursive", Map.of("type", "boolean")
                    ))),
            new LlmToolDefinition("sandbox.upload_file", "把用户上传文件导入 /workspace/input",
                    Map.of("type", "object", "properties", Map.of(
                            "attachment_id", Map.of("type", "integer"),
                            "target_path", Map.of("type", "string")
                    ), "required", List.of("attachment_id", "target_path")))
    );

    private final SandboxSessionService sandboxSessionService;
    private final SandboxToolService sandboxToolService;

    public BuiltinSandboxToolProvider(SandboxSessionService sandboxSessionService, SandboxToolService sandboxToolService) {
        this.sandboxSessionService = sandboxSessionService;
        this.sandboxToolService = sandboxToolService;
    }

    @Override
    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        return TOOLS;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && toolName.startsWith(PREFIX);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        String sandboxSessionId = sandboxSessionService.ensureSession(context.userId(), context.conversationId());
        try {
            String result = switch (request.toolName()) {
                case "sandbox.exec" -> executeCommandTool(request, context, sandboxSessionId);
                case "sandbox.read_file" -> sandboxToolService.readFile(context.userId(), context.conversationId(), sandboxSessionId,
                        stringArg(request, "path", "/workspace"), intArg(request, "max_bytes", 4096));
                case "sandbox.write_file" -> writeFile(request, context, sandboxSessionId);
                case "sandbox.list_files" -> formatFiles(sandboxToolService.listFiles(context.userId(), context.conversationId(), sandboxSessionId,
                        stringArg(request, "path", "/workspace"), booleanArg(request, "recursive", false)));
                case "sandbox.upload_file" -> uploadFile(request, context, sandboxSessionId);
                default -> "不支持的工具: " + request.toolName();
            };
            return ToolExecutionResult.completed(result);
        } catch (Exception exception) {
            return ToolExecutionResult.failed(defaultString(exception.getMessage()));
        }
    }

    private String executeCommandTool(ToolExecutionRequest request, ToolExecutionContext context, String sandboxSessionId) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        sandboxToolService.exec(context.userId(), context.conversationId(), sandboxSessionId,
                stringArg(request, "command", ""),
                stringArg(request, "working_dir", DEFAULT_TOOL_WORKING_DIR),
                intArg(request, "timeout_seconds", DEFAULT_TOOL_EXEC_TIMEOUT_SECONDS),
                event -> handleSandboxEvent(request, context, stdout, stderr, event));
        return "stdout:\n" + stdout + "\nstderr:\n" + stderr;
    }

    private String writeFile(ToolExecutionRequest request, ToolExecutionContext context, String sandboxSessionId) {
        String path = stringArg(request, "path", DEFAULT_TOOL_OUTPUT_PATH);
        String content = stringArg(request, "content", "");
        context.eventSink().output(request.toolCallId(), request.toolName(), "stdout", "准备写入文件: " + path + "\n");
        sandboxToolService.writeFile(context.userId(), context.conversationId(), sandboxSessionId,
                path, content, booleanArg(request, "append", false));
        return "文件写入完成: " + path;
    }

    private String uploadFile(ToolExecutionRequest request, ToolExecutionContext context, String sandboxSessionId) {
        sandboxToolService.uploadFile(context.userId(), context.conversationId(), sandboxSessionId,
                longArg(request, "attachment_id"), stringArg(request, "target_path", DEFAULT_TOOL_UPLOAD_PATH));
        return "上传文件已放入工作区";
    }

    private void handleSandboxEvent(ToolExecutionRequest request, ToolExecutionContext context,
                                    StringBuilder stdout, StringBuilder stderr, SandboxEvent event) {
        if (event == null) {
            return;
        }
        if ("stdout_delta".equals(event.type())) {
            String content = defaultString(event.content());
            stdout.append(content);
            context.eventSink().output(request.toolCallId(), request.toolName(), "stdout", content);
        } else if ("stderr_delta".equals(event.type())) {
            String content = defaultString(event.content());
            stderr.append(content);
            context.eventSink().output(request.toolCallId(), request.toolName(), "stderr", content);
        }
    }

    private String formatFiles(List<SandboxFileItem> files) {
        if (files == null || files.isEmpty()) {
            return "未找到文件";
        }
        return files.stream()
                .map(item -> (item.directory() ? "[DIR] " : "[FILE] ") + item.path() + " " + item.size() + " bytes")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("未找到文件");
    }

    private String stringArg(ToolExecutionRequest request, String name, String defaultValue) {
        Object value = request.arguments().get(name);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int intArg(ToolExecutionRequest request, String name, int defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private Long longArg(ToolExecutionRequest request, String name) {
        Object value = request.arguments().get(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    private boolean booleanArg(ToolExecutionRequest request, String name, boolean defaultValue) {
        Object value = request.arguments().get(name);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 4: 运行 tool 模块测试**

Run:

```powershell
mvn -pl agent-hub-tool -am test
```

Expected: PASS，`BuiltinSandboxToolProviderTest` 和 `ToolRegistryTest` 全部通过。

- [ ] **Step 5: 提交**

```powershell
git add backend/agent-hub-tool
git commit -m "迁移内置 sandbox 工具提供者"
```

---

### Task 3: 改造 ChatService 使用 ToolRegistry

**Files:**
- Modify: `backend/agent-hub-chat/pom.xml`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: 写 ChatService registry 回归测试**

Modify `ChatServiceImplTest.java` by adding a test near existing sandbox tool loop tests:

```java
@Test
void shouldUseToolRegistryForToolDefinitionsAndExecution() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    AiModelService modelService = mock(AiModelService.class);
    ConversationTitleService titleService = mock(ConversationTitleService.class);
    TransactionTemplate transactionTemplate = transactionTemplate();
    AttachmentService attachmentService = mock(AttachmentService.class);
    AgentService agentService = mock(AgentService.class);
    ConversationContextBuilder contextBuilder = mock(ConversationContextBuilder.class);
    ToolRegistry toolRegistry = mock(ToolRegistry.class);
    CapturingToolLoopClient llmClient = new CapturingToolLoopClient();
    ChatServiceImpl service = new ChatServiceImpl(
            conversationMapper,
            messageMapper,
            modelService,
            titleService,
            transactionTemplate,
            new SseEventSender(),
            attachmentService,
            agentService,
            contextBuilder,
            ignored -> llmClient,
            Runnable::run,
            toolRegistry
    );
    when(modelService.requireAvailableRuntimeConfig(10L)).thenReturn(runtimeConfig());
    when(contextBuilder.build(100L, 10L, List.of())).thenReturn(new ConversationContext(List.of(LlmMessage.user("hi")), 1));
    when(toolRegistry.listTools(any(ToolExecutionContext.class))).thenReturn(List.of(
            new LlmToolDefinition("mcp.github.search_repositories", "搜索仓库", Map.of("type", "object"))
    ));
    when(toolRegistry.execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class)))
            .thenReturn(ToolExecutionResult.completed("ok"));
    prepareConversationInsert(conversationMapper, 100L);
    prepareAssistantInsert(messageMapper, 200L);

    service.streamChat(7L, 100L, "hi", 10L, false);

    assertThat(llmClient.firstRequest.tools())
            .extracting(LlmToolDefinition::name)
            .containsExactly("mcp.github.search_repositories");
    verify(toolRegistry).execute(argThat(request ->
            request.toolName().equals("mcp.github.search_repositories")
                    && request.toolCallId().equals("call_1")
    ), any(ToolExecutionContext.class));
}
```

If the existing test helpers have different names, use the existing local helper patterns and keep these assertions:

```java
assertThat(llmClient.firstRequest.tools()).extracting(LlmToolDefinition::name)
        .containsExactly("mcp.github.search_repositories");
verify(toolRegistry).execute(any(ToolExecutionRequest.class), any(ToolExecutionContext.class));
```

Add `CapturingToolLoopClient` test helper:

```java
private static class CapturingToolLoopClient implements LlmClient {
    private LlmStreamRequest firstRequest;
    private int count;

    @Override
    public void stream(LlmStreamRequest request, java.util.function.Consumer<LlmStreamChunk> chunkConsumer) {
        count++;
        if (count == 1) {
            firstRequest = request;
            chunkConsumer.accept(LlmStreamChunk.toolCall(new LlmToolCall(
                    "call_1",
                    "mcp.github.search_repositories",
                    Map.of("query", "agent hub")
            )));
            chunkConsumer.accept(LlmStreamChunk.done());
            return;
        }
        chunkConsumer.accept(LlmStreamChunk.content("完成"));
        chunkConsumer.accept(LlmStreamChunk.done());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-chat -am test
```

Expected: FAIL，原因是 ChatService 构造函数未接收 `ToolRegistry` 或仍使用 `SANDBOX_TOOLS`。

- [ ] **Step 3: 修改 chat 模块依赖**

Modify `backend/agent-hub-chat/pom.xml`:

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-tool</artifactId>
    <version>${project.version}</version>
</dependency>
```

保留 `agent-hub-sandbox` 依赖，直到确认 ChatService 中 artifact 相关能力不再直接需要 sandbox 类型；如果 artifact DTO 仍在 ChatService 中使用，不要移除 sandbox 依赖。

- [ ] **Step 4: 修改 ChatService 构造与字段**

In `ChatServiceImpl.java`:

```java
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolRegistry;
```

Add field:

```java
private final ToolRegistry toolRegistry;
```

Replace constructor parameters that currently take `SandboxSessionService` and `SandboxToolService` for tool loop with `ToolRegistry toolRegistry`. Keep old test-only constructors if needed, but route them to the main constructor with `null` or a provided registry.

Main Spring constructor should include:

```java
ToolRegistry toolRegistry
```

Set:

```java
this.toolRegistry = toolRegistry;
```

- [ ] **Step 5: 替换工具定义来源**

Replace `buildRequest` tools argument:

```java
toolsAvailable() ? SANDBOX_TOOLS : List.of()
```

with:

```java
toolsAvailable() ? toolRegistry.listTools(toolContext(null, null, ToolEventSink.noop())) : List.of()
```

Then refine `streamLlm` so it creates one `ToolExecutionContext` with assistant message ID and sink:

```java
ToolExecutionContext toolContext = new ToolExecutionContext(
        userId,
        conversationId,
        assistantMessage.getId(),
        null,
        (toolCallId, toolName, stream, output) -> {
            processRecorder.appendToolOutput(toolCallId, toolName, stream, defaultString(output));
            send(emitter, SseEventType.TOOL_OUTPUT,
                    new SseEventData.ToolOutput(toolCallId, stream, defaultString(output)));
        }
);
```

Pass `toolContext` into `streamOnce` and `buildRequest` so `buildRequest` can call:

```java
toolRegistry.listTools(toolContext)
```

- [ ] **Step 6: 替换 executeToolCall**

Replace the old `executeToolCall` switch body with:

```java
private String executeToolCall(
        ToolExecutionContext toolContext,
        SseEmitter emitter,
        LlmToolCall toolCall,
        ProcessEventRecorder processRecorder
) {
    ToolExecutionResult result = toolRegistry.execute(
            new ToolExecutionRequest(toolCall.id(), toolCall.name(), toolCall.arguments()),
            toolContext
    );
    processRecorder.completeTool(toolCall.id(), toolCall.name(), result.status(), result.content());
    send(emitter, SseEventType.TOOL_RESULT,
            new SseEventData.ToolResult(toolCall.id(), toolCall.name(), result.status(), result.content()));
    return result.content();
}
```

Remove the old private helper methods only after compilation proves they are unused:

```text
executeCommandTool
handleSandboxEvent
appendAndSendToolOutput
formatWriteFilePreview
formatFiles
stringArg
intArg
longArg
booleanArg
```

Keep helper methods that are still used outside tool execution.

- [ ] **Step 7: 更新 toolsAvailable**

Replace old sandbox availability check:

```java
return sandboxSessionService != null && sandboxToolService != null;
```

with:

```java
return toolRegistry != null;
```

- [ ] **Step 8: 运行 Chat 测试**

Run:

```powershell
mvn -pl agent-hub-chat -am test
```

Expected: PASS，现有 ChatService tests 和新增 registry test 全部通过。

- [ ] **Step 9: 提交**

```powershell
git add backend/agent-hub-chat backend/agent-hub-tool
git commit -m "改造聊天服务使用统一工具注册表"
```

---

### Task 4: 新增 MCP 数据模型、迁移和管理 DTO

**Files:**
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V11__init_mcp_tool_registry.sql`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/mcp/McpMigrationSmokeTest.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/entity/McpServer.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/entity/McpTool.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/entity/McpToolCallLog.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/mapper/McpServerMapper.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/mapper/McpToolMapper.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/mapper/McpToolCallLogMapper.java`
- Create DTO and command files under `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/dto` and `command`.
- Modify: `backend/agent-hub-server/pom.xml`

- [ ] **Step 1: 写迁移冒烟测试**

Create `McpMigrationSmokeTest.java`:

```java
package com.agenthub.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolCallLogMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class McpMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private McpServerMapper serverMapper;

    @Autowired
    private McpToolMapper toolMapper;

    @Autowired
    private McpToolCallLogMapper callLogMapper;

    @Test
    void shouldCreateMcpTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('mcp_server', 'mcp_tool', 'mcp_tool_call_log')
                ORDER BY table_name
                """, String.class);

        assertThat(tableNames).containsExactly("mcp_server", "mcp_tool", "mcp_tool_call_log");
    }

    @Test
    void shouldRegisterMcpMappers() {
        assertThat(serverMapper).isNotNull();
        assertThat(toolMapper).isNotNull();
        assertThat(callLogMapper).isNotNull();
    }

    @Test
    void shouldRegisterMcpMenuAndPermissions() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer menuCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE name = 'mcpServers'
                    AND path = '/ai/mcp-servers'
                    AND permission = 'mcp:server:list'
                """, Integer.class);

        assertThat(menuCount).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行迁移测试确认失败**

Run:

```powershell
mvn -pl agent-hub-server -am -Dtest=McpMigrationSmokeTest test
```

Expected: FAIL，原因是迁移、mapper 和实体不存在。

- [ ] **Step 3: 修改 server pom 依赖**

Modify `backend/agent-hub-server/pom.xml`:

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-tool</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: 新增 migration**

Create `V11__init_mcp_tool_registry.sql` with the schema from the design spec. Include menu insert:

```sql
INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT parent.id, 'mcpServers', 'MCP 服务', '/ai/mcp-servers', 'ai/mcp-servers/index', 'ApiOutlined', 'mcp:server:list', 'MENU', TRUE, 3, 1, 1
FROM sys_menu parent
WHERE parent.parent_id = 0
    AND parent.name = 'ai'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu child
        WHERE child.parent_id = parent.id
            AND child.name = 'mcpServers'
    );

INSERT INTO sys_role_menu (role_id, menu_id, created_by, updated_by)
SELECT sr.id, sm.id, 1, 1
FROM sys_role sr
JOIN sys_menu sm ON sm.name = 'mcpServers'
WHERE sr.code = 'ADMIN'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_role_menu srm
        WHERE srm.role_id = sr.id
            AND srm.menu_id = sm.id
    );
```

Also insert button permissions if the project stores buttons as menus. Use `menu_type = 'BUTTON'`, parent `mcpServers`, names:

```text
mcpServerCreate
mcpServerUpdate
mcpServerDelete
mcpServerTest
mcpToolSync
mcpToolUpdate
mcpCallList
```

with permissions from the spec.

- [ ] **Step 5: 新增 entity 和 mapper**

Create entity classes with `@TableName` and `@TableId(type = IdType.AUTO)`. Use fields exactly matching table columns in camelCase:

```java
private Long id;
private String serverKey;
private String name;
private String description;
private String endpointUrl;
private String transportType;
private String protocolVersion;
private String authType;
private String authTokenCipher;
private String extraHeaders;
private Integer connectTimeoutSeconds;
private Integer requestTimeoutSeconds;
private Boolean enabled;
private String lastSyncStatus;
private String lastSyncMessage;
private OffsetDateTime lastSyncedAt;
private Integer sort;
```

Mapper classes:

```java
package com.agenthub.tool.mcp.mapper;

import com.agenthub.tool.mcp.entity.McpServer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface McpServerMapper extends BaseMapper<McpServer> {
}
```

Repeat for `McpTool` and `McpToolCallLog`.

- [ ] **Step 6: 新增 DTO 和 command records**

Create records:

```text
CreateMcpServerCommand
UpdateMcpServerCommand
McpServerItem
McpToolItem
McpToolCallLogItem
McpConnectionTestResult
McpToolSyncResult
```

`McpServerItem` must expose `hasAuthToken` instead of `authTokenCipher`.

- [ ] **Step 7: 运行迁移测试确认通过**

Run:

```powershell
mvn -pl agent-hub-server -am -Dtest=McpMigrationSmokeTest test
```

Expected: PASS，MCP 表、Mapper、菜单权限都可用。

- [ ] **Step 8: 提交**

```powershell
git add backend/pom.xml backend/agent-hub-tool backend/agent-hub-server
git commit -m "初始化 MCP 工具注册数据模型"
```

---

### Task 5: 实现 McpClient Streamable HTTP

**Files:**
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/client/McpClient.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/client/HttpMcpClient.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/client/McpInitializeResult.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/client/McpToolDescriptor.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/client/McpToolCallResult.java`
- Test: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/client/HttpMcpClientTest.java`

- [ ] **Step 1: 写 McpClient HTTP JSON 测试**

Create `HttpMcpClientTest.java` with a fake `HttpClient` pattern like existing `HttpSandboxClientTest`. Required tests:

```java
@Test
void shouldSendInitializeWithMcpHeaders() throws Exception {
    // response body contains {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"demo","version":"1.0.0"}}}
    // assert request method POST
    // assert Accept contains application/json and text/event-stream
    // assert MCP-Protocol-Version is 2025-11-25
    // assert result.capabilities contains tools
}

@Test
void shouldListToolsFromJsonResponse() throws Exception {
    // response body contains result.tools[0].name = "search"
    // assert descriptor name, description and inputSchema
}

@Test
void shouldCallToolFromJsonResponse() throws Exception {
    // response body contains result.content [{"type":"text","text":"ok"}]
    // assert result.isError false and text "ok"
}
```

- [ ] **Step 2: 写 McpClient SSE 测试**

Add:

```java
@Test
void shouldReadFinalJsonRpcResultFromSseResponse() throws Exception {
    // response content-type text/event-stream
    // body:
    // event: message
    // data: {"jsonrpc":"2.0","id":"call_1","result":{"content":[{"type":"text","text":"ok"}]}}
    //
    // assert callTool returns "ok"
}

@Test
void shouldThrowReadableExceptionForJsonRpcError() throws Exception {
    // response body contains {"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"method not found"}}
    // assert exception message contains method not found
}
```

- [ ] **Step 3: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-tool -am -Dtest=HttpMcpClientTest test
```

Expected: FAIL，MCP client classes 不存在。

- [ ] **Step 4: 实现 client 接口和 records**

Create `McpClient.java`:

```java
package com.agenthub.tool.mcp.client;

import com.agenthub.tool.mcp.entity.McpServer;
import java.util.List;
import java.util.Map;

public interface McpClient {

    McpInitializeResult initialize(McpServer server);

    List<McpToolDescriptor> listTools(McpServer server);

    McpToolCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments);
}
```

Create records:

```java
public record McpInitializeResult(String protocolVersion, Map<String, Object> capabilities, Map<String, Object> serverInfo) {
}
```

```java
public record McpToolDescriptor(String name, String displayName, String description, Map<String, Object> inputSchema) {
}
```

```java
public record McpToolCallResult(boolean error, String content) {
}
```

- [ ] **Step 5: 实现 HttpMcpClient**

Implementation requirements:

```text
- Use ObjectMapper.
- Use java.net.http.HttpClient.
- Build POST requests with Accept, Content-Type, MCP-Protocol-Version.
- For BEARER auth, add Authorization: Bearer token.
- For API_KEY_HEADER auth, read header name from extraHeaders.apiKeyHeaderName if present, default X-API-Key.
- Merge extraHeaders JSON object except dangerous headers.
- Parse application/json directly.
- Parse text/event-stream by collecting data lines and reading first JSON-RPC response with result or error.
- Throw BusinessException(ErrorCode.INTERNAL_ERROR, message) for IO, interrupted, invalid response, JSON-RPC error.
```

Use JSON-RPC body shape:

```java
Map<String, Object> body = Map.of(
        "jsonrpc", "2.0",
        "id", UUID.randomUUID().toString(),
        "method", method,
        "params", params
);
```

`initialize` params:

```java
Map.of(
        "protocolVersion", server.getProtocolVersion(),
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "Agent Hub", "version", "0.0.1")
)
```

After initialize succeeds, send notification:

```java
Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
```

- [ ] **Step 6: 运行 McpClient 测试**

Run:

```powershell
mvn -pl agent-hub-tool -am -Dtest=HttpMcpClientTest test
```

Expected: PASS，JSON 和 SSE 响应都可解析。

- [ ] **Step 7: 提交**

```powershell
git add backend/agent-hub-tool
git commit -m "实现 MCP Streamable HTTP 客户端"
```

---

### Task 6: 实现 MCP 管理服务和 Controller

**Files:**
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/service/McpServerService.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/service/impl/McpServerServiceImpl.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/controller/McpServerController.java`
- Create request records under `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/controller/request`
- Test: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/service/McpServerServiceImplTest.java`

- [ ] **Step 1: 写 Service 测试**

Create tests:

```java
@Test
void shouldCreateServerWithUniqueKey() {
    // mapper.selectCount by serverKey returns 0
    // service.create inserts McpServer
    // assert item.serverKey equals "github"
}

@Test
void shouldRejectDuplicateServerKey() {
    // mapper.selectCount returns 1
    // assert BusinessException message "MCP 服务标识已存在"
}

@Test
void shouldKeepExistingTokenWhenUpdateTokenBlank() {
    // existed.authTokenCipher = "old"
    // update command authToken blank
    // capture updateById entity, assert authTokenCipher "old"
}

@Test
void shouldTestConnectionByInitializeAndToolsCapability() {
    // mcpClient.initialize returns capabilities {"tools":{}}
    // assert result.success true
}

@Test
void shouldSyncToolsByUpsertingDescriptors() {
    // mcpClient.listTools returns one descriptor
    // mapper existing select returns null
    // verify mcpToolMapper.insert called
    // verify server lastSyncStatus SUCCESS
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-tool -am -Dtest=McpServerServiceImplTest test
```

Expected: FAIL，service 不存在。

- [ ] **Step 3: 实现 McpServerService**

Interface methods:

```java
List<McpServerItem> list();
McpServerItem create(CreateMcpServerCommand command);
McpServerItem update(Long serverId, UpdateMcpServerCommand command);
void delete(Long serverId);
McpConnectionTestResult testConnection(Long serverId);
McpToolSyncResult syncTools(Long serverId);
List<McpToolItem> tools(Long serverId);
McpToolItem updateToolEnabled(Long toolId, boolean enabled);
List<McpToolCallLogItem> callLogs(Long serverId, Long conversationId, String status, Integer limit, Integer offset);
```

Service rules:

```text
- serverKey 创建后不可修改。
- 创建时校验 serverKey 唯一。
- 更新 token 为空则保留旧 authTokenCipher。
- extraHeaders 为空时存 "{}"。
- syncTools 成功时 lastSyncStatus = "SUCCESS"。
- syncTools 失败时 lastSyncStatus = "FAILED"，lastSyncMessage 写异常消息。
- tools 按 server_id、enabled desc、tool_name asc 排序。
- callLogs 默认 limit 100，最大 500，offset 默认 0。
```

- [ ] **Step 4: 实现 Controller**

Create `McpServerController.java`:

```java
@RestController
@RequestMapping("/api/mcp")
@PreAuthorize("hasAuthority('mcp:server:list')")
public class McpServerController {
    // GET /servers
    // POST /servers
    // PUT /servers/{serverId}
    // DELETE /servers/{serverId}
    // POST /servers/{serverId}/test
    // POST /servers/{serverId}/sync-tools
    // GET /servers/{serverId}/tools
    // PUT /tools/{toolId}/enabled
    // GET /calls
}
```

Use request records:

```java
public record CreateMcpServerRequest(
        @NotBlank String serverKey,
        @NotBlank String name,
        String description,
        @NotBlank String endpointUrl,
        String transportType,
        String protocolVersion,
        String authType,
        String authToken,
        String extraHeaders,
        Integer connectTimeoutSeconds,
        Integer requestTimeoutSeconds,
        Boolean enabled,
        Integer sort
) {
}
```

For update request omit `serverKey`.

- [ ] **Step 5: 运行 service 测试**

Run:

```powershell
mvn -pl agent-hub-tool -am -Dtest=McpServerServiceImplTest test
```

Expected: PASS。

- [ ] **Step 6: 运行 server 相关测试**

Run:

```powershell
mvn -pl agent-hub-server -am test
```

Expected: PASS，Spring 装配正常。

- [ ] **Step 7: 提交**

```powershell
git add backend/agent-hub-tool backend/agent-hub-server
git commit -m "新增 MCP 服务管理接口"
```

---

### Task 7: 实现 McpToolProvider 并接入 ToolRegistry

**Files:**
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java`
- Test: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java`

- [ ] **Step 1: 写 McpToolProvider 测试**

Create tests:

```java
@Test
void shouldExposeEnabledMcpToolsWithNamespace() {
    // server enabled github, tool enabled search_repositories
    // assert listTools returns mcp.github.search_repositories
}

@Test
void shouldSupportOnlyMcpNamespacedTools() {
    // supports("mcp.github.search") true
    // supports("sandbox.exec") false
}

@Test
void shouldExecuteRemoteToolAndWriteCallLog() {
    // request mcp.github.search_repositories
    // mcpClient.callTool(server, "search_repositories", args) returns ok
    // assert result completed ok
    // verify callLogMapper.insert status COMPLETED
}

@Test
void shouldReturnFailedResultAndLogWhenMcpClientFails() {
    // mcpClient throws BusinessException message "认证失败"
    // assert result failed "认证失败"
    // verify callLogMapper.insert status FAILED
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl agent-hub-tool -am -Dtest=McpToolProviderTest test
```

Expected: FAIL，provider 不存在。

- [ ] **Step 3: 实现 McpToolProvider**

Rules:

```text
- listTools 只返回 enabled server + enabled tool。
- 暴露名为 mcp.{serverKey}.{toolName}。
- LlmToolDefinition.description 使用 mcp_tool.description，为空时使用 displayName。
- parameters 从 input_schema JSON 解析为 Map<String,Object>。
- supports 只判断 toolName 以 "mcp." 开头且至少包含两个点。
- execute 解析 serverKey 和 remoteToolName。
- server/tool 未启用时返回 failed result。
- 调用 McpClient.callTool。
- 每次执行都写 McpToolCallLog。
```

Call log excerpts:

```java
private String excerpt(String value) {
    if (value == null) {
        return null;
    }
    return value.length() <= 8192 ? value : value.substring(0, 8192);
}
```

- [ ] **Step 4: 运行 provider 测试**

Run:

```powershell
mvn -pl agent-hub-tool -am -Dtest=McpToolProviderTest test
```

Expected: PASS。

- [ ] **Step 5: 运行 tool 模块全部测试**

Run:

```powershell
mvn -pl agent-hub-tool -am test
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add backend/agent-hub-tool
git commit -m "接入 MCP 工具提供者"
```

---

### Task 8: 后端整体验证与 Chat tool loop 回归

**Files:**
- No planned file edits. If verification fails, edit only the file named by the failing test output, rerun the same command, and commit that focused fix.

- [ ] **Step 1: 运行目标后端模块测试**

Run:

```powershell
mvn -pl agent-hub-chat,agent-hub-tool,agent-hub-server -am test
```

Expected: PASS。重点确认：

```text
agent-hub-tool tests pass
agent-hub-chat tests pass
agent-hub-server migration smoke tests pass
```

- [ ] **Step 2: 修复测试暴露的问题**

If failures appear, fix only the module touched by the failing test. Do not change test expectations to hide behavior changes. Preserve:

```text
- sandbox.exec stdout/stderr process events
- artifact 列表、预览、下载接口
- ChatService tool call max count
- context length retry behavior
- unified ApiResponse
```

- [ ] **Step 3: 重新运行同一测试命令**

Run:

```powershell
mvn -pl agent-hub-chat,agent-hub-tool,agent-hub-server -am test
```

Expected: PASS。

- [ ] **Step 4: 提交整体验证修复**

If Step 2 changed files:

```powershell
git add backend
git commit -m "修复 MCP 工具接入回归问题"
```

If Step 2 changed no files, do not create an empty commit.

---

### Task 9: 新增 admin-web MCP API、类型和路由

**Files:**
- Create: `admin-web/src/api/mcp.ts`
- Create: `admin-web/src/types/mcp.ts`
- Modify: `admin-web/src/router/route-registry.ts`
- Test: `admin-web/tests/mcp-servers.spec.ts`

- [ ] **Step 1: 写前端源代码测试**

Create `admin-web/tests/mcp-servers.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/mcp.ts?raw'
import routeSource from '../src/router/route-registry.ts?raw'
import typeSource from '../src/types/mcp.ts?raw'

describe('MCP 服务管理', () => {
  it('定义 MCP 服务和工具类型', () => {
    expect(typeSource).toContain('export interface McpServerRecord')
    expect(typeSource).toContain('serverKey: string')
    expect(typeSource).toContain('hasAuthToken: boolean')
    expect(typeSource).toContain('export interface McpToolRecord')
    expect(typeSource).toContain('exposedToolName: string')
  })

  it('提供 MCP 管理 API', () => {
    expect(apiSource).toContain(\"'/mcp/servers'\")
    expect(apiSource).toContain('testMcpServer')
    expect(apiSource).toContain('syncMcpServerTools')
    expect(apiSource).toContain('/mcp/tools/${toolId}/enabled')
    expect(apiSource).toContain(\"'/mcp/calls'\")
  })

  it('注册 MCP 服务路由', () => {
    expect(routeSource).toContain(\"path: '/ai/mcp-servers'\")
    expect(routeSource).toContain(\"name: 'ai-mcp-servers'\")
    expect(routeSource).toContain(\"permission: 'mcp:server:list'\")
    expect(routeSource).toContain(\"component: () => import('../views/McpServersView.vue')\")
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
npm test -- mcp-servers.spec.ts
```

Workdir: `admin-web`

Expected: FAIL，API、类型、页面路由不存在。

- [ ] **Step 3: 新增 MCP 类型**

Create `admin-web/src/types/mcp.ts`:

```ts
export interface McpServerRecord {
  serverId: number
  serverKey: string
  name: string
  description?: string | null
  endpointUrl: string
  transportType: string
  protocolVersion: string
  authType: string
  hasAuthToken: boolean
  extraHeaders?: string | null
  connectTimeoutSeconds: number
  requestTimeoutSeconds: number
  enabled: boolean
  lastSyncStatus?: string | null
  lastSyncMessage?: string | null
  lastSyncedAt?: string | null
  toolCount: number
  sort: number
}

export interface McpServerFormPayload {
  serverKey?: string
  name: string
  description?: string
  endpointUrl: string
  transportType: string
  protocolVersion: string
  authType: string
  authToken?: string
  extraHeaders?: string
  connectTimeoutSeconds: number
  requestTimeoutSeconds: number
  enabled: boolean
  sort: number
}

export interface McpToolRecord {
  toolId: number
  serverId: number
  serverKey: string
  toolName: string
  exposedToolName: string
  displayName: string
  description?: string | null
  inputSchema: string
  enabled: boolean
  lastSeenAt: string
}

export interface McpConnectionTestResult {
  success: boolean
  protocolVersion?: string | null
  serverName?: string | null
  message: string
}

export interface McpToolSyncResult {
  success: boolean
  syncedCount: number
  message: string
}

export interface McpToolCallLogRecord {
  callId: number
  serverKey: string
  exposedToolName: string
  remoteToolName: string
  conversationId?: number | null
  userId?: number | null
  status: string
  errorMessage?: string | null
  durationMs?: number | null
  startedAt: string
}
```

- [ ] **Step 4: 新增 MCP API**

Create `admin-web/src/api/mcp.ts`:

```ts
import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  McpConnectionTestResult,
  McpServerFormPayload,
  McpServerRecord,
  McpToolCallLogRecord,
  McpToolRecord,
  McpToolSyncResult
} from '../types/mcp'

export async function fetchMcpServers() {
  const response = await http.get<ApiResponse<McpServerRecord[]>>('/mcp/servers')
  return response.data.data
}

export async function createMcpServer(payload: McpServerFormPayload) {
  const response = await http.post<ApiResponse<McpServerRecord>>('/mcp/servers', payload)
  return response.data.data
}

export async function updateMcpServer(serverId: number, payload: Omit<McpServerFormPayload, 'serverKey'>) {
  const response = await http.put<ApiResponse<McpServerRecord>>(`/mcp/servers/${serverId}`, payload)
  return response.data.data
}

export async function deleteMcpServer(serverId: number) {
  await http.delete(`/mcp/servers/${serverId}`)
}

export async function testMcpServer(serverId: number) {
  const response = await http.post<ApiResponse<McpConnectionTestResult>>(`/mcp/servers/${serverId}/test`)
  return response.data.data
}

export async function syncMcpServerTools(serverId: number) {
  const response = await http.post<ApiResponse<McpToolSyncResult>>(`/mcp/servers/${serverId}/sync-tools`)
  return response.data.data
}

export async function fetchMcpTools(serverId: number) {
  const response = await http.get<ApiResponse<McpToolRecord[]>>(`/mcp/servers/${serverId}/tools`)
  return response.data.data
}

export async function updateMcpToolEnabled(toolId: number, enabled: boolean) {
  const response = await http.put<ApiResponse<McpToolRecord>>(`/mcp/tools/${toolId}/enabled`, { enabled })
  return response.data.data
}

export async function fetchMcpCallLogs(params: Record<string, string | number | undefined>) {
  const response = await http.get<ApiResponse<McpToolCallLogRecord[]>>('/mcp/calls', { params })
  return response.data.data
}
```

- [ ] **Step 5: 注册路由**

Modify `route-registry.ts`:

```ts
{
  path: '/ai/mcp-servers',
  name: 'ai-mcp-servers',
  title: 'MCP 服务',
  icon: 'api',
  permission: 'mcp:server:list',
  component: () => import('../views/McpServersView.vue')
}
```

Place after AI Models.

- [ ] **Step 6: 新增临时页面骨架**

Create `admin-web/src/views/McpServersView.vue` with minimal valid component:

```vue
<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>MCP 服务</h2>
        <p>维护外部 MCP Server、同步工具并控制启停状态。</p>
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 7: 运行前端测试**

Run:

```powershell
npm test -- mcp-servers.spec.ts
```

Workdir: `admin-web`

Expected: PASS。

- [ ] **Step 8: 提交**

```powershell
git add admin-web/src/api/mcp.ts admin-web/src/types/mcp.ts admin-web/src/router/route-registry.ts admin-web/src/views/McpServersView.vue admin-web/tests/mcp-servers.spec.ts
git commit -m "新增 MCP 服务管理前端入口"
```

---

### Task 10: 实现 admin-web MCP 服务管理页面

**Files:**
- Modify: `admin-web/src/views/McpServersView.vue`
- Modify: `admin-web/tests/mcp-servers.spec.ts`

- [ ] **Step 1: 扩展前端页面测试**

Add assertions:

```ts
import viewSource from '../src/views/McpServersView.vue?raw'

it('页面提供测试、同步工具和工具列表操作', () => {
  expect(viewSource).toContain('testMcpServer')
  expect(viewSource).toContain('syncMcpServerTools')
  expect(viewSource).toContain('fetchMcpTools')
  expect(viewSource).toContain(\"default: () => '测试'\")
  expect(viewSource).toContain(\"default: () => '同步工具'\")
  expect(viewSource).toContain(\"default: () => '工具列表'\")
})

it('认证 Token 使用密码输入并展示工具暴露名', () => {
  expect(viewSource).toContain('type=\"password\"')
  expect(viewSource).toContain('show-password-on=\"click\"')
  expect(viewSource).toContain('exposedToolName')
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
npm test -- mcp-servers.spec.ts
```

Workdir: `admin-web`

Expected: FAIL，页面功能未实现。

- [ ] **Step 3: 实现页面**

Use `AiProvidersView.vue` style. Required state:

```ts
const loading = ref(false)
const showModal = ref(false)
const showToolModal = ref(false)
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const syncStatusFilter = ref<string | null>(null)
const servers = ref<McpServerRecord[]>([])
const tools = ref<McpToolRecord[]>([])
const editingServer = ref<McpServerRecord | null>(null)
const selectedServer = ref<McpServerRecord | null>(null)
const testingServerIds = ref<Set<number>>(new Set())
const syncingServerIds = ref<Set<number>>(new Set())
```

Required form defaults:

```ts
const formValue = reactive<McpServerFormPayload>({
  serverKey: '',
  name: '',
  description: '',
  endpointUrl: '',
  transportType: 'STREAMABLE_HTTP',
  protocolVersion: '2025-11-25',
  authType: 'NONE',
  authToken: '',
  extraHeaders: '{}',
  connectTimeoutSeconds: 10,
  requestTimeoutSeconds: 60,
  enabled: true,
  sort: 0
})
```

Required auth options:

```ts
const authTypeOptions: SelectOption[] = [
  { label: '无需认证', value: 'NONE' },
  { label: 'Bearer Token', value: 'BEARER' },
  { label: 'API Key Header', value: 'API_KEY_HEADER' }
]
```

Required operations:

```ts
async function handleTest(row: McpServerRecord) {
  testingServerIds.value = new Set(testingServerIds.value).add(row.serverId)
  try {
    const result = await testMcpServer(row.serverId)
    result.success ? message.success(result.message) : message.error(result.message)
  } finally {
    const next = new Set(testingServerIds.value)
    next.delete(row.serverId)
    testingServerIds.value = next
  }
}

async function handleSync(row: McpServerRecord) {
  syncingServerIds.value = new Set(syncingServerIds.value).add(row.serverId)
  try {
    const result = await syncMcpServerTools(row.serverId)
    result.success ? message.success(result.message) : message.error(result.message)
    await loadData()
  } finally {
    const next = new Set(syncingServerIds.value)
    next.delete(row.serverId)
    syncingServerIds.value = next
  }
}

async function openTools(row: McpServerRecord) {
  selectedServer.value = row
  tools.value = await fetchMcpTools(row.serverId)
  showToolModal.value = true
}
```

Required table operations:

```text
测试
同步工具
工具列表
编辑
删除
```

Tool modal table must show:

```text
toolName
exposedToolName
description
enabled
lastSeenAt
```

- [ ] **Step 4: 运行前端测试**

Run:

```powershell
npm test -- mcp-servers.spec.ts
```

Workdir: `admin-web`

Expected: PASS。

- [ ] **Step 5: 运行 admin-web 全量测试**

Run:

```powershell
npm test
```

Workdir: `admin-web`

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add admin-web/src/views/McpServersView.vue admin-web/tests/mcp-servers.spec.ts
git commit -m "完善 MCP 服务管理页面"
```

---

### Task 11: 最终验证和清理

**Files:**
- No planned file edits.

- [ ] **Step 1: 运行后端目标测试**

Run:

```powershell
mvn -pl agent-hub-chat,agent-hub-tool,agent-hub-server -am test
```

Workdir: `backend`

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 运行前端测试**

Run:

```powershell
npm test
```

Workdir: `admin-web`

Expected: all test files pass。

- [ ] **Step 3: 检查工作区**

Run:

```powershell
git status --short
```

Expected: no output。

- [ ] **Step 4: 总结实现状态**

Report:

```text
- 后端统一工具注册层完成
- sandbox 工具已迁移到 BuiltinSandboxToolProvider
- MCP Streamable HTTP tools/list 和 tools/call 完成
- MCP 管理端页面完成
- 验证命令和结果
```

If `git status --short` has output, inspect it and either commit intentional changes or explain why they remain uncommitted.
