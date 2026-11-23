
-- V3: comandos remotos e respostas dos dispositivos.
-- Preserva a separação por tenant e os pacotes originais.

CREATE TABLE device_commands (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL,

    protocol VARCHAR(50) NOT NULL,
    payload BYTEA NOT NULL,

    status VARCHAR(30) NOT NULL DEFAULT 'queued',
    result VARCHAR(30),

    attempts INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    responded_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,

    error_code VARCHAR(100),

    CONSTRAINT fk_commands_device
        FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices (tenant_id, id),

    CONSTRAINT uq_commands_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT chk_commands_payload
        CHECK (
            octet_length(payload) BETWEEN 1 AND 8192
        ),

    CONSTRAINT chk_commands_status
        CHECK (
            status IN (
                'queued',
                'sending',
                'sent',
                'responded',
                'timeout',
                'delivery_failed',
                'cancelled'
            )
        ),

    CONSTRAINT chk_commands_result
        CHECK (
            result IS NULL OR result IN (
                'success',
                'failure',
                'unknown'
            )
        ),

    CONSTRAINT chk_commands_attempts
        CHECK (attempts >= 0),

    CONSTRAINT chk_commands_expiration
        CHECK (expires_at > created_at),

    CONSTRAINT chk_commands_response_state
        CHECK (
            status <> 'responded'
            OR responded_at IS NOT NULL
        )
);

CREATE INDEX idx_commands_dispatch
    ON device_commands (
        available_at,
        created_at,
        id
    )
    WHERE status = 'queued';

CREATE INDEX idx_commands_device_status
    ON device_commands (
        tenant_id,
        device_id,
        status
    );

-- Permite uma FK composta que também valide o tenant
-- do pacote original.
CREATE UNIQUE INDEX uq_raw_packets_tenant_id
    ON raw_packets (tenant_id, id);

CREATE TABLE device_command_responses (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL,

    command_id UUID,
    raw_packet_id UUID NOT NULL,

    protocol VARCHAR(50) NOT NULL,
    response_type VARCHAR(30) NOT NULL,

    payload BYTEA NOT NULL,

    result VARCHAR(30) NOT NULL DEFAULT 'unknown',

    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_responses_device
        FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices (tenant_id, id),

    CONSTRAINT fk_responses_command
        FOREIGN KEY (tenant_id, command_id)
        REFERENCES device_commands (tenant_id, id),

    CONSTRAINT fk_responses_raw_packet
        FOREIGN KEY (tenant_id, raw_packet_id)
        REFERENCES raw_packets (tenant_id, id),

    CONSTRAINT uq_responses_raw_packet
        UNIQUE (raw_packet_id),

    CONSTRAINT chk_responses_payload
        CHECK (
            octet_length(payload) BETWEEN 1 AND 65536
        ),

    CONSTRAINT chk_responses_type
        CHECK (
            response_type IN ('RES', 'RPR')
        ),

    CONSTRAINT chk_responses_result
        CHECK (
            result IN (
                'success',
                'failure',
                'unknown'
            )
        )
);

CREATE INDEX idx_responses_command
    ON device_command_responses (
        tenant_id,
        command_id,
        received_at DESC
    )
    WHERE command_id IS NOT NULL;

CREATE INDEX idx_responses_device
    ON device_command_responses (
        tenant_id,
        device_id,
        received_at DESC
    );
