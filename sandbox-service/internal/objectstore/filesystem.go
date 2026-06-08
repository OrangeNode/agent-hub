package objectstore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
)

type FilesystemStore struct {
	root string
}

func NewFilesystemStore(root string) (*FilesystemStore, error) {
	root = strings.TrimSpace(root)
	if root == "" {
		return nil, errors.New("本地对象存储目录不能为空")
	}
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("解析本地对象存储目录失败: %w", err)
	}
	if err := os.MkdirAll(absRoot, 0o755); err != nil {
		return nil, fmt.Errorf("创建本地对象存储目录失败: %w", err)
	}
	return &FilesystemStore{root: absRoot}, nil
}

func (s *FilesystemStore) Upload(req UploadRequest) error {
	ctx := req.Context
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	objectPath, err := s.objectPath(req.Bucket, req.Key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(objectPath), 0o755); err != nil {
		return fmt.Errorf("创建本地对象目录失败: %w", err)
	}
	file, err := os.Create(objectPath)
	if err != nil {
		return fmt.Errorf("创建本地对象文件失败: %w", err)
	}
	defer file.Close()
	if _, err := io.Copy(file, req.Body); err != nil {
		return fmt.Errorf("写入本地对象文件失败: %w", err)
	}
	return nil
}

func (s *FilesystemStore) Download(req DownloadRequest) (io.ReadCloser, error) {
	ctx := req.Context
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	objectPath, err := s.objectPath(req.Bucket, req.Key)
	if err != nil {
		return nil, err
	}
	file, err := os.Open(objectPath)
	if err != nil {
		return nil, fmt.Errorf("读取本地对象文件失败: %w", err)
	}
	return file, nil
}

func (s *FilesystemStore) objectPath(bucket string, key string) (string, error) {
	bucket, err := cleanBucket(bucket)
	if err != nil {
		return "", err
	}
	key, err = cleanObjectKey(key)
	if err != nil {
		return "", err
	}
	objectPath := filepath.Join(s.root, bucket, filepath.FromSlash(key))
	rel, err := filepath.Rel(s.root, objectPath)
	if err != nil {
		return "", fmt.Errorf("校验本地对象路径失败: %w", err)
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return "", errors.New("对象 key 不能逃逸本地对象存储目录")
	}
	return objectPath, nil
}

func cleanBucket(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", errors.New("对象 bucket 不能为空")
	}
	if strings.ContainsAny(value, `/\`) || value == "." || value == ".." {
		return "", errors.New("对象 bucket 不能包含路径分隔符或逃逸片段")
	}
	return value, nil
}

func cleanObjectKey(value string) (string, error) {
	cleaned := strings.ReplaceAll(strings.TrimSpace(value), "\\", "/")
	if cleaned == "" {
		return "", errors.New("对象 key 不能为空")
	}
	if strings.HasPrefix(cleaned, "/") {
		return "", errors.New("对象 key 不能逃逸本地对象存储目录")
	}
	for _, segment := range strings.Split(cleaned, "/") {
		if segment == ".." {
			return "", errors.New("对象 key 不能逃逸本地对象存储目录")
		}
	}
	cleaned = path.Clean(cleaned)
	if cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, "../") {
		return "", errors.New("对象 key 不能逃逸本地对象存储目录")
	}
	return cleaned, nil
}
