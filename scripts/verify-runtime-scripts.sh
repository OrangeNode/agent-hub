#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

required_scripts=(
  "_runtime-common.sh"
  "start-all.sh"
  "stop-all.sh"
  "status-all.sh"
)

for script in "${required_scripts[@]}"; do
  path="${SCRIPT_DIR}/${script}"
  if [[ ! -f "${path}" ]]; then
    echo "缺少脚本: scripts/${script}" >&2
    exit 1
  fi
  bash -n "${path}"
done

grep -q "logs/runtime" "${SCRIPT_DIR}/_runtime-common.sh"
grep -q "require_port_free" "${SCRIPT_DIR}/_runtime-common.sh"
grep -q -- "--with-sandbox" "${SCRIPT_DIR}/start-all.sh"
grep -q -- "--with-sandbox" "${SCRIPT_DIR}/stop-all.sh"
grep -q "sandbox-service/scripts/start-sandbox-service.sh" "${SCRIPT_DIR}/start-all.sh"
grep -q "sandbox-service/scripts/stop-sandbox-service.sh" "${SCRIPT_DIR}/stop-all.sh"
grep -q "run_docker_compose" "${SCRIPT_DIR}/start-all.sh"
grep -q "run_docker_compose" "${SCRIPT_DIR}/stop-all.sh"
grep -q "load_nvm_if_needed" "${SCRIPT_DIR}/start-all.sh"
grep -q "java -jar agent-hub-server/target/agent-hub-server-0.0.1-SNAPSHOT.jar" "${SCRIPT_DIR}/start-all.sh"
grep -q "admin-web" "${SCRIPT_DIR}/start-all.sh"
grep -q "user-web" "${SCRIPT_DIR}/start-all.sh"

backend_env="${PROJECT_DIR}/docker/env/backend.env"
# 真实 env 不入库，CI 等无本地配置的环境退回校验样例文件。
[[ -f "${backend_env}" ]] || backend_env="${backend_env}.example"
grep -q "^AGENT_HUB_STORAGE_ENDPOINT=" "${backend_env}"
grep -q "^AGENT_HUB_STORAGE_PUBLIC_ENDPOINT=" "${backend_env}"
grep -q "^AGENT_HUB_STORAGE_BUCKET=" "${backend_env}"
if grep -Eq "^AGENT_HUB_STORAGE_PUBLIC_ENDPOINT=https?://(127\\.0\\.0\\.1|localhost)(:|/|$)" "${backend_env}"; then
  echo "AGENT_HUB_STORAGE_PUBLIC_ENDPOINT 不能配置为本机地址，沙箱隔离环境无法访问" >&2
  exit 1
fi

password_sample="12""3123"
if grep -R "${password_sample}" "${PROJECT_DIR}/scripts" >/dev/null 2>&1; then
  echo "脚本中不应写入服务器密码" >&2
  exit 1
fi

echo "统一启动脚本验证通过"
