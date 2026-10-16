#requires -Version 5.1
<#
.SYNOPSIS
    NetheriteAgent PostgreSQL logical backup + retention.

.DESCRIPTION
    Produces a consistent, restorable PostgreSQL backup using PostgreSQL-native
    tooling (pg_dump, custom/compressed format) and records a sidecar manifest
    (timestamp, format, sha256, size, schema identity and representative row
    counts) WITHOUT any sensitive data.

    Credential handling (no password on a command line, in logs, or in any
    generated artifact):
      - Source 'Compose' (default): runs inside the netherite postgres container
        and reads the admin password from /run/secrets/db_password INSIDE that
        container. The value never appears on the host command line, in the host
        environment, or in output.
      - Source 'Container': runs inside an explicitly named disposable container
        (CI / verification only) using trust auth (-w, no password at all).
      - Source 'Direct': connects to a host PostgreSQL using the local pg_dump
        from -PgToolsDir. The password is read from -PasswordFile into the child
        process environment ONLY (never argv, never echoed).

    Safe-by-default details:
      - Writes to a unique temporary file in the backup directory, verifies
        (non-empty, PostgreSQL custom-format PGDMP magic, pg_restore --list,
        sha256), then atomically renames to <timestamp>-netherite-pg.dump. A
        final file with the same timestamp cannot exist, so a previously valid
        backup is never silently overwritten.
      - Fails loudly (exit 1, sanitized message) on any unsuccessful backup and
        removes the temporary file; a leftover *.dump.tmp therefore signals a
        crashed run and is flagged by the ops checks.
      - Checks available disk space before starting (default minimum 1 GiB).
      - Retention: report-only by default; -Apply actually deletes. Keeps the
        newest -RetentionCount recovery points and everything younger than
        -RetentionDays, never deletes the newest backup, and never deletes when
        only one backup exists. Operates only on files matching the tool's own
        strict naming pattern inside the exact backup directory (no recursion,
        no globbing, path-traversal and symlink/junction escapes refused).

    The backup is a full logical dump of the database schema (Flyway V1..V13),
    migration history and data. Roles/ACLs are NOT stored in the dump; the
    restore workflow pre-creates the standard roles which are reproducible from
    the repository bootstrap (init.sql / init-db.sh).

    Encryption and off-host durability are NOT implemented; those requirements
    are documented owner decisions (see docs/operations-and-recovery.md). No
    schedule is claimed: this script is a manually (or externally orchestrated)
    invoked backup step.

