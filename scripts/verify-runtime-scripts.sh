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
grep -q -- "--with-sandbox" "${SCRIPT_DIR}/start-all.sh"
grep -q -- "--with-sandbox" "${SCRIPT_DIR}/stop-all.sh"
grep -q "sandbox-service/scripts/start-sandbox-service.sh" "${SCRIPT_DIR}/start-all.sh"
grep -q "sandbox-service/scripts/stop-sandbox-service.sh" "${SCRIPT_DIR}/stop-all.sh"
grep -q "run_docker_compose" "${SCRIPT_DIR}/start-all.sh"
grep -q "run_docker_compose" "${SCRIPT_DIR}/stop-all.sh"
grep -q "admin-web" "${SCRIPT_DIR}/start-all.sh"
grep -q "user-web" "${SCRIPT_DIR}/start-all.sh"

password_sample="12""3123"
if grep -R "${password_sample}" "${PROJECT_DIR}/scripts" >/dev/null 2>&1; then
  echo "脚本中不应写入服务器密码" >&2
  exit 1
fi

echo "统一启动脚本验证通过"
