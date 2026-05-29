# Agent 平台初始化实现计划

> **给代理工作者：** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal / 目标：** 生成一个可编译、可构建、边界清晰的企业级 AI Agent 平台初始骨架，包含 `admin-web`、`user-web` 和 Maven 多模块 `backend`。

**Architecture / 架构：** 仓库采用单仓库三应用结构。后端按 `common/security/system/ai/agent/chat/server` 拆分 Maven 模块，统一响应、错误码和业务异常放在 `agent-hub-common`，全局异常处理和安全异常响应装配放在 `agent-hub-server`。两个前端分别作为独立 Vite 应用，复用接口风格但不抽公共包。

**Tech Stack / 技术栈：** Spring Boot 4.0.6、JDK 21、Maven、Spring Security、JWT、PostgreSQL 配置入口、Redis 配置入口、Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios。

---

## 范围检查

本计划实现初始化骨架和最小闭环，不实现完整业务 CRUD，不接入真实外部大模型供应商，不定义 SSE 事件类型、事件协议和流内错误事件格式。

SSE 本次只保留 `/api/chat/stream` 接口入口和前端连接封装。流开始前的 HTTP 错误响应使用统一结构；流开始后的事件细节在后续独立设计中处理。

## 文件结构

创建以下目录和文件：

```text
Agent-Hub/
  .gitignore
  README.md
  AGENTS.md
  docs/superpowers/specs/2026-05-29-agent-platform-design.md
  docs/superpowers/plans/2026-05-29-agent-platform-initialization.md
  backend/
    pom.xml
    agent-hub-common/
    agent-hub-security/
    agent-hub-system/
    agent-hub-ai/
    agent-hub-agent/
    agent-hub-chat/
    agent-hub-server/
  admin-web/
  user-web/
```

后端关键包名统一使用 `com.agenthub`：

```text
com.agenthub.common
com.agenthub.security
com.agenthub.system
com.agenthub.ai
com.agenthub.agent
com.agenthub.chat
com.agenthub.server
```

## 任务 1：根目录工程约定

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\.gitignore`
- Create: `C:\Users\Orange\Documents\Agent-Hub\README.md`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\AGENTS.md`

- [ ] **Step 1: 写入根目录说明**

创建 `README.md`，内容：

```markdown
# Agent Hub

企业级 AI Agent 平台，采用单仓库结构：

- `admin-web`：管理端前端
- `user-web`：用户端前端
- `backend`：Java 后端 Maven 多模块工程

## 技术栈

- 管理端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios
- 用户端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios、SSE 流式聊天入口
- 后端：Spring Boot 4、JDK 21、Maven、Spring Security、JWT、PostgreSQL、Redis、SSE

## 运行验证

```powershell
cd backend
mvn test

cd ..\admin-web
npm install
npm run type-check
npm run build

cd ..\user-web
npm install
npm run type-check
npm run build
```
```

- [ ] **Step 2: 写入忽略规则**

创建 `.gitignore`，内容：

```gitignore
.idea/
.vscode/
.DS_Store
Thumbs.db

target/
*.class

node_modules/
dist/
.vite/

*.log
.env
.env.local
.env.*.local
```

- [ ] **Step 3: 检查项目记忆**

Run: `Get-Content -Encoding UTF8 AGENTS.md`

Expected: 输出包含“项目内所有文档”和“后端统一规范”。

- [ ] **Step 4: 提交根目录约定**

```powershell
git add .gitignore README.md AGENTS.md
git commit -m "docs: add root project conventions"
```

## 任务 2：后端 Maven 多模块骨架

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-security\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-agent\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-chat\pom.xml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\pom.xml`

- [ ] **Step 1: 创建父工程 POM**

`backend/pom.xml` 使用 `spring-boot-starter-parent` 版本 `4.0.6`，`java.version` 为 `21`，模块列表包含七个子模块：

```xml
<modules>
  <module>agent-hub-common</module>
  <module>agent-hub-security</module>
  <module>agent-hub-system</module>
  <module>agent-hub-ai</module>
  <module>agent-hub-agent</module>
  <module>agent-hub-chat</module>
  <module>agent-hub-server</module>
