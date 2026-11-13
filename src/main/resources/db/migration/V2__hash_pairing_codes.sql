DELETE FROM pairing_code;

ALTER TABLE pairing_code
    DROP COLUMN code,
    ADD COLUMN code_hash varchar(64) NOT NULL;

CREATE UNIQUE INDEX uq_pairing_code_code_hash ON pairing_code(code_hash);
