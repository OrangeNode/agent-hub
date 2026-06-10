package objectstore

import (
	"fmt"
	"strings"
	"testing"
)

func TestDefaultStoreUsesMinioWhenLocalDirIsBlank(t *testing.T) {
	store, err := NewDefaultStore(DefaultStoreConfig{
		Endpoint:  "127.0.0.1:9000",
		AccessKey: "agenthub",
		SecretKey: "agenthub123",
	})
	if err != nil {
		t.Fatalf("NewDefaultStore() error = %v", err)
	}
	if !strings.Contains(typeName(store), "MinioStore") {
		t.Fatalf("store type = %s, want MinioStore", typeName(store))
	}
}

func TestDefaultStoreUsesFilesystemWhenLocalDirIsSet(t *testing.T) {
	store, err := NewDefaultStore(DefaultStoreConfig{
		Endpoint:  "127.0.0.1:9000",
		AccessKey: "agenthub",
		SecretKey: "agenthub123",
		LocalDir:  t.TempDir(),
	})
	if err != nil {
		t.Fatalf("NewDefaultStore() error = %v", err)
	}
	if !strings.Contains(typeName(store), "FilesystemStore") {
		t.Fatalf("store type = %s, want FilesystemStore", typeName(store))
	}
}

func typeName(value any) string {
	return fmt.Sprintf("%T", value)
}
