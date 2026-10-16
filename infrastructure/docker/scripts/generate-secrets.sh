#!/usr/bin/env bash
# Provision the file-based docker compose secrets (POSIX / CI variant).
# See generate-secrets.ps1 for the Windows path and full documentation.
#
# Password input: OWNER_PASSWORD environment variable (never echoed).
# Note: the plaintext owner password never touches disk or any output stream; only
# its BCrypt hash is written to owner_password_hash.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SECRETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/secrets"
APP_JAR="${APP_JAR:-$ROOT/backend/netherite-app/target/netherite-app-0.1.0-SNAPSHOT.jar}"

if [ -z "${OWNER_PASSWORD:-}" ]; then
    echo "Set OWNER_PASSWORD in the environment before running this script." >&2
    exit 2
fi
[ -f "$APP_JAR" ] || { echo "app jar missing - build first: mvn -pl netherite-app -am -DskipTests package" >&2; exit 2; }

mkdir -p "$SECRETS_DIR"
umask 0177
DB_PASSWORD="$(head -c 32 /dev/urandom | xxd -p -c 64)"
DB_APP_PASSWORD="$(head -c 32 /dev/urandom | xxd -p -c 64)"
REDIS_PASSWORD="$(head -c 32 /dev/urandom | xxd -p -c 64)"
OPA_POLICY_TOKEN="$(head -c 32 /dev/urandom | xxd -p -c 64)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -j "$APP_JAR" 'BOOT-INF/lib/spring-security-crypto-*.jar'   -d "$TMP" >/dev/null
unzip -j "$APP_JAR" 'BOOT-INF/lib/spring-jcl-*.jar'              -d "$TMP" >/dev/null
CRYPTO="$(ls "$TMP"/spring-security-crypto-*.jar)"
JCL="$(ls "$TMP"/spring-jcl-*.jar)"
OWNER_HASH="$(java -cp "$CRYPTO:$JCL" "$ROOT/infrastructure/docker/scripts/BcryptTool.java")"

printf '%s' "$DB_PASSWORD"       > "$SECRETS_DIR/db_password"
printf '%s' "$DB_APP_PASSWORD"   > "$SECRETS_DIR/db_app_password"
printf '%s' "$REDIS_PASSWORD"    > "$SECRETS_DIR/redis_password"
printf '%s' "$OPA_POLICY_TOKEN"  > "$SECRETS_DIR/opa_policy_token"
printf '%s' "$OWNER_HASH"        > "$SECRETS_DIR/owner_password_hash"
# gemini_api_key is declared by compose (so `up` resolves the source file) but
# only read by the app when LLM_PROVIDER=gemini. Empty is the correct default.
printf '%s' ''                    > "$SECRETS_DIR/gemini_api_key"
# openrouter_api_key likewise: declared by compose, read only when
# LLM_PROVIDER=openrouter. Empty is the correct default.
printf '%s' ''                    > "$SECRETS_DIR/openrouter_api_key"

echo "Secrets provisioned (no trailing newline, no BOM, 0600):"
echo "  $SECRETS_DIR/db_password          (${#DB_PASSWORD} hex chars, netherite_admin / migrate)"
echo "  $SECRETS_DIR/db_app_password      (${#DB_APP_PASSWORD} hex chars, netherite_app / runtime app)"
echo "  $SECRETS_DIR/redis_password       (${#REDIS_PASSWORD} hex chars)"
echo "  $SECRETS_DIR/opa_policy_token     (${#OPA_POLICY_TOKEN} hex chars, OPA bearer authorization)"
echo "  $SECRETS_DIR/owner_password_hash  (BCrypt \$10)"
echo "  $SECRETS_DIR/gemini_api_key       (EMPTY - add a real key only for LLM_PROVIDER=gemini)"
echo "  $SECRETS_DIR/openrouter_api_key    (EMPTY - add a real key only for LLM_PROVIDER=openrouter)"
echo "Next: docker compose run --rm migrate   then   docker compose up -d --scale app=2"
