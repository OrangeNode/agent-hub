# Agent 平台项目初始化设计

## 目标

初始化一个企业级 AI Agent 平台单仓库项目。项目包含两个 Vue 3 前端和一个 Spring Boot 4 / JDK 21 / Maven 多模块后端。初始化结果必须具备可运行、可编译、边界清晰的基础形态，并为 RBAC、JWT 登录认证、AI 供应商管理、AI 模型管理、MCP 管理、Skill 库管理、Agent 管理、会话管理、SSE 流式聊天、自研 LLM Client、自研 Agent Runtime 和自研 Tool Loop 预留稳定扩展点。

## 范围

本次初始化交付“企业级骨架 + 最小可运行闭环”：

- `admin-web`：管理端前端，包含登录、主布局、系统管理、AI 管理、MCP、Skill 和 Agent 模块页面。
- `user-web`：用户端前端，包含登录、会话列表和 SSE 流式聊天页面。
- `backend`：Java 后端，采用 Maven 多模块结构，包含安全认证基础、核心领域模块、基础接口和模拟流式聊天链路。

第一个版本不追求所有业务模块的完整 CRUD 深度，也不接入真实外部大模型供应商。初始化阶段的重点是建立清晰的工程结构、模块边界、认证链路、接口契约和后续扩展位置。

## 总体架构

仓库采用单仓库结构：

```text
Agent-Hub/
  admin-web/
  user-web/
  backend/
  docs/
```

后端采用 Maven 多模块结构：

```text
backend/
  pom.xml
  agent-hub-common/
  agent-hub-security/
  agent-hub-system/
  agent-hub-ai/
  agent-hub-agent/
  agent-hub-chat/
  agent-hub-server/
```

模块职责：

- `agent-hub-common`：统一响应、统一错误码、业务异常模型、参数校验异常模型、常量、基础 DTO 和通用工具。
- `agent-hub-security`：JWT 服务、Spring Security 配置、认证过滤器、登录主体和权限辅助能力。
- `agent-hub-system`：用户、角色、权限、菜单和登录所需的系统服务契约。
- `agent-hub-ai`：AI 供应商、AI 模型和自研 LLM Client 抽象。
- `agent-hub-agent`：Agent 定义、MCP Server、Tool 定义、Skill 库、Agent Runtime 接口和 Tool Loop 骨架。
- `agent-hub-chat`：会话、消息、聊天编排和 SSE 流式响应契约。
- `agent-hub-server`：Spring Boot 启动模块、REST Controller、全局异常处理、安全异常响应装配、配置装配和跨模块组合。

两个前端都是独立的 Vite 应用。它们保持一致的工程习惯和接口风格，但初始化阶段不抽取前端公共包，以降低安装、运行和维护复杂度。

## 后端设计

后端技术栈为 Spring Boot 4、Java 21、Maven、Spring Security、JWT、PostgreSQL、Redis 和 SSE。不使用 Spring AI。

后端基础设施必须统一管理。`agent-hub-common` 提供 `ApiResponse`、`ErrorCode`、`BusinessException` 和参数校验错误模型；`agent-hub-server` 通过 `GlobalExceptionHandler` 统一处理业务异常、参数校验异常、认证异常、授权异常和未知异常。业务 Controller 不直接返回零散的 `Map`、字符串或不一致响应结构。

初始接口分组：

- `/api/auth/login`：用户名密码登录，返回 JWT 和基础用户信息。
- `/api/auth/me`：返回当前登录用户信息。
- `/api/system/users`：用户管理接口。
- `/api/system/roles`：角色管理接口。
- `/api/system/permissions`：权限管理接口。
- `/api/system/menus`：菜单管理接口。
- `/api/ai/providers`：AI 供应商管理接口。
- `/api/ai/models`：AI 模型管理接口。
- `/api/mcp/servers`：MCP Server 管理接口。
- `/api/skills`：Skill 库管理接口。
- `/api/agents`：Agent 管理接口。
- `/api/chat/conversations`：会话管理接口。
- `/api/chat/stream`：SSE 流式聊天接口。

初始化阶段使用内存服务提供启动数据，同时提供 PostgreSQL 和 Redis 的配置入口。这样可以保证项目创建后立刻编译运行，也能让后续持久化实现替换服务层时不影响前端接口契约。

安全模型采用无状态 JWT 认证。JWT 中包含用户标识、用户名、角色和权限。后端通过 Spring Security 过滤器解析令牌，并构建当前登录主体。权限字符串采用类似 `system:user:list`、`ai:model:list`、`agent:manage` 的格式。

自研 LLM Client 从第一版开始建立接口：