</modules>
```

依赖版本由 Spring Boot 父工程托管，显式声明 `jjwt.version` 为 `0.12.6`。

- [ ] **Step 2: 创建子模块 POM**

每个子模块的 `artifactId` 与目录名一致。依赖关系：

```text
agent-hub-common: spring-boot-starter-validation, spring-boot-starter-test
agent-hub-security: common, spring-boot-starter-security, spring-boot-starter-web, jjwt-api, jjwt-impl, jjwt-jackson, spring-boot-starter-test
agent-hub-system: common, spring-boot-starter-test
agent-hub-ai: common, reactor-core, spring-boot-starter-test
agent-hub-agent: common, agent-hub-ai, reactor-core, spring-boot-starter-test
agent-hub-chat: common, agent-hub-ai, agent-hub-agent, reactor-core, spring-boot-starter-test
agent-hub-server: common, security, system, ai, agent, chat, spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-validation, spring-boot-starter-data-redis, postgresql, spring-boot-starter-test
```

- [ ] **Step 3: 运行编译红灯**

Run: `cd backend; mvn test`

Expected: FAIL，原因是各模块还没有 Java 源码和启动类，`agent-hub-server` 尚未提供 Spring Boot 入口。

- [ ] **Step 4: 创建最小包目录**

为每个模块创建 `src/main/java` 和 `src/test/java` 下的包目录：

```text
agent-hub-common/src/main/java/com/agenthub/common
agent-hub-security/src/main/java/com/agenthub/security
agent-hub-system/src/main/java/com/agenthub/system
agent-hub-ai/src/main/java/com/agenthub/ai
agent-hub-agent/src/main/java/com/agenthub/agent
agent-hub-chat/src/main/java/com/agenthub/chat
agent-hub-server/src/main/java/com/agenthub/server
```

- [ ] **Step 5: 提交 Maven 骨架**

```powershell
git add backend
git commit -m "build: add backend maven modules"
```

## 任务 3：统一响应、错误码和全局异常基础

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\src\main\java\com\agenthub\common\api\ApiResponse.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\src\main\java\com\agenthub\common\api\ErrorCode.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\src\main\java\com\agenthub\common\exception\BusinessException.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\src\main\java\com\agenthub\common\exception\ValidationError.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\src\test\java\com\agenthub\common\api\ApiResponseTest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-common\src\test\java\com\agenthub\common\exception\BusinessExceptionTest.java`

- [ ] **Step 1: 写失败测试**

`ApiResponseTest.java`：

```java
package com.agenthub.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void successShouldUseZeroCodeAndDefaultMessage() {
        ApiResponse<String> response = ApiResponse.success("ok-data");

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isEqualTo("ok-data");
    }

    @Test
    void failureShouldUseErrorCodeMessage() {
        ApiResponse<Void> response = ApiResponse.failure(ErrorCode.UNAUTHORIZED);

        assertThat(response.code()).isEqualTo(401);
        assertThat(response.message()).isEqualTo("未登录或登录已过期");
        assertThat(response.data()).isNull();
    }
}
```

`BusinessExceptionTest.java`：

```java
package com.agenthub.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void shouldExposeErrorCodeAndChineseMessage() {
        BusinessException exception = new BusinessException(ErrorCode.BAD_REQUEST, "请求参数不正确");

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(exception.getMessage()).isEqualTo("请求参数不正确");
    }
}
```

- [ ] **Step 2: 运行红灯**

Run: `cd backend; mvn -pl agent-hub-common test`

Expected: FAIL，原因是 `ApiResponse`、`ErrorCode`、`BusinessException` 尚不存在。

- [ ] **Step 3: 实现最小统一响应模型**

`ErrorCode` 至少包含：

```java
SUCCESS(0, "ok")
BAD_REQUEST(400, "请求参数不正确")
UNAUTHORIZED(401, "未登录或登录已过期")
FORBIDDEN(403, "没有访问权限")
NOT_FOUND(404, "资源不存在")
INTERNAL_ERROR(500, "系统繁忙，请稍后重试")
```

