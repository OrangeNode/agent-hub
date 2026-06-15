# Skill 库轻量化重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建不含版本管理、激活和 sandbox 校验的轻量 Skill 库；默认 Agent 可在 `/chat` 多选 Skill，业务 Agent 使用后台绑定 Skill；聊天运行时通过渐进披露和 `skill.read` 使用完整 `SKILL.md`。

**Architecture:** 新增 `agent-hub-skill` 模块负责上传导入、同名覆盖/重命名、删除、Agent 绑定、运行时 Skill 摘要 prompt 构建和 `skill.read` 工具。Java 后端直接读取对象存储中的 zip 并提取 `SKILL.md`；admin-web 提供 Skill 库和业务 Agent 绑定入口；user-web 的 `/chat` 只在默认 Agent 下提供 Skill 多选，业务 Agent 隐藏前台选择。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis Plus、Flyway、JUnit 5、Mockito、Vue 3、TypeScript、Naive UI、Vitest、Playwright。

---

## File Map

- Create: `backend/agent-hub-skill/pom.xml`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/**`
- Create: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/**`
- Modify: `backend/pom.xml`
- Modify: `backend/agent-hub-server/pom.xml`
- Modify: `backend/agent-hub-chat/pom.xml`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/AgentHubApplication.java`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_library.sql`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- Modify: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java`
- Create: `admin-web/src/types/skill.ts`
- Create: `admin-web/src/api/skill.ts`
- Create: `admin-web/src/views/SkillsView.vue`
- Modify: `admin-web/src/views/AgentsView.vue`
- Modify: `admin-web/src/router/route-registry.ts`
- Create: `admin-web/tests/skills.spec.ts`
- Create: `admin-web/tests/agent-skill-binding.spec.ts`
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/src/views/ChatView.vue`
- Create: `user-web/tests/chat-skill-runtime.spec.ts`

## Task 1: 后端 Skill 解析器

**Files:**
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/SkillArchiveParser.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/ParsedSkillArchive.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/ParsedSkillFile.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/importing/SkillArchiveParserTest.java`

- [ ] Step 1: 写失败测试，覆盖 frontmatter `name`、一级标题回退、嵌套 `SKILL.md` 和缺失 `SKILL.md`。
- [ ] Step 2: 运行 `mvn -pl agent-hub-skill -Dtest=SkillArchiveParserTest test`，期望编译失败或测试失败。
- [ ] Step 3: 实现 zip 解析器，使用 `ZipInputStream` 读取条目，选择根目录 `SKILL.md` 或最短路径的 `SKILL.md`，解析 `name` 和 `description`。
- [ ] Step 4: 再次运行同一测试，期望通过。

## Task 2: 后端导入、覆盖、重命名和删除

**Files:**
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/*.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/mapper/*.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/*.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/impl/SkillPackageServiceImpl.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/controller/SkillController.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/service/SkillPackageServiceImplTest.java`

- [ ] Step 1: 写失败测试，覆盖确认导入无冲突时直接创建 Skill。
- [ ] Step 2: 写失败测试，覆盖同名确认返回 `duplicate=true` 且不覆盖已有 Skill。
- [ ] Step 3: 写失败测试，覆盖 `OVERWRITE` 替换已有 Skill 内容。
- [ ] Step 4: 写失败测试，覆盖 `RENAME` 使用新名称创建 Skill，名称仍重复时报错。
- [ ] Step 5: 写失败测试，覆盖删除 Skill 同时删除文件清单和绑定。
- [ ] Step 6: 运行 `mvn -pl agent-hub-skill -Dtest=SkillPackageServiceImplTest test`，期望失败。
- [ ] Step 7: 实现实体、DTO、Mapper、Service 和 Controller。
- [ ] Step 8: 再次运行同一测试，期望通过。

## Task 3: 数据库迁移和模块接入

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/agent-hub-server/pom.xml`
- Modify: `backend/agent-hub-chat/pom.xml`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/AgentHubApplication.java`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_library.sql`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/skill/SkillMigrationSmokeTest.java`

- [ ] Step 1: 写迁移冒烟测试，断言 `skill_package`、`skill_import`、`skill_file_manifest`、`agent_skill_binding` 存在，并且 ADMIN 拥有 Skill 菜单权限。
- [ ] Step 2: 运行 `mvn -pl agent-hub-server -Dtest=SkillMigrationSmokeTest test`，期望失败。
- [ ] Step 3: 接入 Maven module、server/chat 依赖、MapperScan 和 V12 迁移。
- [ ] Step 4: 再次运行同一测试，期望通过。

## Task 4: Agent Skill 绑定、渐进披露和 `skill.read`

**Files:**
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/AgentSkillBindingService.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/SkillRuntimeService.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/tool/SkillToolProvider.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/controller/AgentSkillController.java`
- Modify: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/service/AgentSkillBindingServiceImplTest.java`
- Test: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/tool/SkillToolProviderTest.java`
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] Step 1: 写失败测试，覆盖保存 Agent Skill 绑定时去重、排序和替换旧绑定。
- [ ] Step 2: 写失败测试，覆盖 `SkillRuntimeService` 为默认 Agent 使用前端 `skillIds`、为业务 Agent 忽略前端 `skillIds` 并使用绑定 Skill。
- [ ] Step 3: 写失败测试，覆盖 prompt 只包含 Skill ID、名称、说明和摘要，不完整注入 `SKILL.md`。
- [ ] Step 4: 写失败测试，覆盖 `skill.read` 只能读取当前上下文启用的 Skill。
- [ ] Step 5: 写失败测试，覆盖 `ChatServiceImpl` 把 Agent 系统提示词和 Skill 渐进披露块同时传给 LLM，并把启用 Skill IDs 传给工具上下文。
- [ ] Step 6: 运行 `mvn -pl agent-hub-chat,agent-hub-skill -am -Dtest=ChatServiceImplTest,AgentSkillBindingServiceImplTest,SkillRuntimeServiceTest,SkillToolProviderTest test`，期望失败。
- [ ] Step 7: 实现绑定服务、运行时服务、`skill.read` ToolProvider、Agent Skill Controller 和 ChatService 构造器扩展。
- [ ] Step 8: 再次运行同一测试，期望通过。

## Task 5: 管理端 Skill 库

**Files:**
- Create: `admin-web/src/types/skill.ts`
- Create: `admin-web/src/api/skill.ts`
- Create: `admin-web/src/views/SkillsView.vue`
- Modify: `admin-web/src/router/route-registry.ts`
- Test: `admin-web/tests/skills.spec.ts`

- [ ] Step 1: 写失败测试，断言类型、API、路由、上传控件、覆盖/重命名弹窗、文件清单和删除按钮存在。
- [ ] Step 2: 运行 `npm test -- skills.spec.ts`，期望失败。
- [ ] Step 3: 实现类型、API、路由和 `SkillsView.vue`。
- [ ] Step 4: 再次运行同一测试，期望通过。

## Task 6: 管理端 Agent Skill 绑定入口

**Files:**
- Modify: `admin-web/src/types/skill.ts`
- Modify: `admin-web/src/api/skill.ts`
- Modify: `admin-web/src/views/AgentsView.vue`
- Test: `admin-web/tests/agent-skill-binding.spec.ts`

- [ ] Step 1: 写失败测试，断言 Agent 列表提供 `Skill` 绑定入口，调用查询和保存绑定 API。
- [ ] Step 2: 运行 `npm test -- agent-skill-binding.spec.ts`，期望失败。
- [ ] Step 3: 在 Agent 绑定弹窗中加载 Skill 列表和当前绑定，支持新增、移除和保存。
- [ ] Step 4: 再次运行同一测试，期望通过。

## Task 7: 用户端 `/chat` Skill 选择

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/src/views/ChatView.vue`
- Create: `user-web/tests/chat-skill-runtime.spec.ts`

- [ ] Step 1: 为 user-web 写测试，mock `/api/skills/available` 和 `/api/chat/stream`，验证默认 Agent 可在 “+” 菜单多选 Skill 并随消息发送 `skillIds`。
- [ ] Step 2: 写测试验证业务 Agent 隐藏 Skill 选择，切换业务 Agent 后清空已选 Skill。
- [ ] Step 3: 运行 `npm test -- chat-skill-runtime.spec.ts`，期望通过。
- [ ] Step 4: 启动 backend、admin-web 和 user-web，本地浏览器验证 Skill 上传、同名覆盖、同名重命名、删除、业务 Agent 绑定、默认 Agent `/chat` 多选 Skill 和业务 Agent 禁止前台选择。

## Task 8: 浏览器验收

**Files:**
- Manual: backend、admin-web、user-web

- [ ] Step 1: 启动 backend、admin-web 和 user-web。
- [ ] Step 2: 浏览器验证 Skill 上传、同名覆盖、同名重命名、删除、业务 Agent 绑定。
- [ ] Step 3: 浏览器进入 `/chat`，验证默认 Agent 可通过 “+” 多选 Skill 并发送。
- [ ] Step 4: 浏览器切换业务 Agent，验证聊天框不允许选择 Skill。
- [ ] Step 5: 通过实际聊天或 mock 流验证 `skill.read` 可读取启用 Skill 的完整 `SKILL.md`，且未启用 Skill 不能读取。

## Final Verification

- [ ] 运行 `mvn -pl agent-hub-skill,agent-hub-chat,agent-hub-server -am test`。
- [ ] 运行 `npm test` 和 `npm run type-check`，工作目录 `admin-web`。
- [ ] 运行 `npm test`，工作目录 `user-web`。
- [ ] 使用浏览器完成管理端和 `/chat` 验收。
