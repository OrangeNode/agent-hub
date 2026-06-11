package host

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type AdminConfig struct {
	RunDir              string
	TapNamePrefix       string
	StartingGracePeriod time.Duration
	MaxLogTailLines     int
}

type AdminServer struct {
	Manager   *SessionManager
	WarmPool  *WarmPool
	Scanner   ResourceScanner
	LogFile   string
	StartedAt time.Time
	Config    AdminConfig
}

type AdminStatusResponse struct {
	ServiceHealthy  bool                     `json:"serviceHealthy"`
	StartedAt       time.Time                `json:"startedAt"`
	UptimeSeconds   int64                    `json:"uptimeSeconds"`
	ActiveSessions  int                      `json:"activeSessions"`
	Sessions        []RuntimeSessionSnapshot `json:"sessions"`
	WarmPool        WarmPoolSnapshot         `json:"warmPool"`
	Resources       ResourceSnapshot         `json:"resources"`
	ResourceSummary ResourceSummary          `json:"resourceSummary"`
}

type ResourceSummary struct {
	FirecrackerProcesses int   `json:"firecrackerProcesses"`
	TapDevices           int   `json:"tapDevices"`
	RunDirs              int   `json:"runDirs"`
	RunDirBytes          int64 `json:"runDirBytes"`
	OrphanResources      int   `json:"orphanResources"`
}

type ControlRequest struct {
	DryRun bool `json:"dryRun"`
}

type ControlResult struct {
	Success  bool                `json:"success"`
	DryRun   bool                `json:"dryRun"`
	Affected []string            `json:"affected"`
	Errors   []string            `json:"errors"`
	Items    []ControlResultItem `json:"items"`
}

