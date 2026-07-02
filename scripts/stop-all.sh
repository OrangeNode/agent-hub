#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_runtime-common.sh
. "${SCRIPT_DIR}/_runtime-common.sh"

WITH_SANDBOX=0
SKIP_SANDBOX=0
KEEP_DEPS=0

usage() {
  cat <<'USAGE'
用法: bash scripts/stop-all.sh [选项]

选项:
  --with-sandbox  强制转调 sandbox-service/scripts/stop-sandbox-service.sh
  --skip-sandbox  跳过 sandbox-service 停止
  --keep-deps     保留 Docker 基础依赖，不执行 docker compose down
  -h, --help      显示帮助
USAGE
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --with-sandbox)
      WITH_SANDBOX=1
      ;;
    --skip-sandbox)
      SKIP_SANDBOX=1
      ;;
    --keep-deps)
      KEEP_DEPS=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

stop_sandbox_service() {
  local sandbox_pid_file="${AGENT_HUB_PROJECT_DIR}/sandbox-service/sandbox-service.pid"
  if [[ "${SKIP_SANDBOX}" -eq 1 ]]; then
    echo "跳过 sandbox-service 停止"
    return 0
  fi
  if [[ "${WITH_SANDBOX}" -eq 1 || -f "${sandbox_pid_file}" ]]; then
    echo "停止 sandbox-service..."
    bash "${AGENT_HUB_PROJECT_DIR}/sandbox-service/scripts/stop-sandbox-service.sh"
  else
    echo "sandbox-service 未检测到 PID 文件，跳过"
  fi
}

stop_dependencies() {
  if [[ "${KEEP_DEPS}" -eq 1 ]]; then
    echo "保留 Docker 基础依赖"
    return 0
  fi

  require_command docker
  echo "停止 Docker 基础依赖..."
  run_docker_compose down
}

stop_background_service "user-web" "用户端前端"
stop_background_service "admin-web" "管理端前端"
stop_background_service "backend" "后端"
stop_sandbox_service
stop_dependencies

echo "Agent Hub 停止命令已执行"
