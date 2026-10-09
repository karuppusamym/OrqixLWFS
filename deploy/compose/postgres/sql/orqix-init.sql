-- Orqix local PostgreSQL bootstrap (development only).
--
-- Executed once by 01-orqix-init.sh as the superuser, connected to database "orqix" (POSTGRES_DB).
-- psql variables migrator_password and app_password are supplied by the script.
--
-- Mirrors the production privilege model (docs/build/S1 section 5/6, contracts/database/V1__orqix_core.sql):
--   orqix_migrator  owns the database and both schemas; only the migration job (profile "migrate") connects as this role
--                   and it is the only writer of DDL (Flyway for schema "orqix", Flowable schema update for "flowable").
--   orqix_app       runtime role of the application pods: DML only, no DDL, no CREATE anywhere.
-- Application tables are granted explicitly by the Flyway migrations (${app_role} placeholder); the append-only
-- tables are deliberately NOT covered by default privileges. Flowable tables are created at migration time by
-- orqix_migrator, so the "flowable" schema gets default privileges for the runtime role.

CREATE ROLE orqix_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE orqix_app      LOGIN PASSWORD :'app_password';

ALTER DATABASE orqix OWNER TO orqix_migrator;
REVOKE ALL ON DATABASE orqix FROM PUBLIC;
GRANT CONNECT ON DATABASE orqix TO orqix_migrator, orqix_app;

-- Nobody builds objects in "public".
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA orqix    AUTHORIZATION orqix_migrator;
CREATE SCHEMA flowable AUTHORIZATION orqix_migrator;

GRANT USAGE ON SCHEMA orqix, flowable TO orqix_app;

-- Tables and sequences that orqix_migrator creates later in "flowable" (Flowable schema update) are usable by the
-- runtime role without any further grant.
ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO orqix_app;
ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable
    GRANT USAGE, SELECT ON SEQUENCES TO orqix_app;