type ControlResultItem struct {
	Type   string `json:"type"`
	ID     string `json:"id"`
	Path   string `json:"path,omitempty"`
	Action string `json:"action"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

func (s *AdminServer) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /internal/admin/sandbox/status", s.handleStatus)
	mux.HandleFunc("GET /internal/admin/sandbox/sessions", s.handleSessions)
	mux.HandleFunc("GET /internal/admin/sandbox/resources", s.handleResources)
	mux.HandleFunc("POST /internal/admin/sandbox/reap-idle", s.handleReapIdle)
	mux.HandleFunc("POST /internal/admin/sandbox/sessions/{sessionId}/close", s.handleCloseSession)
	mux.HandleFunc("POST /internal/admin/sandbox/vms/{vmId}/kill", s.handleKillVM)
	mux.HandleFunc("POST /internal/admin/sandbox/resources/cleanup-orphans", s.handleCleanupOrphans)
	mux.HandleFunc("POST /internal/admin/sandbox/run-dir/clear", s.handleClearRunDir)
	mux.HandleFunc("POST /internal/admin/sandbox/warm-pool/rebuild", s.handleRebuildWarmPool)
	mux.HandleFunc("GET /internal/admin/sandbox/logs/tail", s.handleTailLogs)
}

func (s *AdminServer) handleStatus(w http.ResponseWriter, _ *http.Request) {
	now := time.Now()
	sessions, warmPool, resources, err := s.snapshots(now)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	startedAt := s.StartedAt
	if startedAt.IsZero() {
		startedAt = now
	}
	writeJSON(w, http.StatusOK, AdminStatusResponse{
		ServiceHealthy:  true,
		StartedAt:       startedAt,
		UptimeSeconds:   int64(now.Sub(startedAt).Seconds()),
		ActiveSessions:  len(sessions),
		Sessions:        sessions,
		WarmPool:        warmPool,
		Resources:       resources,
		ResourceSummary: summarizeResources(resources),
	})
}

func (s *AdminServer) handleSessions(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.sessions(time.Now()))
}

func (s *AdminServer) handleResources(w http.ResponseWriter, _ *http.Request) {
	_, _, resources, err := s.snapshots(time.Now())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, resources)
}

func (s *AdminServer) handleReapIdle(w http.ResponseWriter, _ *http.Request) {
	if s.Manager == nil {
		writeError(w, http.StatusServiceUnavailable, "sandbox session 管理器未初始化")
		return
	}
	writeJSON(w, http.StatusOK, s.Manager.ReapIdleWithResult(time.Now()))
}

func (s *AdminServer) handleCloseSession(w http.ResponseWriter, r *http.Request) {
	if s.Manager == nil {
		writeError(w, http.StatusServiceUnavailable, "sandbox session 管理器未初始化")
		return
	}
	sessionID := strings.TrimSpace(r.PathValue("sessionId"))
	result := s.Manager.Close(sessionID)
	if len(result.ClosedSessions) == 0 && len(result.Errors) > 0 {
		writeJSON(w, http.StatusNotFound, result)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *AdminServer) handleKillVM(w http.ResponseWriter, r *http.Request) {
	req, err := decodeControlRequest(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	vmID := strings.TrimSpace(r.PathValue("vmId"))
	if vmID == "" {
		writeError(w, http.StatusBadRequest, "vmId 不能为空")
		return
	}

	result := ControlResult{DryRun: req.DryRun}
	if s.Manager != nil {
		for _, session := range s.Manager.Snapshot(time.Now()) {
			if session.VMID != vmID {
				continue
			}
			if req.DryRun {
				result.addItem("SESSION", session.SandboxSessionID, "", "CLOSE_SESSION", "DRY_RUN", "")
			} else {
				result.mergeReap(s.Manager.Close(session.SandboxSessionID))
			}
			result.finish()
			writeJSON(w, http.StatusOK, result)
			return
		}
	}
	if s.WarmPool != nil {
		if metadata, ok := findWarmPoolVM(s.WarmPool.Snapshot(), vmID); ok {
			if req.DryRun {
				result.addItem("WARM_POOL_VM", vmID, metadata.RunDir, "STOP_VM", "DRY_RUN", "")
			} else {
				stoppedMetadata, _, stopErr := s.WarmPool.StopByID(vmID)
				if stopErr != nil {
					result.addItem("WARM_POOL_VM", vmID, stoppedMetadata.RunDir, "STOP_VM", "FAILED", stopErr.Error())
				} else {
					result.addItem("WARM_POOL_VM", vmID, stoppedMetadata.RunDir, "STOP_VM", "SUCCESS", "")
				}
			}
			result.finish()
			writeJSON(w, http.StatusOK, result)
			return
		}
	}

	_, _, resources, scanErr := s.snapshots(time.Now())
	if scanErr != nil {
		writeError(w, http.StatusInternalServerError, scanErr.Error())
		return
	}
	runDir, safeErr := s.safeRunDir()
	if safeErr != nil {
		writeError(w, http.StatusBadRequest, safeErr.Error())
		return
	}
	for _, process := range resources.FirecrackerProcesses {
		if process.VMID != vmID || process.ResourceStatus != ResourceStatusOrphanProcess {
			continue
		}
		if _, err := safeRunDirChild(runDir, process.RunDir, true); err != nil {
			result.addItem(ResourceStatusOrphanProcess, strconv.Itoa(process.PID), process.RunDir, "KILL_PROCESS", "FAILED", err.Error())
			result.finish()
			writeJSON(w, http.StatusOK, result)
			return
		}
		if req.DryRun {
			result.addItem(ResourceStatusOrphanProcess, strconv.Itoa(process.PID), process.RunDir, "KILL_PROCESS", "DRY_RUN", "")
		} else if err := killProcess(process.PID); err != nil {
			result.addItem(ResourceStatusOrphanProcess, strconv.Itoa(process.PID), process.RunDir, "KILL_PROCESS", "FAILED", err.Error())
		} else {
			result.addItem(ResourceStatusOrphanProcess, strconv.Itoa(process.PID), process.RunDir, "KILL_PROCESS", "SUCCESS", "")
		}
		result.finish()
		writeJSON(w, http.StatusOK, result)
		return
	}
	writeError(w, http.StatusNotFound, "未找到可操作的 VM")
}

func (s *AdminServer) handleCleanupOrphans(w http.ResponseWriter, r *http.Request) {
	req, err := decodeControlRequest(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	runDir, err := s.safeRunDir()
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	_, _, resources, err := s.snapshots(time.Now())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	result := ControlResult{DryRun: req.DryRun}
	for _, orphan := range resources.Orphans {
		switch orphan.Type {
		case ResourceStatusOrphanRunDir:
			s.cleanupOrphanRunDir(&result, runDir, orphan, req.DryRun)
		case ResourceStatusOrphanProcess:
			s.cleanupOrphanProcess(&result, runDir, orphan, req.DryRun)
		case ResourceStatusOrphanTap:
			s.cleanupOrphanTap(&result, orphan, req.DryRun)
		}
	}
	result.finish()
	writeJSON(w, http.StatusOK, result)
}

func (s *AdminServer) handleClearRunDir(w http.ResponseWriter, r *http.Request) {
	req, err := decodeControlRequest(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	runDir, err := s.safeRunDir()
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	entries, err := os.ReadDir(runDir)
	if err != nil {
		if os.IsNotExist(err) {
			writeJSON(w, http.StatusOK, ControlResult{Success: true, DryRun: req.DryRun})
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	result := ControlResult{DryRun: req.DryRun}
	for _, entry := range entries {
		child := filepath.Join(runDir, entry.Name())
		if err := ensureChildUnder(runDir, child, false); err != nil {
			result.addItem("RUN_DIR_CHILD", entry.Name(), child, "DELETE", "FAILED", err.Error())
			continue
		}
		if req.DryRun {
			result.addItem("RUN_DIR_CHILD", entry.Name(), child, "DELETE", "DRY_RUN", "")
			continue
		}
		if err := os.RemoveAll(child); err != nil {
			result.addItem("RUN_DIR_CHILD", entry.Name(), child, "DELETE", "FAILED", err.Error())
			continue
		}
		result.addItem("RUN_DIR_CHILD", entry.Name(), child, "DELETE", "SUCCESS", "")
	}
	result.finish()
	writeJSON(w, http.StatusOK, result)
}

func (s *AdminServer) handleRebuildWarmPool(w http.ResponseWriter, _ *http.Request) {
	if s.WarmPool == nil {
		writeError(w, http.StatusServiceUnavailable, "warm pool 未初始化")
		return
	}
	result := ControlResult{}
	if err := s.WarmPool.Rebuild(); err != nil {
		result.addItem("WARM_POOL", "default", "", "REBUILD", "FAILED", err.Error())
	} else {
		result.addItem("WARM_POOL", "default", "", "REBUILD", "SUCCESS", "")
	}
	result.finish()
	writeJSON(w, http.StatusOK, result)
}

func (s *AdminServer) handleTailLogs(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(s.LogFile) == "" {
		writeError(w, http.StatusBadRequest, "日志文件未配置")
		return
	}
	lines := parsePositiveInt(r.URL.Query().Get("lines"), 100)
	maxLines := s.Config.MaxLogTailLines
	if maxLines <= 0 {
		maxLines = 500
	}
	if lines > maxLines {
		lines = maxLines
	}
	content, err := tailFile(s.LogFile, lines)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"lines": lines, "content": content})
}

func (s *AdminServer) snapshots(now time.Time) ([]RuntimeSessionSnapshot, WarmPoolSnapshot, ResourceSnapshot, error) {
	sessions := s.sessions(now)
	warmPool := WarmPoolSnapshot{}
	if s.WarmPool != nil {
		warmPool = s.WarmPool.Snapshot()
	}
	resources, err := s.scanner().Scan(sessions, warmPool)
	if err != nil {
		return nil, WarmPoolSnapshot{}, ResourceSnapshot{}, err
	}
	return sessions, warmPool, resources, nil
}

func (s *AdminServer) sessions(now time.Time) []RuntimeSessionSnapshot {
	if s.Manager == nil {
		return nil
	}
	return s.Manager.Snapshot(now)
}

func (s *AdminServer) scanner() ResourceScanner {
	scanner := s.Scanner
	if strings.TrimSpace(scanner.RunDir) == "" {
		scanner.RunDir = strings.TrimSpace(s.Config.RunDir)
	}
	if strings.TrimSpace(scanner.TapNamePrefix) == "" {
		scanner.TapNamePrefix = strings.TrimSpace(s.Config.TapNamePrefix)
	}
	if scanner.StartingGracePeriod <= 0 {
		scanner.StartingGracePeriod = s.Config.StartingGracePeriod
	}
	return scanner
}

func summarizeResources(resources ResourceSnapshot) ResourceSummary {
	return ResourceSummary{
		FirecrackerProcesses: len(resources.FirecrackerProcesses),
		TapDevices:           len(resources.Taps),
		RunDirs:              len(resources.RunDirs),
		RunDirBytes:          resources.RunDirBytes,
		OrphanResources:      len(resources.Orphans),
	}
}

func findWarmPoolVM(snapshot WarmPoolSnapshot, vmID string) (VMMetadata, bool) {
	for _, vm := range snapshot.VMs {
		if vm.ID == vmID {
			return vm, true
		}
	}
	return VMMetadata{}, false
}

func decodeControlRequest(r *http.Request) (ControlRequest, error) {
	defer r.Body.Close()
	var req ControlRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		if errors.Is(err, io.EOF) {
			return req, nil
		}
		return ControlRequest{}, fmt.Errorf("请求体不是有效的 JSON: %w", err)
	}
	return req, nil
}

func (s *AdminServer) cleanupOrphanRunDir(result *ControlResult, runDir string, orphan OrphanResource, dryRun bool) {
	path, err := safeRunDirChild(runDir, orphan.Path, true)
	if err != nil {
		result.addItem(orphan.Type, orphan.ID, orphan.Path, "DELETE", "FAILED", err.Error())
		return
	}
	if dryRun {
		result.addItem(orphan.Type, orphan.ID, path, "DELETE", "DRY_RUN", "")
		return
	}
	if err := os.RemoveAll(path); err != nil {
		result.addItem(orphan.Type, orphan.ID, path, "DELETE", "FAILED", err.Error())
		return
	}
	result.addItem(orphan.Type, orphan.ID, path, "DELETE", "SUCCESS", "")
}

func (s *AdminServer) cleanupOrphanProcess(result *ControlResult, runDir string, orphan OrphanResource, dryRun bool) {
	pid, err := strconv.Atoi(orphan.ID)
	if err != nil {
		result.addItem(orphan.Type, orphan.ID, orphan.Path, "KILL_PROCESS", "FAILED", "进程 ID 无效")
		return
	}
	if _, err := safeRunDirChild(runDir, orphan.Path, true); err != nil {
		result.addItem(orphan.Type, orphan.ID, orphan.Path, "KILL_PROCESS", "FAILED", err.Error())
		return
	}
	if dryRun {
		result.addItem(orphan.Type, orphan.ID, orphan.Path, "KILL_PROCESS", "DRY_RUN", "")
		return
	}
	if err := killProcess(pid); err != nil {
		result.addItem(orphan.Type, orphan.ID, orphan.Path, "KILL_PROCESS", "FAILED", err.Error())
		return
	}
	result.addItem(orphan.Type, orphan.ID, orphan.Path, "KILL_PROCESS", "SUCCESS", "")
}

func (s *AdminServer) cleanupOrphanTap(result *ControlResult, orphan OrphanResource, dryRun bool) {
	if !strings.HasPrefix(orphan.ID, s.tapPrefix()) {
		result.addItem(orphan.Type, orphan.ID, "", "DELETE_TAP", "FAILED", "tap 名称不属于当前 sandbox 配置")
		return
	}
	if dryRun {
		result.addItem(orphan.Type, orphan.ID, "", "DELETE_TAP", "DRY_RUN", "")
		return
	}
	if err := deleteTap(orphan.ID); err != nil {
		result.addItem(orphan.Type, orphan.ID, "", "DELETE_TAP", "FAILED", err.Error())
		return
	}
	result.addItem(orphan.Type, orphan.ID, "", "DELETE_TAP", "SUCCESS", "")
}

func (s *AdminServer) safeRunDir() (string, error) {
	value := firstNonEmpty(strings.TrimSpace(s.Scanner.RunDir), strings.TrimSpace(s.Config.RunDir), defaultFirecrackerRunDir)
	runDir, err := filepath.Abs(value)
	if err != nil {
		return "", err
	}
	runDir = filepath.Clean(runDir)
	if filepath.Dir(runDir) == runDir {
		return "", fmt.Errorf("拒绝操作根目录: %s", runDir)
	}
	if filepath.Base(runDir) != "run" {
		return "", fmt.Errorf("运行目录必须指向名为 run 的目录: %s", runDir)
	}
	return runDir, nil
}

func safeRunDirChild(runDir string, child string, requireVMDir bool) (string, error) {
	if strings.TrimSpace(child) == "" {
		return "", errors.New("路径不能为空")
	}
	absRunDir, err := filepath.Abs(runDir)
	if err != nil {
		return "", err
	}
	absChild, err := filepath.Abs(child)
	if err != nil {
		return "", err
	}
	absRunDir = filepath.Clean(absRunDir)
	absChild = filepath.Clean(absChild)
	if err := ensureChildUnder(absRunDir, absChild, requireVMDir); err != nil {
		return "", err
	}
	return absChild, nil
}

func ensureChildUnder(root string, child string, requireVMDir bool) error {
	rel, err := filepath.Rel(root, child)
	if err != nil {
		return err
	}
	if rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) || filepath.IsAbs(rel) {
		return fmt.Errorf("路径不在运行目录下: %s", child)
	}
	if requireVMDir && !strings.HasPrefix(filepath.Base(child), "fc-") {
		return fmt.Errorf("运行目录不是 fc-* 子目录: %s", child)
	}
	return nil
}

func (s *AdminServer) tapPrefix() string {
	if strings.TrimSpace(s.Scanner.TapNamePrefix) != "" {
		return strings.TrimSpace(s.Scanner.TapNamePrefix)
	}
	if strings.TrimSpace(s.Config.TapNamePrefix) != "" {
		return strings.TrimSpace(s.Config.TapNamePrefix)
	}
	return defaultTapNamePrefix
}

func (r *ControlResult) mergeReap(reap ReapResult) {
	for _, sessionID := range reap.ClosedSessions {
		r.addItem("SESSION", sessionID, "", "CLOSE_SESSION", "SUCCESS", "")
	}
	for _, vmID := range reap.StoppedVMs {
		r.addItem("VM", vmID, "", "STOP_VM", "SUCCESS", "")
	}
	for _, message := range reap.Errors {
		r.Errors = append(r.Errors, message)
	}
}

func (r *ControlResult) addItem(resourceType string, id string, path string, action string, status string, errorMessage string) {
	r.Items = append(r.Items, ControlResultItem{
		Type:   resourceType,
		ID:     id,
		Path:   path,
		Action: action,
		Status: status,
		Error:  errorMessage,
	})
	if path != "" {
		r.Affected = append(r.Affected, path)
	} else if id != "" {
		r.Affected = append(r.Affected, id)
	}
	if errorMessage != "" {
		r.Errors = append(r.Errors, errorMessage)
	}
}

func (r *ControlResult) finish() {
	sort.Strings(r.Affected)
	r.Success = len(r.Errors) == 0
}

func killProcess(pid int) error {
	process, err := os.FindProcess(pid)
	if err != nil {
		return err
	}
	return process.Kill()
}

func deleteTap(name string) error {
	if !isValidTapName(name) {
		return fmt.Errorf("tap 名称不合法: %s", name)
	}
	return exec.Command("ip", "link", "delete", name).Run()
}

func parsePositiveInt(value string, fallback int) int {
	parsed, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func tailFile(path string, lines int) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	buffer := make([]string, 0, lines)
	for scanner.Scan() {
		line := scanner.Text()
		if len(buffer) < lines {
			buffer = append(buffer, line)
			continue
		}
		copy(buffer, buffer[1:])
		buffer[len(buffer)-1] = line
	}
	if err := scanner.Err(); err != nil {
		return "", err
	}
	return strings.Join(buffer, "\n"), nil
}
