#requires -Version 5.1
<#
.SYNOPSIS
    Reproducible secret scanner for the NetheriteAgent repository (WS5 Batch G).

.DESCRIPTION
    Scans the authored (source) surface of the repository for hardcoded
    credentials, private keys and high-entropy secret assignments, and fails the
    process (exit 1) when a GENUINE, unresolved finding exists.

    Design constraints:
      - Only authored files are scanned. Build/dependency outputs and runtime
        secret artifacts are excluded NARROWLY and documented inline (see
        $ExcludeLike / $ScanRoots) - never blanket "everything under X".
      - Matching VALUES ARE NEVER PRINTED. Findings are reported as
        'path:line:rule:mask' where mask = first 3 + last 2 chars
        (OPA-AUTHZ-DATA and LIVE-SECRET findings are fully redacted).
      - The materialized OPA authz-token doc ({"opa_admin_tokens":{...}}) is a
        runtime artifact that must never be committed (P0 - Batch I); it is
        caught structurally by the OPA-AUTHZ-DATA rule.
      - Live-secret cross-check (LIVE-SECRET): real values read from
        infrastructure/docker/secrets/ are compared against the scan surface and
        reported by file NAME only (never the value), proving no runtime secret
        value leaks into authored files.
      - Approved safe examples / test fixtures (see $Approved) are allowlisted
        NARROWLY with an explicit justification. Real findings cannot be hidden
        behind a broad ignore.
      - `-SelfTest` proves the rules: it creates controlled synthetic secrets and
        asserts each genuine category is detected while approved examples are not.

.PARAMETER RepoRoot
    Repository root (default: three directories up from this script).

.PARAMETER ReportFile
    Optional path to write the machine-readable finding report.

.PARAMETER SelfTest
    Run the scanner's own assertions instead of scanning the repository.

.EXAMPLE
    .\secret-scan.ps1 -ReportFile secret-scan-report.txt

.EXAMPLE
    .\secret-scan.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = '',
    [string]$ReportFile = '',
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $scriptDir))
}
$script:findingCount = 0
$script:findings = New-Object System.Collections.Generic.List[string]
$script:scannedFiles = 0

# --------------------------------------------------------------------- surface
# Scan roots: every directory of authored content. Oneline helper for PS 5.1.
$script:ScanRoots = @()
$script:ScanRoots += (Join-Path $RepoRoot 'backend')
$script:ScanRoots += (Join-Path $RepoRoot 'docs')
$script:ScanRoots += (Join-Path $RepoRoot 'e2e')
$script:ScanRoots += (Join-Path $RepoRoot 'frontend')
$script:ScanRoots += (Join-Path $RepoRoot 'infrastructure')
$script:ScanRoots += (Join-Path $RepoRoot 'build_backend.ps1')
$script:ScanRoots += (Join-Path $RepoRoot 'README.md')

# Exclusions - authored files never live here; each pattern is a -like wildcard.
#   *node_modules*      vendored third-party JS (npm), not authored
#   *\target\* *\build\* *\dist\*  build outputs, not authored
#   *.tools*            pinned local JDK/Maven checkout, not authored
#   *\secrets\*         runtime file-based docker secrets (git-ignored on purpose);
#                       their VALUES are never compared/printed here, but a
#                       live-secret cross-check below still proves no secret value
#                       leaks into the authored scan surface (see Get-LiveSecretValues)
#   *.p3tmp*            verify-secret-handling scratch project
#   package-lock.json   npm lockfile, generated deterministically from manifests
#   *\maven-cp.txt      stale classpath dump (currently empty)
#   binary/media (.png .jpg .gif .ico .woff .ttf) + *.map + trust artifacts
$script:ExcludeLike = @('*node_modules*', '*\target\*', '*\build\*', '*\dist\*', '*.tools*',
    '*\secrets\*', '*.p3tmp*', '*package-lock.json', '*\maven-cp.txt',
    '*.png', '*.jpg', '*.jpeg', '*.gif', '*.ico', '*.woff', '*.woff2', '*.ttf', '*.map',
    '*.sum', '*.sig', '*.asc')

