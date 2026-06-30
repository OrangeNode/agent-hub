# Agent Hub

Agent Hub 是一个单仓多模块的企业级 AI Agent 平台，面向管理端配置、用户端会话、AI 能力接入、MCP/Skill 工具扩展、沙箱执行和流式聊天等场景。

项目当前包含管理端前端 `admin-web`、用户端前端 `user-web`、Java 后端 `backend`、Go 沙箱服务 `sandbox-service`，以及本地依赖编排目录 `docker`。

## 项目结构

| 路径 | 说明 |
| --- | --- |
| `admin-web` | 管理端前端，提供用户、角色、菜单、AI 供应商、AI 模型、MCP、Skill、Agent、沙箱监控和操作日志等管理能力。 |
| `user-web` | 用户端前端，提供登录、Agent 绑定、SSE 流式聊天、图片附件、Markdown 渲染、工具过程和任务进度展示等能力。 |
| `backend` | Java 后端 Maven 多模块工程，承载认证授权、业务接口、数据迁移、AI 调用、Agent Runtime、Tool Loop、SSE 和存储能力。 |
| `sandbox-service` | Go 实现的内部沙箱服务，生产形态通过 Firecracker microVM 执行命令并同步产物。 |
| `docker` | 本地开发依赖编排，包含 PostgreSQL、Redis、MinIO、初始化脚本和后端环境变量样例。 |
| `docs/superpowers` | 功能设计说明和实现计划归档。 |

## 技术栈

- 管理端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios、Vitest。
- 用户端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios、SSE、Markdown-it、KaTeX、Highlight.js、DOMPurify、Vitest。
- 后端：JDK 21、Spring Boot 4.0.6、Maven、Spring Security、JWT、MyBatis-Plus、Flyway、PostgreSQL、Redis、MinIO。
- 沙箱服务：Go、Firecracker microVM、HTTP/SSE、MinIO 产物同步。
- 本地依赖：Docker Compose、PostgreSQL 16、Redis 7.2、MinIO。

## 核心能力

- 用户、角色、菜单、权限与 JWT 登录认证。
- Spring Security 权限控制和统一安全异常响应。
- AI 供应商、AI 模型和 OpenAI Compatible LLM Client。
- MCP 服务、工具注册、工具绑定和工具调用日志。
- Skill 包上传、导入、文件清单、Agent 绑定和运行时同步。
- Agent 配置、Agent Runtime、任务规划和自研 Tool Loop。
- 会话、消息、上下文构建、SSE 流式聊天和任务进度事件。
- 图片附件、聊天产物、沙箱产物和 MinIO 对象存储。
- 沙箱会话、命令执行、资源观测、warm pool 和管理端审计。

## 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+ 与 npm
- Docker 与 Docker Compose
- Go 1.22+（仅开发或构建 `sandbox-service` 时需要）
- Linux/KVM/Firecracker（仅生产形态沙箱服务需要）

## 快速启动

### 1. 启动基础依赖

```powershell
cd docker
docker compose up -d
docker compose ps
```

默认端口：

| 服务 | 端口 |
| --- | --- |
| PostgreSQL | `5432` |
| Redis | `6379` |
| MinIO API | `9000` |
| MinIO Console | `9001` |

依赖服务、默认账号和环境变量详见 [docker/README.md](docker/README.md)。

### 2. 启动后端

如果使用 `docker/env/*.env` 中的默认依赖配置，至少需要让后端连接到对应的 PostgreSQL 和 Redis 账号：

```powershell
cd backend
$env:SPRING_DATASOURCE_USERNAME = "agent_hub"
$env:SPRING_DATASOURCE_PASSWORD = "agent_hub_123456"
$env:SPRING_DATA_REDIS_PASSWORD = "agent_hub_123456"
$env:AGENT_HUB_JWT_SECRET = "agent-hub-prod-secret-change-me-please-32chars"
mvn -pl agent-hub-server -am spring-boot:run
```

后端默认监听：

```text
http://127.0.0.1:8080
```

更多可配置项在 [backend/agent-hub-server/src/main/resources/application.yml](backend/agent-hub-server/src/main/resources/application.yml) 中维护，也可以参考 [docker/env/backend.env](docker/env/backend.env)。

### 3. 启动管理端

```powershell
cd admin-web
npm install
npm run dev
```

管理端默认访问地址：

```text
http://127.0.0.1:5173
```

`admin-web` 的 Vite 代理会把 `/api` 转发到 `http://127.0.0.1:8080`。

### 4. 启动用户端

```powershell
cd user-web
npm install
npm run dev
```

用户端默认访问地址：

```text
http://127.0.0.1:5174
```

