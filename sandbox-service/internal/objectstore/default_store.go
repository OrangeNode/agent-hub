package objectstore

import "strings"

type DefaultStoreConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Secure    bool
	LocalDir  string
}

func NewDefaultStore(cfg DefaultStoreConfig) (Store, error) {
	if strings.TrimSpace(cfg.LocalDir) != "" {
		return NewFilesystemStore(cfg.LocalDir)
	}
	return NewMinioStore(MinioConfig{
		Endpoint:  cfg.Endpoint,
		AccessKey: cfg.AccessKey,
		SecretKey: cfg.SecretKey,
		Secure:    cfg.Secure,
	})
}
