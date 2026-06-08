//go:build minio

package objectstore

type DefaultStoreConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Secure    bool
	LocalDir  string
}

func NewDefaultStore(cfg DefaultStoreConfig) (Store, error) {
	return NewMinioStore(MinioConfig{
		Endpoint:  cfg.Endpoint,
		AccessKey: cfg.AccessKey,
		SecretKey: cfg.SecretKey,
		Secure:    cfg.Secure,
	})
}
