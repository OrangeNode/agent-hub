package host

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestResourceScannerClassifiesRunDirProcessAndTapResources(t *testing.T) {
	runDir := t.TempDir()
	liveDir := filepath.Join(runDir, "fc-live")
	orphanDir := filepath.Join(runDir, "fc-orphan")
	notVMDir := filepath.Join(runDir, "not-vm")
	if err := os.MkdirAll(liveDir, 0o755); err != nil {
		t.Fatalf("mkdir liveDir: %v", err)
	}
	if err := os.MkdirAll(orphanDir, 0o755); err != nil {
		t.Fatalf("mkdir orphanDir: %v", err)
	}
	if err := os.MkdirAll(notVMDir, 0o755); err != nil {
		t.Fatalf("mkdir notVMDir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(liveDir, "firecracker.socket"), []byte{}, 0o644); err != nil {
		t.Fatalf("write socket: %v", err)
	}
	if err := os.WriteFile(filepath.Join(orphanDir, "rootfs.ext4"), []byte("rootfs"), 0o644); err != nil {
		t.Fatalf("write rootfs: %v", err)
	}
	if err := os.WriteFile(filepath.Join(notVMDir, "readme.txt"), []byte("ignored"), 0o644); err != nil {
		t.Fatalf("write readme: %v", err)
	}

	socketPath := filepath.Join(liveDir, "firecracker.socket")
	scanner := ResourceScanner{
		RunDir:        runDir,
		TapNamePrefix: "tap-ah",
		ProcessLister: func() ([]ProcessInfo, error) {
			return []ProcessInfo{
				{PID: 1001, Args: "firecracker --api-sock " + socketPath},
			}, nil
		},
		TapLister: func() ([]TapInfo, error) {
			return []TapInfo{
				{Name: "tap-ah000001"},
				{Name: "tap-ah999999"},
				{Name: "eth0"},
			}, nil
		},
	}

	snapshot, err := scanner.Scan([]RuntimeSessionSnapshot{
		{
			VMID:           "fc-live",
			RunDir:         liveDir,
			TapName:        "tap-ah000001",
			FirecrackerPID: 1001,
		},
	}, WarmPoolSnapshot{})
	if err != nil {
		t.Fatalf("Scan() error = %v", err)
	}

	if snapshot.RunDir != runDir {
		t.Fatalf("RunDir = %q", snapshot.RunDir)
	}
	if len(snapshot.RunDirs) != 2 {
		t.Fatalf("RunDirs len = %d, want 2: %#v", len(snapshot.RunDirs), snapshot.RunDirs)
	}
	if findRunDir(snapshot.RunDirs, "not-vm") != nil {
		t.Fatalf("not-vm should not be classified as VM run dir: %#v", snapshot.RunDirs)
	}
	liveRunDir := findRunDir(snapshot.RunDirs, "fc-live")
	if liveRunDir == nil {
		t.Fatal("expected fc-live run dir")
	}
	if !liveRunDir.Bound || liveRunDir.ResourceStatus != "ACTIVE" {
		t.Fatalf("live run dir = %#v", *liveRunDir)
	}
	orphanRunDir := findRunDir(snapshot.RunDirs, "fc-orphan")
	if orphanRunDir == nil {
		t.Fatal("expected fc-orphan run dir")
	}
	if orphanRunDir.Bound || orphanRunDir.ResourceStatus != "ORPHAN_RUN_DIR" {
		t.Fatalf("orphan run dir = %#v", *orphanRunDir)
	}
	if !hasOrphan(snapshot.Orphans, "ORPHAN_RUN_DIR", "fc-orphan") {
		t.Fatalf("expected ORPHAN_RUN_DIR orphan: %#v", snapshot.Orphans)
	}

	if len(snapshot.FirecrackerProcesses) != 1 {
		t.Fatalf("process len = %d", len(snapshot.FirecrackerProcesses))
	}
	process := snapshot.FirecrackerProcesses[0]
	if process.PID != 1001 || process.APISocket != socketPath || process.VMID != "fc-live" || !process.Bound {
		t.Fatalf("process = %#v", process)
	}

	liveTap := findTap(snapshot.Taps, "tap-ah000001")
	if liveTap == nil || !liveTap.Bound || liveTap.ResourceStatus != "ACTIVE" {
		t.Fatalf("live tap = %#v", liveTap)
	}
	orphanTap := findTap(snapshot.Taps, "tap-ah999999")
	if orphanTap == nil || orphanTap.Bound || orphanTap.ResourceStatus != "ORPHAN_TAP" {
		t.Fatalf("orphan tap = %#v", orphanTap)
	}
	if findTap(snapshot.Taps, "eth0") != nil {
		t.Fatalf("eth0 should not be included: %#v", snapshot.Taps)
	}
	if !hasOrphan(snapshot.Orphans, "ORPHAN_TAP", "tap-ah999999") {
		t.Fatalf("expected ORPHAN_TAP orphan: %#v", snapshot.Orphans)
	}
}

func TestResourceScannerMarksUnboundFirecrackerProcessAsOrphan(t *testing.T) {
	runDir := t.TempDir()
	orphanDir := filepath.Join(runDir, "fc-orphan-process")
	if err := os.MkdirAll(orphanDir, 0o755); err != nil {
		t.Fatalf("mkdir orphanDir: %v", err)
	}
	socketPath := filepath.Join(orphanDir, "firecracker.socket")

	scanner := ResourceScanner{
		RunDir:        runDir,
		TapNamePrefix: "tap-ah",
		ProcessLister: func() ([]ProcessInfo, error) {
			return []ProcessInfo{
				{PID: 2002, Args: "firecracker --api-sock=" + socketPath},
			}, nil
		},
		TapLister: func() ([]TapInfo, error) {
			return nil, nil
		},
	}

	snapshot, err := scanner.Scan(nil, WarmPoolSnapshot{})
	if err != nil {
		t.Fatalf("Scan() error = %v", err)
	}

	if len(snapshot.FirecrackerProcesses) != 1 {
		t.Fatalf("process len = %d", len(snapshot.FirecrackerProcesses))
	}
	process := snapshot.FirecrackerProcesses[0]
	if process.Bound || process.ResourceStatus != "ORPHAN_PROCESS" || process.VMID != "fc-orphan-process" {
		t.Fatalf("process = %#v", process)
	}
	if !hasOrphan(snapshot.Orphans, "ORPHAN_PROCESS", "2002") {
		t.Fatalf("expected ORPHAN_PROCESS orphan: %#v", snapshot.Orphans)
	}
}

func TestResourceScannerTreatsProcessOutsideRunDirAsUnknownNotOrphan(t *testing.T) {
	runDir := t.TempDir()
	outsideSocket := filepath.Join(t.TempDir(), "fc-other", "firecracker.socket")
	scanner := ResourceScanner{
		RunDir: runDir,
		ProcessLister: func() ([]ProcessInfo, error) {
			return []ProcessInfo{
				{PID: 3003, Args: "firecracker --api-sock " + outsideSocket},
				{PID: 3004, Args: "grep firecracker"},
			}, nil
		},
		TapLister: func() ([]TapInfo, error) {
			return nil, nil
		},
	}

	snapshot, err := scanner.Scan(nil, WarmPoolSnapshot{})
	if err != nil {
		t.Fatalf("Scan() error = %v", err)
	}

	if len(snapshot.Orphans) != 0 {
		t.Fatalf("unexpected actionable orphans: %#v", snapshot.Orphans)
	}
	if len(snapshot.FirecrackerProcesses) != 2 {
		t.Fatalf("process len = %d", len(snapshot.FirecrackerProcesses))
	}
	for _, process := range snapshot.FirecrackerProcesses {
		if process.ResourceStatus != "UNKNOWN_PROCESS" {
			t.Fatalf("process should be unknown, got %#v", process)
		}
	}
}

func TestResourceScannerTreatsRecentUnboundRunDirAsStartingNotOrphan(t *testing.T) {
	runDir := t.TempDir()
	startingDir := filepath.Join(runDir, "fc-starting")
	if err := os.MkdirAll(startingDir, 0o755); err != nil {
		t.Fatalf("mkdir startingDir: %v", err)
	}
	now := time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC)
	if err := os.Chtimes(startingDir, now.Add(-5*time.Second), now.Add(-5*time.Second)); err != nil {
		t.Fatalf("chtimes startingDir: %v", err)
	}

	scanner := ResourceScanner{
		RunDir:              runDir,
		StartingGracePeriod: 30 * time.Second,
		Clock: func() time.Time {
			return now
		},
		ProcessLister: func() ([]ProcessInfo, error) {
			return nil, nil
		},
		TapLister: func() ([]TapInfo, error) {
			return nil, nil
		},
	}

	snapshot, err := scanner.Scan(nil, WarmPoolSnapshot{})
	if err != nil {
		t.Fatalf("Scan() error = %v", err)
	}

	item := findRunDir(snapshot.RunDirs, "fc-starting")
	if item == nil {
		t.Fatal("expected fc-starting run dir")
	}
	if item.ResourceStatus != "STARTING" {
		t.Fatalf("ResourceStatus = %q", item.ResourceStatus)
	}
	if len(snapshot.Orphans) != 0 {
		t.Fatalf("recent run dir should not be actionable orphan: %#v", snapshot.Orphans)
	}
}