.EXAMPLE
    & .\netherite-backup.ps1                        # compose postgres -> backups/
    & .\netherite-backup.ps1 -PruneOnly -Apply      # prune only, actually delete
    & .\netherite-backup.ps1 -Source Direct -Host localhost -Port 5433 `
        -User netherite_admin -PgToolsDir "$projectDir\.tools\postgres-16.4\pgsql\bin"
#>
[CmdletBinding()]
param(
    # --- source ------------------------------------------------------------------
    [ValidateSet('Compose', 'Container', 'Direct')]
    [string]$Source = 'Compose',
    [string]$Database = 'netherite',

    # Compose mode
    [string]$ComposeDir,
    [string]$ComposeService = 'postgres',
    [string]$ComposeDbUser = 'netherite_admin',

    # Container mode (disposable / CI verification containers; trust auth -w)
    [string]$ContainerName,
    [string]$ContainerDbUser = 'postgres',

    # Direct mode
    [string]$HostConnection,
    [int]$Port = 5432,
    [string]$User = 'netherite_admin',
    [string]$PasswordFile,
    [string]$PgToolsDir,

    # --- output -------------------------------------------------------------------
    [string]$BackupDir,

    # --- structure validation image -----------------------------------------------
    [string]$ValidateImage = 'postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea',

    # --- retention -----------------------------------------------------------------
    [switch]$PruneOnly,          # run only the retention pass (no new backup)
    [int]$RetentionCount = 3,    # minimum recovery points to keep (never fewer)
    [int]$RetentionDays = 14,    # keep everything younger than this (0 = keep all ages)
    [switch]$Apply,              # actually delete; without it, report only

    # --- safety --------------------------------------------------------------------
    [long]$MinFreeBytes = 1GB,
    [switch]$NoTempCleanupOnError
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is unavailable during param-default evaluation; resolve here.
if (-not $ComposeDir) { $ComposeDir = Join-Path $PSScriptRoot '..' }
if (-not $BackupDir)  { $BackupDir  = Join-Path $PSScriptRoot '..\backups' }

$script:ExitCode = 0

# ---------------------------------------------------------------------- helpers --
function Fail {
    param([string]$Message)
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Resolve-Docker {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $cmd) { Fail 'docker is not on PATH; Source Compose/Container require the Docker engine.' }
    return $cmd.Source
}

# Runs a native process with stdout/stderr redirected to dedicated files (raw
# bytes so binary custom-format dumps are never re-encoded). Returns exit code.
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

function Read-SanitizedOutput {
    param([string]$Path, [string[]]$SecretNeedles)
    $raw = Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { return '' }
    foreach ($n in $SecretNeedles) {
        if ($n -and $raw.Contains($n)) { $raw = $raw.Replace($n, '<redacted>') }
    }
    return $raw.Trim()
}

# Wraps SQL in a bash single-quoted argument, escaping embedded single quotes
# with the standard '\'' sequence so psql receives the SQL byte-for-byte and no
# token splitting/identifier corruption can occur.
function ConvertTo-BashSingleQuoted {
    param([string]$Sql)
    return "'" + $Sql.Replace("'", "'\''") + "'"
}

function BackupDirGuard {
    if (-not (Test-Path -LiteralPath $BackupDir)) {
        try { New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null }
        catch { Fail ("Cannot create backup directory '{0}': {1}" -f $BackupDir, $_.Exception.Message) }
    }
    $dirItem = Get-Item -LiteralPath (Resolve-Path -LiteralPath $BackupDir).Path -Force
    if ($dirItem.LinkType) {
        Fail "Refusing to use '$BackupDir': the backup directory is a $($dirItem.LinkType); backups must use a real directory."
    }
}

function Test-DiskSpace {
    $qualifier = Split-Path -Qualifier $BackupDir
    if (-not $qualifier -or -not $qualifier.EndsWith(':')) { return }   # UNC path: skip (resolved elsewhere)
    $drive = Get-PSDrive -Name $qualifier.TrimEnd(':') -ErrorAction SilentlyContinue
    if ($drive -and $drive.Free -lt $MinFreeBytes) {
        Fail ("Insufficient disk space: {0:N1} MiB free on '{1}' but {2:N0} MiB is required. Backup refused." -f ($drive.Free / 1MB), $BackupDir, ($MinFreeBytes / 1MB))
    }
}

# ------------------------------------------------------------------ sources ------
# Source runners produce `key|value` lines from a SQL query and raw dump bytes,
# depending on the mode. Passwords are never argv/echoed as described above.
$script:Runner = $null
$script:MetadataSql = $null

function Initialize-Source {
    $script:docker = Resolve-Docker
    $script:needles = Get-RedactionNeedles

    switch ($Source) {
        'Compose' {
            $script:composeFile = Join-Path $ComposeDir 'docker-compose.yml'
            if (-not (Test-Path -LiteralPath $script:composeFile)) {
                Fail "Compose file not found: '$script:composeFile'. Use -Source Direct or -Source Container instead."
            }
            $script:Runner = {
                param([string]$OutFile, [bool]$AsSql)
                $errTmp = Join-Path $env:TEMP ('neth-' + [guid]::NewGuid().ToString('N') + '.err')
                if ($AsSql) {
                    $inner = "PGPASSWORD=`$(cat /run/secrets/db_password) psql -X -q -tA -U {u} -h 127.0.0.1 -p 5432 -d {db} -c " + (ConvertTo-BashSingleQuoted $script:MetadataSql)
                } else {
                    $inner = "PGPASSWORD=`$(cat /run/secrets/db_password) pg_dump -Fc -Z 9 -U {u} -h 127.0.0.1 -p 5432 -d {db}"
                }
                $inner = $inner.Replace('{u}', $script:ComposeDbUser).Replace('{db}', $script:Database)
                $code = Invoke-RawRedirect -FilePath $script:docker -ArgumentList @(
                    'compose', '-f', $script:composeFile, 'exec', '-T', $script:ComposeService,
                    'sh', '-c', $inner
                ) -StdoutFile $OutFile -StderrFile $errTmp
                if ($code -ne 0) {
                    $msg = Read-SanitizedOutput $errTmp $script:needles
                    Remove-Item -LiteralPath $errTmp -Force -ErrorAction SilentlyContinue
                    throw "Backup source command failed (exit $code): $msg"
                }
                Remove-Item -LiteralPath $errTmp -Force -ErrorAction SilentlyContinue
            }
        }
        'Container' {
            if (-not $ContainerName) { Fail 'Source Container requires -ContainerName.' }
            $script:Runner = {
                param([string]$OutFile, [bool]$AsSql)
                $errTmp = Join-Path $env:TEMP ('neth-' + [guid]::NewGuid().ToString('N') + '.err')
                # Trust-only disposable containers (-w); socket connections, no password.
                if ($AsSql) {
                    $inner = "psql -X -q -tA -w -U {u} -d {db} -c " + (ConvertTo-BashSingleQuoted $script:MetadataSql)
                } else {
                    $inner = "pg_dump -Fc -Z 9 -w -U {u} -d {db}"
                }
                $inner = $inner.Replace('{u}', $script:ContainerDbUser).Replace('{db}', $script:Database)
                $code = Invoke-RawRedirect -FilePath $script:docker -ArgumentList @(
                    'exec', $ContainerName, 'sh', '-c', $inner
                ) -StdoutFile $OutFile -StderrFile $errTmp
                if ($code -ne 0) {
                    $msg = Read-SanitizedOutput $errTmp $script:needles
                    Remove-Item -LiteralPath $errTmp -Force -ErrorAction SilentlyContinue
                    throw "Backup source command failed (exit $code): $msg"
                }
                Remove-Item -LiteralPath $errTmp -Force -ErrorAction SilentlyContinue
            }
        }
        'Direct' {
            $pgBin = if ($PgToolsDir) { $PgToolsDir } else {
                Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))) '.tools\postgres-16.4\pgsql\bin'
            }
            $script:pgDumpExe = Join-Path $pgBin 'pg_dump.exe'
            $script:psqlExe   = Join-Path $pgBin 'psql.exe'
            if (-not (Test-Path -LiteralPath $script:pgDumpExe) -or -not (Test-Path -LiteralPath $script:psqlExe)) {
                Fail "PostgreSQL tools not found under '$pgBin' (need pg_dump.exe + psql.exe). Pass -PgToolsDir."
            }
            $script:DirectPassword = $null
            if ($PasswordFile) { $script:DirectPassword = (Get-Content -LiteralPath $PasswordFile -Raw -ErrorAction SilentlyContinue).Trim() }
            $script:Runner = {
                param([string]$OutFile, [bool]$AsSql)
                $errTmp = Join-Path $env:TEMP ('neth-' + [guid]::NewGuid().ToString('N') + '.err')
                $had = Test-Path Env:PGPASSWORD
                $prev = $null; if ($had) { $prev = $env:PGPASSWORD }
                try { if ($script:DirectPassword) { $env:PGPASSWORD = $script:DirectPassword } } catch { }
                try {
                    if ($AsSql) {
                        $args = @('-X', '-q', '-tA', '-h', $HostConnection, '-p', ("$Port"), '-U', $User, '-d', $Database, '-c', $script:MetadataSql)
                    } else {
                        $args = @('-Fc', '-Z', '9', '-h', $HostConnection, '-p', ("$Port"), '-U', $User, '-d', $Database)
                    }
                    if (-not $script:DirectPassword) { $args += '-w' }
                    $code = Invoke-RawRedirect -FilePath $(if ($AsSql) { $script:psqlExe } else { $script:pgDumpExe }) -ArgumentList $args -StdoutFile $OutFile -StderrFile $errTmp
                }
                finally {
                    if ($had) { $env:PGPASSWORD = $prev } else { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
                }
                if ($code -ne 0) {
                    $msg = Read-SanitizedOutput $errTmp $script:needles
                    Remove-Item -LiteralPath $errTmp -Force -ErrorAction SilentlyContinue
                    throw "Backup source command failed (exit $code): $msg"
                }
                Remove-Item -LiteralPath $errTmp -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

# ------------------------------------------------------------- retention --------
$script:BackupFileRegex = '^netherite-pg-([0-9]{8}T[0-9]{6}Z)\.(dump|json)$'

function Invoke-PrunePass {
    # Enumerates own-pattern backups in the exact directory and computes what is
    # safe to delete. Report by default; deletes only with -Apply.
    $dir = (Resolve-Path -LiteralPath $BackupDir).Path
    $files = Get-ChildItem -LiteralPath $dir -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $script:BackupFileRegex }

    $stems = @{}
    foreach ($f in $files) {
        if ($f.Name -match $script:BackupFileRegex) {
            $stem = ($Matches[1])
            if (-not $stems.ContainsKey($stem)) { $stems[$stem] = @{ dump = $false; json = $false; ts = $Matches[1] } }
            if ($f.Name.EndsWith('.dump')) { $stems[$stem].dump = $true } else { $stems[$stem].json = $true }
        }
    }

    if ($stems.Count -eq 0) {
        Write-Host 'Retention: no backups found; nothing to prune.'
        return
    }

    $ordered = @($stems.GetEnumerator() | Sort-Object { $_.Value.ts } -Descending)
    if ($stems.Count -le $RetentionCount) {
        Write-Host ("Retention: {0} backup(s) <= minimum recovery points {1}; nothing to prune." -f $stems.Count, $RetentionCount)
        return
    }

    $now = [DateTime]::UtcNow
    $newestTs = $ordered[0].Value.ts
    $toDelete = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $ordered.Count; $i++) {
        $ts = $ordered[$i].Value.ts
        $tsUtc = [DateTime]::ParseExact($ts, 'yyyyMMddTHHmmssZ',
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AdjustToUniversal)
        $age = $now - $tsUtc
        $keepByCount = ($i -lt $RetentionCount)                         # minimum recovery points
        $keepByAge   = ($RetentionDays -gt 0 -and $age.TotalDays -lt $RetentionDays)
        $isNewest    = ($ts -eq $newestTs)                              # never delete the newest
        if ($keepByCount -or $keepByAge -or $isNewest) { continue }
        $toDelete.Add($ts)
    }

    if ($toDelete.Count -eq 0) {
        Write-Host ('Retention: {0} backup(s) present; none older than {1} days or beyond the newest {2}. Nothing to prune.' -f $stems.Count, $RetentionDays, $RetentionCount)
        return
    }

    Write-Host ("Retention: {0} candidate(s) prunable (keep newest {1} recovery points, retention {2} days):" -f $toDelete.Count, $RetentionCount, $RetentionDays)
    foreach ($ts in $toDelete) { Write-Host "  candidate: netherite-pg-$ts" }

    if (-not $Apply) {
        Write-Host 'Retention: report-only (no -Apply); nothing deleted.'
        return
    }

    foreach ($ts in $toDelete) {
        foreach ($ext in @('.dump', '.json')) {
            $file = Join-Path $dir ('netherite-pg-' + $ts + $ext)
            if (Test-Path -LiteralPath $file) {
                $item = Get-Item -LiteralPath $file -Force
                if ($item.LinkType) {
                    Fail ("Refusing to delete '{0}': it is a {1}; deleting it could escape the backup directory." -f $file, $item.LinkType)
                }
                Remove-Item -LiteralPath $file -Force
                Write-Host ("  deleted: {0}" -f (Split-Path -Leaf $file))
            }
        }
    }
}