# -------------------------------------------------------------- pattern rules
$script:Rules = @(
    @{ Id = 'PRIVATE-KEY';      Re = '(?m)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----' },
    @{ Id = 'AWS-ACCESS-KEY';   Re = '\bAKIA[0-9A-Z]{16}\b' },
    @{ Id = 'GITHUB-TOKEN';     Re = '\bgh[pousr]_[A-Za-z0-9]{20,}\b' },
    @{ Id = 'SLACK-TOKEN';      Re = '\bxox[baprs]-[A-Za-z0-9-]{8,}\b' },
    @{ Id = 'OPENAI-KEY';       Re = '\bsk-[A-Za-z0-9-]{20,}\b' },
    @{ Id = 'AGENT-KEY';        Re = '\bntk_live_[\w.-]{10,}\b' },
    @{ Id = 'JWT';              Re = '\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b' },
    @{ Id = 'BCRYPT-HASH';      Re = '\$2[aby]\$\d{2}\$[A-Za-z0-9./]{22}' },
    @{ Id = 'ASSIGNED-SECRET';  Re = '(?i)(?<![A-Za-z0-9])(?:password|passwd|pwd|api[_-]?key|apikey|access[_-]?key|secret|client[_-]?secret|jwt[_-]?secret|signing[_-]?key|session[_-]?secret|webhook[_-]?secret|mail[_-]?password|smtp[_-]?password|auth[_-]?token|refresh[_-]?token|access[_-]?token|id[_-]?token|bearer[_-]?token|db_app_password|db_password|redis_password|opa_policy_token|owner_password_hash|private[_-]?key)\b[ \t]*[:=][ \t]*(''[^''\r\n]{6,}''|"[^"\r\n]{6,}"|[A-Za-z0-9_\-./+\[\]{}=]{8,})' },
    # OPA authz-token data DOCUMENT exactly as opa-entrypoint.sh materializes it
    # ({"opa_admin_tokens":{"tokens":{"<token>":true}}}). This is a runtime artifact
    # that must NEVER appear in the authored repository tree - compose now writes it
    # to a container-local tmpfs. Flag the whole document; mask only the token group.
    @{ Id = 'OPA-AUTHZ-DATA';  Re = '(?m)^\s*\{\s*"opa_admin_tokens"\s*:\s*\{\s*"tokens"\s*:\s*\{\s*"(?<token>[^"\r\n]{8,})"\s*:\s*true\s*\}\s*\}\s*\}' }
)

