package host

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFirecrackerFactoryValidateRequiresKernelAndRootfs(t *testing.T) {
	factory := &FirecrackerFactory{VMCpu: 1, VMMemoryMB: 128}

	err := factory.validate()
	if err == nil {
		t.Fatal("expected validation error")
	}
	if err.Error() != "SANDBOX_KERNEL_IMAGE 不能为空" {
		t.Fatalf("error = %q", err.Error())
	}
}

func TestFirecrackerFactoryAllocatesSequentialGuestNetworks(t *testing.T) {
	factory := &FirecrackerFactory{
		GuestNetworkCIDR: "172.16.200.0/29",
		TapNamePrefix:    "tap-t",
		GuestAgentPort:   8081,
	}

	first, err := factory.allocateNetwork()
	if err != nil {
		t.Fatalf("allocateNetwork(first) error = %v", err)
	}
	second, err := factory.allocateNetwork()
	if err != nil {
		t.Fatalf("allocateNetwork(second) error = %v", err)
	}

	if first.TapName != "tap-t000001" {
		t.Fatalf("first tap = %q", first.TapName)
	}
	if first.HostIP.String() != "172.16.200.1" {
		t.Fatalf("first host IP = %s", first.HostIP)
	}
	if first.GuestIP.String() != "172.16.200.2" {
		t.Fatalf("first guest IP = %s", first.GuestIP)
	}
	if first.AgentURL != "http://172.16.200.2:8081" {
		t.Fatalf("first agent URL = %q", first.AgentURL)
	}
	if second.HostIP.String() != "172.16.200.5" {
		t.Fatalf("second host IP = %s", second.HostIP)
	}
	if second.GuestIP.String() != "172.16.200.6" {
		t.Fatalf("second guest IP = %s", second.GuestIP)
	}
}

func TestFirecrackerFactoryRejectsExhaustedNetworkPool(t *testing.T) {
	factory := &FirecrackerFactory{GuestNetworkCIDR: "172.16.200.0/30"}
	if _, err := factory.allocateNetwork(); err != nil {
		t.Fatalf("allocateNetwork(first) error = %v", err)
	}
	if _, err := factory.allocateNetwork(); err == nil {
		t.Fatal("expected exhausted network pool error")
	}
}

func TestFirecrackerFactoryValidateAcceptsExistingAssets(t *testing.T) {
	dir := t.TempDir()
	kernel := filepath.Join(dir, "vmlinux.bin")
	rootfs := filepath.Join(dir, "rootfs.ext4")
	if err := os.WriteFile(kernel, []byte("kernel"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(rootfs, []byte("rootfs"), 0o644); err != nil {
		t.Fatal(err)
	}

	factory := &FirecrackerFactory{
		KernelImagePath:  kernel,
		RootDrivePath:    rootfs,
		GuestNetworkCIDR: "172.16.200.0/24",
		TapNamePrefix:    "tap-ah",
		VMCpu:            1,
		VMMemoryMB:       128,
	}

	if err := factory.validate(); err != nil {
		t.Fatalf("validate() error = %v", err)
	}
}
