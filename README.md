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
