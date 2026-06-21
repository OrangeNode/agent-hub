# sandbox-service

`sandbox-service` 是 Agent Hub 的内部沙箱服务。生产形态为宿主机上的 Go 服务管理 Firecracker microVM，microVM 内运行 `sandbox-agent` 并暴露 HTTP/SSE 文件和命令执行接口。

## 生产链路

```text
Java 后端
  -> sandbox-service
    -> Firecracker microVM
      -> sandbox-agent
      -> /workspace
```

## 目录约定

所有沙箱服务脚本统一放在 `sandbox-service/scripts`。

默认运行和资产目录：

- `sandbox-service/rootfs/vmlinux`：Firecracker kernel 镜像。
- `sandbox-service/rootfs/rootfs.ext4`：microVM rootfs 镜像。
- `sandbox-service/bin/sandbox-service`：启动脚本编译出的服务二进制。
- `sandbox-service/logs/sandbox-service.log`：服务日志。
- `sandbox-service/run`：Firecracker socket、日志和每个 VM 的 rootfs 副本。

这些运行产物已加入 `.gitignore`。

## 一键构建 rootfs

在 KVM Linux 服务器上执行：

```bash
GO_BIN=/usr/local/go/bin/go ./sandbox-service/scripts/build-rootfs.sh
```

默认产物：

- `sandbox-service/rootfs/rootfs.ext4`
- `sandbox-service/build/agent/sandbox-agent`

默认 rootfs 临时树在 `/tmp/agent-hub-sandbox-rootfs-tree`，避免 Go module 下出现 root 权限目录影响 `go test ./...`。

rootfs 基于 Ubuntu noble minbase，预装 `bash`、`python3`/`python`、`pip`、`venv`、`openpyxl`、`python-docx`、`python-pptx`、`pypdf`、`reportlab`、`Pillow`、`lxml`、`nodejs`、`npm`、`dotnet-sdk-8.0`、`pandoc`、`poppler-utils`、`libreoffice-writer`、`libreoffice-impress`、`libreoffice-calc`、CJK/常用字体、`git`、`curl`、`jq`、`zip`、`unzip`、`tar`、`build-essential`、`iproute2`、`procps` 等运行和常用构建依赖。

## 准备 kernel

如果服务器已有可用 Firecracker kernel，可直接复制：

```bash
mkdir -p sandbox-service/rootfs
cp /path/to/vmlinux sandbox-service/rootfs/vmlinux
```

也可以按 Firecracker 官方 getting-started 的 CI kernel 规则下载：

```bash
./sandbox-service/scripts/download-firecracker-kernel.sh
mkdir -p sandbox-service/rootfs
cp /var/lib/firecracker/vmlinux.bin sandbox-service/rootfs/vmlinux
```

## 启动和停止

启动前脚本会检查 `sandbox-service/rootfs/rootfs.ext4` 和 `sandbox-service/rootfs/vmlinux` 是否存在，通过后再编译并启动 `sandbox-service`。

启动脚本会自动读取 `sandbox-service/.env`。仓库提供了 `sandbox-service/.env.example`，本地开发可直接复制后按需修改；如果需要指定其他配置文件，可通过 `SANDBOX_ENV_FILE=/path/to/file` 覆盖。

```bash
./sandbox-service/scripts/start-sandbox-service.sh
```

停止服务：

```bash
./sandbox-service/scripts/stop-sandbox-service.sh
```

非交互调试时可以临时传入 sudo 密码：

```bash
export SANDBOX_SUDO_PASSWORD=123123
```

## 关键配置

