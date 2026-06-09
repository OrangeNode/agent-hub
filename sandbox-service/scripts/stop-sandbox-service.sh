#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUN_DIR="${SANDBOX_FIRECRACKER_RUN_DIR:-${SERVICE_DIR}/run}"
PID_FILE="${SANDBOX_SERVICE_PID_FILE:-${SERVICE_DIR}/sandbox-service.pid}"
SERVICE_BIN="${SERVICE_DIR}/bin/sandbox-service"
TAP_PREFIX="${SANDBOX_TAP_NAME_PREFIX:-tap-ah}"
LISTEN_ADDR="${SANDBOX_LISTEN_ADDR:-:8090}"
LISTEN_PORT="${LISTEN_ADDR##*:}"

sudo_cmd() {
  if [[ "${EUID}" -eq 0 ]]; then
    "$@"
  elif [[ -n "${SANDBOX_SUDO_PASSWORD:-}" ]]; then
    printf '%s\n' "${SANDBOX_SUDO_PASSWORD}" | sudo -S "$@"
  else
    sudo "$@"
  fi
}

process_exists() {
  local pid="$1"
  sudo_cmd kill -0 "${pid}" 2>/dev/null
}

stop_pid() {
  local pid="$1"
  [[ -n "${pid}" ]] || return 0
  if ! process_exists "${pid}"; then
    return 0
  fi
  echo "停止进程: ${pid}"
  sudo_cmd kill "${pid}" 2>/dev/null || true
  sleep 2
  if process_exists "${pid}"; then
    echo "强制停止进程: ${pid}"
    sudo_cmd kill -9 "${pid}" 2>/dev/null || true
  fi
}

if [[ -f "${PID_FILE}" ]]; then
  stop_pid "$(cat "${PID_FILE}")"
  rm -f "${PID_FILE}"
fi

for pid in $(ps -eo pid=,comm=,args= | awk -v bin="${SERVICE_BIN}" '$2 == "sandbox-service" && index($0, bin) > 0 {print $1}'); do
  stop_pid "${pid}"
done

if command -v ss >/dev/null 2>&1; then
  for pid in $(sudo_cmd ss -ltnp "sport = :${LISTEN_PORT}" 2>/dev/null | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | sort -u); do
    stop_pid "${pid}"
  done
fi

for pid in $(ps -eo pid=,args= | awk -v run_dir="${RUN_DIR}" '/firecracker --api-sock/ && index($0, run_dir) > 0 {print $1}'); do
  stop_pid "${pid}"
done

if command -v ip >/dev/null 2>&1; then
  for dev in $(ip -o link show | awk -F': ' -v prefix="${TAP_PREFIX}" 'index($2, prefix) == 1 {print $2}'); do
    echo "删除 tap 设备: ${dev}"
    sudo_cmd ip link delete "${dev}" 2>/dev/null || true
  done
fi

if [[ -d "${RUN_DIR}" ]]; then
  echo "清理运行目录: ${RUN_DIR}"
  sudo_cmd rm -rf "${RUN_DIR}"
fi

echo "sandbox-service 已停止"
