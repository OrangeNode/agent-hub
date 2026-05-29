# 依赖服务部署说明

本目录用于部署 Agent Hub 运行所需的基础依赖服务，当前包含：

- PostgreSQL 16
- Redis 7.2

## 目录结构

```text
docker
├─ docker-compose.yml
├─ env
│  ├─ postgres.env
│  └─ redis.env
└─ README.md
```

## 使用方式

1. 根据实际环境修改 `env/postgres.env` 与 `env/redis.env`
2. 在服务器或本机进入当前目录
3. 执行启动命令

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

## 默认数据卷

- `postgres-data`：PostgreSQL 数据目录
- `redis-data`：Redis 数据目录

## 配置说明

### PostgreSQL

`env/postgres.env` 默认提供以下变量：

- `POSTGRES_DB`：数据库名称
- `POSTGRES_USER`：数据库用户名
- `POSTGRES_PASSWORD`：数据库密码
- `TZ`：时区

### Redis

`env/redis.env` 默认提供以下变量：

- `REDIS_PASSWORD`：Redis 密码
- `TZ`：时区

## 注意事项

- 当前默认密码仅用于本地开发或初始化环境，请在部署前替换为正式密码
- `docker-compose.yml` 预留了 `./initdb` 挂载目录，后续如果需要初始化 SQL，可直接在本目录新增 `initdb` 文件夹并放入脚本
- 如果服务器已占用 `5432` 或 `6379` 端口，请先调整 `docker-compose.yml` 中的端口映射
