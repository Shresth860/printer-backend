ALTER TABLE print_job
    ADD COLUMN printer_connected boolean,
    ADD COLUMN bytes_written bigint,
    ADD COLUMN flush_ok boolean;
