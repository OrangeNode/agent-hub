package host

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"agent-hub/sandbox-service/internal/objectstore"
	"agent-hub/sandbox-service/internal/policy"
	"agent-hub/sandbox-service/internal/sse"
	"agent-hub/sandbox-service/internal/workspace"
)

const (
	defaultArtifactPath = "/workspace/artifacts"
	defaultContentType  = "application/octet-stream"
	maxArtifactBytes    = 10 * 1024 * 1024
	guestHealthTimeout  = 2 * time.Second
)

const (
	proxyExecStreamClientMessage = "沙箱命令执行代理失败"
	syncArtifactsClientMessage   = "产物同步失败"
)

type apiServer struct {
	manager    *SessionManager
	client     *http.Client
	uploader   objectstore.Uploader
	downloader objectstore.Downloader
	syncer     *ArtifactSyncer
	policy     policy.CommandPolicy
	pathPolicy workspace.PathPolicy
	admin      *AdminServer
}

type RouterOption func(*apiServer)

func NewRouter() http.Handler {
	mux := http.NewServeMux()
	registerHealth(mux)
	return mux
}

func NewRouterWithManager(manager *SessionManager, options ...RouterOption) http.Handler {
	server := &apiServer{
		manager:    manager,
		client:     http.DefaultClient,
		syncer:     NewArtifactSyncer("agent-hub-sandbox-artifacts"),
		policy:     policy.NewCommandPolicy(8192, 300),
		pathPolicy: workspace.NewPathPolicy("/workspace"),
	}
	for _, option := range options {
		option(server)
	}

	mux := http.NewServeMux()
	registerHealth(mux)
	mux.HandleFunc("POST /internal/sandbox/sessions", server.handleCreateSession)
	mux.HandleFunc("GET /internal/sandbox/sessions/{sessionId}/health", server.handleSessionHealth)
	mux.HandleFunc("POST /internal/sandbox/sessions/{sessionId}/exec/stream", server.handleExecStream)
	mux.HandleFunc("POST /internal/sandbox/sessions/{sessionId}/skills/sync-zip", server.handleSyncSkillZip)
	mux.HandleFunc("GET /internal/sandbox/sessions/{sessionId}/files", server.handleListFiles)
	mux.HandleFunc("GET /internal/sandbox/sessions/{sessionId}/files/content", server.handleReadFile)
	mux.HandleFunc("POST /internal/sandbox/sessions/{sessionId}/files/content", server.handleWriteFile)
	if server.admin != nil {
		server.admin.Register(mux)
	}
	return mux
}

func WithHTTPClient(client *http.Client) RouterOption {
	return func(server *apiServer) {
		if client != nil {
			server.client = client
		}
	}
}

func WithUploader(uploader objectstore.Uploader) RouterOption {
	return func(server *apiServer) {
		server.uploader = uploader
	}
}

func WithDownloader(downloader objectstore.Downloader) RouterOption {
	return func(server *apiServer) {
		server.downloader = downloader
	}
}

func WithArtifactSyncer(syncer *ArtifactSyncer) RouterOption {
	return func(server *apiServer) {
		if syncer != nil {
			server.syncer = syncer
		}
	}
}

func WithAdminServer(admin *AdminServer) RouterOption {
	return func(server *apiServer) {
		server.admin = admin
	}
}

func registerHealth(mux *http.ServeMux) {
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
	})
}

type createSessionRequest struct {
	ConversationID string            `json:"conversationId"`
	Artifacts      []restoreArtifact `json:"artifacts"`
}

type restoreArtifact struct {
	RelativePath  string `json:"relativePath"`
	StorageBucket string `json:"storageBucket"`
	StorageKey    string `json:"storageKey"`
	Bucket        string `json:"bucket"`
	Key           string `json:"key"`
	Sha256        string `json:"sha256"`
}

type createSessionResponse struct {
	SandboxSessionID string `json:"sandboxSessionId"`
	Status           string `json:"status"`
}