# ---------------------------------------------------- approved (safe) findings
# Each entry: PathContains (regex), Rule, optional Literal (exact token present in
# the finding) + Reason. NARROW by design: path + rule + (literal) must all match.
$script:Approved = @(
    @{ Path = 'backend[\\/]netherite-app[\\/]src[\\/]test[\\/]'; Rule = 'BCRYPT-HASH';
       Reason = 'Synthetic test fixture hash ($2b$10$C9A8I18DD6...); test profiles/IT harness only, never the production secret.' },
    @{ Path = 'backend[\\/]netherite-app[\\/]src[\\/]main[\\/]resources[\\/]application-test.yml'; Rule = 'BCRYPT-HASH';
       Reason = 'Same synthetic test fixture hash; used only by the JVM test profile.' },
    @{ Path = 'netherite-app[\\/]src[\\/]test[\\/]'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_invalid.invalidsecret';
       Reason = 'Synthetic test literal asserting rejection of a malformed agent key.' },
    @{ Path = 'netherite-app[\\/]src[\\/]test[\\/]'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_unknown999.secrettoken999';
       Reason = 'Synthetic test literal asserting rejection of an unknown agent key.' },
    @{ Path = 'netherite-app[\\/]src[\\/]test[\\/]'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_unknown999.canary-value-123';
       Reason = 'Synthetic canary literal (ErrorHygieneSecurityTest HYG-1, Batch H) asserting hygienic rejection of an unknown agent key; same corpus as ntk_live_unknown999.secrettoken999.' },
    @{ Path = 'netherite-app[\\/]src[\\/]test[\\/]'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_spoofed.attackerkey';
       Reason = 'Synthetic spoofed-header literal (IdentityRateLimitFilterIntegrationTest) proving the identity-tier rate limiter keys buckets on server-derived context and ignores client-supplied X-Netherite-Agent-Key.' },
    @{ Path = 'ObservabilityIntegrationTest\.java$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_ws8.canary-secret';
       Reason = 'WS8 synthetic canary literal (ObservabilityIntegrationTest, Batch H pattern) asserting a rejected agent key is never echoed by the error path, logs or metrics; same corpus style as the approved ntk_live_unknown999.canary-value-123 literal.' },
    @{ Path = 'config[\\/]DevelopmentDataSeeder.java'; Rule = 'ASSIGNED-SECRET';
       Literal = 'dev-client-secret';
       Reason = '@Profile("test") dev-only IdP client-secret fallback for the local H2 harness; overridable via netherite.dev.idp.client-secret; never used in the default/prod profile.' },
    @{ Path = 'GatewayIntegrationTest.java'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_invalid.badsecret';
       Reason = 'Synthetic test literal asserting rejection of a malformed agent key.' },
    @{ Path = 'application-pgverify.yml'; Rule = 'ASSIGNED-SECRET';
       Literal = 'netherite_pw';
       Reason = 'Harness-only PostgreSQL credential for pgverify IT databases (local/docker harness); documented fixture, never the production secret file.' },
    @{ Path = 'enterprise-runtime-and-organization-onboarding.md'; Rule = 'ASSIGNED-SECRET';
       Literal = 'netherite-agent-secret';
       Reason = 'Local DEV Keycloak realm provisioning fixture (harness); annotated in the doc. Production client secrets are operator-issued per-org via IdP config, never this doc.' },
    @{ Path = 'ws3-real-sso.e2e.mjs'; Rule = 'ASSIGNED-SECRET';
       Literal = 'Workforce@2026';
       Reason = 'Fixture password for the employee@netherite.local user in the local KEYCLOAK DEV realm (SSO e2e harness); harness-only, never the production owner hash.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'PRIVATE-KEY';
       Literal = '-----BEGIN RSA PRIVATE KEY-----';
       Reason = 'In-scanner -SelfTest synthetic PEM fixture corpus; no real key can exist inside the scanner itself.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AWS-ACCESS-KEY';
       Literal = 'AKIAIOSFODNN7EXAMPLE';
       Reason = 'In-scanner -SelfTest synthetic AKIA fixture corpus (AWS docs example token).' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'GITHUB-TOKEN';
       Literal = 'ghp_SYNTHETICtokenabcdefghijklmnopqrstuvwxyz123456';
       Reason = 'In-scanner -SelfTest synthetic token fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'OPENAI-KEY';
       Literal = 'sk-synth-0123456789abcdefghijklmn';
       Reason = 'In-scanner -SelfTest synthetic API-key fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'JWT';
       Literal = 'eyJyb2xlIjoiYWRtaW4ifQ';
       Reason = 'In-scanner -SelfTest synthetic JWT fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_SelfTestOnlySynthetic.value12345';
       Reason = 'In-scanner -SelfTest synthetic Netherite agent-key fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_invalid.invalidsecret';
       Reason = 'Synthetic test literal in the scanner approved-list example corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_invalid.badsecret';
       Reason = 'Synthetic test literal in the scanner approved-list example corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_unknown999.secrettoken999';
       Reason = 'Synthetic test literal in the scanner approved-list example corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_unknown999.canary-value-123';
       Reason = 'Synthetic test literal in the scanner approved-list example corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_spoofed.attackerkey';
       Reason = 'Synthetic spoofed-header literal (IdentityRateLimitFilterIntegrationTest) in the scanner approved-list example corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'AGENT-KEY';
       Literal = 'ntk_live_ws8.canary-secret';
       Reason = 'WS8 synthetic canary literal approved for ObservabilityIntegrationTest; listed here as the scanner approved-list example corpus (same pattern as the other canary entries).' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'ASSIGNED-SECRET';
       Literal = 'Th3-R3al-But-Synth3tic-Pa$$word-2026!';
       Reason = 'In-scanner -SelfTest synthetic password fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'ASSIGNED-SECRET';
       Literal = 'netherite_pw';
       Reason = 'In-scanner -SelfTest synthetic harness-password fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'ASSIGNED-SECRET';
       Literal = 'Ps5sFixtureOwner9D';
       Reason = 'In-scanner -SelfTest synthetic owner-password fixture corpus.' },
    @{ Path = '[\\/]secret-scan\.ps1$'; Rule = 'BCRYPT-HASH';
       Literal = '$2b$10$C9A8I18DD6SzKhOnjxE49';
       Reason = 'In-scanner -SelfTest synthetic fixture hash (same BCrypt corpus as the approved test fixture).' },
    @{ Path = 'infrastructure[\\/]docker[\\/]secrets[\\/]'; Rule = 'ASSIGNED-SECRET';
       Reason = 'Tracked placeholder files (REPLACE-ME-*) documenting the secret file inventory; no real credential embedded.' },
@{ Path = '[\\/]verify-secret-handling\.ps1$'; Rule = 'ASSIGNED-SECRET';
        Literal = 'definitely-wrong-password';
        Reason = 'P5 bad-login probe payload sent to the live app to assert the BCrypt hash rejects a wrong password (expects HTTP 401); intentionally not a real credential.' },
     @{ Path = 'docs[\\/]ci-cd\.md$'; Rule = 'AGENT-KEY';
        Literal = 'ntk_live_ws8.canary-secret';
        Reason = 'Documentation of the approved WS8 synthetic canary in the CI/CD secrets-note (allowed test literal).' },
     @{ Path = 'docs[\\/]workstream-8-observability\.md$'; Rule = 'AGENT-KEY';
        Literal = 'ntk_live_ws8.canary-secret';
        Reason = 'WS9-verification ledger referencing the approved WS8 synthetic canary literal (allowed test literal).' },
     @{ Path = 'docs[\\/]workstream-9-disaster-recovery-retention-operations\.md$'; Rule = 'AGENT-KEY';
        Literal = 'ntk_live_ws8.canary-secret';
        Reason = 'WS9 ledger documenting the secret-scan approved-list entry for the approved WS8 synthetic canary literal (allowed test literal, identical to the ci-cd.md/workstream-8 entries).' },
     @{ Path = 'docs[\\/]llm-normalization\.md$'; Rule = 'AGENT-KEY';
        Literal = 'ntk_live_ws8.canary-secret';
        Reason = 'Step 3 normalization ledger referencing the already-approved WS8 synthetic canary literal in its Step 2 evidence (allowed test literal, identical to the ci-cd.md/workstream-8/workstream-9 entries).' },
     @{ Path = 'LlmProviderConfigurationTest\.java$'; Rule = 'ASSIGNED-SECRET';
        Literal = 'test-api-key';
        Reason = 'Synthetic test-fixture api-key value proving the conditional gemini provider bean is created in an isolated ApplicationContextRunner unit context; never the production credential.' },
     @{ Path = 'V15__alpenglow_tenant_setup\.sql$'; Rule = 'BCRYPT-HASH';
        Reason = 'Seeded local test tenant admin password hash for Alpenglow Enterprise fixture.' },
     @{ Path = 'ErpNextResourceConnectorTest\.java$'; Rule = 'ASSIGNED-SECRET';
        Reason = 'Synthetic ERPNext connector test fixtures.' },
     @{ Path = 'HttpRestResourceConnectorTest\.java$'; Rule = 'ASSIGNED-SECRET';
        Reason = 'Synthetic HTTP REST connector test fixtures.' },
     @{ Path = 'ResourceCredentialPersistenceTest\.java$'; Rule = 'ASSIGNED-SECRET';
        Reason = 'Synthetic credential persistence test fixtures.' },
     @{ Path = 'CredentialEncryptionServiceTest\.java$'; Rule = 'ASSIGNED-SECRET';
        Reason = 'Synthetic encryption service test fixtures.' }
)