`ApiResponse<T>` 使用 Java record，并提供：

```java
public static <T> ApiResponse<T> success(T data)
public static ApiResponse<Void> success()
public static ApiResponse<Void> failure(ErrorCode errorCode)
public static ApiResponse<Void> failure(int code, String message)
```

`BusinessException` 继承 `RuntimeException`，保存 `ErrorCode errorCode`。

`ValidationError` 使用 record，字段为 `field`、`message`。

- [ ] **Step 4: 运行绿灯**

Run: `cd backend; mvn -pl agent-hub-common test`

Expected: PASS，`ApiResponseTest` 和 `BusinessExceptionTest` 全部通过。

- [ ] **Step 5: 提交统一响应基础**

```powershell
git add backend/agent-hub-common
git commit -m "feat: add unified response model"
```

## 任务 4：JWT、安全主体和认证基础

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-security\src\main\java\com\agenthub\security\jwt\JwtProperties.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-security\src\main\java\com\agenthub\security\jwt\JwtService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-security\src\main\java\com\agenthub\security\principal\LoginUser.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-security\src\test\java\com\agenthub\security\jwt\JwtServiceTest.java`

- [ ] **Step 1: 写失败测试**

`JwtServiceTest.java`：

```java
package com.agenthub.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.security.principal.LoginUser;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void shouldCreateAndParseToken() {
        JwtProperties properties = new JwtProperties(
                "agent-hub-test-secret-agent-hub-test-secret",
                Duration.ofHours(2)
        );
        JwtService jwtService = new JwtService(properties);
        LoginUser loginUser = new LoginUser(
                1L,
                "admin",
                List.of("ADMIN"),
                List.of("system:user:list", "agent:manage")
        );

        String token = jwtService.createToken(loginUser);
        LoginUser parsed = jwtService.parseToken(token);

        assertThat(parsed.userId()).isEqualTo(1L);
        assertThat(parsed.username()).isEqualTo("admin");
        assertThat(parsed.roles()).containsExactly("ADMIN");
        assertThat(parsed.permissions()).containsExactly("system:user:list", "agent:manage");
    }
}
```

- [ ] **Step 2: 运行红灯**

Run: `cd backend; mvn -pl agent-hub-security -am test`

Expected: FAIL，原因是 `JwtService`、`JwtProperties`、`LoginUser` 尚不存在。

- [ ] **Step 3: 实现 JWT 最小能力**

`LoginUser` 使用 record：

```java
public record LoginUser(Long userId, String username, List<String> roles, List<String> permissions) {}
```

`JwtProperties` 使用 record：

```java
public record JwtProperties(String secret, Duration ttl) {}
```

`JwtService` 使用 JJWT 创建和解析令牌，claims 包含 `userId`、`username`、`roles`、`permissions`，解析失败时抛出 `BusinessException(ErrorCode.UNAUTHORIZED)`。

- [ ] **Step 4: 运行绿灯**

Run: `cd backend; mvn -pl agent-hub-security -am test`

Expected: PASS，JWT 测试通过。

- [ ] **Step 5: 提交认证基础**

```powershell
git add backend/agent-hub-security
git commit -m "feat: add jwt security foundation"
```

## 任务 5：系统模块启动数据与登录服务

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\auth\AuthService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\auth\LoginCommand.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\auth\LoginResult.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\auth\InMemoryAuthService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\user\UserProfile.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\menu\MenuItem.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\test\java\com\agenthub\system\auth\InMemoryAuthServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.agenthub.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class InMemoryAuthServiceTest {

    @Test
    void shouldLoginWithBootstrapAdmin() {
        AuthService authService = new InMemoryAuthService();

        LoginResult result = authService.login(new LoginCommand("admin", "admin123"));

        assertThat(result.profile().username()).isEqualTo("admin");
        assertThat(result.profile().roles()).contains("ADMIN");
        assertThat(result.profile().permissions()).contains("system:user:list", "agent:manage");
    }

    @Test
    void shouldRejectWrongPassword() {
        AuthService authService = new InMemoryAuthService();

        assertThatThrownBy(() -> authService.login(new LoginCommand("admin", "bad")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }
}
```