type execRequest struct {
	Command        string `json:"command"`
	WorkingDir     string `json:"workingDir"`
	TimeoutSeconds int    `json:"timeoutSeconds"`
}

func (s *apiServer) handleCreateSession(w http.ResponseWriter, r *http.Request) {
	if s.manager == nil {
		writeError(w, http.StatusServiceUnavailable, "沙箱会话管理器未初始化")
		return
	}

	var req createSessionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	req.ConversationID = strings.TrimSpace(req.ConversationID)
	if req.ConversationID == "" {
		writeError(w, http.StatusBadRequest, "conversationId 不能为空")
		return
	}
	if err := s.validateRestoreArtifacts(req.Artifacts); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	session, err := s.manager.GetOrCreate(req.ConversationID)
	if err != nil {
		status := http.StatusInternalServerError
		if strings.Contains(err.Error(), "达到上限") || strings.Contains(err.Error(), "上限") {
			status = http.StatusServiceUnavailable
		}
		writeError(w, status, err.Error())
		return
	}
	if err := s.restoreArtifacts(r, session, req.Artifacts); err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, createSessionResponse{
		SandboxSessionID: session.ID,
		Status:           "ready",
	})
}

func (s *apiServer) handleSessionHealth(w http.ResponseWriter, r *http.Request) {
	session, ok := s.beginSessionUse(w, r.PathValue("sessionId"))
	if !ok {
		return
	}
	err := s.probeGuestHealth(r, session)
	s.manager.EndUse(session.ID)
	if err != nil {
		_ = s.manager.Close(session.ID)
		writeError(w, http.StatusServiceUnavailable, "沙箱会话不可用，请重新创建")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *apiServer) handleExecStream(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")

	if s.manager == nil {
		sse.WriteEvent(w, "error", map[string]string{"message": "沙箱会话管理器未初始化"})
		return
	}
	sessionID := r.PathValue("sessionId")
	var req execRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		sse.WriteEvent(w, "error", map[string]string{"message": "请求体不是有效的 JSON"})
		return
	}
	if req.WorkingDir == "" {
		req.WorkingDir = "/workspace"
	}
	if req.TimeoutSeconds == 0 {
		req.TimeoutSeconds = 60
	}
	if err := s.policy.Validate(policy.CommandRequest{
		Command:        req.Command,
		WorkingDir:     req.WorkingDir,
		TimeoutSeconds: req.TimeoutSeconds,
	}); err != nil {
		sse.WriteEvent(w, "error", map[string]string{"message": err.Error()})
		return
	}

	session, ok := s.manager.BeginUse(sessionID)
	if !ok {
		sse.WriteEvent(w, "error", map[string]string{"message": "沙箱会话不存在"})
		return
	}
	defer s.manager.EndUse(sessionID)

	completedOK, err := s.proxyExecStream(r, w, session, req)
	if err != nil {
		sse.WriteEvent(w, "error", map[string]string{"message": proxyExecStreamClientMessage})
		return
	}
	if !completedOK {
		return
	}
	if err := s.syncArtifacts(r, w, session); err != nil {
		sse.WriteEvent(w, "error", map[string]string{"message": syncArtifactsClientMessage})
		return
	}
}

