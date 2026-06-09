#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_DIR="${PROJECT_DIR}/sandbox-service"
ROOTFS_DIR="${SCRIPT_DIR}/rootfs"

mkdir -p "${ROOTFS_DIR}"

export SANDBOX_ROOTFS_IMAGE="${SANDBOX_ROOTFS_IMAGE:-${ROOTFS_DIR}/rootfs.ext4}"
export SANDBOX_ROOTFS_DIR="${SANDBOX_ROOTFS_DIR:-/tmp/agent-hub-sandbox-rootfs-tree}"

if [[ ! -x "${SERVICE_DIR}/scripts/build-minimal-rootfs.sh" ]]; then
  echo "未找到 rootfs 构建脚本: ${SERVICE_DIR}/scripts/build-minimal-rootfs.sh" >&2
  exit 1
fi

echo "开始构建 sandbox rootfs"
echo "rootfs 镜像: ${SANDBOX_ROOTFS_IMAGE}"
"${SERVICE_DIR}/scripts/build-minimal-rootfs.sh"
echo "sandbox rootfs 构建完成: ${SANDBOX_ROOTFS_IMAGE}"
