# Linux 统一启动脚本设计

## 背景

项目包含 Docker 基础依赖、Java 后端、管理端前端、用户端前端和 Go sandbox-service。当前启动方式分散在 `docker`、`backend`、`admin-web`、`user-web` 和 `sandbox-service/scripts` 目录中，Linux 服务器上联调时需要频繁切换目录，停止服务也缺少统一入口。

## 目标

- 在项目根目录提供统一的 Linux 启动、停止和状态查看入口。
- 默认启动 Docker 依赖、Java 后端、管理端前端和用户端前端。
- sandbox-service 保留既有脚本实现，由统一入口通过参数转调，避免复制 Firecracker、sudo 和清理逻辑。
- 运行态 PID 和日志集中到 `logs/runtime`，方便排查和清理。
- 脚本不写入服务器密码；需要 sudo 时通过临时环境变量或交互式 sudo 提供。

## 非目标

- 不替代生产环境 systemd 部署。
- 不自动清空数据库或重建 Docker volume。
- 不修改前后端业务代码。
- 不在仓库中保存服务器账号、密码或私有 token。

## 设计

根目录新增 `scripts` 目录，包含：

- `start-all.sh`：统一启动入口。默认执行 `docker compose up -d`，在 Docker Compose 支持时追加 `--wait` 等待健康检查，再启动后端、管理端和用户端；传入 `--with-sandbox` 时先转调 `sandbox-service/scripts/start-sandbox-service.sh`，并把后端 sandbox 地址默认设置为 `http://127.0.0.1:8090`。
- `stop-all.sh`：统一停止入口。按用户端、管理端、后端、sandbox、Docker 依赖的顺序停止；sandbox 仅在 PID 文件存在或传入 `--with-sandbox` 时转调既有停止脚本。
- `status-all.sh`：展示三个本地进程的 PID/日志路径、sandbox PID 状态和 Docker Compose 服务状态。
- `_runtime-common.sh`：抽取项目根目录、日志目录、PID 文件、进程检测、后台启动、停止等共享逻辑。
- `verify-runtime-scripts.sh`：轻量验证脚本，检查脚本存在、可解析，以及关键约束未被破坏。

前端使用 `npm run dev -- --host 0.0.0.0`，便于 Linux 服务器从外部访问。首次运行时如果缺少 `node_modules`，启动脚本会自动执行 `npm install`；可通过 `--no-install` 跳过。

后端启动时读取 `docker/env/backend.env`，默认补充 `SPRING_FLYWAY_ENABLED=false`，以符合当前“导入完整 SQL 后启动”的部署约定。后端仍从 `backend` 目录执行 Maven 命令，避免改变 Maven 多模块工程的相对路径行为。

## 测试策略

- 先编写 `scripts/verify-runtime-scripts.sh`，在目标脚本不存在时运行并确认失败。
- 补齐统一脚本后运行 `bash scripts/verify-runtime-scripts.sh`。
- 运行 `bash -n scripts/*.sh` 做语法检查。
- 运行 `scripts/status-all.sh` 确认未启动服务时也能正常输出状态。
