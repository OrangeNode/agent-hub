package host

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"agent-hub/sandbox-service/internal/objectstore"
	"agent-hub/sandbox-service/internal/policy"
	"agent-hub/sandbox-service/internal/sse"
)

const (
	defaultArtifactPath = "/workspace/artifacts"
	defaultContentType  = "application/octet-stream"
)

type apiServer struct {
	manager    *SessionManager
	client     *http.Client
	uploader   objectstore.Uploader
	downloader objectstore.Downloader
	syncer     *ArtifactSyncer
	policy     policy.CommandPolicy
}

type RouterOption func(*apiServer)

func NewRouter() http.Handler {
	mux := http.NewServeMux()
	registerHealth(mux)
	return mux
}

func NewRouterWithManager(manager *SessionManager, options ...RouterOption) http.Handler {
	server := &apiServer{
		manager: manager,
		client:  http.DefaultClient,
		syncer:  NewArtifactSyncer("agent-hub-sandbox-artifacts"),
		policy:  policy.NewCommandPolicy(8192, 300),
	}
	for _, option := range options {
		option(server)
	}

	mux := http.NewServeMux()
	registerHealth(mux)
	mux.HandleFunc("POST /internal/sandbox/sessions", server.handleCreateSession)
	mux.HandleFunc("POST /internal/sandbox/sessions/{sessionId}/exec/stream", server.handleExecStream)
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
	RelativePath string `json:"relativePath"`
	Bucket       string `json:"bucket"`
	Key          string `json:"key"`
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
		if strings.Contains(err.Error(), "达到上限") {
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

func (s *apiServer) handleExecStream(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")

	if s.manager == nil {
		sse.WriteEvent(w, "error", map[string]string{"message": "沙箱会话管理器未初始化"})
		return
	}
	sessionID := r.PathValue("sessionId")
	session, ok := s.manager.Get(sessionID)
	if !ok {
		sse.WriteEvent(w, "error", map[string]string{"message": "沙箱会话不存在"})
		return
	}

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

	if err := s.proxyExecStream(r, w, session, req); err != nil {
		sse.WriteEvent(w, "error", map[string]string{"message": err.Error()})
		return
	}
	if err := s.syncArtifacts(r, w, session); err != nil {
		sse.WriteEvent(w, "error", map[string]string{"message": err.Error()})
		return
	}
}

func (s *apiServer) proxyExecStream(r *http.Request, w http.ResponseWriter, session Session, req execRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("编码命令请求失败: %w", err)
	}
	guestReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, joinAgentURL(session.AgentURL, "/exec/stream"), bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("创建 guest-agent 请求失败: %w", err)
	}
	guestReq.Header.Set("Content-Type", "application/json; charset=utf-8")
	resp, err := s.client.Do(guestReq)
	if err != nil {
		return fmt.Errorf("请求 guest-agent 执行命令失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("guest-agent 执行命令返回异常状态: %d", resp.StatusCode)
	}
	if _, err := io.Copy(w, resp.Body); err != nil {
		return fmt.Errorf("转发 guest-agent SSE 失败: %w", err)
	}
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
	return nil
}

func (s *apiServer) syncArtifacts(r *http.Request, w http.ResponseWriter, session Session) error {
	if s.uploader == nil {
		return nil
	}
	artifacts, err := s.fetchArtifacts(r, session)
	if err != nil {
		return err
	}
	changes, err := s.syncer.Diff(session.ConversationID, artifacts)
	if err != nil {
		return err
	}
	for _, change := range changes {
		content, err := s.readGuestArtifact(r, session, change.RelativePath)
		if err != nil {
			return err
		}
		if err := s.uploader.Upload(objectstore.UploadRequest{
			Context:     r.Context(),
			Bucket:      change.Bucket,
			Key:         change.StorageKey,
			Body:        bytes.NewReader(content),
			Size:        int64(len(content)),
			ContentType: defaultContentType,
		}); err != nil {
			return fmt.Errorf("上传产物失败: %w", err)
		}
		sse.WriteEvent(w, "artifact_synced", change)
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
	resp, err := s.client.Do(req)
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
	query.Set("maxBytes", "0")
	parsed.RawQuery = query.Encode()
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, parsed.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("创建产物读取请求失败: %w", err)
	}
	resp, err := s.client.Do(req)
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
	if body.Truncated {
		return nil, errors.New("产物内容被截断，无法同步")
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
			Bucket: artifact.Bucket,
			Key:    artifact.Key,
		})
		if err != nil {
			return fmt.Errorf("下载恢复产物失败: %w", err)
		}
		data, readErr := io.ReadAll(content)
		closeErr := content.Close()
		if readErr != nil {
			return fmt.Errorf("读取恢复产物失败: %w", readErr)
		}
		if closeErr != nil {
			return fmt.Errorf("关闭恢复产物失败: %w", closeErr)
		}
		if err := s.writeGuestFile(r, session, defaultArtifactPath+"/"+relativePath, data); err != nil {
			return err
		}
	}
	return nil
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
		return fmt.Errorf("创建恢复产物写入请求失败: %w", err)
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	resp, err := s.client.Do(req)
	if err != nil {
		return fmt.Errorf("写入 guest-agent 产物失败: %w", err)
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
