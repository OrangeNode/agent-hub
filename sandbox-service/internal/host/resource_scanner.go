package host

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	ResourceStatusActive         = "ACTIVE"
	ResourceStatusStarting       = "STARTING"
	ResourceStatusOrphanRunDir   = "ORPHAN_RUN_DIR"
	ResourceStatusOrphanProcess  = "ORPHAN_PROCESS"
	ResourceStatusOrphanTap      = "ORPHAN_TAP"
	ResourceStatusUnknownProcess = "UNKNOWN_PROCESS"
)

type ResourceScanner struct {
	RunDir              string
	TapNamePrefix       string
	StartingGracePeriod time.Duration
	ProcessLister       func() ([]ProcessInfo, error)
	TapLister           func() ([]TapInfo, error)
	Clock               func() time.Time
}

type ResourceSnapshot struct {
	ScannedAt            time.Time        `json:"scannedAt"`
	RunDir               string           `json:"runDir"`
	RunDirBytes          int64            `json:"runDirBytes"`
	RunDirs              []RunDirInfo     `json:"runDirs"`
	FirecrackerProcesses []ProcessInfo    `json:"firecrackerProcesses"`
	Taps                 []TapInfo        `json:"taps"`
	Orphans              []OrphanResource `json:"orphans"`
}

type RunDirInfo struct {
	VMID           string    `json:"vmId"`
	Path           string    `json:"path"`
	SizeBytes      int64     `json:"sizeBytes"`
	CreatedAt      time.Time `json:"createdAt"`
	HasSocket      bool      `json:"hasSocket"`
	Bound          bool      `json:"bound"`
	ResourceStatus string    `json:"resourceStatus"`
}

type ProcessInfo struct {
	PID            int    `json:"pid"`
	Args           string `json:"args"`
	APISocket      string `json:"apiSocket,omitempty"`
	VMID           string `json:"vmId,omitempty"`
	RunDir         string `json:"runDir,omitempty"`
	Bound          bool   `json:"bound"`
	ResourceStatus string `json:"resourceStatus,omitempty"`
}

type TapInfo struct {
	Name           string `json:"name"`
	VMID           string `json:"vmId,omitempty"`
	Bound          bool   `json:"bound"`
	ResourceStatus string `json:"resourceStatus,omitempty"`
}

type OrphanResource struct {
	Type   string `json:"type"`
	ID     string `json:"id"`
	Path   string `json:"path,omitempty"`
	Reason string `json:"reason,omitempty"`
}

