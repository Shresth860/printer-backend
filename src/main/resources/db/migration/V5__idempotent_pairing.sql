ALTER TABLE pairing_code
    ADD COLUMN paired_device_id uuid REFERENCES app_device(id) ON DELETE SET NULL;
