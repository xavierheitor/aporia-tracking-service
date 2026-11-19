
-- Aporia Tracking Service
-- V2: Assets, assignments, messages, positions and outbox

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 1. Bens rastreados
CREATE TABLE assets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(150) NOT NULL,
    external_id VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, external_id)
);

-- 2. Histórico de associação entre dispositivo e bem
CREATE TABLE assignments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,

    FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices(tenant_id, id),

    FOREIGN KEY (tenant_id, asset_id)
        REFERENCES assets(tenant_id, id),

    CHECK (ends_at IS NULL OR ends_at > starts_at),

    -- Um dispositivo não pode pertencer a dois bens
    -- no mesmo instante.
    EXCLUDE USING gist (
        device_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

-- 3. Mensagens únicas por dispositivo e conteúdo
CREATE TABLE messages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL,
    raw_packet_id UUID NOT NULL REFERENCES raw_packets(id),
    payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices(tenant_id, id),

    UNIQUE (tenant_id, device_id, payload_hash),
    UNIQUE (raw_packet_id),
    UNIQUE (tenant_id, id),

    CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

-- 4. Histórico de posições
CREATE TABLE positions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    device_id UUID NOT NULL,
    message_id UUID NOT NULL,
    reading_index INTEGER NOT NULL DEFAULT 0,

    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,

    geom GEOMETRY(Point, 4326) NOT NULL,

    speed_kph DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    satellites INTEGER,
    ignition BOOLEAN,

    FOREIGN KEY (tenant_id, asset_id)
        REFERENCES assets(tenant_id, id),

    FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices(tenant_id, id),

    FOREIGN KEY (tenant_id, message_id)
        REFERENCES messages(tenant_id, id),

    UNIQUE (tenant_id, id),
    UNIQUE (message_id, reading_index),

    CHECK (reading_index >= 0),
    CHECK (speed_kph IS NULL OR speed_kph >= 0),
    CHECK (heading IS NULL OR
           (heading >= 0 AND heading < 360)),
    CHECK (satellites IS NULL OR
           satellites BETWEEN 0 AND 99),
    CHECK (ST_X(geom) BETWEEN -180 AND 180),
    CHECK (ST_Y(geom) BETWEEN -90 AND 90)
);

-- 5. Última posição conhecida por bem
CREATE TABLE last_positions (
    tenant_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    position_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (tenant_id, asset_id),

    FOREIGN KEY (tenant_id, asset_id)
        REFERENCES assets(tenant_id, id),

    FOREIGN KEY (tenant_id, position_id)
        REFERENCES positions(tenant_id, id)
);

-- 6. Eventos para publicação confiável
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,

    CHECK (attempts >= 0)
);

-- Índices para consultas frequentes

CREATE INDEX idx_assets_tenant
    ON assets(tenant_id);

CREATE INDEX idx_assignments_asset
    ON assignments(tenant_id, asset_id, starts_at DESC);

CREATE INDEX idx_positions_history
    ON positions(tenant_id, asset_id, recorded_at DESC);

CREATE INDEX idx_positions_device
    ON positions(tenant_id, device_id, recorded_at DESC);

CREATE INDEX idx_positions_geom
    ON positions USING GIST(geom);

CREATE INDEX idx_outbox_pending
    ON outbox_events(created_at, id)
    WHERE published_at IS NULL;
