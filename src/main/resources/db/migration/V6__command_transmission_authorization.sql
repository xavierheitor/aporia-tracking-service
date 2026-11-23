
-- Etapa 84J
-- Estado intermediário para autorização de transmissão.

ALTER TABLE device_commands
    ADD COLUMN transmission_started_at TIMESTAMPTZ;

-- A restrição de status foi criada na V4.
ALTER TABLE device_commands
    DROP CONSTRAINT ck_device_commands_status;

ALTER TABLE device_commands
    ADD CONSTRAINT ck_device_commands_status
    CHECK (
        status IN (
            'queued',
            'sending',
            'transmitting',
            'sent',
            'responded',
            'timeout',
            'delivery_failed',
            'delivery_unknown',
            'cancelled'
        )
    );

-- A tentativa em transmissão também precisa manter
-- um identificador de propriedade.
ALTER TABLE device_commands
    DROP CONSTRAINT chk_commands_claim_token;

ALTER TABLE device_commands
    ADD CONSTRAINT chk_commands_claim_token
    CHECK (
        status NOT IN ('sending', 'transmitting')
        OR claim_token IS NOT NULL
    );

-- Um comando em transmissão precisa registrar
-- quando a tentativa foi iniciada.
ALTER TABLE device_commands
    ADD CONSTRAINT chk_commands_transmission_started
    CHECK (
        status <> 'transmitting'
        OR transmission_started_at IS NOT NULL
    );

CREATE INDEX idx_device_commands_transmitting
    ON device_commands (transmission_started_at, id)
    WHERE status = 'transmitting';
