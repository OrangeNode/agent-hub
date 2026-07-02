#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_runtime-common.sh
. "${SCRIPT_DIR}/_runtime-common.sh"

WITH_SANDBOX=0
SKIP_DEPS=0
NO_INSTALL=0

usage() {
  cat <<'USAGE'
用法: bash scripts/start-all.sh [选项]

选项:
  --with-sandbox  同时启动 sandbox-service，转调 sandbox-service/scripts/start-sandbox-service.sh
  --skip-deps     跳过 Docker 基础依赖启动
  --no-install    前端缺少 node_modules 时不自动执行 npm install
  -h, --help      显示帮助
USAGE
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --with-sandbox)
      WITH_SANDBOX=1
      ;;
    --skip-deps)
      SKIP_DEPS=1
      ;;
    --no-install)
      NO_INSTALL=1
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

start_dependencies() {
  if [[ "${SKIP_DEPS}" -eq 1 ]]; then
    echo "跳过 Docker 基础依赖启动"
    return 0
  fi

  require_command docker
  echo "启动 Docker 基础依赖..."
  if docker compose up --help 2>/dev/null | grep -q -- '--wait'; then
    run_docker_compose up -d --wait
  else
    run_docker_compose up -d
  fi
}

load_sandbox_env() {
  local sandbox_dir="${AGENT_HUB_PROJECT_DIR}/sandbox-service"
  SERVICE_DIR="${sandbox_dir}"
  export SERVICE_DIR
  SANDBOX_ENV_FILE="${SANDBOX_ENV_FILE:-${sandbox_dir}/.env}"
  export SANDBOX_ENV_FILE
  load_env_file "${SANDBOX_ENV_FILE}"
}

start_sandbox_service() {
  load_sandbox_env
  echo "启动 sandbox-service..."
  bash "${AGENT_HUB_PROJECT_DIR}/sandbox-service/scripts/start-sandbox-service.sh"
}

prepare_backend_env() {
  load_env_file "${AGENT_HUB_BACKEND_ENV_FILE:-${AGENT_HUB_PROJECT_DIR}/docker/env/backend.env}"
  if [[ "${WITH_SANDBOX}" -eq 1 ]]; then
    load_sandbox_env
  fi

  export TZ="${TZ:-Asia/Shanghai}"
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.timezone=Asia/Shanghai}"
  export SPRING_FLYWAY_ENABLED="${SPRING_FLYWAY_ENABLED:-false}"
  export AGENT_HUB_SANDBOX_BASE_URL="${AGENT_HUB_SANDBOX_BASE_URL:-http://127.0.0.1:8090}"
  export AGENT_HUB_SANDBOX_INTERNAL_TOKEN="${AGENT_HUB_SANDBOX_INTERNAL_TOKEN:-${SANDBOX_INTERNAL_TOKEN:-dev-sandbox-token}}"
}

ensure_node_modules() {
  local web_dir="$1"
  local label="$2"
  if [[ "${NO_INSTALL}" -eq 1 ]]; then
    return 0
  fi
  if [[ ! -d "${web_dir}/node_modules" ]]; then
    echo "${label} 缺少 node_modules，执行 npm install..."
    (cd "${web_dir}" && npm install)
  fi
}

start_backend() {
  require_command mvn
  prepare_backend_env
  start_background_service "backend" "后端" "${AGENT_HUB_PROJECT_DIR}/backend" \
    mvn -pl agent-hub-server -am spring-boot:run
}

start_admin_web() {
  require_command npm
  ensure_node_modules "${AGENT_HUB_PROJECT_DIR}/admin-web" "管理端前端"
  start_background_service "admin-web" "管理端前端" "${AGENT_HUB_PROJECT_DIR}/admin-web" \
    npm run dev -- --host 0.0.0.0 --strictPort
}

start_user_web() {
  require_command npm
  ensure_node_modules "${AGENT_HUB_PROJECT_DIR}/user-web" "用户端前端"
  start_background_service "user-web" "用户端前端" "${AGENT_HUB_PROJECT_DIR}/user-web" \
    npm run dev -- --host 0.0.0.0 --strictPort
}

ensure_runtime_dirs
start_dependencies
if [[ "${WITH_SANDBOX}" -eq 1 ]]; then
  start_sandbox_service
fi
start_backend
start_admin_web
start_user_web

cat <<EOF

Agent Hub 启动命令已执行。
后端:   http://127.0.0.1:8080
管理端: http://127.0.0.1:5173
用户端: http://127.0.0.1:5174
状态:   bash scripts/status-all.sh
日志:   ${AGENT_HUB_RUNTIME_DIR}/logs
EOF
