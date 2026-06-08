package agent

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"sync"

	"agent-hub/sandbox-service/internal/policy"
)

const (
	defaultTimeoutSeconds = 60
	defaultMaxExcerpt     = 64 * 1024
)

type ExecRunner interface {
	Exec(ctx context.Context, req ExecRequest, emit func(ExecEvent)) ExecResult
}

type Server struct {
	executor ExecRunner
	files    *FileService
	policy   policy.CommandPolicy
}

func NewServer(executor ExecRunner, files *FileService) *Server {
	if executor == nil {
		executor = NewExecutor(defaultMaxExcerpt)
	}
	if files == nil {
		files = NewFileService("/workspace")
	}
	return &Server{
		executor: executor,
		files:    files,
		policy:   policy.NewCommandPolicy(8192, 300),
	}
}

func (s *Server) Router() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("POST /exec/stream", s.handleExecStream)
	mux.HandleFunc("GET /files", s.handleListFiles)
	mux.HandleFunc("GET /files/content", s.handleReadFile)
	mux.HandleFunc("POST /files/content", s.handleWriteFile)
	mux.HandleFunc("PUT /files/content", s.handleWriteFile)
	mux.HandleFunc("GET /artifacts", s.handleArtifacts)
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *Server) handleExecStream(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")

	var req ExecRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeSSE(w, "error", map[string]string{"message": "请求体不是有效的 JSON"})
		return
	}
	if req.WorkingDir == "" {
		req.WorkingDir = "/workspace"
	}
	if req.TimeoutSeconds == 0 {
		req.TimeoutSeconds = defaultTimeoutSeconds
	}
	if err := s.policy.Validate(policy.CommandRequest{
		Command:        req.Command,
		WorkingDir:     req.WorkingDir,
		TimeoutSeconds: req.TimeoutSeconds,
	}); err != nil {
		writeSSE(w, "error", map[string]string{"message": err.Error()})
		return
	}

	writeSSE(w, "started", map[string]string{"message": "命令已开始执行"})
	var mu sync.Mutex
	result := s.executor.Exec(r.Context(), req, func(event ExecEvent) {
		mu.Lock()
		defer mu.Unlock()
		writeSSE(w, event.Type, map[string]string{"content": event.Content})
	})
	if result.Error != "" {
		writeSSE(w, "error", map[string]string{"message": result.Error})
	}
	writeSSE(w, "completed", result)
}

func (s *Server) handleListFiles(w http.ResponseWriter, r *http.Request) {
	recursive, err := strconv.ParseBool(defaultString(r.URL.Query().Get("recursive"), "false"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "recursive 参数必须是 true 或 false")
		return
	}
	maxFiles, err := parseIntDefault(r.URL.Query().Get("maxFiles"), 100)
	if err != nil {
		writeError(w, http.StatusBadRequest, "maxFiles 参数必须是数字")
		return
	}
	files, err := s.files.ListFiles(r.URL.Query().Get("path"), recursive, maxFiles)
	if err != nil {
		writeError(w, http.StatusBadRequest, "列出文件失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"files": files})
}

func (s *Server) handleReadFile(w http.ResponseWriter, r *http.Request) {
	maxBytes, err := parseInt64Default(r.URL.Query().Get("maxBytes"), 0)
	if err != nil {
		writeError(w, http.StatusBadRequest, "maxBytes 参数必须是数字")
		return
	}
	content, err := s.files.ReadFile(r.URL.Query().Get("path"), maxBytes)
	if err != nil {
		writeError(w, http.StatusBadRequest, "读取文件失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"content":   string(content.Content),
		"truncated": content.Truncated,
	})
}

func (s *Server) handleWriteFile(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path       string `json:"path"`
		Content    string `json:"content"`
		AppendMode bool   `json:"appendMode"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	if err := s.files.WriteFile(req.Path, []byte(req.Content), req.AppendMode); err != nil {
		writeError(w, http.StatusBadRequest, "写入文件失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleArtifacts(w http.ResponseWriter, r *http.Request) {
	artifacts, err := s.files.ScanArtifacts(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "扫描产物失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"artifacts": artifacts})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeSSE(w http.ResponseWriter, event string, value any) {
	data, err := json.Marshal(value)
	if err != nil {
		data = []byte(`{"message":"响应编码失败"}`)
	}
	_, _ = w.Write([]byte("event: " + event + "\n"))
	_, _ = w.Write([]byte("data: " + string(data) + "\n\n"))
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
}

func parseIntDefault(value string, fallback int) (int, error) {
	if value == "" {
		return fallback, nil
	}
	return strconv.Atoi(value)
}

func parseInt64Default(value string, fallback int64) (int64, error) {
	if value == "" {
		return fallback, nil
	}
	return strconv.ParseInt(value, 10, 64)
}

func defaultString(value string, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
