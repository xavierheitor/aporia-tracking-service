ALTER TABLE device_commands
    ADD COLUMN dispatch_owner uuid,
    ADD CONSTRAINT chk_commands_response_state
        CHECK (status <> 'responded' OR responded_at IS NOT NULL);

CREATE UNIQUE INDEX uq_commands_in_flight ON device_commands (tenant_id, device_id)
    WHERE status IN ('sending', 'transmitting', 'sent');

CREATE INDEX idx_commands_sent_expiration ON device_commands (expires_at)
    WHERE status = 'sent';

ALTER TABLE device_commands ADD UNIQUE (tenant_id, device_id, protocol, id);
ALTER TABLE device_command_responses ADD FOREIGN KEY (tenant_id, device_id, protocol, command_id)
    REFERENCES device_commands (tenant_id, device_id, protocol, id);
ALTER TABLE raw_packets ADD UNIQUE (tenant_id, device_id, id);
ALTER TABLE device_command_responses ADD FOREIGN KEY (tenant_id, device_id, raw_packet_id)
    REFERENCES raw_packets (tenant_id, device_id, id);

ALTER TABLE device_commands ADD COLUMN requested_by uuid;