function Test-ValidateDump {
    param([string]$DumpFile)
    # Structural validation with pg_restore --list via a short-lived disposable
    # container (no server needed). Reuses the digest-pinned postgres image.
    $docker = Resolve-Docker
    $outTmp = Join-Path $env:TEMP ('neth-pgr-' + [guid]::NewGuid().ToString('N') + '.out')
    $errTmp = Join-Path $env:TEMP ('neth-pgr-' + [guid]::NewGuid().ToString('N') + '.err')
    $code = Invoke-RawRedirect -FilePath $docker -ArgumentList @(
        'run', '--rm', '-v', ("{0}:/tmp/verify.dump:ro" -f $DumpFile), '--entrypoint', 'pg_restore',
        $ValidateImage, '--list', '/tmp/verify.dump'
    ) -StdoutFile $outTmp -StderrFile $errTmp
    # The entrypoint is pg_restore itself; no server is involved.
    if ($code -ne 0) {
        $msg = Read-SanitizedOutput $errTmp (Get-RedactionNeedles)
        Remove-Item -LiteralPath $outTmp, $errTmp -Force -ErrorAction SilentlyContinue
        return $false, "pg_restore --list rejected the dump: $msg"
    }
    $list = Get-Content -LiteralPath $outTmp -ErrorAction SilentlyContinue | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    Remove-Item -LiteralPath $outTmp, $errTmp -Force -ErrorAction SilentlyContinue
    if (-not $list -or $list.Count -lt 1) {
        return $false, 'pg_restore --list produced no entries: dump has no storable content.'
    }
    return $true, ('pg_restore --list OK ({0} entries)' -f $list.Count)
}

