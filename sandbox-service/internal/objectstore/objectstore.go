package objectstore

import (
	"context"
	"io"
)

type UploadRequest struct {
	Context     context.Context
	Bucket      string
	Key         string
	Body        io.Reader
	Size        int64
	ContentType string
}

type DownloadRequest struct {
	Context context.Context
	Bucket  string
	Key     string
}

type Uploader interface {
	Upload(req UploadRequest) error
}

type Downloader interface {
	Download(req DownloadRequest) (io.ReadCloser, error)
}