func (s ResourceScanner) Scan(sessions []RuntimeSessionSnapshot, warmPool WarmPoolSnapshot) (ResourceSnapshot, error) {
	runDir, err := filepath.Abs(firstNonEmpty(strings.TrimSpace(s.RunDir), defaultFirecrackerRunDir))
	if err != nil {
		return ResourceSnapshot{}, err
	}

	processes, err := s.listProcesses()
	if err != nil {
		return ResourceSnapshot{}, err
	}
	taps, err := s.listTaps()
	if err != nil {
		return ResourceSnapshot{}, err
	}

	boundRunDirs := make(map[string]string)
	boundTaps := make(map[string]string)
	boundPIDs := make(map[int]string)
	for _, session := range sessions {
		if session.RunDir != "" {
			if abs, err := filepath.Abs(session.RunDir); err == nil {
				boundRunDirs[abs] = session.VMID
			}
		}
		if session.TapName != "" {
			boundTaps[session.TapName] = session.VMID
		}
		if session.FirecrackerPID > 0 {
			boundPIDs[session.FirecrackerPID] = session.VMID
		}
	}
	for _, vm := range warmPool.VMs {
		if vm.RunDir != "" {
			if abs, err := filepath.Abs(vm.RunDir); err == nil {
				boundRunDirs[abs] = vm.ID
			}
		}
		if vm.TapName != "" {
			boundTaps[vm.TapName] = vm.ID
		}
		if vm.PID > 0 {
			boundPIDs[vm.PID] = vm.ID
		}
	}

	scannedAt := s.now()
	snapshot := ResourceSnapshot{
		ScannedAt: scannedAt,
		RunDir:    runDir,
	}

	runDirs, err := scanRunDirs(runDir, boundRunDirs, scannedAt, s.StartingGracePeriod)
	if err != nil {
		return ResourceSnapshot{}, err
	}
	snapshot.RunDirs = runDirs
	for _, item := range runDirs {
		snapshot.RunDirBytes += item.SizeBytes
		if item.ResourceStatus == ResourceStatusOrphanRunDir {
			snapshot.Orphans = append(snapshot.Orphans, OrphanResource{
				Type:   ResourceStatusOrphanRunDir,
				ID:     item.VMID,
				Path:   item.Path,
				Reason: "运行目录未绑定当前 session 或 warm pool VM",
			})
		}
	}

	for _, process := range processes {
		process.APISocket = firstNonEmpty(process.APISocket, parseAPISocket(process.Args))
		if runDirPath, vmID, ok := processRunDir(process.APISocket, runDir); ok {
			process.RunDir = runDirPath
			process.VMID = vmID
		}
		if vmID, ok := boundPIDs[process.PID]; ok {
			process.Bound = true
			process.VMID = firstNonEmpty(process.VMID, vmID)
		} else if process.RunDir != "" {
			if abs, err := filepath.Abs(process.RunDir); err == nil {
				if vmID, ok := boundRunDirs[abs]; ok {
					process.Bound = true
					process.VMID = firstNonEmpty(process.VMID, vmID)
				}
			}
		}
		if process.Bound {
			process.ResourceStatus = ResourceStatusActive
		} else if process.RunDir != "" && strings.HasPrefix(process.VMID, "fc-") {
			process.ResourceStatus = ResourceStatusOrphanProcess
			snapshot.Orphans = append(snapshot.Orphans, OrphanResource{
				Type:   ResourceStatusOrphanProcess,
				ID:     strconv.Itoa(process.PID),
				Path:   process.RunDir,
				Reason: "Firecracker 进程未绑定当前 session 或 warm pool VM",
			})
		} else {
			process.ResourceStatus = ResourceStatusUnknownProcess
		}
		snapshot.FirecrackerProcesses = append(snapshot.FirecrackerProcesses, process)
	}

	for _, tap := range taps {
		if !strings.HasPrefix(tap.Name, s.tapPrefix()) {
			continue
		}
		if vmID, ok := boundTaps[tap.Name]; ok {
			tap.Bound = true
			tap.VMID = vmID
			tap.ResourceStatus = ResourceStatusActive
		} else {
			tap.ResourceStatus = ResourceStatusOrphanTap
			snapshot.Orphans = append(snapshot.Orphans, OrphanResource{
				Type:   ResourceStatusOrphanTap,
				ID:     tap.Name,
				Reason: "tap 设备未绑定当前 session 或 warm pool VM",
			})
		}
		snapshot.Taps = append(snapshot.Taps, tap)
	}

	sort.Slice(snapshot.Orphans, func(i, j int) bool {
		if snapshot.Orphans[i].Type == snapshot.Orphans[j].Type {
			return snapshot.Orphans[i].ID < snapshot.Orphans[j].ID
		}
		return snapshot.Orphans[i].Type < snapshot.Orphans[j].Type
	})
	return snapshot, nil
}

func (s ResourceScanner) listProcesses() ([]ProcessInfo, error) {
	if s.ProcessLister != nil {
		return s.ProcessLister()
	}
	return defaultProcessLister()
}

func (s ResourceScanner) listTaps() ([]TapInfo, error) {
	if s.TapLister != nil {
		return s.TapLister()
	}
	return defaultTapLister()
}

func (s ResourceScanner) now() time.Time {
	if s.Clock != nil {
		return s.Clock()
	}
	return time.Now()
}

func (s ResourceScanner) tapPrefix() string {
	if strings.TrimSpace(s.TapNamePrefix) == "" {
		return defaultTapNamePrefix
	}
	return strings.TrimSpace(s.TapNamePrefix)
}