- `LlmClient`：供应商无关的大模型调用接口，支持普通响应和流式响应。
- `ChatRequest`、`ChatMessage`、`ChatChunk`：通用聊天请求、消息和流式片段 DTO。
- `MockLlmClient`：本地模拟实现，用于驱动初始 SSE 聊天链路。

自研 Agent Runtime 从第一版开始建立核心边界：

- `AgentRuntime`：负责根据 Agent 定义和用户输入执行一次运行。
- `ToolRegistry`：负责注册和解析可调用工具。
- `ToolLoop`：负责模型输出、工具调用和最终回答之间的循环。
- `RuntimeEvent`：负责表达模型片段、工具调用、工具结果和最终回答等运行事件。

## 前端设计

两个前端都使用 Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router 和 Axios。

`admin-web` 初始路由：

- `/login`
- `/`
- `/system/users`
- `/system/roles`
- `/system/permissions`
- `/system/menus`
- `/ai/providers`
- `/ai/models`
- `/mcp/servers`
- `/skills`
- `/agents`

管理端包含持久化布局、基于路由的菜单、认证状态管理、自动注入 Bearer Token 的 Axios 客户端，以及面向 CRUD 扩展的表格型模块页面。

`user-web` 初始路由：

- `/login`
- `/`
- `/chat`

用户端包含聊天工作区、会话列表面板、消息列表、输入区和连接 `/api/chat/stream` 的 SSE 客户端。流式返回时，前端会把服务端片段持续追加到当前助手消息中。

## 数据流

登录认证流程：

1. 前端向 `/api/auth/login` 提交用户名和密码。
2. 后端使用启动用户数据完成校验。
3. 后端签发包含用户标识、用户名、角色和权限的 JWT。
4. 前端将令牌保存到 Pinia 和本地存储。
5. Axios 请求拦截器自动附加 `Authorization: Bearer <token>`。
6. 后端认证过滤器校验令牌，并构建 Spring Security 登录主体。

流式聊天流程：

1. 用户在 `user-web` 输入问题。
2. 前端向 `/api/chat/stream` 建立 SSE 请求。
3. `agent-hub-chat` 调用 Agent Runtime 或 LLM Client。
4. 初始化版本通过 `MockLlmClient` 输出确定性的模拟片段。
5. 前端持续追加片段，形成流式回复体验。

管理模块流程：

1. 管理端页面通过类型化 API 客户端调用后端接口。
2. 后端 Controller 将请求转发到对应模块服务。
3. 初始化服务返回启动数据和稳定 DTO 结构。
4. 后续可以替换为数据库和 Redis 实现，而不改变前端调用契约。

## 错误处理

后端 REST 响应和异常返回由统一基础设施管理。所有普通 REST 接口使用统一结构：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

`code` 为 `0` 表示成功，非 `0` 表示失败。错误码由 `agent-hub-common` 统一定义，错误信息默认使用中文。参数校验、业务异常、认证失败、授权失败和未知异常都由 `GlobalExceptionHandler` 统一转换为结构化响应。业务模块只抛出统一业务异常或返回明确 DTO，不自行拼装异常响应。

SSE 接口遵循事件流协议，不强行包装为普通 JSON 响应。流开始前失败时返回统一 HTTP 错误响应；流开始后失败时发送统一错误事件，事件内容包含错误码、中文错误信息和可选追踪标识。

前端 Axios 拦截器负责把 HTTP 错误转换成 Naive UI 消息提示。登录过期时清理本地认证状态，并跳转回 `/login`。

## 测试与验证

初始化后的验证命令：

- 后端：在 `backend/` 下执行 `mvn test`。
- 管理端前端：在 `admin-web/` 下执行 `npm install`、`npm run type-check` 和 `npm run build`。
- 用户端前端：在 `user-web/` 下执行 `npm install`、`npm run type-check` 和 `npm run build`。

首批测试聚焦高价值边界：

- 统一响应结构和全局异常处理。
- JWT 创建和解析。
- 登录主体和权限集合构建。
- `MockLlmClient` 流式片段输出。
- Agent Runtime 事件流。
- 两个前端项目的类型检查和构建检查。

## 非目标

- 初始化阶段不使用 Spring AI。
- 初始化阶段不接入真实外部大模型供应商。
- 初始化阶段不完成完整数据库迁移体系。
- 初始化阶段不为每个管理模块实现完整 CRUD。
- 初始化阶段不拆分微服务。

## 参考资料

- Spring Boot 4 官方文档：https://docs.spring.io/spring-boot/documentation.html
- Spring Security 官方文档：https://docs.spring.io/spring-security/reference/