# ------------------------------------------------------------------- main ------
if ($PruneOnly) {
    BackupDirGuard
    Invoke-PrunePass
    exit 0
}

BackupDirGuard
Test-DiskSpace
Initialize-Source

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$base = "netherite-pg-$stamp"
$tempDump = Join-Path $BackupDir ($base + '.dump.tmp')
$finalDump = Join-Path $BackupDir ($base + '.dump')
$finalJson = Join-Path $BackupDir ($base + '.json')

Write-Host "== Backing up database '$Database' (source: $Source) -> $BackupDir"
Write-Host "== base name: $base"

if (Test-Path -LiteralPath $finalDump) {
    Fail "Refusal: final backup '$finalDump' already exists. A valid backup is never silently overwritten."
}

# --- metadata: schema identity + representative counts (no sensitive data) ------
$meta = [ordered]@{
    created_at = (Get-Date).ToUniversalTime().ToString('o')
    format     = 'postgres-custom-compressed'
    tool       = 'pg_dump -Fc -Z 9'
    database   = $Database
    source     = $Source
    size_bytes = 0
    sha256     = ''
}

$script:MetadataSql = ("SELECT 'server_version_num', current_setting('server_version_num')" +
    " UNION ALL SELECT 'schema_version', COALESCE(v.version, 'none') FROM (SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1) v" +
    " UNION ALL SELECT 'schema_history_rows', COUNT(*)::text FROM flyway_schema_history" +
    " UNION ALL SELECT 'organizations', COUNT(*)::text FROM organizations" +
    " UNION ALL SELECT 'users', COUNT(*)::text FROM users" +
    " UNION ALL SELECT 'audit_logs', COUNT(*)::text FROM audit_logs" +
    " UNION ALL SELECT 'audit_chain_heads', COUNT(*)::text FROM audit_chain_heads" +
    " UNION ALL SELECT 'user_sessions', COUNT(*)::text FROM user_sessions" +
    " UNION ALL SELECT 'idempotency_records', COUNT(*)::text FROM idempotency_records" +
    " UNION ALL SELECT 'approval_requests', COUNT(*)::text FROM approval_requests" +
    " UNION ALL SELECT 'agents', COUNT(*)::text FROM agents")