func TestResourceScannerTreatsResourcesForStartingRunDirAsStarting(t *testing.T) {
	runDir := t.TempDir()
	startingDir := filepath.Join(runDir, "fc-starting")
	if err := os.MkdirAll(startingDir, 0o755); err != nil {
		t.Fatalf("mkdir startingDir: %v", err)
	}
	socketPath := filepath.Join(startingDir, "firecracker.socket")
	if err := os.WriteFile(socketPath, []byte{}, 0o644); err != nil {
		t.Fatalf("write socket: %v", err)
	}
	now := time.Date(2026, 6, 11, 10, 0, 0, 0, time.UTC)
	if err := os.Chtimes(startingDir, now.Add(-2*time.Minute), now.Add(-2*time.Minute)); err != nil {
		t.Fatalf("chtimes startingDir: %v", err)
	}

	scanner := ResourceScanner{
		RunDir:              runDir,
		TapNamePrefix:       "tap-ah",
		StartingGracePeriod: 10 * time.Minute,
		Clock: func() time.Time {
			return now
		},
		ProcessLister: func() ([]ProcessInfo, error) {
			return []ProcessInfo{
				{PID: 4004, Args: "firecracker --api-sock " + socketPath},
			}, nil
		},
		TapLister: func() ([]TapInfo, error) {
			return []TapInfo{{Name: "tap-ah000001"}}, nil
		},
	}

	snapshot, err := scanner.Scan(nil, WarmPoolSnapshot{})
	if err != nil {
		t.Fatalf("Scan() error = %v", err)
	}

	process := snapshot.FirecrackerProcesses[0]
	if process.ResourceStatus != "STARTING" {
		t.Fatalf("process ResourceStatus = %q", process.ResourceStatus)
	}
	tap := findTap(snapshot.Taps, "tap-ah000001")
	if tap == nil || tap.ResourceStatus != "STARTING" {
		t.Fatalf("tap = %#v", tap)
	}
	if len(snapshot.Orphans) != 0 {
		t.Fatalf("starting resources should not be actionable orphans: %#v", snapshot.Orphans)
	}
}

