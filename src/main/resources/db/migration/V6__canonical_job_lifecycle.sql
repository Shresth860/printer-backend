ALTER TABLE print_job DROP CONSTRAINT print_job_status_check;
ALTER TABLE print_job ALTER COLUMN status TYPE varchar(32);

UPDATE print_job SET status = CASE status
    WHEN 'PENDING' THEN 'QUEUED'
    WHEN 'DISPATCHED' THEN 'SENT_TO_PRINTER'
    WHEN 'SENT_TO_DEVICE' THEN 'SENT_TO_PRINTER'
    WHEN 'PRINTING' THEN 'SENT_TO_PRINTER'
    WHEN 'SUCCESS' THEN 'PRINTED_CONFIRMED'
    ELSE status
END;

ALTER TABLE print_job
    ADD CONSTRAINT print_job_status_check
    CHECK (status IN ('QUEUED','SENT_TO_PRINTER','PRINTED_CONFIRMED','FAILED','CANCELLED')),
    ADD COLUMN next_retry_at timestamptz,
    ADD COLUMN client_updated_at timestamptz;

ALTER TABLE print_job ALTER COLUMN status TYPE varchar(32);
ALTER TABLE print_log ALTER COLUMN status TYPE varchar(32);

CREATE INDEX idx_print_job_retry ON print_job(status, next_retry_at)
    WHERE status = 'QUEUED' AND next_retry_at IS NOT NULL;
