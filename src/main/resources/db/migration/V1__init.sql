CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_device (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id varchar(160) NOT NULL,
    device_name varchar(160) NOT NULL,
    android_version varchar(80) NOT NULL,
    app_version varchar(80) NOT NULL,
    fcm_token varchar(4096),
    last_seen_at timestamptz,
    is_active boolean NOT NULL DEFAULT true,
    refresh_token_hash varchar(64),
    refresh_token_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_device_seller_id ON app_device(seller_id);

CREATE TABLE pairing_code (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id varchar(160) NOT NULL,
    code varchar(8) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_pairing_code_code ON pairing_code(code);

CREATE TABLE printer (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id uuid NOT NULL REFERENCES app_device(id) ON DELETE CASCADE,
    seller_id varchar(160) NOT NULL,
    name varchar(160) NOT NULL,
    connection_type varchar(16) NOT NULL CHECK (connection_type IN ('BLUETOOTH','USB','LAN')),
    address varchar(512),
    paper_width_mm integer NOT NULL CHECK (paper_width_mm IN (58,80)),
    is_default boolean NOT NULL DEFAULT false,
    last_status varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_printer_device_name UNIQUE(device_id, name)
);

CREATE TABLE print_job (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id varchar(160) NOT NULL,
    device_id uuid REFERENCES app_device(id) ON DELETE SET NULL,
    order_id varchar(160) NOT NULL,
    job_type varchar(16) NOT NULL CHECK (job_type IN ('RECEIPT','TEST','KOT')),
    payload_json jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DISPATCHED','PRINTING','SUCCESS','FAILED','CANCELLED')),
    retry_count integer NOT NULL DEFAULT 0,
    max_retries integer NOT NULL DEFAULT 5,
    idempotency_key varchar(160) NOT NULL,
    error_code varchar(120),
    error_message varchar(1000),
    open_cash_drawer boolean NOT NULL DEFAULT false,
    cut_paper boolean NOT NULL DEFAULT true,
    copies integer NOT NULL DEFAULT 1 CHECK (copies BETWEEN 1 AND 10),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    dispatched_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_print_job_seller_idempotency UNIQUE(seller_id, idempotency_key)
);
CREATE INDEX idx_print_job_seller_status_created ON print_job(seller_id, status, created_at);
CREATE INDEX idx_print_job_idempotency_key ON print_job(idempotency_key);
CREATE INDEX idx_print_job_device_status ON print_job(device_id, status);
CREATE INDEX idx_print_job_dispatch ON print_job(status, dispatched_at);

CREATE TABLE print_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id uuid NOT NULL REFERENCES print_job(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL,
    message varchar(1000),
    device_id uuid REFERENCES app_device(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_print_log_job_created ON print_log(job_id, created_at);

CREATE TABLE app_release (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_code integer NOT NULL UNIQUE,
    version_name varchar(80) NOT NULL,
    apk_url varchar(2048) NOT NULL,
    sha256 varchar(64) NOT NULL,
    mandatory boolean NOT NULL DEFAULT false,
    release_notes varchar(4000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE print_job_archive (
    id uuid PRIMARY KEY,
    seller_id varchar(160) NOT NULL,
    archived_job jsonb NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now()
);
