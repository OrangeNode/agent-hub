# Docker 依赖与后端启动说明

当前 `docker/` 目录用于部署 Agent Hub 运行所需的基础依赖服务，并提供一份后端环境变量样例。

当前包含：

- PostgreSQL 16
- Redis 7.2
- PostgreSQL 初始化脚本
- 后端环境变量样例

## 目录结构

```text
docker
├── docker-compose.yml
├── env
│   ├── backend.env
│   ├── postgres.env
│   └── redis.env
├── initdb
│   └── 01-init-rbac.sql
└── README.md
```

## 使用方式

1. 根据实际环境修改：
   - `env/postgres.env`
   - `env/redis.env`
   - `env/backend.env`
2. 在服务器或本机进入 `docker/` 目录
3. 启动 PostgreSQL 与 Redis

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

## PostgreSQL 初始化

`docker-compose.yml` 已挂载：

- `./initdb:/docker-entrypoint-initdb.d`

当 PostgreSQL 使用全新数据卷首次启动时，会自动执行：

- [01-init-rbac.sql](/D:/project/Agent-Hub/docker/initdb/01-init-rbac.sql)

该脚本会初始化：

- `sys_user`
- `sys_role`
- `sys_menu`
- `sys_user_role`
- `sys_role_menu`

并写入默认种子数据：

- 管理员账号：`admin` / `admin123`
- 普通用户账号：`user` / `user123`

如果你的 PostgreSQL 容器已经启动且数据卷已存在，这个脚本不会自动补跑；这时应通过后端 Flyway 启动迁移，或手动在库里执行同内容 SQL。

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
