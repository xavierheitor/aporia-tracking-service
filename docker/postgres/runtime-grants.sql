-- Aplicar como proprietário após o Flyway no schema da aplicação.
-- Interrompe a instalação se um login existente herdou acesso administrativo.
DO $$
DECLARE
    runtime_role name;
BEGIN
    FOREACH runtime_role IN ARRAY ARRAY['tracking_gateway', 'tracking_worker'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role
                   AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
           OR has_database_privilege(runtime_role, current_database(), 'CREATE')
           OR has_schema_privilege(runtime_role, current_schema(), 'CREATE')
           OR has_table_privilege(runtime_role, 'devices', 'INSERT')
           OR has_function_privilege(runtime_role, 'approve_device_discovery(uuid,uuid)', 'EXECUTE') THEN
            RAISE EXCEPTION 'Login de serviço com privilégios administrativos: %', runtime_role;
        END IF;
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role);
    END LOOP;
END
$$;

GRANT SELECT ON devices TO tracking_gateway, tracking_worker;
GRANT UPDATE (command_session_token) ON devices TO tracking_gateway;
GRANT INSERT (id, tenant_id, device_id, payload, payload_hash, decoder_version, status, correlation_id)
    ON raw_packets TO tracking_gateway;
GRANT SELECT, INSERT (protocol, identity, manufacturer, device_type, wire_format, reported_model),
    UPDATE (last_seen_at, observations, wire_format, reported_model)
    ON device_discoveries TO tracking_gateway;
GRANT SELECT, UPDATE (status, attempts, claimed_at, claim_token, error_code, dispatch_owner,
    transmission_started_at, delivery_unknown_at, sent_at) ON device_commands TO tracking_gateway;

GRANT SELECT, UPDATE (status, attempts, available_at, processed_at, error_code)
    ON raw_packets TO tracking_worker;
GRANT SELECT ON assignments TO tracking_worker;
GRANT SELECT, INSERT ON messages, device_command_responses TO tracking_worker;
GRANT INSERT ON positions, outbox_events TO tracking_worker;
GRANT SELECT, INSERT, UPDATE (position_id, recorded_at, received_at) ON last_positions TO tracking_worker;
