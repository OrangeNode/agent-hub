#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_DIR="${SERVICE_DIR}/build"
ROOTFS_DIR="${SANDBOX_ROOTFS_DIR:-/tmp/agent-hub-sandbox-rootfs-tree}"
ROOTFS_IMAGE="${SANDBOX_ROOTFS_IMAGE:-${BUILD_DIR}/rootfs/rootfs.ext4}"
ROOTFS_SIZE_MB="${SANDBOX_ROOTFS_SIZE_MB:-8192}"
UBUNTU_CODENAME="${SANDBOX_ROOTFS_UBUNTU_CODENAME:-noble}"
UBUNTU_MIRROR="${SANDBOX_ROOTFS_UBUNTU_MIRROR:-http://archive.ubuntu.com/ubuntu}"
GO_BIN="${GO_BIN:-}"

BOOTSTRAP_PACKAGES=(
  apt
  bash
  busybox
  ca-certificates
  iproute2
  login
  passwd
  util-linux
)

RUNTIME_PACKAGES=(
  bash
  build-essential
  busybox
  ca-certificates
  coreutils
  curl
  dotnet-sdk-8.0
  findutils
  fontconfig
  fonts-dejavu-core
  fonts-liberation
  fonts-noto-cjk
  fonts-noto-core
  gawk
  git
  grep
  gzip
  iproute2
  jq
  libreoffice-calc
  libreoffice-impress
  libreoffice-writer
  login
  npm
  nodejs
  pandoc
  passwd
  procps
  python3
  python3-docx
  python-is-python3
  python3-lxml
  python3-openpyxl
  python3-pil
  python3-pip
  python3-pypdf
  python3-reportlab
  python3-venv
  poppler-utils
  sed
  tar
  unzip
  util-linux
  xz-utils
  zip
)

PYTHON_PACKAGES=(
  python-pptx
)

EXTRA_PACKAGES=()
if [[ -n "${SANDBOX_ROOTFS_EXTRA_PACKAGES:-}" ]]; then
  # shellcheck disable=SC2206
  EXTRA_PACKAGES=(${SANDBOX_ROOTFS_EXTRA_PACKAGES})
fi

EXTRA_PYTHON_PACKAGES=()
if [[ -n "${SANDBOX_ROOTFS_EXTRA_PYTHON_PACKAGES:-}" ]]; then
  # shellcheck disable=SC2206
  EXTRA_PYTHON_PACKAGES=(${SANDBOX_ROOTFS_EXTRA_PYTHON_PACKAGES})
fi

require_linux() {
  if [[ "$(uname -s)" != "Linux" ]]; then
    echo "rootfs 构建脚本需要在 Linux/KVM 服务器上运行" >&2
    exit 1
  fi
}

