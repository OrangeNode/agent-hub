#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_runtime-common.sh
. "${SCRIPT_DIR}/_runtime-common.sh"

print_sandbox_status() {
  local pid_file="${AGENT_HUB_PROJECT_DIR}/sandbox-service/sandbox-service.pid"
  local pid
  pid="$(read_pid_file "${pid_file}")"
  if pid_running "${pid}"; then
    echo "sandbox-service: 运行中，PID=${pid}，日志=${AGENT_HUB_PROJECT_DIR}/sandbox-service/logs/sandbox-service.log"
  else
    echo "sandbox-service: 未运行"
  fi
}

echo "Agent Hub 运行状态"
echo
print_service_status "backend" "后端"
print_service_status "admin-web" "管理端前端"
print_service_status "user-web" "用户端前端"
print_sandbox_status

echo
echo "Docker 基础依赖:"
if command -v docker >/dev/null 2>&1; then
  run_docker_compose ps || true
else
  echo "未找到 docker 命令"
fi