- [ ] **Step 2: 运行红灯**

Run: `cd backend; mvn -pl agent-hub-system -am test`

Expected: FAIL，原因是系统认证类尚不存在。

- [ ] **Step 3: 实现启动登录数据**

`InMemoryAuthService` 提供两个启动用户：

```text
admin / admin123 / ADMIN / 全部初始管理权限
user / user123 / USER / chat:conversation:list, chat:conversation:create
```

权限至少包含：

```text
system:user:list
system:role:list
system:permission:list
system:menu:list
ai:provider:list
ai:model:list
mcp:server:list
skill:list
agent:manage
chat:conversation:list
chat:conversation:create
```

- [ ] **Step 4: 运行绿灯**

Run: `cd backend; mvn -pl agent-hub-system -am test`

Expected: PASS，登录服务测试通过。

- [ ] **Step 5: 提交系统启动数据**

```powershell
git add backend/agent-hub-system
git commit -m "feat: add bootstrap system auth"
```

## 任务 6：AI、Agent 和 Chat 核心接口

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\src\main\java\com\agenthub\ai\llm\LlmClient.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\src\main\java\com\agenthub\ai\llm\ChatRequest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\src\main\java\com\agenthub\ai\llm\ChatMessage.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\src\main\java\com\agenthub\ai\llm\ChatChunk.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\src\main\java\com\agenthub\ai\llm\MockLlmClient.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-agent\src\main\java\com\agenthub\agent\runtime\AgentRuntime.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-agent\src\main\java\com\agenthub\agent\runtime\RuntimeEvent.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-agent\src\main\java\com\agenthub\agent\tool\ToolRegistry.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-agent\src\main\java\com\agenthub\agent\tool\ToolLoop.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-chat\src\main\java\com\agenthub\chat\ChatStreamService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-ai\src\test\java\com\agenthub\ai\llm\MockLlmClientTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MockLlmClientTest {

    @Test
    void shouldStreamDeterministicChunks() {
        LlmClient client = new MockLlmClient();
        ChatRequest request = new ChatRequest(
                "mock",
                List.of(new ChatMessage("user", "你好"))
        );

        List<ChatChunk> chunks = client.stream(request).collectList().block();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().map(ChatChunk::content).toList())
                .contains("你好，我是 Agent Hub 的模拟助手。");
    }
}
```

- [ ] **Step 2: 运行红灯**

Run: `cd backend; mvn -pl agent-hub-ai -am test`

Expected: FAIL，原因是 LLM Client 类尚不存在。

- [ ] **Step 3: 实现自研 LLM Client 最小接口**

`LlmClient`：

```java
public interface LlmClient {
    ChatMessage complete(ChatRequest request);
    reactor.core.publisher.Flux<ChatChunk> stream(ChatRequest request);
}
```

`ChatRequest`、`ChatMessage`、`ChatChunk` 都使用 record。

`MockLlmClient.stream` 返回确定性片段，至少包含：

```text
你好，我是 Agent Hub 的模拟助手。
当前响应来自自研 LLM Client 抽象。
```

- [ ] **Step 4: 实现 Agent 和 Chat 接口骨架**

创建 `AgentRuntime`、`RuntimeEvent`、`ToolRegistry`、`ToolLoop`、`ChatStreamService`，只定义清晰接口和最小默认实现，不定义 SSE 事件协议。

- [ ] **Step 5: 运行绿灯**

Run: `cd backend; mvn -pl agent-hub-ai,agent-hub-agent,agent-hub-chat -am test`

Expected: PASS，AI、Agent、Chat 模块测试通过。

- [ ] **Step 6: 提交 AI/Agent/Chat 接口**

```powershell
git add backend/agent-hub-ai backend/agent-hub-agent backend/agent-hub-chat
git commit -m "feat: add ai agent chat contracts"
```

## 任务 7：后端启动模块、Controller 和全局异常处理

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\AgentHubApplication.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\config\SecurityConfig.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\exception\GlobalExceptionHandler.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\auth\AuthController.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\system\SystemController.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\ai\AiController.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\agent\AgentController.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\chat\ChatController.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\resources\application.yml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\test\java\com\agenthub\server\exception\GlobalExceptionHandlerTest.java`

