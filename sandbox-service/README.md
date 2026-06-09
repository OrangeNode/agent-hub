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

## 推荐启动方式

生产部署优先使用仓库顶层 `sandbox/` 下的一键脚本：

```bash
export SANDBOX_INTERNAL_TOKEN=dev-sandbox-token
./sandbox/build-rootfs.sh
cp /path/to/vmlinux ./sandbox/rootfs/vmlinux
./sandbox/start-sandbox-service.sh
```

停止服务：

```bash
./sandbox/stop-sandbox-service.sh
```

`start-sandbox-service.sh` 会检查 `sandbox/rootfs/rootfs.ext4` 和 `sandbox/rootfs/vmlinux` 是否存在，通过后再编译并启动 `sandbox-service`。

## 构建 rootfs

在 KVM Linux 服务器上执行：

```bash
GO_BIN=/usr/local/go/bin/go ./sandbox/build-rootfs.sh
```

默认产物：

- `sandbox/rootfs/rootfs.ext4`
- `sandbox-service/build/agent/sandbox-agent`

默认 rootfs 临时树在 `/tmp/agent-hub-sandbox-rootfs-tree`，避免 Go module 下出现 root 权限目录影响 `go test ./...`。

rootfs 基于 Ubuntu noble minbase，预装 `bash`、`python3`、`pip`、`venv`、`numpy`、`pandas`、`matplotlib`、`openpyxl`、`nodejs`、`npm`、`git`、`curl`、`jq`、`zip`、`unzip`、`tar`、`build-essential`、`iproute2`、`procps` 等运行和常用构建依赖。

## 启动服务

需要准备 Firecracker 可启动 kernel。可以先按 Firecracker 官方 getting-started 的 CI kernel 规则下载：

```bash
cd sandbox-service
./scripts/download-firecracker-kernel.sh
```

然后启动服务：

```bash
export SANDBOX_KERNEL_IMAGE=/var/lib/firecracker/vmlinux.bin
export SANDBOX_ROOTFS_IMAGE=$PWD/build/rootfs/rootfs.ext4
export SANDBOX_INTERNAL_TOKEN=dev-sandbox-token
./scripts/run-dev-firecracker.sh
```

关键配置：

- `SANDBOX_VM_MODE=firecracker`：默认生产模式。
- `SANDBOX_FIRECRACKER_BIN`：Firecracker 二进制路径，默认 `/usr/local/bin/firecracker`。
- `SANDBOX_KERNEL_IMAGE`：Firecracker kernel 镜像路径，生产模式必填。
- `SANDBOX_ROOTFS_IMAGE`：rootfs ext4 镜像路径，生产模式必填。
- `SANDBOX_FIRECRACKER_RUN_DIR`：每个 VM 的 socket、日志和 rootfs 副本目录，默认 `/var/lib/agent-hub-sandbox/run`，不要放在空间较小的 `/var/run` tmpfs。
- `SANDBOX_GUEST_NETWORK_CIDR`：为 microVM 分配 `/30` 子网的地址池，默认 `172.16.200.0/24`。
- `SANDBOX_TAP_NAME_PREFIX`：tap 设备名前缀，默认 `tap-ah`。

`sandbox-service` 会为每个 microVM 创建独立运行目录、复制 rootfs 镜像、创建 tap 设备，通过 Firecracker API 配置 machine、boot source、root drive 和网络设备，然后等待 microVM 内 `sandbox-agent /health` 就绪。

## 开发联调模式

仍可临时使用宿主机上的 agent：

```bash
export SANDBOX_VM_MODE=dev-agent
export SANDBOX_GUEST_AGENT_URL=http://127.0.0.1:8081
go run ./cmd/sandbox-service
```

该模式只用于接口联调，不提供 microVM 隔离。
