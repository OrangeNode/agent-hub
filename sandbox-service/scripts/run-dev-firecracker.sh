#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export SANDBOX_INTERNAL_TOKEN="${SANDBOX_INTERNAL_TOKEN:-dev-sandbox-token}"
export SANDBOX_LISTEN_ADDR="${SANDBOX_LISTEN_ADDR:-:8090}"
export SANDBOX_VM_MODE="${SANDBOX_VM_MODE:-firecracker}"
export SANDBOX_FIRECRACKER_BIN="${SANDBOX_FIRECRACKER_BIN:-/usr/local/bin/firecracker}"
export SANDBOX_FIRECRACKER_RUN_DIR="${SANDBOX_FIRECRACKER_RUN_DIR:-${SERVICE_DIR}/run}"
export SANDBOX_KERNEL_IMAGE="${SANDBOX_KERNEL_IMAGE:-${SERVICE_DIR}/rootfs/vmlinux}"
export SANDBOX_ROOTFS_IMAGE="${SANDBOX_ROOTFS_IMAGE:-${SERVICE_DIR}/rootfs/rootfs.ext4}"
export SANDBOX_WARM_POOL_SIZE="${SANDBOX_WARM_POOL_SIZE:-1}"
export SANDBOX_MAX_ACTIVE_SESSIONS="${SANDBOX_MAX_ACTIVE_SESSIONS:-5}"
export SANDBOX_IDLE_TIMEOUT="${SANDBOX_IDLE_TIMEOUT:-5m}"
export SANDBOX_VM_CPU="${SANDBOX_VM_CPU:-1}"
export SANDBOX_VM_MEMORY_MB="${SANDBOX_VM_MEMORY_MB:-1024}"
export SANDBOX_VM_DISK_GB="${SANDBOX_VM_DISK_GB:-8}"
export SANDBOX_GUEST_AGENT_PORT="${SANDBOX_GUEST_AGENT_PORT:-8081}"
export SANDBOX_GUEST_NETWORK_CIDR="${SANDBOX_GUEST_NETWORK_CIDR:-172.16.200.0/24}"
export SANDBOX_TAP_NAME_PREFIX="${SANDBOX_TAP_NAME_PREFIX:-tap-ah}"
export SANDBOX_NETWORK_ALLOW_CIDRS="${SANDBOX_NETWORK_ALLOW_CIDRS:-127.0.0.1/32}"

GO_BIN="${GO_BIN:-}"
if [[ -z "${GO_BIN}" ]]; then
  if command -v go >/dev/null 2>&1; then
    GO_BIN="$(command -v go)"
  elif [[ -x /usr/local/go/bin/go ]]; then
    GO_BIN=/usr/local/go/bin/go
  else
    echo "未找到 go，可通过 GO_BIN=/path/to/go 指定" >&2
    exit 1
  fi
fi

cd "${SERVICE_DIR}"
echo "启动 sandbox-service: ${SANDBOX_LISTEN_ADDR}"
echo "kernel=${SANDBOX_KERNEL_IMAGE}"
echo "rootfs=${SANDBOX_ROOTFS_IMAGE}"
exec sudo -E "${GO_BIN}" run ./cmd/sandbox-service
