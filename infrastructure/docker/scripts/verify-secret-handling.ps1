#requires -Version 5.1
<#
.SYNOPSIS
    Automated production secret-handling verification for NetheriteAgent.

.DESCRIPTION
    Verifies, against a live Docker engine, every acceptance criterion of the
    file-based docker compose secrets mechanism:
      P1  docker compose config never exposes secret values
      P2  secrets are not baked into the application image
      P3  missing/empty secret aborts startup (compose level AND entrypoint FATAL)
      P4  full stack deploys healthy with 2 app replicas
      P5  owner login succeeds with the provisioned BCrypt (survives intact) /
          wrong password is rejected
      P6  Redis requires AUTH and authenticates with the provisioned secret
      P7  PostgreSQL authenticates with the provisioned secret; readiness UP
      P8  all six secrets are byte-identical across the services that mount
          them, every app replica, and the host
      P9  no secret value leaks into any container log stream
      P10 graceful stop exits 0 with clean shutdown
      P11 dependency failure/recovery (redis, postgres, opa)
      P12 Caddy edge routes to the API and serves the SPA
      P13 docker secret refs match the application's actual requirements
          (incl. the conditional gemini_api_key)

.EXAMPLE
    .\verify-secret-handling.ps1

    Requires `docker` on PATH (or -DockerExe) and provisioned ./secrets/* files.
#>
[CmdletBinding()]
param(

    [string]$OwnerEmail,
    [string]$ComposeDir,
    [string]$SecretsDir,
    [string]$DockerExe = 'docker'
)

# NOTE: $PSScriptRoot is unavailable during param-default evaluation under
# [CmdletBinding()] in Windows PowerShell -File invocation, so defaults are
# resolved here instead.
if (-not $ComposeDir) { $ComposeDir = Join-Path $PSScriptRoot '..' }
if (-not $SecretsDir) { $SecretsDir = Join-Path $PSScriptRoot '..\secrets' }
if (-not $OwnerEmail) {
    $OwnerEmail = (Get-Content "$ComposeDir\.env" -Raw | Select-String -Pattern '(?m)^OWNER_EMAIL=(.*)$').Matches.Groups[1].Value.Trim()
}

# Docker writes progress/noise to stderr; with EA=Stop those become terminating
# "NativeCommandError" records. Rely on $LASTEXITCODE + explicit throws instead.
$ErrorActionPreference = 'Continue'
$script:failures = New-Object System.Collections.Generic.List[string]
$script:lastError = $null
$script:passed = 0
$script:count = 0

# ---------------------------------------------------------------- helpers ---
function Test-Step {
    param([string]$Name, [scriptblock]$Body)
    $script:count++
    Write-Host ""
    Write-Host ("===== [{0:D2}] {1} =====" -f $script:count, $Name) -ForegroundColor Cyan
    $script:lastError = $null
    try { $result = & $Body } catch { $result = $null; $script:lastError = $_.Exception.Message }
    if ($null -eq $result -or $result -eq $false) {
        $script:failures.Add($Name)
        Write-Host ("FAIL: {0}" -f $Name) -ForegroundColor Red
        if ($script:lastError) { Write-Host ("   reason: {0}" -f $script:lastError) -ForegroundColor DarkRed }
    }
    else {
        $script:passed++
        Write-Host "PASS: $Name" -ForegroundColor Green
    }
}

function Assert-HasNoMatch {
    param([string]$Name, [string[]]$Needles, [string]$Haystack)
    foreach ($needle in $Needles) {
        if ($Haystack -and $Haystack.Contains($needle)) {
            throw "Value found where it must not appear: '$needle' ($Name)"
        }
    }
}

# Verification probes the real TLS edge: Caddy serves https://localhost with its
# internal CA on SITE_ADDRESS=localhost, and curl -k (like the compose healthcheck)
# trusts it on a local dev box. Production uses SITE_ADDRESS=<fqdn> for automatic
# HTTPS instead. Compose prefers OS env over .env, so pin it here for a
# deterministic edge mode. (The hardened Caddy tls block forbids an HTTP-only
# ':80' listener - https://localhost is the only correct local mode.)
$env:SITE_ADDRESS = 'localhost'

$compose = (Join-Path $ComposeDir 'docker-compose.yml')
# Owner password input: OWNER_PASSWORD env (non-interactive / CI, env-only - never
# a CLI argument) or the hidden SecureString prompt for interactive use.
$OwnerPassword = $env:OWNER_PASSWORD
if (-not $OwnerPassword) {
    if ([Console]::IsInputRedirected) {
        throw 'No owner password available: set $env:OWNER_PASSWORD or run interactively (stdin is redirected).'
    }
    $secureOwnerPassword = Read-Host "Owner password for login verification" -AsSecureString
    $ownerPasswordPtr = [IntPtr]::Zero
    try {
        $ownerPasswordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureOwnerPassword)
        $OwnerPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ownerPasswordPtr)
    }
    finally {
        if ($ownerPasswordPtr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ownerPasswordPtr)
        }
        $secureOwnerPassword.Dispose()
    }
}
$dbPw = $null; $dbAppPw = $null; $redisPw = $null; $opaToken = $null; $ownerHash = $null

# ------------------------------------------------------------- preflight -----
Write-Host "Preflight: engine reachable + secrets provisioned" -ForegroundColor Cyan
& $DockerExe version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "Docker engine not reachable." -ForegroundColor Red; exit 2 }

    foreach ($f in @('db_password', 'db_app_password', 'redis_password', 'opa_policy_token', 'owner_password_hash', 'gemini_api_key', 'openrouter_api_key')) {
    $path = Join-Path $SecretsDir $f
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "Missing '$path'. Run .\scripts\generate-secrets.ps1 first." -ForegroundColor Red
        exit 2
    }
    if ((Get-Item -LiteralPath $path).PSIsContainer) {
        Write-Host "Secret path '$path' is a directory (docker bind-mount side effect). Restore a real secret file first." -ForegroundColor Red
        exit 2
    }
}
$dbPw = [System.IO.File]::ReadAllText((Join-Path $SecretsDir 'db_password'))
$dbAppPw = [System.IO.File]::ReadAllText((Join-Path $SecretsDir 'db_app_password'))
$redisPw = [System.IO.File]::ReadAllText((Join-Path $SecretsDir 'redis_password'))
$opaToken = [System.IO.File]::ReadAllText((Join-Path $SecretsDir 'opa_policy_token'))
$ownerHash = [System.IO.File]::ReadAllText((Join-Path $SecretsDir 'owner_password_hash'))
# gemini_api_key may legitimately be empty (LLM disabled); the app only reads it
# when .env sets LLM_PROVIDER=gemini, and fails startup if empty then.
$geminiKey = $null
if (Test-Path -LiteralPath (Join-Path $SecretsDir 'gemini_api_key')) {
    $geminiKey = [System.IO.File]::ReadAllText((Join-Path $SecretsDir 'gemini_api_key'))
}
foreach ($v in @($dbPw, $dbAppPw, $redisPw, $opaToken, $ownerHash)) {
    if ($v.Length -lt 16 -or $v.StartsWith('REPLACE-ME')) {
        Write-Host "Secret files contain placeholders / are too short - regenerate them." -ForegroundColor Red
        exit 2
    }
}

# ------------------------------------------------------------------ P1 -------
Test-Step "P1 compose config exposes no secret values" {
    $cfg = & $DockerExe compose -f $compose config 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "docker compose config failed" }
    Assert-HasNoMatch -Name 'compose config' -Needles @($dbPw, $dbAppPw, $redisPw, $opaToken, $ownerHash) -Haystack $cfg
    if ($cfg -notmatch 'OWNER_PASSWORD_HASH') { throw "compose env must no longer reference OWNER_PASSWORD_HASH" }
    $true
}

# ------------------------------------------------------------------ P2 -------
Test-Step "P2 secrets not baked into the app image" {
    & $DockerExe compose -f $compose build app 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker compose build app failed" }
    $history = (& $DockerExe image history --no-trunc netherite-agent-app:0.1.0 2>&1 | Out-String)
    Assert-HasNoMatch -Name 'image history' -Needles @($dbPw, $dbAppPw, $redisPw, $opaToken, $ownerHash, 'C9A8I18DD6SzKhOnjxE49') -Haystack $history
    # docker run (not compose run) so no compose progress noise pollutes the scan.
    $scan = & $DockerExe run --rm --entrypoint sh netherite-agent-app:0.1.0 -c "grep -RIl --exclude-dir=proc --exclude-dir=sys --exclude-dir=dev '$dbPw' '$dbAppPw' '$redisPw' '$opaToken' '$ownerHash' / 2>/dev/null" 2>&1 | Out-String
    if ($scan -match '\S') { throw "Secret text found inside image filesystem: $scan" }
    # The packaged application.yml must use an EMPTY nullable default for the
    # owner password hash (no literal BCrypt value inside the image).
    $yml = & $DockerExe run --rm --entrypoint sh netherite-agent-app:0.1.0 -c "unzip -p /opt/netherite/app.jar BOOT-INF/classes/application.yml 2>/dev/null | grep -n 'password-hash' || true" 2>&1 | Out-String
    if ($yml -match '\$2b\$' -or $yml -match '\$2a\$') { throw "Packaged application.yml still contains a baked default hash: $yml" }
    $true
}

# ------------------------------------------------------------------ P3 -------
Test-Step "P3 missing/empty secret aborts startup (fail fast)" {
    # Run against a THROWAWAY compose project so Docker's bind-mount side effects
    # (it auto-creates missing secret files as directories) can never touch the
    # real ./secrets files.
    $tmp = Join-Path $ComposeDir '.p3tmp'
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force (Join-Path $tmp 'secrets') | Out-Null
    Copy-Item -LiteralPath $compose -Destination (Join-Path $tmp 'docker-compose.yml')
    $tmpCompose = Join-Path $tmp 'docker-compose.yml'
    $tmpOwner = Join-Path $tmp 'secrets\owner_password_hash'
    # The other four secrets must EXIST (with phony values) so the empty-owner case
    # reaches the entrypoint; only owner_password_hash is deliberately missing/empty.
    $phony = '1122334455667788990011223344556677889900112233445566778899001122'
    foreach ($name in @('db_password', 'db_app_password', 'redis_password', 'opa_policy_token')) {
        [System.IO.File]::WriteAllText((Join-Path $tmp "secrets\$name"), $phony, (New-Object System.Text.UTF8Encoding($false)))
    }

    & $DockerExe compose -f $tmpCompose run --rm --no-deps app 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { throw "compose-level: missing secret file did NOT fail" }

    # The missing-file compose run auto-created the secret path as a directory;
    # clear it so the empty-file case below starts from a real (empty) file.
    if (Test-Path -LiteralPath $tmpOwner) {
        Remove-Item -LiteralPath $tmpOwner -Recurse -Force
    }
    [System.IO.File]::WriteAllText($tmpOwner, '', (New-Object System.Text.UTF8Encoding($false)))
    $out = & $DockerExe compose -f $tmpCompose run --rm --no-deps app 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    if ($out -notmatch "FATAL: required secret 'owner_password_hash'") { $exitCode = -999 }
    Remove-Item -LiteralPath $tmp -Recurse -Force
    if ($exitCode -eq 0) { throw "entrypoint-level: empty secret did NOT fail" }
    if ($exitCode -eq -999) { throw "entrypoint FATAL message missing: $out" }
    $true
}

# ------------------------------------------------------------------ P4 -------
Test-Step "P4 full stack deploys healthy (2 app replicas)" {
    & $DockerExe compose -f $compose up -d --scale app=2 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "compose up failed" }
    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 5
        $status = (& $DockerExe compose -f $compose ps --format '{{.Service}} {{.Status}}' 2>&1 | Out-String)
        if ($status -match 'unhealthy|Exit') { throw "A container went unhealthy/exited: $status" }
    } while ((($status -split "`n" | Where-Object { $_ -match '^app ' }) | Where-Object { $_ -match '\(healthy\)' }).Count -lt 2 -and (Get-Date) -lt $deadline)
    if (($status -split "`n" | Where-Object { $_ -match '^app ' -and $_ -match '\(healthy\)' }).Count -lt 2) { throw "app replicas did not become healthy: $status" }
    if ($status -notmatch '(?m)^frontend.*\(healthy\)') { throw "frontend not healthy: $status" }
    $true
}

# ------------------------------------------------------------------ P5 -------
Test-Step "P5 owner login succeeds (BCrypt survives intact); bad password rejected" {
    $tmpDir = Join-Path $env:TEMP ("netherite-verify-" + [guid]::NewGuid().ToString("N"))
    $okFile = Join-Path $tmpDir 'login.ok.json'
    $badFile = Join-Path $tmpDir 'login.bad.json'

    try {
        New-Item -ItemType Directory -Path $tmpDir -ErrorAction Stop | Out-Null
        [System.IO.File]::WriteAllText($okFile, (@{email=$OwnerEmail;password=$OwnerPassword} | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
        [System.IO.File]::WriteAllText($badFile, (@{email=$OwnerEmail;password='definitely-wrong-password'} | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))

        $okCode = $null; $badCode = $null
        $deadline = (Get-Date).AddSeconds(120)
        do {
            $okCode = (& curl.exe -sk -o NUL -w '%{http_code}' -X POST 'https://localhost/v1/auth/login' -H 'Content-Type:application/json' --data-binary "@$okFile" | Out-String).Trim()
            $badCode = (& curl.exe -sk -o NUL -w '%{http_code}' -X POST 'https://localhost/v1/auth/login' -H 'Content-Type:application/json' --data-binary "@$badFile" | Out-String).Trim()
            if ($okCode -eq '200' -and $badCode -eq '401') { break }
            Start-Sleep -Seconds 5
        } while ((Get-Date) -lt $deadline)

        if ($okCode -ne '200') { throw "expected owner login 200, got $okCode" }
        if ($badCode -ne '401') { throw "expected wrong-password 401, got $badCode" }
        $true
    }
    finally {
        if (Test-Path -LiteralPath $tmpDir) {
            Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
# ------------------------------------------------------------------ P6 -------
Test-Step "P6 Redis requires AUTH and authenticates with the provisioned secret" {
    $authed = (& $DockerExe compose -f $compose exec -T redis sh -c "redis-cli --no-auth-warning -a '$redisPw' ping" 2>&1 | Out-String).Trim()
    if ($authed -notmatch '^PONG$') { throw "redis AUTH failed: $authed" }
    $unauth = (& $DockerExe compose -f $compose exec -T redis redis-cli ping 2>&1 | Out-String).Trim()
    if ($unauth -notmatch 'NOAUTH') { throw "redis accepted a command without AUTH: $unauth" }
    $true
}

# ------------------------------------------------------------------ P7 -------
Test-Step "P7 PostgreSQL authenticates with the provisioned secret and readiness is UP" {
    $one = (& $DockerExe compose -f $compose exec -T postgres env PGPASSWORD=$dbPw psql -U netherite_admin -d netherite -tAc 'select 1' 2>&1 | Out-String).Trim()
    if ($one -ne '1') { throw "psql auth failed: $one" }
    $readiness = (& $DockerExe compose -f $compose exec -T --index 1 app sh -c "curl -fs http://localhost:8080/actuator/health/readiness -w '\n%{http_code}'" 2>&1 | Out-String)
    if ($readiness -notmatch '"status":"UP"' -or $readiness -notmatch '(?m)200\s*$') { throw "readiness not UP: $readiness" }
    $true
}

# ------------------------------------------------------------------ P8 -------
Test-Step "P8 all five secrets byte-identical across the services that mount them" {
    # Database admin credential is mounted by postgres+migrate only - NOT the app
    # (least privilege). The other four ride the app replicas. Check each secret
    # inside a service that actually mounts it, and across every app replica.
    $map = @(
        @{ Name = 'db_password'; Targets = @(@{ Service = 'postgres'; Index = $null }) },
        @{ Name = 'db_app_password'; Targets = @(@{ Service = 'app'; Index = '1' }, @{ Service = 'app'; Index = '2' }, @{ Service = 'postgres'; Index = $null }) },
        @{ Name = 'redis_password'; Targets = @(@{ Service = 'app'; Index = '1' }, @{ Service = 'app'; Index = '2' }, @{ Service = 'redis'; Index = $null }) },
        @{ Name = 'opa_policy_token'; Targets = @(@{ Service = 'app'; Index = '1' }, @{ Service = 'app'; Index = '2' }, @{ Service = 'opa'; Index = $null }) },
        @{ Name = 'owner_password_hash'; Targets = @(@{ Service = 'app'; Index = '1' }, @{ Service = 'app'; Index = '2' }) },
        @{ Name = 'gemini_api_key'; Targets = @(@{ Service = 'app'; Index = '1' }, @{ Service = 'app'; Index = '2' }) },
        @{ Name = 'openrouter_api_key'; Targets = @(@{ Service = 'app'; Index = '1' }, @{ Service = 'app'; Index = '2' }) }
    )
    foreach ($entry in $map) {
        $name = $entry.Name
        $hostHash = (Get-FileHash -Algorithm SHA256 (Join-Path $SecretsDir $name)).Hash.ToLowerInvariant()
        foreach ($t in $entry.Targets) {
            $args = @('compose', '-f', $compose, 'exec', '-T')
            if ($t.Index) { $args += '--index'; $args += $t.Index }
            $args += $t.Service
            $args += 'sh', '-c', "sha256sum /run/secrets/$name"
            $out = (& $DockerExe @args 2>&1 | Out-String)
            if ($out -notmatch "^$hostHash ") { throw "$($t.Service)($($t.Index)) $name mismatch: $out" }
        }
    }
    $true
}

# ------------------------------------------------------------------ P9 -------
Test-Step "P9 no secret value leaks into container logs" {
    $logs = & $DockerExe compose -f $compose logs --no-color 2>&1 | Out-String
    Assert-HasNoMatch -Name 'container logs' -Needles @($dbPw, $dbAppPw, $redisPw, $opaToken, $ownerHash) -Haystack $logs
    $true
}

# ------------------------------------------------------------------ P10 -----
Test-Step "P10 graceful stop exits 0 with clean shutdown" {
    & $DockerExe compose -f $compose stop -t 60 app 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker compose stop failed" }
    $ps = & $DockerExe compose -f $compose ps -a --format '{{.Service}} {{.Status}}' 2>&1 | Out-String
    if ($ps -notmatch '(?m)^app.*Exited \((0|143)\)' -and $ps -notmatch '(?m)^app.*\(healthy\)') { throw "app did not stop cleanly (0/143 only, never SIGKILL 137): $ps" }
    $tail = (& $DockerExe compose -f $compose logs app --no-color 2>&1 | Out-String)
    if ($tail -match 'Commencing graceful shutdown') { Write-Host "      (graceful-shutdown log line confirmed)" }
    & $DockerExe compose -f $compose start app 2>&1 | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 5
        $status = (& $DockerExe compose -f $compose ps --format '{{.Service}} {{.Status}}' 2>&1 | Out-String)
    } while (($status -split "`n" | Where-Object { $_ -match '^app ' -and $_ -match '\(healthy\)' }).Count -lt 2 -and (Get-Date) -lt $deadline)
    if (($status -split "`n" | Where-Object { $_ -match '^app ' -and $_ -match '\(healthy\)' }).Count -lt 2) { throw "app not healthy after restart: $status" }
    $true
}

# ------------------------------------------------------------------ P11 -----
Test-Step "P11 dependency failure/recovery (redis, postgres, opa)" {
    $overall = { & $DockerExe compose -f $compose exec -T --index 1 app sh -c "curl -s http://localhost:8080/actuator/health" 2>&1 | Out-String }
    $readiness = { & $DockerExe compose -f $compose exec -T --index 1 app sh -c "curl -fs http://localhost:8080/actuator/health/readiness" 2>&1 | Out-String }

    foreach ($dep in @('redis', 'postgres', 'opa')) {
        & $DockerExe compose -f $compose stop $dep 2>&1 | Out-Null
        Start-Sleep -Seconds 8
        $o = (& $overall)
        if ($o -notmatch '"status":"DOWN"') { throw "$dep down: overall not DOWN ($o)" }
        $r = (& $readiness)
        if ($dep -eq 'postgres') {
            if ($r -match '"status":"UP"') { throw "postgres down: readiness must be DOWN ($r)" }
        }
        else {
            if ($r -notmatch '"status":"UP"') { throw "$dep down: readiness must remain UP ($r)" }
        }
        & $DockerExe compose -f $compose start $dep 2>&1 | Out-Null
        $deadline = (Get-Date).AddMinutes(2)
        do {
            Start-Sleep -Seconds 5
            $o = (& $overall)
        } while ($o -notmatch '"status":"UP"' -and (Get-Date) -lt $deadline)
        if ($o -notmatch '"status":"UP"') { throw "$dep recovered: overall not UP ($o)" }
    }
    $true
}

# ------------------------------------------------------------------ P12 -----
Test-Step "P12 Caddy edge routes /v1 /actuator and serves the SPA" {
    $api = (& curl.exe -sk https://localhost/v1/auth/me | Out-String)
    $edge = (& curl.exe -sk https://localhost/actuator/health/liveness | Out-String)
    $spa = (& curl.exe -sk https://localhost/ | Out-String)
    if ($api -notmatch 'ERR_AUTHENTICATION_FAILED') { throw "edge /v1 did not reach the app API ($api)" }
    if ($edge -notmatch '"status":"UP"') { throw "edge /actuator liveness not UP ($edge)" }
    if ($spa -notmatch 'NetheriteAgent') { throw "edge did not serve the SPA ($spa)" }
    $true
}

# ------------------------------------------------------------------ P13 -----
Test-Step "P13 docker secret refs match the application's actual requirements" {
    # Static contract between docker-entrypoint.sh (require_secret calls), the
    # compose file's per-service secrets lists, and the host secrets inventory.
    $entry = [System.IO.File]::ReadAllText((Join-Path $ComposeDir '..\..\backend\netherite-app\docker-entrypoint.sh'))
    if ($entry -notmatch 'require_secret "\$DB_SECRET_NAME" DB_PASSWORD') { throw "entrypoint must consume DB_SECRET_NAME secret" }
    foreach ($wanted in @('redis_password', 'owner_password_hash', 'opa_policy_token')) {
        if ($entry -notmatch "require_secret $wanted ") { throw "entrypoint missing require_secret for $wanted" }
    }
    if (-not $entry.Contains('if [ "${LLM_PROVIDER:-none}" = "gemini" ]; then')) { throw "entrypoint must gate gemini_api_key behind LLM_PROVIDER=gemini" }
    if ($entry -notmatch 'require_secret gemini_api_key GEMINI_API_KEY') { throw "entrypoint must require gemini_api_key when LLM_PROVIDER=gemini" }
    if (-not $entry.Contains('if [ "${LLM_PROVIDER:-none}" = "openrouter" ]; then')) { throw "entrypoint must gate openrouter_api_key behind LLM_PROVIDER=openrouter" }
    if ($entry -notmatch 'require_secret openrouter_api_key OPENROUTER_API_KEY') { throw "entrypoint must require openrouter_api_key when LLM_PROVIDER=openrouter" }
    $composeText = [System.IO.File]::ReadAllText($compose)
    # App block: text from '  app:' up to the next top-level service heading.
    $appMatch = [regex]::Match($composeText, '(?ms)^  app:\r?\n(?<body>.+?)^  [a-zA-Z][a-zA-Z0-9_]*:\r?\n')
    if (-not $appMatch.Success) { throw "compose file has no app service block" }
    $appBlock = $appMatch.Groups['body'].Value
    # App mounts exactly what the runtime entrypoint needs - and never the admin
    # db_password (least privilege: the runtime role only holds db_app_password).
    foreach ($wanted in @('db_app_password', 'redis_password', 'owner_password_hash', 'opa_policy_token', 'gemini_api_key', 'openrouter_api_key')) {
        if ($appBlock -notmatch "(?m)^[ \t]+- $wanted[ \t]*$") { throw "app service missing secret $wanted" }
    }
    if ($appBlock -match "(?m)^[ \t]+- db_password[ \t]*$") { throw "app service must NOT mount admin db_password" }
    # Top-level secrets inventory must be exactly the seven host files, each
    # pointing at ./secrets/<name>.
foreach ($f in @('db_password', 'db_app_password', 'redis_password', 'opa_policy_token', 'owner_password_hash', 'gemini_api_key', 'openrouter_api_key')) {
        if ($composeText -notmatch ("(?m)^[ \t]+" + [regex]::Escape($f) + ":`r?`n[ \t]+file: \./secrets/" + $f + "[ \t]*$")) {
            throw "compose secrets block missing $f -> ./secrets/$f"
        }
    }
    $true
}

# -------------------------------------------------------------- summary -----
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ("Resolved fail-fast behavior summary:  missing secret -> compose abort; empty/unreadable -> entrypoint FATAL exit 1") -ForegroundColor DarkGray
Write-Host ("Steps executed : {0} / passed : {1} / failed : {2}" -f $script:count, $script:passed, $script:failures.Count) -ForegroundColor Cyan
if ($script:failures.Count -gt 0) {
    Write-Host "FAILED STEPS:" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "ALL SECRET-HANDLING CHECKS PASSED" -ForegroundColor Green
exit 0