$metaOut = Join-Path $env:TEMP ('neth-meta-' + [guid]::NewGuid().ToString('N') + '.out')
try {
    & $script:Runner -OutFile $metaOut -AsSql $true
    foreach ($ln in (Get-Content -LiteralPath $metaOut -ErrorAction SilentlyContinue)) {
        if ($ln -match '^([A-Za-z_]+)\|(.+)$') { $meta[$Matches[1]] = $Matches[2] }
    }
}
catch { Fail "Backup aborted while collecting metadata: $($_.Exception.Message)" }
finally { Remove-Item -LiteralPath $metaOut -Force -ErrorAction SilentlyContinue }

# --- dump -----------------------------------------------------------------------
try {
    & $script:Runner -OutFile $tempDump -AsSql $false
}
catch {
    if (-not $NoTempCleanupOnError -and (Test-Path -LiteralPath $tempDump)) {
        Remove-Item -LiteralPath $tempDump -Force -ErrorAction SilentlyContinue
    }
    Fail "Backup failed: $($_.Exception.Message)"
}

# --- verify ---------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $tempDump)) {
    Fail 'Backup verification failed: no output file was produced. (temp file absent)'
}
$file = Get-Item -LiteralPath $tempDump
if ($file.Length -le 0) {
    if (-not $NoTempCleanupOnError) { Remove-Item -LiteralPath $tempDump -Force -ErrorAction SilentlyContinue }
    Fail 'Backup verification failed: output file is empty.'
}
$meta['size_bytes'] = $file.Length

