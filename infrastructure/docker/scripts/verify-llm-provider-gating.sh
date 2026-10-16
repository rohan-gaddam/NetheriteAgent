#!/usr/bin/env bash
# Verify LLM provider / Gemini-key gating in the app entrypoint, WITHOUT a live
# Docker engine and WITHOUT any real credential.
#
# Three deployment configurations are covered (the CI/CD contract):
#   1. LLM_PROVIDER=none (default)   -> boots WITHOUT a real provider key. This
#      is checked in two forms: gemini_api_key source file absent, and empty.
#   2. LLM_PROVIDER=gemini           -> REQUIRES a real, non-empty Gemini key.
#      Missing or empty gemini_api_key must fail closed (entrypoint FATAL) and
#      never reach the JVM; a present key must be exported as GEMINI_API_KEY.
#   3. LLM_PROVIDER=openrouter       -> REQUIRES a real, non-empty OpenRouter key.
#      Missing or empty openrouter_api_key must fail closed (entrypoint FATAL)
#      and never reach the JVM; a present key must be exported as
#      OPENROUTER_API_KEY.
#
# The entrypoint normally `exec java ...`; here a stub `java` records the
# environment the JVM would see and exits 0. The compose secrets themselves are
# NOT created by this test - it exercises docker-entrypoint.sh directly with a
# throwaway SECRETS_DIR (only the non-Gemini secrets are synthetically written).
#
#   PASS:   prints "PASS: ..." per scenario and exits 0
#   FAILURE: prints a diagnostic and exits non-zero
#
# Requires a POSIX-ish `sh` (Git Bash on Windows, /bin/sh or busybox on Linux).

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENTRYPOINT="$ROOT/backend/netherite-app/docker-entrypoint.sh"
STUB_OUT=""
FAILED=0

if [ ! -f "$ENTRYPOINT" ]; then
  echo "FAIL: entrypoint not found at $ENTRYPOINT" >&2
  exit 2
fi

# ------------------------------------------------------------------ helpers --
# Prepare a throwaway secrets dir + stub java. $1 = result dir.
setup() {
  local base="$1"
  mkdir -p "$base/secrets" "$base/bin"
  STUB_OUT="$base/java.env"
  printf '%s\n' '#!/bin/sh' "env | grep -E '^GEMINI_API_KEY=|^OPENROUTER_API_KEY=|^LLM_PROVIDER=' | sort > '$STUB_OUT'" 'exit 0' > "$base/bin/java"
  chmod +x "$base/bin/java"
  # The four secrets the entrypoint always requires (must be non-empty).
  printf '%s' 'n3v3r-us3d-db-app-pw-0000000000000000000000000000000000000000000000' > "$base/secrets/db_app_password"
  printf '%s' 'n3v3r-us3d-redis-pw-0000000000000000000000000000000000000000000000' > "$base/secrets/redis_password"
  printf '%s' 'n3v3r-us3d-owner-hash-\$2b\$10\$abcdefghijklmnopqrstuvwxyzABCDEFGHIJ'  > "$base/secrets/owner_password_hash"
  printf '%s' 'n3v3r-us3d-opa-token-00000000000000000000000000000000000000000000000' > "$base/secrets/opa_policy_token"
}

# Run the entrypoint and return its exit code.
run_entrypoint() {
  ( cd "$base" && SECRETS_DIR="$base/secrets" PATH="$base/bin:$PATH" LLM_PROVIDER="$1" sh "$ENTRYPOINT" >/dev/null 2>"$base/entry.stderr" )
  echo $?
}

check_no_gemini_in_env() {
  if [ -s "$STUB_OUT" ] && grep -q '^GEMINI_API_KEY=' "$STUB_OUT"; then
    echo "FAIL: GEMINI_API_KEY was exported to the JVM but should not have been ($scenario)" >&2
    cat "$STUB_OUT" >&2
    return 1
  fi
  return 0
}

check_no_openrouter_in_env() {
  if [ -s "$STUB_OUT" ] && grep -q '^OPENROUTER_API_KEY=' "$STUB_OUT"; then
    echo "FAIL: OPENROUTER_API_KEY was exported to the JVM but should not have been ($scenario)" >&2
    cat "$STUB_OUT" >&2
    return 1
  fi
  return 0
}

# --------------------------------------------------------- prereq + setup ---
base="$(mktemp -d)"
trap 'rm -rf "$base"' EXIT
setup "$base"

# ----------------------------------------------------- none + file absent ----
rm -f "$base/secrets/gemini_api_key"
scenario="LLM_PROVIDER=none, gemini_api_key absent"
code="$(run_entrypoint none)"
if [ "$code" -ne 0 ] || ! check_no_gemini_in_env || ! check_no_openrouter_in_env; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want 0)" >&2
  cat "$base/entry.stderr" >&2
else
  echo "PASS: $scenario reaches the JVM without a Gemini key"
fi

# ------------------------------------------------------ none + empty file ----
: > "$base/secrets/gemini_api_key"
scenario="LLM_PROVIDER=none, gemini_api_key empty"
code="$(run_entrypoint none)"
if [ "$code" -ne 0 ] || ! check_no_gemini_in_env || ! check_no_openrouter_in_env; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want 0)" >&2
  cat "$base/entry.stderr" >&2
else
  echo "PASS: $scenario reaches the JVM with an empty Gemini key file"
fi

