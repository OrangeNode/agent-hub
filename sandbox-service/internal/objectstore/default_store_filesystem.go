//go:build !minio

package objectstore

type DefaultStoreConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Secure    bool
	LocalDir  string
}

func NewDefaultStore(cfg DefaultStoreConfig) (Store, error) {
	return NewFilesystemStore(cfg.LocalDir)
}
