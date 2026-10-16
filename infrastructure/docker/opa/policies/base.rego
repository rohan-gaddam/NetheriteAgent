# package netherite.authz
# Base Rego v1 policy definition for NetheriteAgent.
#
# API-STABLE FAIL-CLOSED CONTRACT (must not change):
#   evaluation_output := {
#       "decision": "DENY" | "HUMAN_APPROVAL" | "ALLOW",
#       "denyReasons": [..],
#       "approvalReasons": [..],
#       "allowReasons": [..]
#   }
#   Precedence: DENY > HUMAN_APPROVAL > ALLOW
# The default decision is DENY; any ambiguity, malformed input, unknown
# organization, missing facts or unavailable policy data fails closed.
#
# Tenant isolation: this single module is the base of the authorization decision
# tree. Tenant-specific policy (pushed by the control plane for organization
# <org>) lives under `data.netherite.authz.tenants.<org>` and is READ by this
# base module — a tenant can only ADD restrictions (decision overrides, risk
# thresholds, allowed-resource allowlists) and can never weaken structural
# guards or the global high-risk gate below.

package netherite.authz

import rego.v1

# Selected organization (from the trusted action envelope, never client input).
# Normalized identically to the PolicyPushService pinning rule: dashed ids map
# to "org_" + id with dashes replaced by underscores, so org-trigger lookups
# always match the tenant module/data the control plane pushes.
organization_id := concat("", ["org_", replace(input.agent.organizationId, "-", "_")])

# Organization policy document, if one was pushed for this tenant.
org_doc(org) := data.netherite.authz.tenants[org] if {
    data.netherite.authz.tenants[org]
}

# Default fail-closed decision.
default decision := "DENY"

# Evaluates all candidate rule results.
# Precedence: DENY > HUMAN_APPROVAL > ALLOW
decision := "DENY" if {
    count(deny_reasons) > 0
} else := "HUMAN_APPROVAL" if {
    count(approval_reasons) > 0
} else := "ALLOW" if {
    count(allow_reasons) > 0
}

# ---------------------------------------------------------------------------
# Structural guards — malformed or incomplete input can never be allowed.
# These fire before any tenant or risk logic and cannot be overridden.
#
# Presence flags below use explicit defaults so a MISSING field still forces a
# guard to fire: `not is_string(input.x)` is undefined (never true) when the
# reference is undefined, whereas `not resource_present` over a default=false
# flag is always decidable.
# ---------------------------------------------------------------------------
default resource_present := false
resource_present if {
    is_string(input.action.resource)
    input.action.resource != ""
}

default agent_present := false
agent_present if {
    is_string(input.agent.id)
    input.agent.id != ""
}

default risk_score_present := false
risk_score_present if {
    is_number(input.context.riskScore)
}

deny_reasons contains "Action missing target resource" if {
    not resource_present
}

deny_reasons contains "Agent is not identified" if {
    not agent_present
}

deny_reasons contains "Risk score is unavailable; failing closed" if {
    not risk_score_present
}

# Agent trust-state guards emitted by the control-plane fact provider. These only
# fire when the provider explicitly produced the fact; when the fact is absent the
# rules are inert (preserves the base decision contract for consumers that do not
# supply agent state).
deny_reasons contains "Agent binding is not active" if {
    input.context.bindingActive == false
}

deny_reasons contains "Agent is not active" if {
    input.context.agentStatus == "SUSPENDED"
}

deny_reasons contains "Agent is not active" if {
    input.context.agentStatus == "REVOKED"
}

# ---------------------------------------------------------------------------
# Organization (tenant) policy join — strictest-first, additive-only.
# A tenant that pushed a disabled policy document fails closed for its org.
# ---------------------------------------------------------------------------
deny_reasons contains "Organization policy is not enabled; failing closed" if {
    d := org_doc(organization_id)
    object.get(d, "enabled", true) == false
}

# A tenant-declared allowed-resource allowlist restricts what may be targeted.
deny_reasons contains "Resource is not allowed for this organization" if {
    d := org_doc(organization_id)
    allowed := object.get(d, "allowedResources", null)
    is_array(allowed)
    count(allowed) > 0
    is_string(input.action.resource)
    not in_list(input.action.resource, allowed)
}

# An org-riding DENY contributes to the deny set (never weaker than the base).
deny_reasons contains "Organization policy denies the action" if {
    d := org_doc(organization_id)
    d.decision == "DENY"
}

# ---------------------------------------------------------------------------
# Human-approval gates (high risk of any kind requires human approval).
# ---------------------------------------------------------------------------
# Global gate: any risk score at or above 50 requires human approval.
approval_reasons contains "High risk action requires human approval" if {
    risk_score_present
    input.context.riskScore >= 50
    resource_present
    agent_present
    not count(deny_reasons) > 0
}

# Organization threshold override: a tenant may demand approval for risk scores
# below the global 50 (e.g. riskThreshold=30). It can never lower the global bar.
approval_reasons contains "Organization risk threshold requires human approval" if {
    d := org_doc(organization_id)
    threshold := object.get(d, "riskThreshold", null)
    is_number(threshold)
    threshold > 0
    risk_score_present
    input.context.riskScore >= threshold
    resource_present
    agent_present
    not count(deny_reasons) > 0
}

# An org-riding HUMAN_APPROVAL contributes to the approval set.
approval_reasons contains "Organization policy requires human approval" if {
    d := org_doc(organization_id)
    d.decision == "HUMAN_APPROVAL"
    not count(deny_reasons) > 0
}

# ---------------------------------------------------------------------------
# Allow gates.
# ---------------------------------------------------------------------------
allow_reasons contains "Standard allow for verified low-risk action" if {
    resource_present
    agent_present
    risk_score_present
    input.context.riskScore < 50
    not count(deny_reasons) > 0
    not count(approval_reasons) > 0
}

# An org-riding ALLOW only adds to the allow set; the DENY > HUMAN_APPROVAL
# precedence above keeps it from ever weakening a guard or a risk gate.
allow_reasons contains "Organization policy allows the action" if {
    d := org_doc(organization_id)
    d.decision == "ALLOW"
    not count(deny_reasons) > 0
    not count(approval_reasons) > 0
}

# Final output payload.
evaluation_output := {
    "decision": decision,
    "denyReasons": deny_reasons,
    "approvalReasons": approval_reasons,
    "allowReasons": allow_reasons
}

# Bound membership helper (callable from inside `not`; the anonymous `_`
# alternative is unsafe in a negation). `some v in arr` binds each array element.
in_list(x, arr) if {
    some v in arr
    v == x
}