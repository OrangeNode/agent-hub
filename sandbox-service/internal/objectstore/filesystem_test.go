package objectstore

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"
)

func TestFilesystemStoreUploadsAndDownloadsObject(t *testing.T) {
	store, err := NewFilesystemStore(t.TempDir())
	if err != nil {
		t.Fatalf("NewFilesystemStore() error = %v", err)
	}

	err = store.Upload(UploadRequest{
		Context:     context.Background(),
		Bucket:      "bucket-a",
		Key:         "nested/out.txt",
		Body:        strings.NewReader("content"),
		Size:        int64(len("content")),
		ContentType: "text/plain",
	})
	if err != nil {
		t.Fatalf("Upload() error = %v", err)
	}

	body, err := store.Download(DownloadRequest{
		Context: context.Background(),
		Bucket:  "bucket-a",
		Key:     "nested/out.txt",
	})
	if err != nil {
		t.Fatalf("Download() error = %v", err)
	}
	defer body.Close()
	data, err := io.ReadAll(body)
	if err != nil {
		t.Fatalf("ReadAll() error = %v", err)
	}
	if string(data) != "content" {
		t.Fatalf("content = %q", data)
	}
}

func TestFilesystemStoreRejectsEscapingObjectKey(t *testing.T) {
	store, err := NewFilesystemStore(t.TempDir())
	if err != nil {
		t.Fatalf("NewFilesystemStore() error = %v", err)
	}

	err = store.Upload(UploadRequest{
		Context: context.Background(),
		Bucket:  "bucket-a",
		Key:     `..\secret.txt`,
		Body:    strings.NewReader("content"),
		Size:    int64(len("content")),
	})
	if err == nil {
		t.Fatal("expected escaping key to be rejected")
	}
}

func TestFilesystemStoreUploadFailureKeepsExistingObject(t *testing.T) {
	store, err := NewFilesystemStore(t.TempDir())
	if err != nil {
		t.Fatalf("NewFilesystemStore() error = %v", err)
	}

	err = store.Upload(UploadRequest{
		Context: context.Background(),
		Bucket:  "bucket-a",
		Key:     "out.txt",
		Body:    strings.NewReader("old"),
		Size:    int64(len("old")),
	})
	if err != nil {
		t.Fatalf("Upload(initial) error = %v", err)
	}

	err = store.Upload(UploadRequest{
		Context: context.Background(),
		Bucket:  "bucket-a",
		Key:     "out.txt",
		Body:    failingReader{},
	})
	if err == nil {
		t.Fatal("expected upload failure")
	}

	body, err := store.Download(DownloadRequest{
		Context: context.Background(),
		Bucket:  "bucket-a",
		Key:     "out.txt",
	})
	if err != nil {
		t.Fatalf("Download() error = %v", err)
	}
	defer body.Close()
	data, err := io.ReadAll(body)
	if err != nil {
		t.Fatalf("ReadAll() error = %v", err)
	}
	if string(data) != "old" {
		t.Fatalf("content = %q, want old", data)
	}
}

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) {
	return 0, errors.New("read failed")
}