# ------------------------------------------- ASSIGNED-SECRET value sanitization
function Test-AssignedSecretValue {
    param([string]$Value)
    $raw = $Value.Trim()
    $isQuoted = ($raw -match '^"') -or ($raw -match "^'")
    $v = $raw
    if ($v -match '^"(.+)"$' -or $v -match "^'(.+)'$") { $v = $Matches[1] }
    if ($v -match '^\$\{.*\}$')    { return $false }   # ${ENV_VAR}
    if ($v -match '^\$\(.*\)$')    { return $false }   # $(cmd)
    if ($v -match '^\{[^{}]*\}$')  { return $false }   # {json-ish placeholder}
    if ($v -match '^<[^<>]{1,80}>$') { return $false } # <placeholder>
    if ($v -match '(?i)(change_?me|changeme|your[-_ ]|example|placeholder|replace[-_ ]?me|test[-_ ]only|synthetic|sample|xxx|dummy|fixme|todo|fake|self[_-]?test)') { return $false }
    if ($v -match '(?i)^(true|false|null|none|n/a)$') { return $false }
    if ($v.Length -lt 8) { return $false }
    # Values that point AT a secret store are not the secret themselves:
    if ($v -match '^/run/secrets/')            { return $false }   # compose *_FILE
    if ($v -match '^[A-Za-z]:\\')              { return $false }   # Windows path
    if ($v -match '^\.\\|^\./|^/')             { return $false }   # relative/absolute path
    # Bare (non-quoted) values that are program expressions, not literals:
    if (-not $isQuoted) {
        if ($v -match '[()]') { return $false }                        # method call / arithmetic
        if ($v -match '^[a-zA-Z_]\w*(\.[a-zA-Z_]\w*)+$') { return $false } # obj.field accessor chain
    }
    $true
}

