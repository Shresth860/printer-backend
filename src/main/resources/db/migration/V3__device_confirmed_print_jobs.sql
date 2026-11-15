ALTER TABLE print_job
    ADD COLUMN printer_id uuid REFERENCES printer(id) ON DELETE SET NULL,
    ADD COLUMN printing_started_at timestamptz,
    ADD COLUMN printed_at timestamptz;

ALTER TABLE print_job
    DROP CONSTRAINT print_job_status_check,
    ADD CONSTRAINT print_job_status_check
        CHECK (status IN ('PENDING','SENT_TO_DEVICE','DISPATCHED','PRINTING','SUCCESS','FAILED','CANCELLED'));

CREATE INDEX idx_print_job_printer_status_created ON print_job(printer_id, status, created_at);

ALTER TABLE printer ADD COLUMN last_seen_at timestamptz;

ALTER TABLE print_job ALTER COLUMN max_retries SET DEFAULT 3;
UPDATE print_job SET max_retries = 3;
