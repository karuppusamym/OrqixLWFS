-- S68: editing and disabling adapters and inbound triggers (optimistic version), and inbound event retention.
-- Kind, key, trigger source and target workflow stay fixed after creation; only the allowlists, mapping, name, secret
-- reference and enabled flag change. The adapter version is recorded with every workflow send in effect_journal.
ALTER TABLE adapter_config
    ADD COLUMN version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    ADD COLUMN updated_by uuid REFERENCES subject(id),
    ADD COLUMN updated_at timestamptz;

ALTER TABLE inbound_trigger
    ADD COLUMN version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    ADD COLUMN updated_by uuid REFERENCES subject(id),
    ADD COLUMN updated_at timestamptz;

-- The pending-event cap (1,000 unstarted events per trigger) counts these rows on every receive.
CREATE INDEX inbound_event_pending ON inbound_event (trigger_id) WHERE status = 'received';

GRANT UPDATE ON adapter_config TO ${app_role};
GRANT UPDATE ON inbound_trigger TO ${app_role};
GRANT DELETE ON inbound_event TO ${app_role};