func TestIsFirecrackerCommandRequiresExecutableName(t *testing.T) {
	tests := []struct {
		name string
		args string
		want bool
	}{
		{name: "bare executable", args: "firecracker --api-sock /run/fc-1/firecracker.socket", want: true},
		{name: "absolute executable", args: "/usr/bin/firecracker --api-sock /run/fc-1/firecracker.socket", want: true},
		{name: "grep command", args: "grep firecracker", want: false},
		{name: "helper command", args: "firecracker-helper --api-sock /run/fc-1/firecracker.socket", want: false},
		{name: "empty", args: "", want: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := isFirecrackerCommand(tt.args); got != tt.want {
				t.Fatalf("isFirecrackerCommand(%q) = %v, want %v", tt.args, got, tt.want)
			}
		})
	}
}

func findRunDir(items []RunDirInfo, vmID string) *RunDirInfo {
	for i := range items {
		if items[i].VMID == vmID {
			return &items[i]
		}
	}
	return nil
}

func findTap(items []TapInfo, name string) *TapInfo {
	for i := range items {
		if items[i].Name == name {
			return &items[i]
		}
	}
	return nil
}

func hasOrphan(items []OrphanResource, resourceType string, id string) bool {
	for _, item := range items {
		if item.Type == resourceType && item.ID == id {
			return true
		}
	}
	return false
}
