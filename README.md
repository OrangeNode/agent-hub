# Agent Hub

企业级 AI Agent 平台，采用单仓多模块结构：

- `admin-web`：管理端前端
- `user-web`：用户端前端
- `backend`：Java 后端 Maven 多模块工程

## 技术栈

- 管理端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios
- 用户端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios、SSE
- 后端：Spring Boot 4、JDK 21、Maven、Spring Security、JWT、PostgreSQL、Redis

## Docker 与依赖

依赖服务和后端环境变量说明位于 `docker/README.md`。当前 `docker/` 已提供：

- PostgreSQL 16
- Redis 7.2
- PostgreSQL 初始化脚本
- 后端环境变量样例

快速启动：

```powershell
cd docker
docker compose up -d
```

如需修改数据库名、用户名、密码、Redis 密码或 JWT 密钥，请先调整：

- `docker/env/postgres.env`
- `docker/env/redis.env`
- `docker/env/backend.env`

## RBAC 数据库化说明

当前后端 RBAC 已切换为数据库实现，运行链路不再装配 `InMemory*Service`。

后端启动时会自动执行 Flyway 迁移脚本，初始化以下 RBAC 表：

- `sys_user`
- `sys_role`
- `sys_menu`
- `sys_user_role`
- `sys_role_menu`

默认初始化账号：

- 管理员账号：`admin`
- 管理员密码：`admin123`
- 普通用户账号：`user`
- 普通用户密码：`user123`

补充说明：

- 用户密码使用 `BCrypt` 存储
- JWT 只保存 `userId`、`username`、`roles`
- 权限集合由服务端基于数据库动态装配

## 运行与验证

```powershell
cd docker
docker compose config

cd ..\backend
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
