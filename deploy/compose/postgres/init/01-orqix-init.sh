#!/bin/sh
# Runs once, on first initialisation of the data volume (docker-entrypoint-initdb.d), as the PostgreSQL superuser
# against the database named by POSTGRES_DB (orqix). Role passwords come from the container environment so they
# can be overridden in deploy/compose/.env; the SQL itself lives in ../sql/orqix-init.sql.
set -eu

: "${ORQIX_MIGRATOR_PASSWORD:?ORQIX_MIGRATOR_PASSWORD must be set}"
: "${ORQIX_APP_PASSWORD:?ORQIX_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v migrator_password="$ORQIX_MIGRATOR_PASSWORD" \
     -v app_password="$ORQIX_APP_PASSWORD" \
     -f /orqix-sql/orqix-init.sql
