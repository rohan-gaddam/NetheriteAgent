# package system.authz
# OPA HTTP API authorization policy (enforced when the daemon runs with
# --authentication=token --authorization=basic).
#
# `input.identity` is the bearer token extracted by OPA from the
# `Authorization: Bearer <token>` header. Requests are allowed only when the
# token is a member of the operator-bootstrapped token set, loaded at container
# start from the mounted docker secret (see opa-entrypoint.sh). A secondary set
# is reserved for data-driven rotation by the control plane.
#
# Poor man's but correct: default DENY — missing header, unknown token,
# unreachable token data and malformed state all fail closed with 401.

package system.authz

import rego.v1

default allow := false

allow if {
    data.opa_admin_tokens.tokens[input.identity]
}

allow if {
    data.opa_admin_tokens.secondary[input.identity]
}