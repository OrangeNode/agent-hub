# Agent Hub

企业级 AI Agent 平台，采用单仓库结构：

- `admin-web`：管理端前端
- `user-web`：用户端前端
- `backend`：Java 后端 Maven 多模块工程

## 技术栈

- 管理端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios
- 用户端前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios、SSE 流式聊天入口
- 后端：Spring Boot 4、JDK 21、Maven、Spring Security、JWT、PostgreSQL、Redis、SSE

## 依赖服务

项目依赖的基础服务部署文件位于 `docker/README.md`。

当前提供：

- PostgreSQL 16
- Redis 7.2

快速启动方式：

```powershell
cd docker
docker compose up -d
```

如需修改数据库名、用户名、密码或 Redis 密码，请先调整：

- `docker/env/postgres.env`
- `docker/env/redis.env`

## 运行验证

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
