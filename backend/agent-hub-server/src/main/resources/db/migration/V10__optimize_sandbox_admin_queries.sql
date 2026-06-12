CREATE INDEX IF NOT EXISTS idx_sandbox_session_status_created_at
    ON sandbox_session (status, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_sandbox_execution_status_started_at
    ON sandbox_execution (status, started_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_sandbox_execution_started_at
    ON sandbox_execution (started_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_sandbox_artifact_created_at
    ON sandbox_artifact (created_at DESC, id DESC);
