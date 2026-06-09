#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOTFS_DIR="${SANDBOX_ASSET_DIR:-${SERVICE_DIR}/rootfs}"

mkdir -p "${ROOTFS_DIR}"

export SANDBOX_ROOTFS_IMAGE="${SANDBOX_ROOTFS_IMAGE:-${ROOTFS_DIR}/rootfs.ext4}"
export SANDBOX_ROOTFS_DIR="${SANDBOX_ROOTFS_DIR:-/tmp/agent-hub-sandbox-rootfs-tree}"

if [[ ! -x "${SCRIPT_DIR}/build-minimal-rootfs.sh" ]]; then
  echo "未找到 rootfs 构建脚本: ${SCRIPT_DIR}/build-minimal-rootfs.sh" >&2
  exit 1
fi

echo "开始构建 sandbox rootfs"
echo "rootfs 镜像: ${SANDBOX_ROOTFS_IMAGE}"
"${SCRIPT_DIR}/build-minimal-rootfs.sh"
echo "sandbox rootfs 构建完成: ${SANDBOX_ROOTFS_IMAGE}"
