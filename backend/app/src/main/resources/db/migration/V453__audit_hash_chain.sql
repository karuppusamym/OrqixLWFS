-- CG-I06 (docs/build/S151): tamper-evident audit. Every audit_event row joins a hash chain per workspace (chain key =
-- workspace ID, or 'platform' for platform-level events): chain_seq counts 1, 2, 3, ... and
--   hash = sha256(prev_hash | chain_seq | every stored column), prev_hash = hash of the previous event in the chain
-- (64 zeros for the first). The chain is written by a SECURITY DEFINER trigger, so every insert path is chained, and
-- the head table that serialises inserts per chain cannot be changed by the application role. Who can read audit data
-- does not change. Verification recomputes the hashes and reports the first broken link (governance AuditChains).
ALTER TABLE audit_event ADD COLUMN chain_seq bigint;
ALTER TABLE audit_event ADD COLUMN prev_hash char(64);
ALTER TABLE audit_event ADD COLUMN hash char(64);

CREATE TABLE audit_chain_head (
    chain_key  text PRIMARY KEY,
    seq        bigint NOT NULL CHECK (seq >= 0),
    hash       char(64) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Each field is written as <length>:<value> so no two different rows can produce the same text. The time is
-- rendered in UTC with microseconds, independent of the session time zone.
CREATE FUNCTION orqix_audit_hash(prev_hash text, chain_seq bigint, id uuid, workspace_id uuid, actor_type text,
        actor_subject_id uuid, action text, resource_type text, resource_id text, outcome text, correlation_id text,
        occurred_at timestamptz, metadata jsonb) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT encode(sha256(convert_to(
        length(prev_hash) || ':' || prev_hash || '|' ||
        length(chain_seq::text) || ':' || chain_seq::text || '|' ||
        length(id::text) || ':' || id::text || '|' ||
        length(COALESCE(workspace_id::text, '')) || ':' || COALESCE(workspace_id::text, '') || '|' ||
        length(actor_type) || ':' || actor_type || '|' ||
        length(COALESCE(actor_subject_id::text, '')) || ':' || COALESCE(actor_subject_id::text, '') || '|' ||
        length(action) || ':' || action || '|' ||
        length(resource_type) || ':' || resource_type || '|' ||
        length(COALESCE(resource_id, '')) || ':' || COALESCE(resource_id, '') || '|' ||
        length(outcome) || ':' || outcome || '|' ||
        length(correlation_id) || ':' || correlation_id || '|' ||
        to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' ||
        length(metadata::text) || ':' || metadata::text, 'UTF8')), 'hex')
$$;

-- Backfill: chain the existing events of every workspace (and the platform) in occurred_at, id order.
DO $$
DECLARE
    r record;
    last_key text := NULL;
    n bigint := 0;
    prev text := repeat('0', 64);
    h text;
BEGIN
    FOR r IN SELECT a.*, COALESCE(a.workspace_id::text, 'platform') AS ck FROM audit_event a
             ORDER BY COALESCE(a.workspace_id::text, 'platform'), a.occurred_at, a.id LOOP
        IF last_key IS DISTINCT FROM r.ck THEN
            IF last_key IS NOT NULL THEN
                INSERT INTO audit_chain_head (chain_key, seq, hash) VALUES (last_key, n, prev);
            END IF;
            last_key := r.ck;
            n := 0;
            prev := repeat('0', 64);
        END IF;
        n := n + 1;
        h := orqix_audit_hash(prev, n, r.id, r.workspace_id, r.actor_type, r.actor_subject_id, r.action,
                              r.resource_type, r.resource_id, r.outcome, r.correlation_id, r.occurred_at, r.metadata);
        UPDATE audit_event SET chain_seq = n, prev_hash = prev, hash = h WHERE id = r.id;
        prev := h;
    END LOOP;
    IF last_key IS NOT NULL THEN
        INSERT INTO audit_chain_head (chain_key, seq, hash) VALUES (last_key, n, prev);
    END IF;
END;
$$;

CREATE FUNCTION audit_event_chain() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = orqix, pg_temp AS $$
DECLARE
    k text := COALESCE(NEW.workspace_id::text, 'platform');
    head record;
BEGIN
    INSERT INTO audit_chain_head (chain_key, seq, hash) VALUES (k, 0, repeat('0', 64))
        ON CONFLICT (chain_key) DO NOTHING;
    SELECT seq, hash INTO head FROM audit_chain_head WHERE chain_key = k FOR UPDATE;
    NEW.chain_seq := head.seq + 1;
    NEW.prev_hash := head.hash;
    NEW.hash := orqix_audit_hash(NEW.prev_hash, NEW.chain_seq, NEW.id, NEW.workspace_id, NEW.actor_type,
                                 NEW.actor_subject_id, NEW.action, NEW.resource_type, NEW.resource_id, NEW.outcome,
                                 NEW.correlation_id, NEW.occurred_at, NEW.metadata);
    UPDATE audit_chain_head SET seq = NEW.chain_seq, hash = NEW.hash, updated_at = now() WHERE chain_key = k;
    RETURN NEW;
END;
$$;
CREATE TRIGGER audit_event_chain BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION audit_event_chain();

ALTER TABLE audit_event ALTER COLUMN chain_seq SET NOT NULL;
ALTER TABLE audit_event ALTER COLUMN prev_hash SET NOT NULL;
ALTER TABLE audit_event ALTER COLUMN hash SET NOT NULL;
CREATE UNIQUE INDEX audit_event_chain_position ON audit_event ((COALESCE(workspace_id::text, 'platform')), chain_seq);

-- Results of the verification job and endpoint (one row per check and chain).
CREATE TABLE audit_chain_check (
    id           uuid PRIMARY KEY,
    chain_key    text NOT NULL,
    checked_at   timestamptz NOT NULL,
    checked      bigint NOT NULL CHECK (checked >= 0),
    intact       boolean NOT NULL,
    broken_seq   bigint,
    broken_event uuid,
    reason       text CHECK (reason IS NULL OR reason IN ('hashMismatch', 'previousHashMismatch', 'missingEvent',
                                                         'headMismatch')),
    CONSTRAINT audit_chain_check_shape CHECK (intact = (broken_seq IS NULL AND reason IS NULL))
);
CREATE INDEX audit_chain_check_latest ON audit_chain_check (chain_key, checked_at DESC);

GRANT SELECT ON audit_chain_head TO ${app_role};
GRANT EXECUTE ON FUNCTION orqix_audit_hash(text, bigint, uuid, uuid, text, uuid, text, text, text, text, text,
    timestamptz, jsonb) TO ${app_role};
GRANT SELECT, INSERT ON audit_chain_check TO ${app_role};
