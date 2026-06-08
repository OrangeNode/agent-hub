package host

import (
	"errors"
	"net/url"
	"path"
	"strings"
	"sync"
)

type Artifact struct {
	RelativePath string `json:"relativePath"`
	Size         int64  `json:"size"`
	SHA256       string `json:"sha256"`
}

type SyncedArtifact struct {
	Artifact
	Bucket     string `json:"bucket"`
	StorageKey string `json:"storageKey"`
	Version    int    `json:"version"`
}

type ArtifactSyncer struct {
	bucket string

	mu       sync.Mutex
	versions map[string]map[string]artifactVersion
}

type artifactVersion struct {
	sha     string
	version int
}

func NewArtifactSyncer(bucket string) *ArtifactSyncer {
	bucket = strings.TrimSpace(bucket)
	if bucket == "" {
		bucket = "agent-hub-sandbox-artifacts"
	}
	return &ArtifactSyncer{
		bucket:   bucket,
		versions: make(map[string]map[string]artifactVersion),
	}
}

func (s *ArtifactSyncer) Bucket() string {
	if s == nil {
		return ""
	}
	return s.bucket
}

func (s *ArtifactSyncer) Diff(conversationID string, artifacts []Artifact) ([]SyncedArtifact, error) {
	return s.Plan(conversationID, artifacts)
}

func (s *ArtifactSyncer) Pending(conversationID string, artifacts []Artifact) ([]SyncedArtifact, error) {
	return s.Plan(conversationID, artifacts)
}

func (s *ArtifactSyncer) Plan(conversationID string, artifacts []Artifact) ([]SyncedArtifact, error) {
	if s == nil {
		return nil, errors.New("产物同步器未初始化")
	}
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return nil, errors.New("conversationId 不能为空")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	byPath := s.versions[conversationID]
	changes := make([]SyncedArtifact, 0)
	for _, artifact := range artifacts {
		relativePath, err := CleanArtifactRelativePath(artifact.RelativePath)
		if err != nil {
			return nil, err
		}
		sha := strings.TrimSpace(artifact.SHA256)
		if sha == "" {
			return nil, errors.New("产物 sha256 不能为空")
		}
		current := byPath[relativePath]
		if current.sha == sha {
			continue
		}
		version := current.version + 1
		artifact.RelativePath = relativePath
		artifact.SHA256 = sha
		changes = append(changes, SyncedArtifact{
			Artifact:   artifact,
			Bucket:     s.bucket,
			StorageKey: artifactStorageKey(conversationID, sha, relativePath),
			Version:    version,
		})
	}
	return changes, nil
}

func (s *ArtifactSyncer) MarkSynced(conversationID string, artifact SyncedArtifact) error {
	if s == nil {
		return errors.New("产物同步器未初始化")
	}
	conversationID = strings.TrimSpace(conversationID)
	if conversationID == "" {
		return errors.New("conversationId 不能为空")
	}
	relativePath, err := CleanArtifactRelativePath(artifact.RelativePath)
	if err != nil {
		return err
	}
	sha := strings.TrimSpace(artifact.SHA256)
	if sha == "" {
		return errors.New("产物 sha256 不能为空")
	}
	version := artifact.Version
	if version <= 0 {
		version = 1
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	byPath := s.versions[conversationID]
	if byPath == nil {
		byPath = make(map[string]artifactVersion)
		s.versions[conversationID] = byPath
	}
	byPath[relativePath] = artifactVersion{sha: sha, version: version}
	return nil
}

func CleanArtifactRelativePath(value string) (string, error) {
	cleaned := strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
	if cleaned == "" {
		return "", errors.New("产物路径不能为空")
	}
	if strings.HasPrefix(cleaned, "/") {
		return "", errors.New("产物路径不能超出工作目录")
	}
	for _, segment := range strings.Split(cleaned, "/") {
		if segment == ".." {
			return "", errors.New("产物路径不能超出工作目录")
		}
	}
	cleaned = path.Clean(cleaned)
	if cleaned == "." || strings.HasPrefix(cleaned, "../") || cleaned == ".." {
		return "", errors.New("产物路径不能超出工作目录")
	}
	return cleaned, nil
}

func artifactStorageKey(conversationID string, sha string, relativePath string) string {
	return "sandbox-artifacts/" + url.PathEscape(conversationID) + "/" + url.PathEscape(sha) + "/" + relativePath
}
