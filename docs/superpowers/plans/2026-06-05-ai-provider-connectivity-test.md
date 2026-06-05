# AI Provider Connectivity Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在供应商管理中增加已保存供应商配置的连通性测试能力。

**Architecture:** 后端在 `agent-hub-ai` 中新增测试结果 DTO 和可注入的连通性测试器，供应商服务负责读取配置并委托测试器。前端在供应商列表操作列增加按钮，通过现有 `admin-web/src/api/ai.ts` 调用统一响应接口并展示中文提示。

**Tech Stack:** Java 21、Spring Web、MyBatis-Plus、JUnit 5、Mockito、Vue 3、Naive UI、Vitest。

---

### Task 1: 后端服务接口与测试

**Files:**
- Modify: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/provider/ProviderServiceImplTest.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/service/AiProviderService.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/service/impl/AiProviderServiceImpl.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/dto/AiProviderConnectionTestResult.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/service/AiProviderConnectivityTester.java`

- [ ] 写失败测试：`shouldTestProviderConnectionWithSavedConfig`，断言服务读取供应商并调用测试器。
- [ ] 运行 `mvn -pl agent-hub-ai test -Dtest=ProviderServiceImplTest#shouldTestProviderConnectionWithSavedConfig`，期望因缺少类型或方法失败。
- [ ] 新增 DTO、接口和服务方法，构造器注入测试器。
- [ ] 再次运行同一测试，期望通过。

### Task 2: 后端 HTTP 探测实现与控制器

**Files:**
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/service/impl/HttpAiProviderConnectivityTester.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/controller/AiProviderController.java`

- [ ] 写失败测试：验证测试器对 OpenAI 兼容协议构造 `/v1/models` 请求并识别 2xx。
- [ ] 实现 `HttpAiProviderConnectivityTester`，使用 `HttpClient` 发起 GET 请求，设置协议对应认证头和 15 秒超时。
- [ ] 控制器新增 `POST /{providerId}/test-connection`。
- [ ] 运行后端相关测试，期望通过。

### Task 3: 前端 API、类型与页面按钮

**Files:**
- Modify: `admin-web/src/types/ai.ts`
- Modify: `admin-web/src/api/ai.ts`
- Modify: `admin-web/src/views/AiProvidersView.vue`
- Create: `admin-web/tests/ai-provider-connection-test.spec.ts`

- [ ] 写失败的 Vitest 静态测试，断言新增类型、API 方法和测试按钮文案。
- [ ] 新增 `AiProviderConnectionTestResult` 类型和 `testAiProviderConnection` API。
- [ ] 在操作列加入“测试”按钮，点击时调用接口并按 `success` 展示消息。
- [ ] 运行前端测试，期望通过。

### Task 4: 验证

**Files:**
- No file changes.

- [ ] 运行 `mvn -pl agent-hub-ai test`。
- [ ] 运行 `npm --prefix admin-web test -- ai-provider-connection-test.spec.ts`。
- [ ] 运行 `npm --prefix admin-web type-check`。