ensure_host_dependencies() {
  local missing=()
  for bin in debootstrap mkfs.ext4 rsync; do
    if ! command -v "${bin}" >/dev/null 2>&1; then
      missing+=("${bin}")
    fi
  done
  if (( ${#missing[@]} == 0 )); then
    return
  fi
  echo "安装 rootfs 构建依赖: ${missing[*]}"
  sudo apt-get update
  sudo apt-get install -y debootstrap e2fsprogs rsync
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
  echo "未找到 go，可通过 GO_BIN=/path/to/go 指定" >&2
  exit 1
}

build_agent() {
  local go_bin="$1"
  local output="${BUILD_DIR}/agent/sandbox-agent"
  mkdir -p "$(dirname "${output}")"
  echo "编译 sandbox-agent: ${output}"
  (
    cd "${SERVICE_DIR}"
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 "${go_bin}" build -trimpath -ldflags="-s -w" -o "${output}" ./cmd/sandbox-agent
  )
}

cleanup_rootfs_mounts() {
  for target in dev sys proc; do
    local mount_path="${ROOTFS_DIR}/${target}"
    if mountpoint -q "${mount_path}"; then
      sudo umount "${mount_path}" 2>/dev/null || sudo umount -l "${mount_path}" 2>/dev/null || true
    fi
  done
}

bootstrap_rootfs() {
	local package_csv
	package_csv="$(IFS=,; echo "${BOOTSTRAP_PACKAGES[*]}")"
	echo "生成 rootfs 目录: ${ROOTFS_DIR}"
	cleanup_rootfs_mounts
	sudo rm -rf "${ROOTFS_DIR}"
	sudo mkdir -p "${ROOTFS_DIR}"
	sudo debootstrap --variant=minbase --components=main,universe --include="${package_csv}" "${UBUNTU_CODENAME}" "${ROOTFS_DIR}" "${UBUNTU_MIRROR}"
}

install_runtime_packages() {
  local all_packages=("${RUNTIME_PACKAGES[@]}" "${EXTRA_PACKAGES[@]}")
  echo "安装 rootfs 运行依赖: ${all_packages[*]}"
  sudo tee "${ROOTFS_DIR}/etc/apt/sources.list" >/dev/null <<EOF
deb ${UBUNTU_MIRROR} ${UBUNTU_CODENAME} main universe
deb ${UBUNTU_MIRROR} ${UBUNTU_CODENAME}-updates main universe
deb http://security.ubuntu.com/ubuntu ${UBUNTU_CODENAME}-security main universe
EOF

  sudo mount -t proc proc "${ROOTFS_DIR}/proc"
  sudo mount -t sysfs sysfs "${ROOTFS_DIR}/sys"
  sudo mount --bind /dev "${ROOTFS_DIR}/dev"
  trap cleanup_rootfs_mounts RETURN ERR
  sudo chroot "${ROOTFS_DIR}" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get update
  sudo chroot "${ROOTFS_DIR}" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${all_packages[@]}"
  install_python_packages
  warm_dotnet_package_cache
  sudo chroot "${ROOTFS_DIR}" apt-get clean
  cleanup_rootfs_mounts
  trap - RETURN ERR
}

install_python_packages() {
  local python_packages=("${PYTHON_PACKAGES[@]}" "${EXTRA_PYTHON_PACKAGES[@]}")
  if (( ${#python_packages[@]} == 0 )); then
    return
  fi
  echo "瀹夎 rootfs Python 鍖? ${python_packages[*]}"
  sudo chroot "${ROOTFS_DIR}" /usr/bin/env \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    python3 -m pip install --no-cache-dir --break-system-packages "${python_packages[@]}"
}

warm_dotnet_package_cache() {
  local warmup_dir="/tmp/dotnet-package-warmup"
  local warmup_project="${warmup_dir}/dotnet-package-warmup.csproj"
  echo "预热 dotnet NuGet 包缓存: DocumentFormat.OpenXml 3.0.2"
  sudo mkdir -p "${ROOTFS_DIR}/opt/nuget/packages"
  sudo rm -rf "${ROOTFS_DIR}${warmup_dir}"
  sudo chroot "${ROOTFS_DIR}" /usr/bin/env \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    NUGET_PACKAGES=/opt/nuget/packages \
    dotnet new console -o "${warmup_dir}" --force >/dev/null
  sudo chroot "${ROOTFS_DIR}" /usr/bin/env \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    NUGET_PACKAGES=/opt/nuget/packages \
    dotnet add "${warmup_project}" package DocumentFormat.OpenXml --version 3.0.2 >/dev/null
  sudo chroot "${ROOTFS_DIR}" /usr/bin/env \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    NUGET_PACKAGES=/opt/nuget/packages \
    dotnet restore "${warmup_project}" >/dev/null
  sudo rm -rf "${ROOTFS_DIR}${warmup_dir}"
}

configure_rootfs() {
	echo "写入 sandbox-agent、workspace 和 init"
  sudo install -m 0755 "${BUILD_DIR}/agent/sandbox-agent" "${ROOTFS_DIR}/usr/local/bin/sandbox-agent"
  sudo mkdir -p "${ROOTFS_DIR}/workspace/input" "${ROOTFS_DIR}/workspace/artifacts" "${ROOTFS_DIR}/tmp" "${ROOTFS_DIR}/opt/nuget/packages"
  sudo chroot "${ROOTFS_DIR}" useradd --uid 1000 --create-home --shell /bin/bash sandbox 2>/dev/null || true
  sudo chroot "${ROOTFS_DIR}" chown -R sandbox:sandbox /workspace /home/sandbox /opt/nuget
  sudo chmod 1777 "${ROOTFS_DIR}/tmp"
  sudo tee "${ROOTFS_DIR}/sbin/init" >/dev/null <<'EOF'
#!/bin/sh
set -eu

export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc 2>/dev/null || true
mount -t sysfs sysfs /sys 2>/dev/null || true
mount -t devtmpfs devtmpfs /dev 2>/dev/null || true

configure_network_from_cmdline() {
  for arg in $(cat /proc/cmdline); do
    case "${arg}" in
      ip=*)
        value="${arg#ip=}"
        client_ip="$(printf '%s' "${value}" | cut -d: -f1)"
        gateway_ip="$(printf '%s' "${value}" | cut -d: -f3)"
        iface="$(printf '%s' "${value}" | cut -d: -f6)"
        if [ -n "${client_ip}" ] && [ -n "${gateway_ip}" ] && [ -n "${iface}" ]; then
          ip link set "${iface}" up 2>/dev/null || true
          ip addr replace "${client_ip}/30" dev "${iface}" 2>/dev/null || true
          ip route replace default via "${gateway_ip}" dev "${iface}" 2>/dev/null || true
        fi
        ;;
    esac
  done
}

mkdir -p /workspace/input /workspace/artifacts /tmp /opt/nuget/packages
chmod 1777 /tmp
chown -R sandbox:sandbox /workspace /opt/nuget
ip link set lo up 2>/dev/null || true
ip link set eth0 up 2>/dev/null || true
configure_network_from_cmdline

exec su -s /bin/sh sandbox -c 'DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 NUGET_PACKAGES=/opt/nuget/packages SANDBOX_WORKSPACE=/workspace SANDBOX_AGENT_ADDR=0.0.0.0:8081 /usr/local/bin/sandbox-agent'
EOF
  sudo chmod 0755 "${ROOTFS_DIR}/sbin/init"
  echo "nameserver 1.1.1.1" | sudo tee "${ROOTFS_DIR}/etc/resolv.conf" >/dev/null
  sudo rm -rf "${ROOTFS_DIR}/var/lib/apt/lists/"*
}

create_image() {
  local mount_dir
  mkdir -p "$(dirname "${ROOTFS_IMAGE}")"
  rm -f "${ROOTFS_IMAGE}"
  echo "创建 ext4 镜像: ${ROOTFS_IMAGE} (${ROOTFS_SIZE_MB}MiB)"
  truncate -s "${ROOTFS_SIZE_MB}M" "${ROOTFS_IMAGE}"
  mkfs.ext4 -F "${ROOTFS_IMAGE}" >/dev/null
  mount_dir="$(mktemp -d)"
  cleanup() {
    if mountpoint -q "${mount_dir}"; then
      sudo umount "${mount_dir}"
    fi
    rmdir "${mount_dir}" 2>/dev/null || true
  }
  trap cleanup EXIT
  sudo mount -o loop "${ROOTFS_IMAGE}" "${mount_dir}"
  sudo rsync -aHAX --numeric-ids "${ROOTFS_DIR}/" "${mount_dir}/"
  sync
  sudo umount "${mount_dir}"
  trap - EXIT
  rmdir "${mount_dir}" 2>/dev/null || true
}

main() {
  require_linux
  ensure_host_dependencies
  local go_bin
  go_bin="$(resolve_go)"
  build_agent "${go_bin}"
  bootstrap_rootfs
  install_runtime_packages
  configure_rootfs
  create_image
  echo "rootfs 构建完成: ${ROOTFS_IMAGE}"
}

main "$@"