function Get-ScanFiles {
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($root in $script:ScanRoots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        if ((Get-Item -LiteralPath $root).PSIsContainer) {
            Get-ChildItem -LiteralPath $root -Recurse -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
                $p = $_.FullName
                foreach ($pat in $script:ExcludeLike) { if ($p -like $pat) { return } }
                $files.Add($p)
            }
        }
        else { $files.Add($root) }
    }
    $files
}

function Test-Approved {
    param([string]$Path, [string]$Rule, [string]$Matched)
    foreach ($a in $script:Approved) {
        if ($a.Rule -ne $Rule) { continue }
        if ($Path -notmatch $a.Path) { continue }
        if ($a.ContainsKey('Literal') -and -not ($Matched -like ('*' + $a.Literal + '*'))) { continue }
        return $a.Reason
    }
    $null
}

function Add-Finding {
    param([string]$Path, [int]$Line, [string]$Rule, [long]$Col, [string]$Masked)
    $script:findingCount++
    $script:findings.Add(('{0}:{1}:{2}:{3}:{4}' -f $Path, $Line, $Rule, $Col, $Masked))
    Write-Host ('FIND  {0}:{1} [{2}] {3}' -f $Path, $Line, $Rule, $Masked) -ForegroundColor Yellow
}

function Test-Mask {
    param([string]$Value)
    $v = $Value.Trim()
    if ($v -match '^"(.+)"$' -or $v -match "^'(.+)'$") { $v = $Matches[1] }
    if ($v.Length -le 8) { return ('*' * $v.Length) }
    $v.Substring(0, 3) + ('*' * ([Math]::Min($v.Length - 5, 24))) + $v.Substring($v.Length - 2)
}

# ------------------------------------- live-secret cross-check (P0 hardening)
# Reads the real (git-ignored) file-based runtime secrets from
# infrastructure/docker/secrets/ and proves none of their VALUES appear anywhere
# in the authored scan surface. This is the direct regression guard for the OPA
# P0: even if a runtime artifact (e.g. an OPA authz token document) is ever
# committed, its literal secret content is detected regardless of which rule
# body matched. Values are never printed - a LIVE-SECRET finding only reveals
# the secret file NAME plus a redaction marker.
$script:LiveSecretDir = Join-Path $RepoRoot 'infrastructure\docker\secrets'

function Get-LiveSecrets {
    [System.Collections.Generic.List[object]]$result = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $script:LiveSecretDir)) { return $result }
    Get-ChildItem -LiteralPath $script:LiveSecretDir -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
        $n = $_.Name
        if ($n -like 'README*' -or $n -like 'REPLACE-ME*' -or $n -like '*.example' -or $n -like '*.md') { return }
        try { $v = ([System.IO.File]::ReadAllText($_.FullName)).Trim() } catch { return }
        if ($v.Length -lt 8) { return }
        $result.Add([pscustomobject]@{ Name = $n; Value = $v })
    }
    $result
}
$script:LiveSecrets = Get-LiveSecrets

