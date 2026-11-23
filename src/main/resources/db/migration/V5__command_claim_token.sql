
ALTER TABLE device_commands
    ADD COLUMN claim_token UUID;

CREATE INDEX idx_device_commands_claim_token
    ON device_commands (claim_token)
    WHERE claim_token IS NOT NULL;

ALTER TABLE device_commands
    ADD CONSTRAINT chk_commands_claim_token
    CHECK (
        status <> 'sending'
        OR claim_token IS NOT NULL
    );
