
-- Etapa 84F
-- Adiciona suporte ao resultado incerto de entrega
-- e ao controle temporal de reservas.

ALTER TABLE device_commands
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

ALTER TABLE device_commands
    ADD COLUMN IF NOT EXISTS delivery_unknown_at TIMESTAMPTZ;

-- Permitir o novo estado sem enfraquecer
-- as demais restrições de integridade.

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid = 'device_commands'::regclass
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid)
              LIKE '%status%'
    LOOP
        EXECUTE format(
            'ALTER TABLE device_commands DROP CONSTRAINT %I',
            constraint_name
        );
    END LOOP;
END $$;

ALTER TABLE device_commands
    ADD CONSTRAINT ck_device_commands_status
    CHECK (
        status IN (
            'queued',
            'sending',
            'sent',
            'responded',
            'timeout',
            'delivery_failed',
            'delivery_unknown',
            'cancelled'
        )
    );

CREATE INDEX IF NOT EXISTS
    idx_device_commands_sending_recovery
ON device_commands (claimed_at, id)
WHERE status = 'sending';

CREATE INDEX IF NOT EXISTS
    idx_device_commands_queued_expiration
ON device_commands (expires_at, id)
WHERE status = 'queued';
