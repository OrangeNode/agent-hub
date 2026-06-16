ALTER TABLE skill_package
    ADD COLUMN IF NOT EXISTS zip_sha256 VARCHAR(64);

ALTER TABLE skill_import
    ADD COLUMN IF NOT EXISTS zip_sha256 VARCHAR(64);

ALTER TABLE skill_file_manifest
    ADD COLUMN IF NOT EXISTS bucket_name VARCHAR(128);

ALTER TABLE skill_file_manifest
    ADD COLUMN IF NOT EXISTS object_key VARCHAR(512);

ALTER TABLE skill_file_manifest
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_skill_file_object_key
    ON skill_file_manifest (bucket_name, object_key);
