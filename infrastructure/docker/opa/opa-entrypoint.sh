#!/bin/sh
set -eu

# NetheriteAgent OPA container entrypoint.
#
# OPA runs with --authentication=token --authorization=basic. Its authorization
# decision (package system.authz, loaded from /policies/system/authz.rego) only
# admits bearer tokens that appear in data.opa_admin_tokens.tokens. That token
# set is materialized here from the mounted docker secret (compose secret
# `opa_policy_token`) into a JSON data document, so the secret never reaches
# OPA's command line, image, logs or compiled config - only a file read at boot.
#
# The data payload is intentionally object-keyed ({ "<token>": true }): Rego
# array lookups by string constant (tokens["x"]) are undefined, while object
# membership is the documented pattern. JSON documents loaded from a directory
# merge their TOP-LEVEL keys into the data root, so the payload itself must carry
# the "opa_admin_tokens" namespace.

OPA_TOKEN_FILE="${OPA_TOKEN_FILE:-/run/secrets/opa_policy_token}"
OPA_TOKEN_DIR="${OPA_TOKEN_DIR:-/policies-data}"

if [ ! -r "$OPA_TOKEN_FILE" ]; then
    echo "FATAL: OPA token secret missing or not readable at '$OPA_TOKEN_FILE'." >&2
    exit 1
fi

token="$(cat "$OPA_TOKEN_FILE" 2>/dev/null)"
if [ -z "$token" ]; then
    echo "FATAL: OPA token secret at '$OPA_TOKEN_FILE' is empty." >&2
    exit 1
fi

mkdir -p "$OPA_TOKEN_DIR"
printf '{"opa_admin_tokens":{"tokens":{"%s":true}}}' "$token" > "$OPA_TOKEN_DIR/authz_tokens.json"

exec opa run \
    --server \
    --addr=0.0.0.0:8181 \
    --log-level=info \
    --authentication=token \
    --authorization=basic \
    /policies \
    "$OPA_TOKEN_DIR" \
    "$@"