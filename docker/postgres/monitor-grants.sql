-- Aplicar como proprietário do schema após o Flyway.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'tracking_monitor'
          AND (
              rolsuper
              OR rolcreaterole
              OR rolcreatedb
              OR rolreplication
              OR rolbypassrls
          )
    )
    OR has_database_privilege(
        'tracking_monitor',
        current_database(),
        'CREATE'
    )
    OR has_schema_privilege(
        'tracking_monitor',
        current_schema(),
        'CREATE'
    )
    THEN
        RAISE EXCEPTION
            'Monitor role has administrative privileges';
    END IF;

    EXECUTE format(
        'GRANT USAGE ON SCHEMA %I TO tracking_monitor',
        current_schema()
    );
END
$$;

GRANT SELECT (
    status,
    attempts,
    available_at,
    received_at
)
ON raw_packets TO tracking_monitor;

GRANT SELECT (
    status,
    created_at,
    available_at,
    expires_at
)
ON device_commands TO tracking_monitor;

GRANT SELECT (
    created_at,
    published_at
)
ON outbox_events TO tracking_monitor;