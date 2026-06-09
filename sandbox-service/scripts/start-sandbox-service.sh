#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ROOTFS_DIR="${SANDBOX_ASSET_DIR:-${SERVICE_DIR}/rootfs}"
BIN_DIR="${SERVICE_DIR}/bin"
LOG_DIR="${SERVICE_DIR}/logs"
RUN_DIR="${SANDBOX_FIRECRACKER_RUN_DIR:-${SERVICE_DIR}/run}"
PID_FILE="${SANDBOX_SERVICE_PID_FILE:-${SERVICE_DIR}/sandbox-service.pid}"
LOG_FILE="${SANDBOX_SERVICE_LOG_FILE:-${LOG_DIR}/sandbox-service.log}"

LISTEN_ADDR="${SANDBOX_LISTEN_ADDR:-0.0.0.0:8090}"
LISTEN_PORT="${LISTEN_ADDR##*:}"
FIRECRACKER_BIN="${SANDBOX_FIRECRACKER_BIN:-/usr/local/bin/firecracker}"
KERNEL_IMAGE="${SANDBOX_KERNEL_IMAGE:-${ROOTFS_DIR}/vmlinux}"
ROOTFS_IMAGE="${SANDBOX_ROOTFS_IMAGE:-${ROOTFS_DIR}/rootfs.ext4}"
SERVICE_BIN="${BIN_DIR}/sandbox-service"
GO_BIN="${GO_BIN:-}"

fail() {
  echo "启动 sandbox-service 失败: $*" >&2
  exit 1
}

resolve_go() {
  if [[ -n "${GO_BIN}" && -x "${GO_BIN}" ]]; then
    echo "${GO_BIN}"
    return
  fi
  if command -v go >/dev/null 2>&1; then
    command -v go
    return
  fi
  if [[ -x /usr/local/go/bin/go ]]; then
    echo /usr/local/go/bin/go
    return
  fi
  fail "未找到 go，可通过 GO_BIN=/path/to/go 指定"
}

check_file() {
  local label="$1"
  local path="$2"
  [[ -f "${path}" ]] || fail "缺少 ${label}: ${path}"
}

check_executable() {
  local label="$1"
  local path="$2"
  [[ -x "${path}" ]] || fail "${label} 不存在或不可执行: ${path}"
}

check_port_free() {
  if command -v ss >/dev/null 2>&1 && ss -ltn "sport = :${LISTEN_PORT}" | grep -q ":${LISTEN_PORT}"; then
    fail "端口 ${LISTEN_PORT} 已被占用，请先执行 sandbox-service/scripts/stop-sandbox-service.sh 或调整 SANDBOX_LISTEN_ADDR"
  fi
}

sudo_check() {
  if [[ -n "${SANDBOX_SUDO_PASSWORD:-}" ]]; then
    printf '%s\n' "${SANDBOX_SUDO_PASSWORD}" | sudo -S -v
    return
  fi
  sudo -v
}

sudo_start_service() {
  if [[ -n "${SANDBOX_SUDO_PASSWORD:-}" ]]; then
    printf '%s\n' "${SANDBOX_SUDO_PASSWORD}" | sudo -S -E setsid "${SERVICE_BIN}" >"${LOG_FILE}" 2>&1 &
    return
  fi
  sudo -E setsid "${SERVICE_BIN}" >"${LOG_FILE}" 2>&1 &
}

if [[ "${EUID}" -eq 0 ]]; then
  fail "请使用普通用户执行，本脚本会在需要时通过 sudo 启动服务"
fi

[[ -n "${SANDBOX_INTERNAL_TOKEN:-}" ]] || fail "SANDBOX_INTERNAL_TOKEN 不能为空"
check_executable "Firecracker" "${FIRECRACKER_BIN}"
check_file "kernel 镜像" "${KERNEL_IMAGE}"
check_file "rootfs 镜像" "${ROOTFS_IMAGE}"
check_port_free

GO_BIN="$(resolve_go)"
mkdir -p "${BIN_DIR}" "${LOG_DIR}" "${RUN_DIR}"

if ! sudo_check; then
  fail "sudo 授权失败，无法创建 tap 设备和启动 Firecracker"
fi

echo "编译 sandbox-service: ${SERVICE_BIN}"
(
  cd "${SERVICE_DIR}"
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 "${GO_BIN}" build -trimpath -ldflags="-s -w" -o "${SERVICE_BIN}" ./cmd/sandbox-service
)

echo "启动 sandbox-service"
echo "listen=${LISTEN_ADDR}"
echo "kernel=${KERNEL_IMAGE}"
echo "rootfs=${ROOTFS_IMAGE}"
echo "runDir=${RUN_DIR}"
echo "log=${LOG_FILE}"

export SANDBOX_LISTEN_ADDR="${LISTEN_ADDR}"
export SANDBOX_VM_MODE="${SANDBOX_VM_MODE:-firecracker}"
export SANDBOX_FIRECRACKER_BIN="${FIRECRACKER_BIN}"
export SANDBOX_KERNEL_IMAGE="${KERNEL_IMAGE}"
export SANDBOX_ROOTFS_IMAGE="${ROOTFS_IMAGE}"
export SANDBOX_FIRECRACKER_RUN_DIR="${RUN_DIR}"
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

sudo_start_service
START_PID=$!
sleep 1
SERVICE_PID="$(ps -eo pid=,comm=,args= | awk -v bin="${SERVICE_BIN}" '$2 == "sandbox-service" && index($0, bin) > 0 {print $1}' | sort -n | tail -1)"
if [[ -z "${SERVICE_PID}" ]]; then
  SERVICE_PID="${START_PID}"
fi
echo "${SERVICE_PID}" >"${PID_FILE}"

for _ in $(seq 1 30); do
  if curl -fsS -H "Authorization: Bearer ${SANDBOX_INTERNAL_TOKEN}" "http://127.0.0.1:${LISTEN_PORT}/health" >/dev/null 2>&1; then
    echo "sandbox-service 已启动，PID=${SERVICE_PID}"
    exit 0
  fi
  if ! kill -0 "${SERVICE_PID}" 2>/dev/null; then
    echo "sandbox-service 启动后立即退出，最近日志如下:" >&2
    tail -80 "${LOG_FILE}" >&2 || true
    exit 1
  fi
  sleep 1
done

echo "sandbox-service 启动超时，最近日志如下:" >&2
tail -80 "${LOG_FILE}" >&2 || true
exit 1
