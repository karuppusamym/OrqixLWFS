-- Shared working calendars (docs/build/S139, CG-W05). A calendar is a workspace asset with numbered, unchangeable
-- versions; a workflow pins one version in dependencies.assets (kind calendar), so a release and its running requests
-- keep the version they were published with. Archiving hides a calendar from new pins; pinned versions keep working.
CREATE TABLE working_calendar (
    id               uuid PRIMARY KEY,
    workspace_id     uuid NOT NULL REFERENCES workspace (id),
    key              text NOT NULL CHECK (key ~ '^[a-z][a-z0-9-]{0,62}[a-z0-9]$'),
    name             text NOT NULL CHECK (length(name) BETWEEN 1 AND 80 AND name = btrim(name)),
    description      text CHECK (description IS NULL OR length(description) <= 300),
    latest_version   integer NOT NULL DEFAULT 1 CHECK (latest_version >= 1),
    archived_at      timestamptz,
    created_by       uuid NOT NULL REFERENCES subject (id),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (workspace_id, key),
    UNIQUE (workspace_id, id)
);
CREATE UNIQUE INDEX working_calendar_name_unique ON working_calendar (workspace_id, lower(name));

CREATE TABLE working_calendar_version (
    workspace_id uuid NOT NULL,
    calendar_id  uuid NOT NULL,
    version      integer NOT NULL CHECK (version >= 1),
    -- {zone, weekdays, intervals[{start,end}], holidays[]}; checked by the compiler's BusinessCalendar on save.
    content      jsonb NOT NULL,
    sha256       text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    change_note  text CHECK (change_note IS NULL OR length(change_note) <= 300),
    created_by   uuid NOT NULL REFERENCES subject (id),
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (calendar_id, version),
    FOREIGN KEY (workspace_id, calendar_id) REFERENCES working_calendar (workspace_id, id)
);
GRANT SELECT, INSERT, UPDATE ON working_calendar TO ${app_role};
GRANT SELECT, INSERT ON working_calendar_version TO ${app_role};