- [ ] **Step 1: 写失败测试**

`GlobalExceptionHandlerTest.java` 使用 `@WebMvcTest` 验证业务异常统一响应：

```java
package com.agenthub.server.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc;

    GlobalExceptionHandlerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldConvertBusinessExceptionToUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/business-error"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不正确"));
    }

    @Controller
    static class TestController {
        @GetMapping("/test/business-error")
        @ResponseBody
        String businessError() {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求参数不正确");
        }
    }
}
```

- [ ] **Step 2: 运行红灯**

Run: `cd backend; mvn -pl agent-hub-server -am test`

Expected: FAIL，原因是启动模块和异常处理类尚不存在。

- [ ] **Step 3: 实现启动入口和统一异常处理**

`AgentHubApplication` 使用 `@SpringBootApplication(scanBasePackages = "com.agenthub")`。

`GlobalExceptionHandler` 使用 `@RestControllerAdvice`，处理：

```text
BusinessException -> 对应错误码 HTTP 状态和 ApiResponse.failure
MethodArgumentNotValidException -> 400 和中文校验提示
AuthenticationException -> 401
AccessDeniedException -> 403
Exception -> 500
```

- [ ] **Step 4: 实现基础 Controller**

Controller 全部返回 `ApiResponse<T>`。初始接口：

```text
POST /api/auth/login
GET /api/auth/me
GET /api/system/users
GET /api/system/roles
GET /api/system/permissions
GET /api/system/menus
GET /api/ai/providers
GET /api/ai/models
GET /api/mcp/servers
GET /api/skills
GET /api/agents
GET /api/chat/conversations
GET /api/chat/stream
```

`/api/chat/stream` 使用框架提供的 SSE 输出能力返回模拟流，不定义自定义事件类型和事件内容结构。

- [ ] **Step 5: 运行绿灯**

Run: `cd backend; mvn test`

Expected: PASS，后端所有模块测试通过。

- [ ] **Step 6: 提交后端启动模块**

```powershell
git add backend/agent-hub-server
git commit -m "feat: add backend server module"
```

## 任务 8：管理端前端骨架

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\package.json`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\index.html`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\vite.config.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\tsconfig.json`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\main.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\App.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\router\index.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\stores\auth.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\http.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\auth.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\layouts\AdminLayout.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\LoginView.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\DashboardView.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\ModuleTableView.vue`

- [ ] **Step 1: 创建 package 和 TypeScript 配置**

