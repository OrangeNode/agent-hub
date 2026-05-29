# Agent Platform Project Initialization Design

## Goal

Initialize a monorepo for an enterprise AI Agent platform with two Vue 3 frontends and one Spring Boot 4 / JDK 21 Maven multi-module backend. The initial implementation must be runnable, compile cleanly, and provide a foundation for RBAC, JWT authentication, AI provider/model management, MCP/Skill/Agent management, conversation management, SSE streaming chat, a self-owned LLM client abstraction, Agent Runtime, and Tool Loop.

## Scope

This initialization creates a production-shaped skeleton and a minimal end-to-end runtime path:

- `admin-web`: management console skeleton for login, layout, system management, AI management, MCP, Skill, and Agent modules.
- `user-web`: user console skeleton for login, conversation list, and SSE streaming chat.
- `backend`: Maven multi-module Java backend with clear module boundaries, security foundation, basic domain APIs, and a simulated streaming chat path.

The first version intentionally avoids full business CRUD depth and avoids external LLM provider integration. Those will be added module by module after the platform baseline is stable.

## Architecture

The repository is organized as a monorepo:

```text
Agent-Hub/
  admin-web/
  user-web/
  backend/
  docs/
```

The backend is a Maven multi-module application:

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

Responsibilities:

- `agent-hub-common`: shared response envelope, exceptions, constants, base DTOs, and utility code.
- `agent-hub-security`: JWT service, security configuration, authentication filter, user principal, and permission helpers.
- `agent-hub-system`: users, roles, permissions, menus, and login-facing system service contracts.
- `agent-hub-ai`: AI providers, models, and self-owned LLM client interfaces.
- `agent-hub-agent`: Agent definitions, MCP server/tool definitions, Skill library, Agent Runtime interfaces, and Tool Loop skeleton.
- `agent-hub-chat`: conversations, messages, chat orchestration, and SSE streaming contracts.
- `agent-hub-server`: executable Spring Boot application, REST controllers, configuration, and module wiring.

The frontends are independent Vite applications. They share conventions but do not introduce a shared package during initialization to keep the first baseline easy to install and run.

## Backend Design

The backend uses Spring Boot 4, Java 21, Maven, Spring Security, JWT, PostgreSQL, Redis, and SSE. It does not use Spring AI.

Initial API groups:

- `/api/auth/login`: username/password login returning JWT and basic user profile.
- `/api/auth/me`: current authenticated user info.
- `/api/system/users`: user management skeleton.
- `/api/system/roles`: role management skeleton.
- `/api/system/permissions`: permission management skeleton.
- `/api/system/menus`: menu management skeleton.
- `/api/ai/providers`: AI provider management skeleton.
- `/api/ai/models`: AI model management skeleton.
- `/api/mcp/servers`: MCP server management skeleton.
- `/api/skills`: Skill library management skeleton.
- `/api/agents`: Agent management skeleton.
- `/api/chat/conversations`: conversation management skeleton.
- `/api/chat/stream`: SSE streaming chat endpoint.

Persistence starts with simple in-memory services for bootstrapping plus PostgreSQL/Redis configuration entries. This lets the project compile and run immediately while keeping the module seams ready for JPA/MyBatis and Redis-backed implementations.

Security starts with stateless JWT authentication. Method and route-level authorization are prepared around permission strings such as `system:user:list`, `ai:model:list`, and `agent:manage`.

The LLM client is self-owned:

- `LlmClient`: provider-neutral streaming and non-streaming completion contract.
- `ChatRequest`, `ChatMessage`, and `ChatChunk`: common DTOs.
- `MockLlmClient`: initial local implementation used by the SSE chat endpoint.

The Agent Runtime is self-owned:

- `AgentRuntime`: runs an Agent against user input.
- `ToolRegistry`: resolves callable tools.
- `ToolLoop`: repeatedly decides whether to call a tool or produce final output.
- `RuntimeEvent`: emits model/tool/final events for future streaming observability.

## Frontend Design

Both frontends use Vue 3, TypeScript, Vite, Naive UI, Pinia, Vue Router, and Axios.

`admin-web` initial routes:

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

The admin UI includes a persistent shell, route-driven menu, auth store, API client with bearer-token injection, and table-style module pages ready for CRUD expansion.

`user-web` initial routes:

- `/login`
- `/`
- `/chat`

The user UI includes a chat workspace, conversation list panel, message list, prompt input, and SSE client that consumes `/api/chat/stream`.

## Data Flow

Authentication:

1. Frontend posts username/password to `/api/auth/login`.
2. Backend validates against bootstrap users.
3. Backend signs a JWT containing user id, username, roles, and permissions.
4. Frontend stores the token in Pinia/local storage.
5. Axios attaches `Authorization: Bearer <token>` to API requests.
6. Backend filter validates the token and builds the security principal.

Streaming chat:

1. User sends a prompt from `user-web`.
2. Frontend opens an SSE request to `/api/chat/stream`.
3. `agent-hub-chat` delegates to the Agent Runtime or LLM client.
4. Initial implementation streams deterministic mock chunks.
5. Frontend appends chunks to the active assistant message.

Management modules:

1. Admin pages call module APIs through typed API clients.
2. Backend controllers delegate to module services.
3. Initial services return bootstrap data and stable DTO shapes.
4. Future persistence implementations can replace services without changing frontend contracts.

## Error Handling

Backend responses use a consistent envelope:

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

Validation and security failures return structured error responses. SSE errors are emitted as stream events where possible and fall back to HTTP status errors before the stream starts.

Frontend Axios interceptors normalize HTTP errors into user-facing Naive UI notifications. Login expiry clears local auth state and routes back to `/login`.

## Testing And Verification

Initial verification commands:

- Backend: `mvn test` from `backend/`.
- Admin frontend: `npm install`, `npm run type-check`, `npm run build` from `admin-web/`.
- User frontend: `npm install`, `npm run type-check`, `npm run build` from `user-web/`.

Initial tests focus on high-value seams:

- JWT creation and parsing.
- Permission principal construction.
- Mock LLM streaming behavior.
- Agent Runtime event flow.
- Vue build/type checks for both frontends.

## Non-Goals

- No Spring AI dependency.
- No real external LLM provider calls in the initialization baseline.
- No full database migration suite in the first skeleton.
- No full CRUD implementation for every management module in the first skeleton.
- No microservice split.

## References

- Spring Boot 4 official documentation: https://docs.spring.io/spring-boot/documentation.html
- Spring Security official documentation: https://docs.spring.io/spring-security/reference/
