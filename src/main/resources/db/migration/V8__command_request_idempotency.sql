ALTER TABLE device_commands ADD COLUMN request_key uuid;
CREATE UNIQUE INDEX uq_commands_request
    ON device_commands (tenant_id, device_id, requested_by, request_key)
    WHERE request_key IS NOT NULL;