$bytes = [System.IO.File]::ReadAllBytes($tempDump)
if ($bytes.Length -lt 5 -or
    -not ([char]$bytes[0] -eq 'P' -and [char]$bytes[1] -eq 'G' -and [char]$bytes[2] -eq 'D' -and
          [char]$bytes[3] -eq 'M' -and [char]$bytes[4] -eq 'P')) {
    if (-not $NoTempCleanupOnError) { Remove-Item -LiteralPath $tempDump -Force -ErrorAction SilentlyContinue }
    Fail 'Backup verification failed: output is not a PostgreSQL custom-format dump (missing PGDMP magic).'
}

$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $fs = [System.IO.File]::OpenRead($tempDump)
    try { $hash = [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '').ToLowerInvariant() }
    finally { $fs.Dispose() }
}
finally { $sha.Dispose() }
if (-not $hash -or $hash.Length -ne 64) {
    if (-not $NoTempCleanupOnError) { Remove-Item -LiteralPath $tempDump -Force -ErrorAction SilentlyContinue }
    Fail 'Backup verification failed: could not compute the SHA-256 of the output.'
}
$meta['sha256'] = $hash

$structOk = $false; $structMsg = ''
$structOk, $structMsg = Test-ValidateDump -DumpFile $tempDump
if (-not $structOk) {
    if (-not $NoTempCleanupOnError) { Remove-Item -LiteralPath $tempDump -Force -ErrorAction SilentlyContinue }
    Fail "Backup verification failed: $structMsg"
}
Write-Host "== verified: size=$($meta['size_bytes']) bytes, sha256=$hash, $structMsg"

# --- atomic publication ----------------------------------------------------------
# Same-volume rename (ASCII name, no collision possible with the guard above).
[System.IO.File]::Move($tempDump, $finalDump)
$script:Published = $finalDump

$jsonBytes = [System.Text.Encoding]::UTF8.GetBytes(($meta | ConvertTo-Json))
[System.IO.File]::WriteAllBytes($finalJson, $jsonBytes)

Write-Host "== backup published: $finalDump"
Write-Host "== manifest published: $finalJson"

# Best-effort restrictive permissions (Windows: current user only; POSIX: 0600).
try {
    if ($PSVersionTable.Platform -eq 'Win32NT' -or [Environment]::OSVersion.Platform.ToString().StartsWith('Win')) {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        foreach ($p in @($finalDump, $finalJson)) {
            & icacls $p /inheritance:r /grant:r ("*{0}:F" -f $sid) 2>$null | Out-Null
        }
    }
    else {
        foreach ($p in @($finalDump, $finalJson)) { & chmod 600 $p 2>$null }
    }
} catch { Write-Host "  (warning) could not apply restrictive permissions: $($_.Exception.Message)" }

# --- retention pass ---------------------------------------------------------------
Invoke-PrunePass

Write-Host '== backup complete'
exit 0