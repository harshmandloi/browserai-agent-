-- Enable pgvector extension (for RAG/memory embeddings in production)
-- CREATE EXTENSION IF NOT EXISTS vector;

-- ==================== DOCUMENTS ====================
CREATE TABLE IF NOT EXISTS documents (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    portal          VARCHAR(100) NOT NULL,
    document_type   VARCHAR(100) NOT NULL,
    reference       VARCHAR(500),
    hash            VARCHAR(64) NOT NULL,
    file_path       VARCHAR(1000) NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_document_hash UNIQUE (hash)
);

CREATE INDEX IF NOT EXISTS idx_documents_user_id ON documents(user_id);
CREATE INDEX IF NOT EXISTS idx_documents_hash ON documents(hash);
CREATE INDEX IF NOT EXISTS idx_documents_reference ON documents(reference);
CREATE INDEX IF NOT EXISTS idx_documents_portal ON documents(portal);

-- ==================== CREDENTIALS ====================
CREATE TABLE IF NOT EXISTS credentials (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(255) NOT NULL,
    portal              VARCHAR(100) NOT NULL,
    username            VARCHAR(500) NOT NULL,
    encrypted_password  TEXT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_credential_user_portal UNIQUE (user_id, portal)
);

CREATE INDEX IF NOT EXISTS idx_credentials_user_id ON credentials(user_id);
CREATE INDEX IF NOT EXISTS idx_credentials_portal ON credentials(portal);

-- ==================== CONVERSATION SESSIONS (FSM State) ====================
CREATE TABLE IF NOT EXISTS conversation_sessions (
    session_id      VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    state           VARCHAR(30) NOT NULL,
    portal          VARCHAR(100),
    document_type   VARCHAR(100),
    reference       VARCHAR(500),
    pending_question VARCHAR(1000),
    llm_calls_count INTEGER DEFAULT 0,
    total_tokens_used BIGINT DEFAULT 0,
    retry_count     INTEGER DEFAULT 0,
    error_message   VARCHAR(2000),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON conversation_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_state ON conversation_sessions(state);
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON conversation_sessions(expires_at);

-- ==================== AUDIT LOGS ====================
CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    action          VARCHAR(50) NOT NULL,
    step            VARCHAR(30),
    portal          VARCHAR(100),
    details         TEXT,
    status          VARCHAR(20),
    duration_ms     BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_session_id ON audit_logs(session_id);
CREATE INDEX IF NOT EXISTS idx_audit_user_id ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_logs(created_at);

-- ==================== SESSION SUMMARIES (Memory/RAG) ====================
CREATE TABLE IF NOT EXISTS session_summaries (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          VARCHAR(64) NOT NULL,
    user_id             VARCHAR(255) NOT NULL,
    portal              VARCHAR(100) NOT NULL,
    document_type       VARCHAR(100),
    summary_text        TEXT NOT NULL,
    steps_taken         TEXT,
    was_successful      BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason      VARCHAR(2000),
    total_duration_ms   BIGINT,
    llm_calls_count     INTEGER DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
    -- Production: ADD COLUMN embedding vector(768) for pgvector semantic search
);

CREATE INDEX IF NOT EXISTS idx_summaries_portal ON session_summaries(portal);
CREATE INDEX IF NOT EXISTS idx_summaries_user_id ON session_summaries(user_id);
CREATE INDEX IF NOT EXISTS idx_summaries_successful ON session_summaries(was_successful);

-- ==================== PORTAL WORKFLOWS (Learned Automation Paths) ====================
CREATE TABLE IF NOT EXISTS portal_workflows (
    id              BIGSERIAL PRIMARY KEY,
    portal          VARCHAR(100) NOT NULL,
    document_type   VARCHAR(100),
    login_url       VARCHAR(1000),
    workflow_steps  TEXT NOT NULL,
    css_selectors   TEXT,
    success_count   INTEGER DEFAULT 0,
    failure_count   INTEGER DEFAULT 0,
    last_success_at TIMESTAMP,
    last_failure_at TIMESTAMP,
    is_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_portal_workflow UNIQUE (portal, document_type)
);

CREATE INDEX IF NOT EXISTS idx_workflows_portal ON portal_workflows(portal);
CREATE INDEX IF NOT EXISTS idx_workflows_verified ON portal_workflows(is_verified);

-- ==================== TOKEN USAGE (Persistent Gemini Token Tracking) ====================
CREATE TABLE IF NOT EXISTS token_usage (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    portal          VARCHAR(100),
    document_type   VARCHAR(100),
    input_tokens    BIGINT NOT NULL DEFAULT 0,
    output_tokens   BIGINT NOT NULL DEFAULT 0,
    total_tokens    BIGINT NOT NULL DEFAULT 0,
    llm_calls       INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_token_usage_session ON token_usage(session_id);
CREATE INDEX IF NOT EXISTS idx_token_usage_user ON token_usage(user_id);
CREATE INDEX IF NOT EXISTS idx_token_usage_created ON token_usage(created_at);
