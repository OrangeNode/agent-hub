#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOTFS_DIR="${1:-"${SCRIPT_DIR}/../build/rootfs"}"

cat <<EOF
准备构建 minimal rootfs: ${ROOTFS_DIR}

MVP rootfs 需要包含:
- sandbox-agent
- python3、pip
- node、npm
- git、curl
- zip、unzip、tar
- jq
- ca-certificates

后续部署任务会补充具体发行版、kernel/rootfs 打包和 Firecracker 启动参数。
EOF

mkdir -p "${ROOTFS_DIR}"
