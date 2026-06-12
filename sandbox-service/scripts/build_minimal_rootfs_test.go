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

func TestStartScriptExportsAdminLogFile(t *testing.T) {
	content := readScript(t, "start-sandbox-service.sh")

	if !strings.Contains(content, `export SANDBOX_LOG_FILE="${LOG_FILE}"`) {
		t.Fatal("start-sandbox-service.sh should export SANDBOX_LOG_FILE for admin log tail")
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
