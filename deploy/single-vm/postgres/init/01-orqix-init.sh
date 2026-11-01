#!/bin/sh
# Runs once, when the data volume is empty (docker-entrypoint-initdb.d), as the PostgreSQL superuser connected to
# database "orqix". Same privilege model as deploy/compose/postgres (docs/admin-guide.md section 2), plus a separate
# database and role for the bundled Keycloak. Passwords come from the container environment (.env).
set -eu

: "${ORQIX_MIGRATOR_PASSWORD:?ORQIX_MIGRATOR_PASSWORD must be set}"
: "${ORQIX_APP_PASSWORD:?ORQIX_APP_PASSWORD must be set}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v migrator_password="$ORQIX_MIGRATOR_PASSWORD" \
     -v app_password="$ORQIX_APP_PASSWORD" \
     -v keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
-- orqix_migrator owns the database and both schemas (DDL, migration job only); orqix_app is the runtime role (DML only).
CREATE ROLE orqix_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE orqix_app      LOGIN PASSWORD :'app_password';

ALTER DATABASE orqix OWNER TO orqix_migrator;
REVOKE ALL ON DATABASE orqix FROM PUBLIC;
GRANT CONNECT ON DATABASE orqix TO orqix_migrator, orqix_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA orqix    AUTHORIZATION orqix_migrator;
CREATE SCHEMA flowable AUTHORIZATION orqix_migrator;
GRANT USAGE ON SCHEMA orqix, flowable TO orqix_app;

ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO orqix_app;
ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable
    GRANT USAGE, SELECT ON SEQUENCES TO orqix_app;

-- Bundled identity provider: own role and database, no access to the Orqix database.
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_password';
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
SQL
