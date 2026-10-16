#!/bin/sh
set -eu

# NetheriteAgent PostgreSQL bootstrap: provision the least-privileged runtime
# DB role `netherite_app` (LOGIN, NOSUPERUSER). The application connects as this
# role only; DDL/triggers/grants run as netherite_admin via the one-shot migrate
# service. init.sql and this script run as the bootstrap superuser
# (POSTGRES_USER/POSTGRES_PASSWORD_FILE from compose).
#
# The app password arrives as the compose secret db_app_password (mounted at
# /run/secrets/db_app_password). It is generated as 64 hex chars (no quotes), so
# the plain SQL interpolation below is safe; never paste a hand-typed password
# with quotes/semicolons here.

APP_DB_PASSWORD_FILE=/run/secrets/db_app_password

if [ ! -r "$APP_DB_PASSWORD_FILE" ]; then
    echo "FATAL: required secret 'db_app_password' missing or not readable at '$APP_DB_PASSWORD_FILE'." >&2
    echo "FATAL: run .\\scripts\\generate-secrets.ps1 and mount ./secrets/db_app_password into the postgres service." >&2
    exit 1
fi

APP_PASSWORD="$(cat "$APP_DB_PASSWORD_FILE")"

if [ -z "$APP_PASSWORD" ]; then
    echo "FATAL: secret 'db_app_password' is empty." >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-netherite_admin}" --dbname "${POSTGRES_DB:-netherite}" <<EOSQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'netherite_app') THEN
        CREATE ROLE netherite_app LOGIN PASSWORD '${APP_PASSWORD}';
        RAISE NOTICE 'Created least-privilege role netherite_app';
    ELSE
        ALTER ROLE netherite_app LOGIN PASSWORD '${APP_PASSWORD}';
        RAISE NOTICE 'Updated least-privilege role netherite_app';
    END IF;
END
\$\$;
EOSQL