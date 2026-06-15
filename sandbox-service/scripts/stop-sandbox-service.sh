#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${SANDBOX_ENV_FILE:-${SERVICE_DIR}/.env}"

load_env_file() {
  local env_file="$1"
  [[ -f "${env_file}" ]] || return 0

  local line name value
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    if [[ "${line}" == export[[:space:]]* ]]; then
      line="${line#export}"
      line="${line#"${line%%[![:space:]]*}"}"
    fi
    [[ "${line}" == *=* ]] || continue
    name="${line%%=*}"
    value="${line#*=}"
    name="${name%"${name##*[![:space:]]}"}"
    [[ "${name}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ -z "${!name+x}" ]]; then
      eval "export ${name}=${value}"
    fi
  done < "${env_file}"
}

load_env_file "${ENV_FILE}"

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
