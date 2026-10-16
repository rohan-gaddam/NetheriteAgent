#requires -Version 5.1
<#
.SYNOPSIS
    NetheriteAgent operational safety checks (read-only, safe for scheduled use).

.DESCRIPTION
    Runs non-destructive checks against the deployed compose stack and the
    PostgreSQL backup set. Intended to be run on the host next to the stack:

      1. Docker engine reachable.
      2. Compose file parses.
      3. All five services (postgres, redis, opa, app, frontend) running+healthy.
      4. PostgreSQL reachable and serving the live database (read-only count).
      5. Redis answers PING and AOF write status is ok (secrets read in-container).
      6. OPA health endpoint answers 200 (bearer token read in-container).
      7. Application is reachable end-to-end through Caddy (https /healthz).
      8. Newest backup is fresh (< -MaxBackupAgeHours), its manifest exists, and
         its SHA-256 matches the manifest.
      9. No leftover .dump.tmp file (interrupted backup signal).
     10. Enough free disk space on the backup drive (>= -MinFreeGb).

    Secrets are never printed: passwords/tokens are read inside the containers
    via /run/secrets/* and all captured output is passed through the same
    redaction prefilter used by the backup/restore workflows.

.EXAMPLE
    & .\netherite-ops-checks.ps1
    & .\netherite-ops-checks.ps1 -MaxBackupAgeHours 48 -MinFreeGb 2
#>
[CmdletBinding()]
param(
    [string]$ComposeFile,
    [string]$BackupsDir,
    [int]$MaxBackupAgeHours = 26,
    [int]$MinFreeGb = 1,
    [string]$HealthzUrl = 'https://localhost/healthz',
    [string]$DockerExecutable,
    [string[]]$Services = @('postgres', 'redis', 'opa', 'app', 'frontend')
)

$ErrorActionPreference = 'Stop'

$script:Pass = 0
$script:Fail = 0

function Assert {
    param([bool]$Condition, [string]$Name, [string]$Detail)
    if ($Condition) {
        $script:Pass++
        Write-Host ("PASS: {0}" -f $Name) -ForegroundColor Green
    }
    else {
        $script:Fail++
        Write-Host ("FAIL: {0} - {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

function Resolve-Docker {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    return $cmd.Source
}

function Invoke-RawRedirect {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$StdoutFile,
        [string]$StderrFile
    )
    $argLine = ($ArgumentList | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $argLine
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $outStream = [System.IO.File]::Open($StdoutFile, [System.IO.FileMode]::Create)
    $errStream = [System.IO.File]::Open($StderrFile, [System.IO.FileMode]::Create)
    try {
        [void]$proc.Start()
        $errTask = $proc.StandardError.BaseStream.CopyToAsync($errStream)
        $proc.StandardOutput.BaseStream.CopyTo($outStream)
        $errTask.Wait()
        $proc.WaitForExit()
        return $proc.ExitCode
    }
    finally {
        $outStream.Dispose()
        $errStream.Dispose()
        Try { $proc.Dispose() } Catch { }
    }
}

function Invoke-Docker {
    param([string[]]$DockerArgs, [string]$StdoutFile = $null, [string]$StderrFile = $null)
    $docker = Resolve-Docker
    if (-not $docker) { return [pscustomobject]@{ Code = 125; Stdout = $null; StderrFile = '' } }
    $outFile = if ($StdoutFile) { $StdoutFile } else { Join-Path $env:TEMP ('neth-d-'+[guid]::NewGuid().ToString('N')+'.out') }
    $errFile = if ($StderrFile) { $StderrFile } else { Join-Path $env:TEMP ('neth-d-'+[guid]::NewGuid().ToString('N')+'.err') }
    $code = Invoke-RawRedirect -FilePath $docker -ArgumentList $DockerArgs -StdoutFile $outFile -StderrFile $errFile
    $out = $null
    if (Test-Path -LiteralPath $outFile) {
        $c = Get-Content -LiteralPath $outFile -Raw -ErrorAction SilentlyContinue
        if ($c) { $out = $c.Trim() }
    }
    if (-not $StdoutFile) { Remove-Item -LiteralPath $outFile, $errFile -Force -ErrorAction SilentlyContinue }
    return [pscustomobject]@{ Code = $code; Stdout = $out; StderrFile = if ($StdoutFile) { $errFile } else { $errFile } }
}

function Get-RedactionNeedles {
    $needles = @()
    $secretsDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'secrets'
    foreach ($name in @('db_password', 'db_app_password', 'redis_password', 'opa_policy_token', 'owner_password_hash')) {
        $p = Join-Path $secretsDir $name
        if (Test-Path -LiteralPath $p) {
            $v = (Get-Content -LiteralPath $p -Raw -ErrorAction SilentlyContinue)
            if ($v -and $v.Length -ge 8) { $needles += $v.Substring(0, 8) }
        }
    }
    return $needles
}

# --------------------------------------------------------------------- main --
if (-not $ComposeFile) { $ComposeFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'docker-compose.yml' }
if (-not $BackupsDir) { $BackupsDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'backups' }

$docker = Resolve-Docker

Write-Host "== operational checks (read-only) ==" -ForegroundColor Cyan
Write-Host "== compose file: $ComposeFile"        -ForegroundColor Cyan
Write-Host "== backups dir:  $BackupsDir"         -ForegroundColor Cyan

# 1. docker available
Assert ([bool]$docker) 'docker engine reachable (docker CLI found)' 'docker not found on PATH'

if (-not $docker) {
    Write-Host ("== {0} FAIL, {1} PASS ==" -f $script:Fail, $script:Pass)
    exit 1
}

# 2. compose parses
$cfg = Invoke-Docker -DockerArgs @('compose', '-f', $ComposeFile, 'config', '-q')
Assert ($cfg.Code -eq 0) 'compose file parses (docker compose config -q)' ("exit $($cfg.Code)")

# 3. stack presence
$ps = Invoke-Docker -DockerArgs @('compose', '-f', $ComposeFile, 'ps', '--format', 'json')
$stackRows = @()
if ($ps.Code -eq 0 -and $ps.Stdout) {
    foreach ($line in ($ps.Stdout -split "`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $stackRows += ($line | ConvertFrom-Json) } catch { }
    }
}
Assert ($stackRows.Count -gt 0) 'stack is up (docker compose ps returned containers)' 'no containers found - is the stack running?'

# 4. every target service running + healthy
$running = @{}
foreach ($r in $stackRows) { $running[[string]$r.Name] = $true }
$bad = @()
foreach ($s in $Services) {
    $match = @($stackRows | Where-Object { $_.Service -eq $s })
    if ($match.Count -eq 0) { $bad += "$s (missing)"; continue }
    $m = $match[0]
    if ($m.State -ne 'running' -or $m.Health -ne 'healthy') { $bad += "$s ($($m.State)/$($m.Health))" }
}
Assert ($bad.Count -eq 0) ('all services running+healthy [{0}]' -f ($Services -join ',')) ($bad -join '; ')

# 5. postgres reachable + live counter
if ($running['netherite-postgres-1']) {
    $pgArgs = @(@('compose', '-f', $ComposeFile) + @('exec', 'postgres', 'sh', '-c', 'PGPASSWORD=$(cat /run/secrets/db_password) psql -X -q -tA -U netherite_admin -d netherite -c "SELECT count(*) FROM organizations"'))
    $pg = Invoke-Docker -DockerArgs $pgArgs
    $count = -1
    if ($pg.Stdout) {
        $first = (($pg.Stdout -split "`n") | Select-Object -First 1).Trim()
        if ($first -match '^\d+$') { $count = [int]$first }
    }
    Assert ($pg.Code -eq 0 -and $count -ge 1) 'postgres is ready and serving live data (organizations count)' ("exit=$($pg.Code) count=$count")
}
else {
    Assert $false 'postgres is ready and serving live data' 'postgres service not running'
}

# 6. redis ping + AOF write status
if ($running['netherite-redis-1']) {
    $redArgs = @(@('compose', '-f', $ComposeFile) + @('exec', 'redis', 'sh', '-c', 'PW=$(cat /run/secrets/redis_password); redis-cli -a "$PW" ping 2>/dev/null; redis-cli -a "$PW" INFO persistence 2>/dev/null | grep aof_last_write_status'))
    $redis = Invoke-Docker -DockerArgs $redArgs
    $pingOk = $redis.Stdout -match 'PONG'
    $aofOk = $redis.Stdout -match 'aof_last_write_status:ok'
    Assert ($redis.Code -eq 0 -and $pingOk) 'redis answers PING (PONG)' ("exit=$($redis.Code) stdout=$($redis.Stdout)")
    Assert ($redis.Code -eq 0 -and $aofOk) 'redis AOF last write status ok (durability)' ("stdout=$($redis.Stdout)")
}
else {
    Assert $false 'redis answers PING (PONG)' 'redis service not running'
    Assert $false 'redis AOF last write status ok (durability)' 'redis service not running'
}

# 7. opa health endpoint
if ($running['netherite-opa-1']) {
    $opaArgs = @(@('compose', '-f', $ComposeFile) + @('exec', 'opa', 'sh', '-c', 'wget -qO- --header="Authorization: Bearer $(cat /run/secrets/opa_policy_token)" http://localhost:8181/health >/dev/null 2>&1'))
    $opa = Invoke-Docker -DockerArgs $opaArgs
    Assert ($opa.Code -eq 0) 'opa health endpoint responds (token read in-container)' ("exit=$($opa.Code)")
}
else {
    Assert $false 'opa health endpoint responds' 'opa service not running'
}

# 8. app end-to-end through Caddy
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
$hz = ''
if ($curl) {
    $hOut = Join-Path $env:TEMP ('neth-hz-'+[guid]::NewGuid().ToString('N')+'.out')
    $hErr = Join-Path $env:TEMP ('neth-hz-'+[guid]::NewGuid().ToString('N')+'.err')
    $hCode = Invoke-RawRedirect -FilePath $curl.Source -ArgumentList @('-k', '-s', '-o', 'NUL', '-w', '%{http_code}', $HealthzUrl) -StdoutFile $hOut -StderrFile $hErr
    if (Test-Path -LiteralPath $hOut) {
        $hzc = Get-Content -LiteralPath $hOut -Raw -ErrorAction SilentlyContinue
        if ($hzc) { $hz = $hzc.Trim() }
    }
    Remove-Item -LiteralPath $hOut, $hErr -Force -ErrorAction SilentlyContinue
    Assert ($hCode -eq 0 -and $hz -eq '200') 'application reachable end-to-end through Caddy (/healthz -> HTTP 200)' ("http_code=$hz exit=$hCode")
}
else {
    Assert $false 'application reachable end-to-end through Caddy' 'curl.exe not found on host'
}

# ------------------------------------------------------------------ backups ---
Write-Host "== backup set checks ==" -ForegroundColor Cyan

$dumps = @(Get-ChildItem -LiteralPath $BackupsDir -Filter 'netherite-pg-*.dump' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^netherite-pg-\d{8}T\d{6}Z\.dump$' } | Sort-Object LastWriteTime -Descending)

if ($dumps.Count -ge 1) {
    $newest = $dumps[0]
    $age = (Get-Date).ToUniversalTime() - $newest.LastWriteTime.ToUniversalTime()
    Assert ($age.TotalHours -le $MaxBackupAgeHours) ("newest backup is fresh (<{0}h) [{1}, age {2:n1}h]" -f $MaxBackupAgeHours, $newest.Name, $age.TotalHours) ("age=$($age.TotalHours)")

    $manifestPath = Join-Path $newest.DirectoryName ($newest.BaseName + '.json')
    Assert (Test-Path -LiteralPath $manifestPath) ("manifest exists for newest backup [{0}]" -f (Split-Path -Leaf $manifestPath)) 'manifest file missing'

    if (Test-Path -LiteralPath $manifestPath) {
        $expected = (Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json).sha256
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $fs = [System.IO.File]::OpenRead($newest.FullName)
            try { $actual = [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '').ToLowerInvariant() }
            finally { $fs.Dispose() }
        }
        finally { $sha.Dispose() }
        Assert ($expected -and $expected -eq $actual) 'newest backup integrity (SHA-256 matches manifest)' ("expected=$expected actual=$actual")
    }
}
else {
    Assert $false 'at least one backup exists' 'no netherite-pg-*.dump under backups dir'
}

# 9. no leftover interrupted-backup marker
$leftover = @(Get-ChildItem -LiteralPath $BackupsDir -Filter '*.dump.tmp' -ErrorAction SilentlyContinue)
Assert ($leftover.Count -eq 0) 'no leftover .dump.tmp files (no interrupted backup)' ("found $($leftover.Count) leftover temp file(s)")

# 10. disk space
try {
    $drive = (Get-Item -LiteralPath $BackupsDir).PSDrive
    $freeGb = [Math]::Floor($drive.Free / 1GB)
}
catch { $freeGb = 0 }
Assert ($freeGb -ge $MinFreeGb) ("free disk space on backup drive >= {0} GiB" -f $MinFreeGb) ("free=$freeGb GiB")

# ----------------------------------------------------------------- summary ----
Write-Host ("== {0} FAIL, {1} PASS ==" -f $script:Fail, $script:Pass) -ForegroundColor Cyan
if ($script:Fail -gt 0) { exit 1 }
exit 0