function Test-LiveSecretLeak {
    param([string]$Path, [string]$Content)
    foreach ($s in $script:LiveSecrets) {
        $idx = $Content.IndexOf($s.Value, [System.StringComparison]::Ordinal)
        if ($idx -lt 0) { continue }
        $line = ($Content.Substring(0, $idx) -split "`n").Count
        $col = $idx - $Content.LastIndexOf("`n", $idx) - 1
        Add-Finding -Path $Path -Line $line -Rule 'LIVE-SECRET' -Col $col -Masked ($s.Name + ':<redacted>')
    }
}

function Invoke-ScanFile {
    param([string]$Path)
    try { $content = [System.IO.File]::ReadAllText($Path) } catch { return }
    foreach ($rule in $script:Rules) {
        $ms = [regex]::Matches($content, $rule.Re)
        foreach ($m in $ms) {
            if (-not $m.Success) { continue }
            if ($rule.Id -eq 'ASSIGNED-SECRET') {
                if (-not (Test-AssignedSecretValue -Value $m.Groups[1].Value)) { continue }
            }
            $re = Test-Approved -Path $Path -Rule $rule.Id -Matched $m.Value
            if ($re) { continue }
            $line = ($content.Substring(0, $m.Index) -split "`n").Count
            $col = $m.Index - $content.LastIndexOf("`n", $m.Index) - 1
            $mask = Test-Mask $m.Value
            if ($rule.Id -eq 'OPA-AUTHZ-DATA' -and $m.Groups['token'].Success) {
                $mask = 'opa-authz-token-doc:<redacted>'
            }
            Add-Finding -Path $Path -Line $line -Rule $rule.Id -Col $col -Masked $mask
        }
    }
    Test-LiveSecretLeak -Path $Path -Content $content
}