`package.json` scripts：

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "type-check": "vue-tsc --noEmit"
  }
}
```

依赖包含 `@vitejs/plugin-vue`、`vite`、`typescript`、`vue-tsc`、`vue`、`vue-router`、`pinia`、`axios`、`naive-ui`。

- [ ] **Step 2: 写前端红灯**

先创建 `src/router/index.ts`，引用尚未创建的页面：

```ts
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import ModuleTableView from '../views/ModuleTableView.vue'
```

Run: `cd admin-web; npm install; npm run type-check`

Expected: FAIL，原因是 `*.vue` 页面文件尚不存在。

- [ ] **Step 3: 实现管理端页面骨架**

创建登录页、主布局、仪表盘和通用表格模块页。路由覆盖：

```text
/login
/
/system/users
/system/roles
/system/permissions
/system/menus
/ai/providers
/ai/models
/mcp/servers
/skills
/agents
```

页面文案使用中文，按钮使用 Naive UI 组件，布局保持企业后台风格。

- [ ] **Step 4: 运行绿灯**

Run: `cd admin-web; npm run type-check; npm run build`

Expected: PASS，管理端类型检查和构建通过。

- [ ] **Step 5: 提交管理端骨架**

```powershell
git add admin-web
git commit -m "feat: add admin web skeleton"
```

## 任务 9：用户端前端骨架

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\package.json`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\index.html`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\vite.config.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\tsconfig.json`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\main.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\App.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\router\index.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\stores\auth.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\api\http.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\api\chat.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\layouts\UserLayout.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\views\LoginView.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\views\HomeView.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\user-web\src\views\ChatView.vue`

- [ ] **Step 1: 创建 package 和 TypeScript 配置**

与 `admin-web` 保持一致，但应用名称为 `agent-hub-user-web`。

- [ ] **Step 2: 写前端红灯**

先创建 `src/api/chat.ts`，导出：

```ts
export function openChatStream(prompt: string, token: string): EventSource {
  const url = new URL('/api/chat/stream', window.location.origin)
  url.searchParams.set('prompt', prompt)
  url.searchParams.set('token', token)
  return new EventSource(url)
}
```

再创建 `ChatView.vue` 引用 `openChatStream`，但先不创建 `src/api/chat.ts` 的依赖类型辅助。

Run: `cd user-web; npm install; npm run type-check`

Expected: FAIL，原因是聊天页面引用的类型或页面文件尚未补齐。

- [ ] **Step 3: 实现用户端页面骨架**

创建登录页、首页、聊天页和用户布局。聊天页包含：

```text
会话列表区域
消息列表区域
输入框
发送按钮
SSE 连接封装调用
```

聊天页只处理默认消息追加，不定义自定义 SSE 事件类型、事件协议和流内错误事件格式。

- [ ] **Step 4: 运行绿灯**

Run: `cd user-web; npm run type-check; npm run build`

Expected: PASS，用户端类型检查和构建通过。

- [ ] **Step 5: 提交用户端骨架**

```powershell
git add user-web
git commit -m "feat: add user web skeleton"
```

## 任务 10：最终联调与文档校验

**Files:**
- Modify: `C:\Users\Orange\Documents\Agent-Hub\README.md`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\docs\superpowers\plans\2026-05-29-agent-platform-initialization.md`

- [ ] **Step 1: 后端完整验证**

Run:

```powershell
cd backend
mvn test
```

Expected: PASS，全部 Maven 模块测试通过。

- [ ] **Step 2: 管理端完整验证**

Run:

```powershell
cd admin-web
npm run type-check
npm run build
```

Expected: PASS，管理端类型检查和构建通过。

- [ ] **Step 3: 用户端完整验证**

Run:

```powershell
cd user-web
npm run type-check
npm run build
```

Expected: PASS，用户端类型检查和构建通过。

- [ ] **Step 4: 文档语言和范围检查**

Run:

```powershell
rg -n "待补充|稍后实现|未完成|补齐|错误事件|事件协议|流内错误" AGENTS.md README.md docs
```

Expected: 只允许出现“后续单独设计”或“不纳入本次初始化设计”的 SSE 排除说明，不允许出现未完成实现标记。

- [ ] **Step 5: 提交最终初始化结果**

```powershell
git add .
git commit -m "feat: initialize agent hub platform"
```

## 自查结果

规格覆盖：

- 管理端 `admin-web`：任务 8 覆盖。
- 用户端 `user-web`：任务 9 覆盖。
- 后端 Maven 多模块：任务 2 覆盖。
- JWT、Spring Security 和登录认证：任务 4、任务 5、任务 7 覆盖。
- 全局异常和统一响应：任务 3、任务 7 覆盖。
- AI 供应商、模型、MCP、Skill、Agent 管理接口入口：任务 7 覆盖。
- 自研 LLM Client、Agent Runtime、Tool Loop：任务 6 覆盖。
- 会话和 SSE 流式聊天入口：任务 6、任务 7、任务 9 覆盖。
- SSE 事件协议不纳入当前设计：范围检查、任务 7、任务 9、任务 10 均明确排除。

未完成标记扫描：

- 本计划未使用常见未完成标记作为实现任务。
- “后续单独设计”仅用于明确排除 SSE 事件协议，符合当前用户要求。

类型一致性：

- 统一响应类固定为 `ApiResponse`。
- 错误码类固定为 `ErrorCode`。
- 业务异常固定为 `BusinessException`。
- JWT 服务固定为 `JwtService`。
- 登录主体固定为 `LoginUser`。
- LLM 接口固定为 `LlmClient`、`ChatRequest`、`ChatMessage`、`ChatChunk`。
