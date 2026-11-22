CREATE TABLE print_job_sync_event (
    client_id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES print_job(id) ON DELETE CASCADE,
    client_timestamp timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
