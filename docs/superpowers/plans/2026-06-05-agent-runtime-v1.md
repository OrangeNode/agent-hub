# Agent Runtime V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现第一版 Agent Runtime：后台可配置 Agent，用户端可选择启用 Agent，聊天时按 Agent 配置注入系统提示词并选择默认模型。

**Architecture:** 新增 `agent-hub-agent` 后端模块承载 Agent 配置与运行配置解析，`agent-hub-chat` 依赖该模块并在构建上下文前解析 Agent。第一版 Runtime 不执行 Tool Loop、Sandbox 或 MCP，只输出普通聊天 SSE。

**Tech Stack:** Spring Boot 4、MyBatis-Plus、Flyway、Vue 3、TypeScript、Naive UI。

---

### Task 1: 后端 Agent 模块与服务

**Files:**
- Create: `backend/agent-hub-agent/pom.xml`
- Create: `backend/agent-hub-agent/src/main/java/com/agenthub/agent/**`
- Modify: `backend/pom.xml`
- Modify: `backend/agent-hub-server/pom.xml`

- [ ] **Step 1: Write failing service tests**

验证 Agent 列表、启用列表、按 key 解析 Runtime 配置、停用 Agent 拒绝使用。

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -pl agent-hub-agent test`
Expected: 编译失败或测试失败，因为模块和类型尚不存在。

- [ ] **Step 3: Implement minimal module**

创建 `AgentConfig`、`AgentItem`、`AgentRuntimeConfig`、`AgentService`、`AgentServiceImpl`、`AgentConfigMapper` 和管理端 Controller。

- [ ] **Step 4: Run module tests**

Run: `mvn -pl agent-hub-agent test`
Expected: PASS。

### Task 2: 数据库迁移与菜单

**Files:**
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V6__init_agent_runtime.sql`

- [ ] **Step 1: Write migration smoke test**

验证 `agent_config` 表、默认 Agent 数据、后台菜单和 ADMIN 授权存在。

- [ ] **Step 2: Run server migration test to verify failure**

Run: `mvn -pl agent-hub-server -Dtest=AgentRuntimeMigrationSmokeTest test`
Expected: FAIL，因为迁移尚不存在。

- [ ] **Step 3: Add migration**

新增 `agent_config` 表，初始化 4 个 Agent，并新增 `/agent/list` 菜单授权。

- [ ] **Step 4: Run migration test**

Run: `mvn -pl agent-hub-server -Dtest=AgentRuntimeMigrationSmokeTest test`
Expected: PASS。

### Task 3: ChatService 接入 Agent Runtime

**Files:**
- Modify: `backend/agent-hub-chat/pom.xml`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/ConversationContextBuilder.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: Write failing chat tests**

验证选择 Agent 时第一条 LLM 消息是 Agent 系统提示词；Agent 默认模型在未显式选择模型时生效；停用 Agent 返回业务错误。

- [ ] **Step 2: Run chat test to verify failure**

Run: `mvn -pl agent-hub-chat test`
Expected: FAIL，因为 ChatService 尚未依赖 AgentService。

- [ ] **Step 3: Implement chat integration**

解析 `agentKey`，用 Agent 默认模型覆盖空 `modelId`，把系统提示词传入 `ConversationContextBuilder`。

- [ ] **Step 4: Run chat tests**

Run: `mvn -pl agent-hub-chat test`
Expected: PASS。

### Task 4: 管理端 Agent 页面

**Files:**
- Create: `admin-web/src/api/agent.ts`
- Create: `admin-web/src/types/agent.ts`
- Create: `admin-web/src/views/AgentsView.vue`
- Modify: `admin-web/src/router/route-registry.ts`
- Modify: `admin-web/src/layouts/AdminLayout.vue` if breadcrumb section mapping requires Agent section support

- [ ] **Step 1: Add type-check failing references**

新增路由和页面引用，运行类型检查应因 API/types 缺失失败。

- [ ] **Step 2: Implement API, types and page**

沿用供应商/模型页面模式，实现 Agent 列表、新增、编辑、启停、删除、默认模型选择和系统提示词大文本框。

- [ ] **Step 3: Run admin checks**

Run: `npm run type-check`
Expected: PASS。

### Task 5: 用户端动态 Agent

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/src/views/ChatView.vue`

- [ ] **Step 1: Replace hard-coded Agent source**

调用 `/api/agents/available` 加载启用 Agent，保留“通用 Agent”作为本地空 Agent。

- [ ] **Step 2: Send stable agent key**

发送消息时传 `agentKey`，不再把 Agent 中文名称作为 key。

- [ ] **Step 3: Run user checks**

Run: `npm run type-check`
Expected: PASS。

### Task 6: Full verification

- [ ] **Step 1: Run backend tests**

Run: `mvn test`
Expected: PASS。

- [ ] **Step 2: Run admin build checks**

Run: `npm run type-check`
Run: `npm run build`
Expected: PASS。

- [ ] **Step 3: Run user build checks**

Run: `npm run type-check`
Run: `npm run build`
Expected: PASS。
