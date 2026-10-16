#!/bin/sh
set -eu

# NetheriteAgent container entrypoint.
#
# Secrets arrive as file-based docker secrets (Compose `secrets:` mounted under
# /run/secrets). This script copies each required secret into its environment
# variable, then execs the application. It NEVER echoes a secret value to any
# stream; only the secret NAME and PATH are reported on failure.
#
# Why a file -> env copy instead of expecting Compose to interpolate secrets
# into `environment:`? Compose performs variable interpolation on the .env/src
# project file and would re-interpret the '$' characters inside a BCrypt hash
# ($2b$10$...) or other values. A file is taken verbatim, and the POSIX
# `export VAR="$value"` copy does not re-expand '$' inside the assignment
# result, so BCrypt hashes arrive byte-for-byte identical.

SECRETS_DIR="${SECRETS_DIR:-/run/secrets}"
# Which compose secret supplies the DB password. The runtime app must use the
# least-privileged netherite_app credential (DB_SECRET_NAME default db_app_password);
# the migrate/maint service overrides it with db_password (netherite_admin).
DB_SECRET_NAME="${DB_SECRET_NAME:-db_app_password}"

require_secret() {
    name="$1"
    var="$2"
    path="${SECRETS_DIR}/${name}"
    if [ ! -r "$path" ]; then
        echo "FATAL: required secret '$name' missing or not readable at '$path'." >&2
        echo "FATAL: mount the compose file secret at $path before starting (see infrastructure/docker)." >&2
        exit 1
    fi
    value="$(cat "$path" 2>/dev/null)"
    if [ -z "$value" ]; then
        echo "FATAL: required secret '$name' ('$path') is empty." >&2
        exit 1
    fi
    export "$var=$value"
}

require_secret "$DB_SECRET_NAME" DB_PASSWORD
if [ "${LLM_PROVIDER:-none}" = "gemini" ]; then
    require_secret gemini_api_key GEMINI_API_KEY
fi
if [ "${LLM_PROVIDER:-none}" = "openrouter" ]; then
    require_secret openrouter_api_key OPENROUTER_API_KEY
fi
require_secret redis_password REDIS_PASSWORD
require_secret owner_password_hash OWNER_PASSWORD_HASH
require_secret opa_policy_token OPA_TOKEN

exec java -XX:MaxRAMPercentage=70 -jar /opt/netherite/app.jar "$@"