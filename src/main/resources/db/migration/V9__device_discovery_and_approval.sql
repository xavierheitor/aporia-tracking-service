-- Discoveries belong to the installation administrator until assigned to a tenant.
CREATE TABLE device_discoveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    protocol varchar(50) NOT NULL,
    identity varchar(100) NOT NULL,
    manufacturer varchar(60) NOT NULL,
    device_type varchar(40) NOT NULL,
    wire_format varchar(40) NOT NULL,
    reported_model varchar(80),
    first_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    observations bigint NOT NULL DEFAULT 1 CHECK (observations > 0),
    approved_at timestamptz,
    approved_by name,
    UNIQUE (protocol, identity),
    CHECK ((approved_at IS NULL) = (approved_by IS NULL))
);

ALTER TABLE devices
    ADD COLUMN manufacturer varchar(60),
    ADD COLUMN device_type varchar(40),
    ADD COLUMN wire_format varchar(40),
    ADD COLUMN reported_model varchar(80);

CREATE INDEX idx_pending_discoveries ON device_discoveries (last_seen_at DESC, id)
    WHERE approved_at IS NULL;

CREATE VIEW pending_device_discoveries AS
SELECT q.id, q.protocol, q.identity, q.manufacturer, q.device_type, q.wire_format,
       q.reported_model, q.first_seen_at, q.last_seen_at, q.observations
FROM device_discoveries q
WHERE q.approved_at IS NULL AND NOT EXISTS (
    SELECT 1 FROM devices d WHERE d.protocol=q.protocol AND d.identity=q.identity
);

-- SECURITY INVOKER: only trusted operators with underlying table privileges can approve.
CREATE FUNCTION approve_device_discovery(discovery uuid, destination_tenant uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE
    candidate device_discoveries%ROWTYPE;
BEGIN
    SELECT * INTO STRICT candidate FROM device_discoveries WHERE id=discovery FOR UPDATE;
    IF candidate.approved_at IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM devices WHERE id=discovery AND tenant_id=destination_tenant
                   AND protocol=candidate.protocol AND identity=candidate.identity) THEN
            RETURN discovery;
        END IF;
        RAISE EXCEPTION 'DISCOVERY_ALREADY_APPROVED';
    END IF;

    INSERT INTO devices (id, tenant_id, protocol, identity, manufacturer, device_type, wire_format, reported_model)
    VALUES (candidate.id, destination_tenant, candidate.protocol, candidate.identity,
            candidate.manufacturer, candidate.device_type, candidate.wire_format, candidate.reported_model);

    UPDATE device_discoveries SET approved_at=clock_timestamp(), approved_by=current_user WHERE id=discovery;
    RETURN discovery;
END
$$;
REVOKE ALL ON FUNCTION approve_device_discovery(uuid, uuid) FROM PUBLIC;
