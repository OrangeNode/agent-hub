#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "scripts/_runtime-common.sh 只能被其他脚本 source" >&2
  exit 1
fi

AGENT_HUB_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_HUB_PROJECT_DIR="$(cd "${AGENT_HUB_SCRIPT_DIR}/.." && pwd)"
AGENT_HUB_RUNTIME_DIR="${AGENT_HUB_RUNTIME_DIR:-${AGENT_HUB_PROJECT_DIR}/logs/runtime}"
AGENT_HUB_PID_DIR="${AGENT_HUB_RUNTIME_DIR}/pids"
AGENT_HUB_LOG_DIR="${AGENT_HUB_RUNTIME_DIR}/logs"

ensure_runtime_dirs() {
  mkdir -p "${AGENT_HUB_PID_DIR}" "${AGENT_HUB_LOG_DIR}"
}

pid_file() {
  local service="$1"
  printf '%s/%s.pid\n' "${AGENT_HUB_PID_DIR}" "${service}"
}

out_log_file() {
  local service="$1"
  printf '%s/%s.out.log\n' "${AGENT_HUB_LOG_DIR}" "${service}"
}

err_log_file() {
  local service="$1"
  printf '%s/%s.err.log\n' "${AGENT_HUB_LOG_DIR}" "${service}"
}

read_pid_file() {
  local path="$1"
  [[ -f "${path}" ]] || return 0
  tr -d '[:space:]' < "${path}"
}

pid_running() {
  local pid="${1:-}"
  [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" 2>/dev/null
}

service_pid() {
  local service="$1"
  read_pid_file "$(pid_file "${service}")"
}

service_running() {
  local service="$1"
  local pid
  pid="$(service_pid "${service}")"
  pid_running "${pid}"
}

require_command() {
  local command_name="$1"
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "缺少命令: ${command_name}" >&2
    exit 1
  fi
}

load_env_file() {
  local env_file="$1"
  [[ -f "${env_file}" ]] || return 0
  set -a
  # shellcheck disable=SC1090
  . "${env_file}"
  set +a
}

run_docker_compose() {
  docker compose --project-directory "${AGENT_HUB_PROJECT_DIR}/docker" \
    -f "${AGENT_HUB_PROJECT_DIR}/docker/docker-compose.yml" "$@"
}

start_background_service() {
  local service="$1"
  local label="$2"
  local workdir="$3"
  shift 3

  ensure_runtime_dirs

  local pid_path out_path err_path pid
  pid_path="$(pid_file "${service}")"
  out_path="$(out_log_file "${service}")"
  err_path="$(err_log_file "${service}")"

  if service_running "${service}"; then
    echo "${label} 已运行，PID=$(service_pid "${service}")"
    echo "日志: ${out_path}"
    return 0
  fi

  rm -f "${pid_path}"
  echo "启动 ${label}..."
  (
    cd "${workdir}"
    if command -v setsid >/dev/null 2>&1; then
      nohup setsid "$@" >"${out_path}" 2>"${err_path}" &
    else
      nohup "$@" >"${out_path}" 2>"${err_path}" &
    fi
    echo $! > "${pid_path}"
  )

  pid="$(service_pid "${service}")"
  sleep 1
  if ! pid_running "${pid}"; then
    echo "${label} 启动后立即退出，错误日志如下:" >&2
    tail -80 "${err_path}" >&2 || true
    exit 1
  fi

  echo "${label} 已启动，PID=${pid}"
  echo "stdout: ${out_path}"
  echo "stderr: ${err_path}"
}

kill_pid_or_group() {
  local signal="$1"
  local pid="$2"
  kill "-${signal}" -- "-${pid}" 2>/dev/null || kill "-${signal}" "${pid}" 2>/dev/null || true
}

stop_background_service() {
  local service="$1"
  local label="$2"
  local wait_seconds="${3:-10}"

  local pid_path pid elapsed
  pid_path="$(pid_file "${service}")"
  pid="$(read_pid_file "${pid_path}")"

  if ! pid_running "${pid}"; then
    rm -f "${pid_path}"
    echo "${label} 未运行"
    return 0
  fi

  echo "停止 ${label}，PID=${pid}"
  kill_pid_or_group TERM "${pid}"

  elapsed=0
  while pid_running "${pid}" && [[ "${elapsed}" -lt "${wait_seconds}" ]]; do
    sleep 1
    elapsed=$((elapsed + 1))
  done

  if pid_running "${pid}"; then
    echo "强制停止 ${label}，PID=${pid}"
    kill_pid_or_group KILL "${pid}"
  fi

  rm -f "${pid_path}"
  echo "${label} 已停止"
}

print_service_status() {
  local service="$1"
  local label="$2"
  local pid
  pid="$(service_pid "${service}")"

  if pid_running "${pid}"; then
    echo "${label}: 运行中，PID=${pid}，日志=$(out_log_file "${service}")"
  else
    echo "${label}: 未运行"
  fi
}