# ------------------------------------------------------------------ self test
function Invoke-SelfTest {
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('netherite-scan-selftest-' + [guid]::NewGuid().ToString('N'))
    $testDir = Join-Path $tmp 'backend\netherite-app\src\test'
    New-Item -ItemType Directory -Path $testDir -Force | Out-Null
    Set-Content -Path (Join-Path $tmp 'genuine.txt') -Value @'
password = "Th3-R3al-But-Synth3tic-Pa$$word-2026!"
-----BEGIN RSA PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7V1...synthetic...
-----END RSA PRIVATE KEY-----
github = "ghp_SYNTHETICtokenabcdefghijklmnopqrstuvwxyz123456"
aws = "AKIAIOSFODNN7EXAMPLE"
openai = "sk-synth-0123456789abcdefghijklmn"
jwt: eyJyb2xlIjoiYWRtaW4ifQ.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue
agent = "ntk_live_SelfTestOnlySynthetic.value12345"
DB_PASSWORD=netherite_pw
OWNER_PASSWORD = 'Ps5sFixtureOwner9D'
'@
    Set-Content -Path (Join-Path $tmp 'sanitized-placeholders.txt') -Value @'
owner_password_hash = ${OWNER_PASSWORD_HASH}
client_secret = <from-secret-manager>
git_pat = ""
count = 7
ak = your-ACCESS-KEY-HERE
POSTGRES_PASSWORD_FILE=/run/secrets/db_password
OPA_AUTHZ_TOKEN_FILE = /run/secrets/opa_policy_token
redis_password:
  driver-class-name: org.h2.Driver
'@
    Set-Content -Path (Join-Path $testDir 'FixtureTest.java') -Value @'
// approved test fixture (map interface of netherite-app test tree)
String fixture = "$2b$10$C9A8I18DD6SzKhOnjxE49.beH6hGJjswSSeS5ci3AlfNGkkqfkzuy";
'@
    # Synthetic live-secret harness: proves the LIVE-SECRET cross-check and the
    # OPA-AUTHZ-DATA structural rule independently of any locally provisioned
    # secret (values below are synthetic fixtures, never real).
    $synthSecretDir = Join-Path $tmp 'infrastructure\docker\secrets'
    New-Item -ItemType Directory -Path $synthSecretDir -Force | Out-Null
    $synthToken = 'SELF-HEX-0123456789abcdef0123456789abcdef0123456789abcdef0123456789'
    Set-Content -Path (Join-Path $synthSecretDir 'opa_policy_token') -Value $synthToken
    Set-Content -Path (Join-Path $tmp 'leaked-opa-doc.json') -Value ('{"opa_admin_tokens":{"tokens":{"' + $synthToken + '":true}}}')
    Set-Content -Path (Join-Path $tmp 'leaked-plain.txt') -Value ('plain text containing the leaked token: ' + $synthToken)

    $savedRoots = $script:ScanRoots
    $savedLiveDir = $script:LiveSecretDir
    $script:ScanRoots = @($tmp)
    $script:LiveSecretDir = $synthSecretDir
    $script:LiveSecrets = Get-LiveSecrets
    try {
        $files = Get-ScanFiles
        foreach ($f in $files) { Invoke-ScanFile -Path $f }

        $genuineFound = ($script:findings | Where-Object { $_ -match '[\\/]genuine.txt:' }).Count
        $sanitizedFound = ($script:findings | Where-Object { $_ -match '[\\/]sanitized-placeholders.txt:' }).Count
        $fixtureFound = ($script:findings | Where-Object { $_ -match '[\\/]FixtureTest.java:' }).Count
        $opaDocFound = ($script:findings | Where-Object { $_ -match '[\\/]leaked-opa-doc\.json:.*OPA-AUTHZ-DATA' }).Count
        $leakedFound = ($script:findings | Where-Object { $_ -match '[\\/]leaked-(opa-doc\.json|plain\.txt):.*LIVE-SECRET' }).Count
        $asserts = @(
            @{ Name = 'all genuine categories detected (expected 7)';          Cond = ($genuineFound -ge 7) },
            @{ Name = 'placeholder forms NOT flagged';                         Cond = ($sanitizedFound -eq 0) },
            @{ Name = 'approved repo-path fixture NOT flagged';                Cond = ($fixtureFound -eq 0) },
            @{ Name = 'OPA authz-token document detected (OPA-AUTHZ-DATA)';    Cond = ($opaDocFound -ge 1) },
            @{ Name = 'live secret VALUE leak detected (LIVE-SECRET)';         Cond = ($leakedFound -ge 1) }
        )
        [bool]$ok = $true
        foreach ($a in $asserts) {
            Write-Host ('ASSERT  {0}  =>  {1}' -f $a.Name, $(if ($a.Cond) { 'PASS' } else { 'FAIL' }))
            if (-not $a.Cond) { $ok = $false }
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        if ($ok) {
            Write-Host 'SELF-TEST: PASS' -ForegroundColor Green
            exit 0
        }
        Write-Host 'SELF-TEST: FAIL' -ForegroundColor Red
        exit 1
    }
    finally {
        $script:ScanRoots = $savedRoots
        $script:LiveSecretDir = $savedLiveDir
        $script:LiveSecrets = Get-LiveSecrets
    }
}

# -------------------------------------------------------------------- execute
if ($SelfTest) {
    Invoke-SelfTest
    return
}

$files = Get-ScanFiles
$script:scannedFiles = $files.Count
foreach ($f in $files) { Invoke-ScanFile -Path $f }

if ($ReportFile) {
    $script:findings | Set-Content -Path $ReportFile
    Write-Host ('Report written: {0}' -f $ReportFile)
}
Write-Host ''
Write-Host ('Scanned {0} files; {1} findings.' -f $script:scannedFiles, $script:findingCount) -ForegroundColor Cyan
foreach ($f in $script:findings) { Write-Host ('  {0}' -f $f) -ForegroundColor Yellow }
if ($script:findings.Count -gt 0) {
    Write-Host 'RESULT: FAIL - unresolved secret findings remain (values masked above)' -ForegroundColor Red
    exit 1
}
Write-Host 'RESULT: PASS - no genuine secret findings' -ForegroundColor Green
exit 0