-- UI02 / S111: registered, versioned UI extension bundles with declared capabilities and admin approval.
CREATE TABLE ui_extension (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace (id),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    description text CHECK (description IS NULL OR length(description) <= 300),
    created_by uuid NOT NULL REFERENCES subject (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, id)
);
CREATE UNIQUE INDEX ui_extension_name_unique ON ui_extension (workspace_id, lower(name));

-- Bundle bytes, hash and capabilities are immutable; only the decision columns may change.
CREATE TABLE ui_extension_version (
    workspace_id uuid NOT NULL,
    extension_id uuid NOT NULL,
    version integer NOT NULL CHECK (version >= 1),
    source text NOT NULL CHECK (octet_length(source) BETWEEN 1 AND 65536),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    capabilities jsonb NOT NULL CHECK (jsonb_typeof(capabilities) = 'object'),
    height integer NOT NULL CHECK (height BETWEEN 80 AND 800),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'revoked')),
    submitted_by uuid NOT NULL REFERENCES subject (id),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    decided_by uuid REFERENCES subject (id),
    decided_at timestamptz,
    decision_note text CHECK (decision_note IS NULL OR length(decision_note) <= 500),
    PRIMARY KEY (extension_id, version),
    FOREIGN KEY (workspace_id, extension_id) REFERENCES ui_extension (workspace_id, id),
    CHECK ((status = 'pending') = (decided_by IS NULL)),
    CHECK (decided_by IS NULL OR decided_by <> submitted_by)
);

CREATE TABLE ui_extension_decision (
    workspace_id uuid NOT NULL,
    extension_id uuid NOT NULL,
    version integer NOT NULL,
    seq integer NOT NULL CHECK (seq >= 1),
    decision text NOT NULL CHECK (decision IN ('approve', 'reject', 'revoke')),
    note text NOT NULL CHECK (length(note) BETWEEN 1 AND 500),
    decided_by uuid NOT NULL REFERENCES subject (id),
    decided_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (extension_id, version, seq),
    FOREIGN KEY (extension_id, version) REFERENCES ui_extension_version (extension_id, version)
);

GRANT SELECT, INSERT ON ui_extension TO ${app_role};
-- Row locks (SELECT ... FOR UPDATE) serialize version numbering; they need UPDATE on some column.
GRANT UPDATE (description) ON ui_extension TO ${app_role};
GRANT SELECT, INSERT ON ui_extension_version TO ${app_role};
GRANT UPDATE (status, decided_by, decided_at, decision_note) ON ui_extension_version TO ${app_role};
GRANT SELECT, INSERT ON ui_extension_decision TO ${app_role};