- `SANDBOX_VM_MODE=firecracker`：默认生产模式。
- `SANDBOX_FIRECRACKER_BIN`：Firecracker 二进制路径，默认 `/usr/local/bin/firecracker`。
- `SANDBOX_KERNEL_IMAGE`：Firecracker kernel 镜像路径，默认 `sandbox-service/rootfs/vmlinux`。
- `SANDBOX_ROOTFS_IMAGE`：rootfs ext4 镜像路径，默认 `sandbox-service/rootfs/rootfs.ext4`。
- `SANDBOX_FIRECRACKER_RUN_DIR`：每个 VM 的 socket、日志和 rootfs 副本目录，默认 `sandbox-service/run`，不要放在空间较小的 `/var/run` tmpfs。
- `SANDBOX_GUEST_NETWORK_CIDR`：为 microVM 分配 `/30` 子网的地址池，默认 `172.16.200.0/24`。
- `SANDBOX_TAP_NAME_PREFIX`：tap 设备名前缀，默认 `tap-ah`。
- `SANDBOX_OBJECT_ENDPOINT`：沙箱产物对象存储地址，默认 `127.0.0.1:9000`。
- `SANDBOX_OBJECT_ACCESS_KEY` / `SANDBOX_OBJECT_SECRET_KEY`：对象存储访问凭证，默认与 `docker/env/minio.env` 一致。
- `SANDBOX_OBJECT_BUCKET`：沙箱产物 bucket，默认 `agent-hub-sandbox-artifacts`。
- `SANDBOX_OBJECT_LOCAL_DIR`：仅用于本地调试；显式配置后沙箱产物会写入本地目录，此时 Java 后端无法通过 MinIO 预览这些产物。

`sandbox-service` 会为每个 microVM 创建独立运行目录、复制 rootfs 镜像、创建 tap 设备，通过 Firecracker API 配置 machine、boot source、root drive 和网络设备，然后等待 microVM 内 `sandbox-agent /health` 就绪。

## 管理接口与资源回收

`sandbox-service` 暴露内部管理接口 `/internal/admin/sandbox/**`，与普通执行接口共用 `SANDBOX_INTERNAL_TOKEN` 鉴权。正常部署中，admin-web 不直接访问该内部接口；管理端请求应先进入 Java 后端 `/api/sandbox/admin/**`，由 Java 完成 RBAC 鉴权、操作日志记录、请求聚合和内部 token 转发。

运行态观测接口包含：

- `GET /internal/admin/sandbox/status`：服务健康、内存会话和 warm pool 状态。
- `GET /internal/admin/sandbox/sessions`：当前 Go 内存态 session 列表。
- `GET /internal/admin/sandbox/resources`：扫描 Firecracker 进程、tap 设备、`run/fc-*` 运行目录和残留资源。
- `GET /internal/admin/sandbox/logs/tail?lines=100`：读取服务日志尾部；需要配置 `SANDBOX_LOG_FILE` 才能返回日志内容。

资源操作接口包含 idle 回收、关闭 session、终止 VM、清理残留资源、清空运行目录和重建 warm pool。高风险操作支持 `dryRun`，并要求从 Java 后端带上操作原因和确认文本，便于审计。

后台 idle reaper 会按固定间隔扫描内存态 session，超过 `SANDBOX_IDLE_TIMEOUT` 且没有活跃执行的 session 会被关闭并释放对应 VM。该回收是兜底机制，创建或获取 session 时仍会进行一次轻量回收。`SANDBOX_WARM_POOL_SIZE` 控制预热 VM 目标数量，服务启动时会异步填充 warm pool；重建 warm pool 会先停止当前预热 VM，再按目标数量重新填充。

运行目录清理只处理 `SANDBOX_FIRECRACKER_RUN_DIR` 下名称符合 `fc-*` 的子目录。普通 VM 停止时会清理自己的 runDir；孤儿目录清理和清空运行目录属于高风险操作，应优先通过 Java 管理 API 和操作日志链路执行。

## 开发联调模式

仍可临时使用宿主机上的 agent：

```bash
export SANDBOX_VM_MODE=dev-agent
export SANDBOX_GUEST_AGENT_URL=http://127.0.0.1:8081
go run ./cmd/sandbox-service
```

该模式只用于接口联调，不提供 microVM 隔离。
