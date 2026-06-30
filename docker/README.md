# Docker 依赖与后端启动说明

当前 `docker/` 目录用于部署 Agent Hub 运行所需的基础依赖服务，并提供一份后端环境变量样例。

当前包含：

- PostgreSQL 16
- Redis 7.2
- MinIO
- PostgreSQL 初始化脚本
- 后端环境变量样例

## 目录结构

```text
docker
├── docker-compose.yml
├── env
│   ├── backend.env
│   ├── minio.env
│   ├── postgres.env
│   └── redis.env
├── initdb
│   └── 01-init-schema.sql
└── README.md
```

## 使用方式

1. 根据实际环境修改：
   - `env/postgres.env`
   - `env/redis.env`
   - `env/minio.env`
   - `env/backend.env`
2. 在服务器或本机进入 `docker/` 目录
3. 启动 PostgreSQL、Redis 与 MinIO

```bash
docker compose up -d
```

4. 查看服务状态

```bash
docker compose ps
```

5. 停止服务

```bash
docker compose down
```

## 默认端口

- PostgreSQL：`5432`
- Redis：`6379`
- MinIO API：`9000`
- MinIO Console：`9001`

## PostgreSQL 初始化

`docker-compose.yml` 已挂载：

- `./initdb:/docker-entrypoint-initdb.d`

当 PostgreSQL 使用全新数据卷首次启动时，会自动执行：

- [01-init-schema.sql](/D:/project/Agent-Hub/docker/initdb/01-init-schema.sql)

该脚本是当前项目的完整数据库初始化 SQL，与后端 Flyway 初始化脚本 `backend/agent-hub-server/src/main/resources/db/migration/V1__init_schema.sql` 保持同一套结构。脚本会初始化：

- RBAC 用户、角色、菜单、权限和默认账号
- AI 供应商、AI 模型、聊天会话、聊天消息、图片附件和会话摘要
- Agent 配置、Skill 库、MCP 工具注册、Agent 工具绑定和任务进度
- 沙箱会话、命令执行、沙箱产物、操作日志和管理端菜单授权

并写入默认种子数据：

- 管理员账号：`admin` / `admin123`
- 普通用户账号：`user` / `user123`

如果你的 PostgreSQL 容器已经启动且数据卷已存在，这个脚本不会自动补跑。当前项目按“数据库可清空重建”的方式维护最终初始化 SQL；如需套用最新结构，请先备份数据，再清空或重建数据库/数据卷。

## MinIO 图片存储

[minio.env](/D:/project/Agent-Hub/docker/env/minio.env) 提供了聊天图片对象存储所需的默认变量。

- API 端口：`9000`
- Console 端口：`9001`
- 聊天图片 bucket 名称来自 `MINIO_BUCKET_CHAT_IMAGES`，默认值为 `agent-hub-chat-images`
- 沙箱产物 bucket 名称来自 `MINIO_BUCKET_SANDBOX_ARTIFACTS`，默认值为 `agent-hub-sandbox-artifacts`
- `minio-init` 会在 MinIO 健康后创建这两个 bucket，并为聊天图片 bucket 配置 `MINIO_IMAGE_EXPIRE_DAYS` 指定的生命周期规则，默认 7 天后过期清理
- bucket 默认不公开读
- 前端上传图片和模型读取图片都通过后端签发的短期预签名 URL 完成
- 云模型读取图片时，后端 `agent-hub.storage.public-endpoint` 必须配置为模型可访问的公网或内网地址

## 后端环境变量

[backend.env](/D:/project/Agent-Hub/docker/env/backend.env) 提供了后端启动所需的默认变量：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATA_REDIS_HOST`
- `SPRING_DATA_REDIS_PORT`
- `SPRING_DATA_REDIS_PASSWORD`
- `AGENT_HUB_JWT_SECRET`
- `AGENT_HUB_JWT_TTL_HOURS`

如果后端和数据库不在同一台机器，请把 `127.0.0.1` 改成实际服务地址。

## 注意事项

- 默认密码仅用于初始化环境，部署前应替换
- `AGENT_HUB_JWT_SECRET` 必须改成正式密钥
- 如果服务端口已被占用，请调整 `docker-compose.yml` 中的映射