func scanRunDirs(runDir string, boundRunDirs map[string]string, now time.Time, startingGracePeriod time.Duration) ([]RunDirInfo, error) {
	entries, err := os.ReadDir(runDir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}

	var runDirs []RunDirInfo
	for _, entry := range entries {
		if !entry.IsDir() || !strings.HasPrefix(entry.Name(), "fc-") {
			continue
		}
		path := filepath.Join(runDir, entry.Name())
		info, err := entry.Info()
		if err != nil {
			return nil, err
		}
		sizeBytes, err := dirSize(path)
		if err != nil {
			return nil, err
		}
		absPath, err := filepath.Abs(path)
		if err != nil {
			return nil, err
		}
		_, hasSocketErr := os.Stat(filepath.Join(path, "firecracker.socket"))
		item := RunDirInfo{
			VMID:      entry.Name(),
			Path:      absPath,
			SizeBytes: sizeBytes,
			CreatedAt: info.ModTime(),
			HasSocket: hasSocketErr == nil,
		}
		if _, ok := boundRunDirs[absPath]; ok {
			item.Bound = true
			item.ResourceStatus = ResourceStatusActive
		} else if isStartingRunDir(item.CreatedAt, now, startingGracePeriod) {
			item.ResourceStatus = ResourceStatusStarting
		} else {
			item.ResourceStatus = ResourceStatusOrphanRunDir
		}
		runDirs = append(runDirs, item)
	}
	sort.Slice(runDirs, func(i, j int) bool {
		return runDirs[i].VMID < runDirs[j].VMID
	})
	return runDirs, nil
}

func isStartingRunDir(createdAt time.Time, now time.Time, startingGracePeriod time.Duration) bool {
	if startingGracePeriod <= 0 || now.IsZero() || createdAt.IsZero() {
		return false
	}
	age := now.Sub(createdAt)
	return age >= 0 && age < startingGracePeriod
}

func dirSize(path string) (int64, error) {
	var size int64
	err := filepath.WalkDir(path, func(_ string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		size += info.Size()
		return nil
	})
	return size, err
}

func parseAPISocket(args string) string {
	fields := strings.Fields(args)
	for index, field := range fields {
		if field == "--api-sock" && index+1 < len(fields) {
			return fields[index+1]
		}
		if strings.HasPrefix(field, "--api-sock=") {
			return strings.TrimPrefix(field, "--api-sock=")
		}
	}
	return ""
}

func processRunDir(apiSocket string, rootRunDir string) (string, string, bool) {
	if strings.TrimSpace(apiSocket) == "" {
		return "", "", false
	}
	absSocket, err := filepath.Abs(apiSocket)
	if err != nil {
		return "", "", false
	}
	absRoot, err := filepath.Abs(rootRunDir)
	if err != nil {
		return "", "", false
	}
	vmRunDir := filepath.Dir(absSocket)
	rel, err := filepath.Rel(absRoot, vmRunDir)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) || filepath.IsAbs(rel) {
		return "", "", false
	}
	vmID := filepath.Base(vmRunDir)
	if !strings.HasPrefix(vmID, "fc-") {
		return "", "", false
	}
	return vmRunDir, vmID, true
}

func defaultProcessLister() ([]ProcessInfo, error) {
	output, err := exec.Command("ps", "-eo", "pid=,args=").Output()
	if err != nil {
		return nil, fmt.Errorf("扫描 Firecracker 进程失败: %w", err)
	}
	var processes []ProcessInfo
	scanner := bufio.NewScanner(bytes.NewReader(output))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || !strings.Contains(line, "firecracker") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		pid, err := strconv.Atoi(fields[0])
		if err != nil {
			continue
		}
		args := strings.TrimSpace(strings.TrimPrefix(line, fields[0]))
		if !isFirecrackerCommand(args) {
			continue
		}
		processes = append(processes, ProcessInfo{PID: pid, Args: args})
	}
	return processes, scanner.Err()
}

func isFirecrackerCommand(args string) bool {
	fields := strings.Fields(args)
	if len(fields) == 0 {
		return false
	}
	return filepath.Base(fields[0]) == "firecracker"
}

func defaultTapLister() ([]TapInfo, error) {
	output, err := exec.Command("ip", "-o", "link", "show").Output()
	if err != nil {
		return nil, fmt.Errorf("扫描 tap 设备失败: %w", err)
	}
	var taps []TapInfo
	scanner := bufio.NewScanner(bytes.NewReader(output))
	for scanner.Scan() {
		name := parseIPLinkName(scanner.Text())
		if name != "" {
			taps = append(taps, TapInfo{Name: name})
		}
	}
	return taps, scanner.Err()
}

func parseIPLinkName(line string) string {
	firstColon := strings.Index(line, ":")
	if firstColon < 0 {
		return ""
	}
	rest := strings.TrimSpace(line[firstColon+1:])
	secondColon := strings.Index(rest, ":")
	if secondColon < 0 {
		return ""
	}
	name := strings.TrimSpace(rest[:secondColon])
	if at := strings.Index(name, "@"); at >= 0 {
		name = name[:at]
	}
	return name
}
