#!/usr/bin/env bash
set -euo pipefail

ARCH="$(uname -m)"
RELEASE_URL="${SANDBOX_FIRECRACKER_RELEASE_URL:-https://github.com/firecracker-microvm/firecracker/releases}"
RELEASE_API="${SANDBOX_FIRECRACKER_RELEASE_API:-https://api.github.com/repos/firecracker-microvm/firecracker/releases/latest}"
DEST="${SANDBOX_KERNEL_IMAGE:-/var/lib/firecracker/vmlinux.bin}"

if ! command -v curl >/dev/null 2>&1; then
  echo "缺少 curl" >&2
  exit 1
fi

if [[ -n "${SANDBOX_FIRECRACKER_VERSION:-}" ]]; then
  LATEST_VERSION="${SANDBOX_FIRECRACKER_VERSION}"
else
  LATEST_VERSION="$(
    curl -fsSL "${RELEASE_API}" \
      | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      | head -1
  )"
fi
if [[ -z "${LATEST_VERSION}" ]]; then
  LATEST_VERSION="$(basename "$(curl -fsSLI -o /dev/null -w '%{url_effective}' "${RELEASE_URL}/latest")")"
fi
CI_VERSION="${LATEST_VERSION%.*}"

echo "查找 Firecracker CI kernel: version=${LATEST_VERSION}, ci=${CI_VERSION}, arch=${ARCH}"
KERNEL_KEY="$(
  curl -fsSL "https://s3.amazonaws.com/spec.ccfc.min?prefix=firecracker-ci/${CI_VERSION}/${ARCH}/vmlinux-&list-type=2" \
    | grep -oP "(?<=<Key>)(firecracker-ci/${CI_VERSION}/${ARCH}/vmlinux-[0-9]+\\.[0-9]+\\.[0-9]{1,3})(?=</Key>)" \
    | sort -V \
    | tail -1
)"

if [[ -z "${KERNEL_KEY}" ]]; then
  echo "未找到可用 Firecracker CI kernel" >&2
  exit 1
fi

TMP_FILE="$(mktemp)"
cleanup() {
  rm -f "${TMP_FILE}"
}
trap cleanup EXIT

echo "下载 kernel: https://s3.amazonaws.com/spec.ccfc.min/${KERNEL_KEY}"
curl -fsSL "https://s3.amazonaws.com/spec.ccfc.min/${KERNEL_KEY}" -o "${TMP_FILE}"
sudo mkdir -p "$(dirname "${DEST}")"
sudo install -m 0644 "${TMP_FILE}" "${DEST}"
echo "kernel 已写入: ${DEST}"