func (s *apiServer) handleSyncSkillZip(w http.ResponseWriter, r *http.Request) {
	session, ok := s.beginSessionUse(w, r.PathValue("sessionId"))
	if !ok {
		return
	}
	defer s.manager.EndUse(session.ID)

	body, err := io.ReadAll(io.LimitReader(r.Body, 2<<20))
	if err != nil {
		writeError(w, http.StatusBadRequest, "读取请求体失败")
		return
	}
	status, responseBody, err := s.proxyJSONResponse(
		r,
		http.MethodPost,
		joinAgentURL(session.AgentURL, "/skills/sync-zip"),
		bytes.NewReader(body),
	)
	if err != nil {
		writeError(w, http.StatusBadGateway, "同步 Skill 包代理失败")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(responseBody)
}

func (s *apiServer) handleListFiles(w http.ResponseWriter, r *http.Request) {
	session, ok := s.beginSessionUse(w, r.PathValue("sessionId"))
	if !ok {
		return
	}
	defer s.manager.EndUse(session.ID)
	resolvedPath, err := s.pathPolicy.Resolve(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	targetURL := joinAgentURL(session.AgentURL, "/files")
	parsed, err := url.Parse(targetURL)
	if err != nil {
		writeError(w, http.StatusBadGateway, "沙箱文件代理失败")
		return
	}
	query := parsed.Query()
	query.Set("path", resolvedPath)
	query.Set("recursive", r.URL.Query().Get("recursive"))
	parsed.RawQuery = query.Encode()
	s.proxyJSON(w, r, http.MethodGet, parsed.String(), nil)
}

func (s *apiServer) handleReadFile(w http.ResponseWriter, r *http.Request) {
	session, ok := s.beginSessionUse(w, r.PathValue("sessionId"))
	if !ok {
		return
	}
	defer s.manager.EndUse(session.ID)
	resolvedPath, err := s.pathPolicy.Resolve(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	maxBytes, err := strconv.Atoi(defaultString(r.URL.Query().Get("maxBytes"), "1048576"))
	if err != nil || maxBytes <= 0 || maxBytes > maxArtifactBytes {
		writeError(w, http.StatusBadRequest, "maxBytes 参数不正确")
		return
	}
	targetURL := joinAgentURL(session.AgentURL, "/files/content")
	parsed, err := url.Parse(targetURL)
	if err != nil {
		writeError(w, http.StatusBadGateway, "沙箱文件代理失败")
		return
	}
	query := parsed.Query()
	query.Set("path", resolvedPath)
	query.Set("maxBytes", strconv.Itoa(maxBytes))
	parsed.RawQuery = query.Encode()
	s.proxyJSON(w, r, http.MethodGet, parsed.String(), nil)
}

func (s *apiServer) handleWriteFile(w http.ResponseWriter, r *http.Request) {
	session, ok := s.beginSessionUse(w, r.PathValue("sessionId"))
	if !ok {
		return
	}
	defer s.manager.EndUse(session.ID)
	var req struct {
		Path       string `json:"path"`
		Content    string `json:"content"`
		AppendMode bool   `json:"appendMode"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
		return
	}
	resolvedPath, err := s.pathPolicy.Resolve(req.Path)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	body, err := json.Marshal(map[string]any{
		"path":       resolvedPath,
		"content":    req.Content,
		"appendMode": req.AppendMode,
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "沙箱文件代理失败")
		return
	}
	status, responseBody, err := s.proxyJSONResponse(r, http.MethodPost, joinAgentURL(session.AgentURL, "/files/content"), bytes.NewReader(body))
	if err != nil {
		writeError(w, http.StatusBadGateway, "沙箱文件代理失败")
		return
	}
	if status < 200 || status >= 300 {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(status)
		_, _ = w.Write(responseBody)
		return
	}
	if !isArtifactWorkspacePath(resolvedPath) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(status)
		_, _ = w.Write(responseBody)
		return
	}
	changes, err := s.syncArtifactChanges(r, session)
	if err != nil {
		writeError(w, http.StatusBadGateway, syncArtifactsClientMessage)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"artifacts": changes,
	})
}

func (s *apiServer) beginSessionUse(w http.ResponseWriter, sessionID string) (Session, bool) {
	if s.manager == nil {
		writeError(w, http.StatusServiceUnavailable, "沙箱会话管理器未初始化")
		return Session{}, false
	}
	session, ok := s.manager.BeginUse(sessionID)
	if !ok {
		writeError(w, http.StatusNotFound, "沙箱会话不存在")
		return Session{}, false
	}
	return session, true
}

func (s *apiServer) proxyJSON(w http.ResponseWriter, r *http.Request, method string, targetURL string, body io.Reader) {
	status, responseBody, err := s.proxyJSONResponse(r, method, targetURL, body)
	if err != nil {
		writeError(w, http.StatusBadGateway, "沙箱文件代理失败")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(responseBody)
}

func (s *apiServer) proxyJSONResponse(r *http.Request, method string, targetURL string, body io.Reader) (int, []byte, error) {
	req, err := http.NewRequestWithContext(r.Context(), method, targetURL, body)
	if err != nil {
		return 0, nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	client := s.client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, nil, err
	}
	return resp.StatusCode, responseBody, nil
}

func (s *apiServer) probeGuestHealth(r *http.Request, session Session) error {
	ctx, cancel := context.WithTimeout(r.Context(), guestHealthTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, joinAgentURL(session.AgentURL, "/health"), nil)
	if err != nil {
		return err
	}
	client := s.client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("guest-agent 健康检查返回异常状态: %d", resp.StatusCode)
	}
	return nil
}

func (s *apiServer) proxyExecStream(r *http.Request, w http.ResponseWriter, session Session, req execRequest) (bool, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return false, errors.New(proxyExecStreamClientMessage)
	}
	guestReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, joinAgentURL(session.AgentURL, "/exec/stream"), bytes.NewReader(body))
	if err != nil {
		return false, errors.New(proxyExecStreamClientMessage)
	}
	guestReq.Header.Set("Content-Type", "application/json; charset=utf-8")
	client := s.client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(guestReq)
	if err != nil {
		return false, errors.New(proxyExecStreamClientMessage)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false, errors.New(proxyExecStreamClientMessage)
	}
	completedOK, err := forwardGuestSSE(resp.Body, w)
	if err != nil {
		return false, errors.New(proxyExecStreamClientMessage)
	}
	return completedOK, nil
}

func forwardGuestSSE(body io.Reader, w http.ResponseWriter) (bool, error) {
	reader := bufio.NewReader(body)
	state := proxySSEState{}
	for {
		line, readErr := reader.ReadString('\n')
		if len(line) > 0 {
			if _, err := io.WriteString(w, line); err != nil {
				return false, err
			}
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
			state.consume(line)
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				break
			}
			return false, readErr
		}
	}
	state.finish()
	return state.completedOK, nil
}

type proxySSEState struct {
	event       string
	dataLines   []string
	hasFrame    bool
	completedOK bool
}

func (s *proxySSEState) consume(line string) {
	line = strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r")
	if line == "" {
		s.finish()
		return
	}
	s.hasFrame = true
	if strings.HasPrefix(line, "event:") {
		s.event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		return
	}
	if strings.HasPrefix(line, "data:") {
		value := strings.TrimPrefix(line, "data:")
		if strings.HasPrefix(value, " ") {
			value = strings.TrimPrefix(value, " ")
		}
		s.dataLines = append(s.dataLines, value)
	}
}

func (s *proxySSEState) finish() {
	if !s.hasFrame {
		return
	}
	if s.event == "completed" {
		s.completedOK = completedDataAllowsArtifactSync(strings.Join(s.dataLines, "\n"))
	}
	s.event = ""
	s.dataLines = nil
	s.hasFrame = false
}

func completedDataAllowsArtifactSync(data string) bool {
	var payload map[string]json.RawMessage
	if err := json.Unmarshal([]byte(data), &payload); err != nil {
		return false
	}
	var exitCode int
	if raw, ok := payload["exitCode"]; !ok || json.Unmarshal(raw, &exitCode) != nil {
		return false
	}
	if exitCode != 0 {
		return false
	}
	if raw, ok := payload["timedOut"]; ok {
		var timedOut bool
		if json.Unmarshal(raw, &timedOut) == nil && timedOut {
			return false
		}
	}
	if raw, ok := payload["error"]; ok && !emptyCompletedError(raw) {
		return false
	}
	return true
}

func emptyCompletedError(raw json.RawMessage) bool {
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		return true
	}
	var text string
	if json.Unmarshal(raw, &text) == nil && strings.TrimSpace(text) == "" {
		return true
	}
	return false
}

func (s *apiServer) syncArtifacts(r *http.Request, w http.ResponseWriter, session Session) error {
	changes, err := s.syncArtifactChanges(r, session)
	if err != nil {
		return err
	}
	for _, change := range changes {
		sse.WriteEvent(w, "artifact_synced", change)
	}
	return nil
}

func (s *apiServer) syncArtifactChanges(r *http.Request, session Session) ([]SyncedArtifact, error) {
	if s.uploader == nil {
		return nil, nil
	}
	artifacts, err := s.fetchArtifacts(r, session)
	if err != nil {
		return nil, err
	}
	changes, err := s.syncer.Diff(session.ConversationID, artifacts)
	if err != nil {
		return nil, err
	}
	for _, change := range changes {
		content, err := s.readGuestArtifact(r, session, change.RelativePath)
		if err != nil {
			return nil, err
		}
		if err := verifySyncedArtifactSHA256(change, content); err != nil {
			return nil, err
		}
		if err := s.uploader.Upload(objectstore.UploadRequest{
			Context:     r.Context(),
			Bucket:      change.Bucket,
			Key:         change.StorageKey,
			Body:        bytes.NewReader(content),
			Size:        int64(len(content)),
			ContentType: defaultContentType,
		}); err != nil {
			return nil, fmt.Errorf("上传产物失败: %w", err)
		}
		if err := s.syncer.MarkSynced(session.ConversationID, change); err != nil {
			return nil, err
		}
	}
	return changes, nil
}

func isArtifactWorkspacePath(value string) bool {
	value = strings.TrimRight(strings.ReplaceAll(strings.TrimSpace(value), "\\", "/"), "/")
	return value == defaultArtifactPath || strings.HasPrefix(value, defaultArtifactPath+"/")
}

func verifySyncedArtifactSHA256(artifact SyncedArtifact, content []byte) error {
	actual := sha256.Sum256(content)
	actualHex := fmt.Sprintf("%x", actual)
	if !strings.EqualFold(strings.TrimSpace(artifact.SHA256), actualHex) {
		return fmt.Errorf("产物 %s 在同步期间发生变化，请重试", artifact.RelativePath)
	}
	return nil
}

func (s *apiServer) fetchArtifacts(r *http.Request, session Session) ([]Artifact, error) {
	artifactURL := joinAgentURL(session.AgentURL, "/artifacts")
	parsed, err := url.Parse(artifactURL)
	if err != nil {
		return nil, fmt.Errorf("解析产物接口地址失败: %w", err)
	}
	query := parsed.Query()
	query.Set("path", defaultArtifactPath)
	parsed.RawQuery = query.Encode()
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, parsed.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("创建产物扫描请求失败: %w", err)
	}
	client := s.client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 guest-agent 产物列表失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("guest-agent 产物列表返回异常状态: %d", resp.StatusCode)
	}
	var body struct {
		Artifacts []Artifact `json:"artifacts"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("解析产物列表失败: %w", err)
	}
	return body.Artifacts, nil
}

func (s *apiServer) readGuestArtifact(r *http.Request, session Session, relativePath string) ([]byte, error) {
	relativePath, err := CleanArtifactRelativePath(relativePath)
	if err != nil {
		return nil, err
	}
	contentURL := joinAgentURL(session.AgentURL, "/files/content")
	parsed, err := url.Parse(contentURL)
	if err != nil {
		return nil, fmt.Errorf("解析文件读取接口地址失败: %w", err)
	}
	query := parsed.Query()
	query.Set("path", defaultArtifactPath+"/"+relativePath)
	query.Set("maxBytes", strconv.Itoa(maxArtifactBytes+1))
	parsed.RawQuery = query.Encode()
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, parsed.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("创建产物读取请求失败: %w", err)
	}
	client := s.client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 guest-agent 产物内容失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("guest-agent 产物内容返回异常状态: %d", resp.StatusCode)
	}
	var body struct {
		Content   string `json:"content"`
		Truncated bool   `json:"truncated"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("解析产物内容失败: %w", err)
	}
	if body.Truncated || len([]byte(body.Content)) > maxArtifactBytes {
		return nil, errors.New("产物超过大小上限，无法同步")
	}
	return []byte(body.Content), nil
}

func (s *apiServer) validateRestoreArtifacts(artifacts []restoreArtifact) error {
	for _, artifact := range artifacts {
		if _, err := CleanArtifactRelativePath(artifact.RelativePath); err != nil {
			return err
		}
	}
	return nil
}

func (s *apiServer) restoreArtifacts(r *http.Request, session Session, artifacts []restoreArtifact) error {
	if len(artifacts) == 0 {
		return nil
	}
	if s.downloader == nil {
		return errors.New("产物下载器未初始化")
	}
	for _, artifact := range artifacts {
		relativePath, err := CleanArtifactRelativePath(artifact.RelativePath)
		if err != nil {
			return err
		}
		content, err := s.downloader.Download(objectstore.DownloadRequest{
			Context: r.Context(),
			Bucket:  artifact.downloadBucket(),
			Key:     artifact.downloadKey(),
		})
		if err != nil {
			return fmt.Errorf("下载恢复产物失败: %w", err)
		}
		data, readErr := io.ReadAll(io.LimitReader(content, int64(maxArtifactBytes)+1))
		closeErr := content.Close()
		if readErr != nil {
			return fmt.Errorf("读取恢复产物失败: %w", readErr)
		}
		if closeErr != nil {
			return fmt.Errorf("关闭恢复产物失败: %w", closeErr)
		}
		if len(data) > maxArtifactBytes {
			return errors.New("恢复产物超过大小上限（10MiB）")
		}
		if err := verifyRestoreArtifactSHA256(relativePath, artifact.Sha256, data); err != nil {
			return err
		}
		if err := s.writeGuestFile(r, session, defaultArtifactPath+"/"+relativePath, data); err != nil {
			return err
		}
	}
	return nil
}

func verifyRestoreArtifactSHA256(relativePath string, expected string, data []byte) error {
	expected = strings.TrimSpace(expected)
	if expected == "" {
		return nil
	}
	actual := sha256.Sum256(data)
	if !strings.EqualFold(expected, fmt.Sprintf("%x", actual)) {
		return fmt.Errorf("恢复产物 %s 的 sha256 校验失败", relativePath)
	}
	return nil
}

func (artifact restoreArtifact) downloadBucket() string {
	if strings.TrimSpace(artifact.StorageBucket) != "" {
		return strings.TrimSpace(artifact.StorageBucket)
	}
	return strings.TrimSpace(artifact.Bucket)
}

func (artifact restoreArtifact) downloadKey() string {
	if strings.TrimSpace(artifact.StorageKey) != "" {
		return strings.TrimSpace(artifact.StorageKey)
	}
	return strings.TrimSpace(artifact.Key)
}

func (s *apiServer) writeGuestFile(r *http.Request, session Session, guestPath string, content []byte) error {
	body, err := json.Marshal(map[string]any{
		"path":    guestPath,
		"content": string(content),
	})
	if err != nil {
		return fmt.Errorf("编码恢复产物失败: %w", err)
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, joinAgentURL(session.AgentURL, "/files/content"), bytes.NewReader(body))
	if err != nil {
		return errors.New("创建沙箱恢复产物写入请求失败")
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	client := s.client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return errors.New("写入沙箱恢复产物失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("guest-agent 写入恢复产物返回异常状态: %d", resp.StatusCode)
	}
	return nil
}

func joinAgentURL(baseURL string, apiPath string) string {
	return strings.TrimRight(baseURL, "/") + apiPath
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func defaultString(value string, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