`user-web` 的 Vite 代理会把 `/api` 转发到 `http://127.0.0.1:8080`。

## 默认账号

后端启动时会通过 Flyway 初始化默认数据；使用全新数据库或全新 Docker 数据卷时，可使用以下账号登录：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `admin123` |
| 普通用户 | `user` | `user123` |

默认密码只适合本地开发和联调，部署前必须替换。

## 后端模块说明

| 模块 | 职责 |
| --- | --- |
| `agent-hub-common` | 统一响应、错误码、业务异常、通用时间处理等公共能力。 |
| `agent-hub-security` | JWT、登录用户主体和安全认证支撑。 |
| `agent-hub-system` | 登录认证、用户、角色、菜单、权限和操作日志。 |
| `agent-hub-ai` | AI 供应商、AI 模型、OpenAI Compatible LLM Client 和模型调用协议。 |
| `agent-hub-agent` | Agent 配置、Agent 选项和运行时配置。 |
| `agent-hub-skill` | Skill 包上传、解析、导入、绑定和运行时包构建。 |
| `agent-hub-tool` | MCP 服务、MCP 工具、Agent 工具绑定和工具运行时抽象。 |
| `agent-hub-sandbox` | Java 后端到沙箱服务的客户端、会话、执行、产物和管理能力。 |
| `agent-hub-storage` | MinIO 对象存储、图片附件、预签名 URL 和清理任务。 |
| `agent-hub-sse` | SSE 事件类型、事件数据和发送器抽象。 |
| `agent-hub-chat` | 会话、消息、上下文、流式聊天、任务进度和聊天产物。 |
| `agent-hub-server` | Spring Boot 启动入口、全局配置、安全装配、异常处理和 Flyway 迁移。 |

## 数据库迁移

后端启动时会自动执行 Flyway 迁移脚本：

```text
backend/agent-hub-server/src/main/resources/db/migration
```

当前迁移覆盖 RBAC、AI 配置、聊天会话、图片摘要、Agent Runtime、沙箱 Tool Loop、操作日志、MCP 工具注册、Skill 库、工具绑定和任务进度等表结构。

`docker/initdb/01-init-rbac.sql` 只会在 PostgreSQL 全新数据卷首次启动时自动执行；已有数据卷不会重复执行 Docker 初始化脚本，应以后端 Flyway 迁移为准。

## 沙箱服务

`sandbox-service` 是后端 Tool Loop 的内部执行服务。生产形态为：

```text
Java 后端 -> sandbox-service -> Firecracker microVM -> sandbox-agent -> /workspace
```

沙箱服务的 rootfs 构建、Firecracker kernel 准备、启动脚本、管理接口和资源回收机制详见 [sandbox-service/README.md](sandbox-service/README.md)。

本地只做普通后端和前端联调时，可以先不启动生产形态沙箱；涉及工具执行、沙箱产物或管理端沙箱监控时，需要准备对应服务，并通过 `AGENT_HUB_SANDBOX_BASE_URL` 和 `AGENT_HUB_SANDBOX_INTERNAL_TOKEN` 接入。

## 验证命令

```powershell
cd docker
docker compose config

cd ..\backend
mvn test

cd ..\admin-web
npm install
npm run type-check
npm run test
npm run build

cd ..\user-web
npm install
npm run type-check
npm run test
npm run build

cd ..\sandbox-service
go test ./...
```

可以按修改范围选择验证命令；涉及跨模块接口、认证、SSE、存储或沙箱链路时，应优先执行后端测试并补充对应前端测试。

## 文档入口

- [AGENTS.md](AGENTS.md)：项目语言、文档、接口返回和后端统一规范。
- [docker/README.md](docker/README.md)：本地依赖服务、环境变量、默认端口和 MinIO 初始化说明。
- [sandbox-service/README.md](sandbox-service/README.md)：沙箱服务、Firecracker、rootfs、启动脚本和管理接口说明。
- [docs/superpowers/specs](docs/superpowers/specs)：功能设计说明。
- [docs/superpowers/plans](docs/superpowers/plans)：实现计划与验证记录。

## 开发约定

- 项目文档、设计说明、接口说明、变更记录和交付说明默认使用中文。
- 前端展示文案、后端错误信息和接口返回消息默认使用中文。
- REST 接口响应必须统一封装，Controller 不直接返回零散 `Map`、字符串或不一致结构。
- 统一响应、错误码、业务异常和参数校验异常模型放在 `agent-hub-common`。
- 全局异常拦截和安全异常响应装配放在 `agent-hub-server`。
- SSE 流式聊天保留独立事件协议，流内错误事件不纳入普通 REST 统一响应格式。