# ---------------------------------------------------- gemini + file absent ---
rm -f "$base/secrets/gemini_api_key"
scenario="LLM_PROVIDER=gemini, gemini_api_key absent"
code="$(run_entrypoint gemini)"
if [ "$code" -eq 0 ]; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want non-zero, fail closed)" >&2
  cat "$base/entry.stderr" >&2
elif ! grep -q "required secret 'gemini_api_key' missing" "$base/entry.stderr"; then
  FAILED=1
  echo "FAIL ($scenario): expected FATAL for missing gemini_api_key" >&2
  cat "$base/entry.stderr" >&2
else
  echo "PASS: $scenario fails closed (FATAL missing secret)"
fi

# ---------------------------------------------------- gemini + empty file ----
: > "$base/secrets/gemini_api_key"
scenario="LLM_PROVIDER=gemini, gemini_api_key empty"
code="$(run_entrypoint gemini)"
if [ "$code" -eq 0 ]; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want non-zero, fail closed)" >&2
  cat "$base/entry.stderr" >&2
elif ! grep -q "required secret 'gemini_api_key'" "$base/entry.stderr" || ! grep -qi "is empty" "$base/entry.stderr"; then
  FAILED=1
  echo "FAIL ($scenario): expected FATAL for empty gemini_api_key" >&2
  cat "$base/entry.stderr" >&2
else
  echo "PASS: $scenario fails closed (FATAL empty secret)"
fi

# ------------------------------------------------- gemini + real key file ----
printf '%s' 'synthetic-test-gemini-key-0123456789-abcdefghijklmnopqrstuvwxyz' > "$base/secrets/gemini_api_key"
scenario="LLM_PROVIDER=gemini, gemini_api_key present"
code="$(run_entrypoint gemini)"
want="GEMINI_API_KEY=synthetic-test-gemini-key-0123456789-abcdefghijklmnopqrstuvwxyz"
if [ "$code" -ne 0 ]; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want 0)" >&2
  cat "$base/entry.stderr" >&2
elif [ ! -f "$STUB_OUT" ] || ! grep -Fq "$want" "$STUB_OUT"; then
  FAILED=1
  echo "FAIL ($scenario): GEMINI_API_KEY not exported to JVM intact" >&2
  cat "$STUB_OUT" >&2
elif ! check_no_openrouter_in_env; then
  FAILED=1
else
  echo "PASS: $scenario exports GEMINI_API_KEY to the JVM (and no OpenRouter key)"
fi

# --------------------------------------------- openrouter + file absent ----
rm -f "$base/secrets/openrouter_api_key"
scenario="LLM_PROVIDER=openrouter, openrouter_api_key absent"
code="$(run_entrypoint openrouter)"
if [ "$code" -eq 0 ]; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want non-zero, fail closed)" >&2
  cat "$base/entry.stderr" >&2
elif ! grep -q "required secret 'openrouter_api_key' missing" "$base/entry.stderr"; then
  FAILED=1
  echo "FAIL ($scenario): expected FATAL for missing openrouter_api_key" >&2
  cat "$base/entry.stderr" >&2
else
  echo "PASS: $scenario fails closed (FATAL missing secret)"
fi

# ---------------------------------------------- openrouter + empty file ----
: > "$base/secrets/openrouter_api_key"
scenario="LLM_PROVIDER=openrouter, openrouter_api_key empty"
code="$(run_entrypoint openrouter)"
if [ "$code" -eq 0 ]; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want non-zero, fail closed)" >&2
  cat "$base/entry.stderr" >&2
elif ! grep -q "required secret 'openrouter_api_key'" "$base/entry.stderr" || ! grep -qi "is empty" "$base/entry.stderr"; then
  FAILED=1
  echo "FAIL ($scenario): expected FATAL for empty openrouter_api_key" >&2
  cat "$base/entry.stderr" >&2
else
  echo "PASS: $scenario fails closed (FATAL empty secret)"
fi

# ------------------------------------------- openrouter + real key file ----
printf '%s' 'synthetic-test-openrouter-key-0123456789-abcdefghijklmnopqrstuvwxyz' > "$base/secrets/openrouter_api_key"
scenario="LLM_PROVIDER=openrouter, openrouter_api_key present"
code="$(run_entrypoint openrouter)"
want="OPENROUTER_API_KEY=synthetic-test-openrouter-key-0123456789-abcdefghijklmnopqrstuvwxyz"
if [ "$code" -ne 0 ]; then
  FAILED=1
  echo "FAIL ($scenario): entrypoint exit=$code (want 0)" >&2
  cat "$base/entry.stderr" >&2
elif [ ! -f "$STUB_OUT" ] || ! grep -Fq "$want" "$STUB_OUT"; then
  FAILED=1
  echo "FAIL ($scenario): OPENROUTER_API_KEY not exported to JVM intact" >&2
  cat "$STUB_OUT" >&2
elif ! check_no_gemini_in_env; then
  FAILED=1
else
  echo "PASS: $scenario exports OPENROUTER_API_KEY to the JVM (and no Gemini key)"
fi

# ---------------------------------------------------------------- summary ----
if [ "$FAILED" -ne 0 ]; then
  echo "LLM PROVIDER GATING CHECK FAILED" >&2
  exit 1
fi
echo "LLM PROVIDER GATING CHECK PASSED (none without key / gemini + openrouter fail-closed)"
exit 0