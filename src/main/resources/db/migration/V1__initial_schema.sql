
-- Aporia Tracking Service
-- V1: Cadastro de contas, dispositivos e pacotes brutos

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    protocol VARCHAR(50) NOT NULL,
    identity VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_devices_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenants(id),

    CONSTRAINT uq_devices_protocol_identity
        UNIQUE (protocol, identity),

    CONSTRAINT uq_devices_tenant_id
        UNIQUE (tenant_id, id)
);

CREATE TABLE raw_packets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL,
    payload BYTEA NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    decoder_version VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_code VARCHAR(100),
    correlation_id UUID NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,

    CONSTRAINT fk_packets_device
        FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices(tenant_id, id),

    CONSTRAINT chk_packet_status
        CHECK (status IN ('pending', 'processed', 'rejected')),

    CONSTRAINT chk_packet_attempts
        CHECK (attempts >= 0),

    CONSTRAINT chk_packet_payload
        CHECK (octet_length(payload) BETWEEN 1 AND 65536)
);

CREATE INDEX idx_devices_tenant
    ON devices(tenant_id);

CREATE INDEX idx_raw_packets_pending
    ON raw_packets(available_at, received_at, id)
    WHERE status = 'pending';

CREATE INDEX idx_raw_packets_device_received
    ON raw_packets(tenant_id, device_id, received_at DESC);
