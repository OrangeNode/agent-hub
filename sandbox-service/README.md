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

rootfs 基于 Ubuntu noble minbase，预装 `bash`、`python3`、`pip`、`venv`、`numpy`、`pandas`、`matplotlib`、`openpyxl`、`nodejs`、`npm`、`git`、`curl`、`jq`、`zip`、`unzip`、`tar`、`build-essential`、`iproute2`、`procps` 等运行和常用构建依赖。

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

```bash
export SANDBOX_INTERNAL_TOKEN=dev-sandbox-token
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

## 开发联调模式

仍可临时使用宿主机上的 agent：

```bash
export SANDBOX_VM_MODE=dev-agent
export SANDBOX_GUEST_AGENT_URL=http://127.0.0.1:8081
go run ./cmd/sandbox-service
```

该模式只用于接口联调，不提供 microVM 隔离。
