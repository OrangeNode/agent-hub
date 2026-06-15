package scripts

import (
	"os"
	"strings"
	"testing"
)

func TestMinimalRootfsInitConfiguresGuestNetworkFromKernelIPArg(t *testing.T) {
	content := readScript(t, "build-minimal-rootfs.sh")

	for _, expected := range []string{
		"for arg in $(cat /proc/cmdline)",
		"ip=*)",
		"ip addr replace",
		"ip route replace default via",
	} {
		if !strings.Contains(content, expected) {
			t.Fatalf("build-minimal-rootfs.sh missing %q", expected)
		}
	}
}

func TestMinimalRootfsIncludesDocxSkillRuntimeDependencies(t *testing.T) {
	content := readScript(t, "build-minimal-rootfs.sh")

	for _, expected := range []string{
		`ROOTFS_SIZE_MB="${SANDBOX_ROOTFS_SIZE_MB:-8192}"`,
		"dotnet-sdk-8.0",
		"pandoc",
		"python3-docx",
		"python3-lxml",
		"fontconfig",
		"fonts-dejavu-core",
		"fonts-liberation",
		"fonts-noto-cjk",
		"fonts-noto-core",
		"libreoffice-writer",
	} {
		if !strings.Contains(content, expected) {
			t.Fatalf("build-minimal-rootfs.sh missing docx runtime dependency %q", expected)
		}
	}
}

func TestStartScriptExportsAdminLogFile(t *testing.T) {
	content := readScript(t, "start-sandbox-service.sh")

	if !strings.Contains(content, `export SANDBOX_LOG_FILE="${LOG_FILE}"`) {
		t.Fatal("start-sandbox-service.sh should export SANDBOX_LOG_FILE for admin log tail")
	}
}

func TestStartScriptLoadsEnvFileBeforeReadingConfig(t *testing.T) {
	content := readScript(t, "start-sandbox-service.sh")

	for _, expected := range []string{
		`ENV_FILE="${SANDBOX_ENV_FILE:-${SERVICE_DIR}/.env}"`,
		`load_env_file "${ENV_FILE}"`,
		`SANDBOX_INTERNAL_TOKEN`,
	} {
		if !strings.Contains(content, expected) {
			t.Fatalf("start-sandbox-service.sh missing env-file support %q", expected)
		}
	}
}

func TestStopScriptLoadsEnvFileBeforeReadingConfig(t *testing.T) {
	content := readScript(t, "stop-sandbox-service.sh")

	for _, expected := range []string{
		`ENV_FILE="${SANDBOX_ENV_FILE:-${SERVICE_DIR}/.env}"`,
		`load_env_file "${ENV_FILE}"`,
	} {
		if !strings.Contains(content, expected) {
			t.Fatalf("stop-sandbox-service.sh missing env-file support %q", expected)
		}
	}
}

func TestSandboxEnvExampleIncludesRequiredLocalDefaults(t *testing.T) {
	content := readScript(t, "../.env.example")

	for _, expected := range []string{
		"SANDBOX_INTERNAL_TOKEN=dev-sandbox-token",
		"SANDBOX_LISTEN_ADDR=0.0.0.0:8090",
		"SANDBOX_VM_MODE=firecracker",
		"SANDBOX_OBJECT_ENDPOINT=127.0.0.1:9000",
		"SANDBOX_OBJECT_BUCKET=agent-hub-sandbox-artifacts",
	} {
		if !strings.Contains(content, expected) {
			t.Fatalf(".env.example missing local default %q", expected)
		}
	}
}

func readScript(t *testing.T, path string) string {
	t.Helper()

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(data)
}
