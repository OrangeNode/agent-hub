//go:build minio

// MinIO 实现只在使用 -tags minio 构建时启用。
// 当前受限环境没有 Go 工具链，因此 go.mod/go.sum 尚未加入 MinIO 依赖；
// 在可用 Go 工具链的环境中启用该实现前，需要执行
// go get github.com/minio/minio-go/v7
// 以更新 go.mod/go.sum。
package objectstore

import (
	"context"
	"io"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type MinioStore struct {
	client *minio.Client
}

type MinioConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Secure    bool
}

func NewMinioStore(cfg MinioConfig) (*MinioStore, error) {
	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: cfg.Secure,
	})
	if err != nil {
		return nil, err
	}
	return &MinioStore{client: client}, nil
}

func (s *MinioStore) Upload(req UploadRequest) error {
	ctx := req.Context
	if ctx == nil {
		ctx = context.Background()
	}
	_, err := s.client.PutObject(ctx, req.Bucket, req.Key, req.Body, req.Size, minio.PutObjectOptions{
		ContentType: req.ContentType,
	})
	return err
}

func (s *MinioStore) Download(req DownloadRequest) (io.ReadCloser, error) {
	ctx := req.Context
	if ctx == nil {
		ctx = context.Background()
	}
	return s.client.GetObject(ctx, req.Bucket, req.Key, minio.GetObjectOptions{})
}
