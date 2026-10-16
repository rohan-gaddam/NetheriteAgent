#requires -Version 5.1
<#
.SYNOPSIS
    NetheriteAgent PostgreSQL restore + verification workflow.

.DESCRIPTION
    Two distinct modes:

      VALIDATE (default)
        Validates a backup WITHOUT touching any database: file exists, non-empty,
        PostgreSQL custom-format (PGDMP) magic, SHA-256 matches the sidecar
        manifest, and pg_restore --list (in a short-lived disposable container)
        can read the archive with storable content. Exits 0 -> "validated";
        exits 1 -> the backup must not be trusted.

      RESTORE (-PerformRestore)
        Restores the validated backup into a FRESHLY CREATED disposable
        PostgreSQL container (never a running/live database) and verifies the
        restored database: schema version + migration history (checksums non-
        zero), representative application data counts against the manifest, the
        audit append-only trigger and V9 constraint, and a negative append-only
        probe. The disposable container and volume are always removed afterwards
        (unless -KeepContainer for debugging), including on failure.

    SAFETY (why a live database can never be a restore target):
      - -PerformRestore is explicitly required; without it nothing is restored.
      - The restore target is always a brand-new disposable container with a
        database that lives inside it. There is no parameter to point the
        restore at a running PostgreSQL instance, compose service, or host port.
      - The disposable database name must match the strict prefix
        `netherite_restore_verify_` and must NOT be in the reserved set of
        existing/known database names (netherite, netherite_verify,
        netherite_upgrade, netherite_distributed, netherite_ws4,
        netherite_ws4b, postgres, template0, template1). Any other value is
        refused.
      - Validation and restoration outputs are sanitized (no secrets, no
        connection details beyond names).

    Out-of-scope here (documented, never claimed): encryption of backups, off-
    host durability, scheduled restore tests, and restoring into the live
    production database during a real incident (that procedure lives in
    docs/operations-and-recovery.md and uses the SAME disposable verification
    flow first).

.EXAMPLE
    & .\netherite-restore.ps1 -BackupFile .\backups\netherite-pg-20261010T073309Z.dump
    # validate only (no database touched)

    & .\netherite-restore.ps1 -BackupFile .\backups\netherite-pg-20261010T073309Z.dump -PerformRestore
    # restore into a disposable container, verify, dispose
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,

    [string]$SidecarFile,

    [switch]$PerformRestore,      # without this switch: validate only
    [switch]$KeepContainer,       # debugging: do not remove the disposable container/volume

    [string]$Image = 'postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea',
    [string]$DockerExecutable,
    [string]$VerificationDatabaseName
)

$ErrorActionPreference = 'Stop'

if (-not $SidecarFile) { $SidecarFile = $BackupFile -replace '\.dump$', '.json' }

$script:Pass = 0
$script:FailCount = 0

function Pass { param([string]$Message) $script:Pass++; Write-Host ("PASS: {0}" -f $Message) -ForegroundColor Green }
function FailLine { param([string]$Message) $script:FailCount++; Write-Host ("FAIL: {0}" -f $Message) -ForegroundColor Red }
function Fail {
    param([string]$Message)
    FailLine $Message
    Write-Host "ERROR: restore workflow did not complete." -ForegroundColor Red
    exit 1
}

function Resolve-Docker {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $cmd) { Fail 'docker is not available; the restore workflow requires the Docker engine for its disposable verification container.' }
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

# Runs docker with an argument list (quoting handled for spaces) and returns
# exit code; optionally captures stdout lines.
function Invoke-Docker {
    param([string[]]$DockerArgs, [string]$StdoutFile = $null, [string]$StderrFile = $null)
    $docker = & { $d = Resolve-Docker; $d }
    $outFile = if ($StdoutFile) { $StdoutFile } else { Join-Path $env:TEMP ('neth-d-'+[guid]::NewGuid().ToString('N')+'.out') }
    $errFile = if ($StderrFile) { $StderrFile } else { Join-Path $env:TEMP ('neth-d-'+[guid]::NewGuid().ToString('N')+'.err') }
    $code = Invoke-RawRedirect -FilePath $docker -ArgumentList $DockerArgs -StdoutFile $outFile -StderrFile $errFile
    $out = $null
    if (Test-Path -LiteralPath $outFile) { $out = Get-Content -LiteralPath $outFile }
    if (-not $StdoutFile) { Remove-Item -LiteralPath $outFile,$errFile -Force -ErrorAction SilentlyContinue }
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

function Get-Sidecar {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return $null }
    try { return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json) } catch { return $null }
}

# --------------------------------------------------------------------- steps --
function Invoke-Validation {
    param([string]$DumpFile)
    Write-Host "== validating backup: $DumpFile"

    if (-not (Test-Path -LiteralPath $DumpFile)) { Fail "Backup file not found: '$DumpFile'." }
    $file = Get-Item -LiteralPath $DumpFile
    if ($file.Length -le 0) { Fail 'Backup file is empty.' }
    Pass "backup exists and is non-empty ($($file.Length) bytes)"

    $bytes = [System.IO.File]::ReadAllBytes($DumpFile)
    if ($bytes.Length -lt 5 -or
        -not ([char]$bytes[0] -eq 'P' -and [char]$bytes[1] -eq 'G' -and [char]$bytes[2] -eq 'D' -and
              [char]$bytes[3] -eq 'M' -and [char]$bytes[4] -eq 'P')) {
        Fail 'File does not start with the PostgreSQL custom-format magic (PGDMP) - not a pg_dump custom archive.'
    }
    Pass 'PostgreSQL custom-format magic (PGDMP) present'

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $fs = [System.IO.File]::OpenRead($DumpFile)
        try { $hash = [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '').ToLowerInvariant() }
        finally { $fs.Dispose() }
    }
    finally { $sha.Dispose() }

    $sidecar = Get-Sidecar ($SidecarFile)
    if ($sidecar -and $sidecar.sha256) {
        if ($sidecar.sha256 -ne $hash) {
            Fail ("SHA-256 does not match the sidecar manifest ({0}); the backup or manifest was tampered with or corrupted." -f $sidecar.sha256)
        }
        Pass ("SHA-256 matches the sidecar manifest ({0}...)" -f $hash.Substring(0, 12))
    }
    else {
        Write-Host "  (info) no sidecar manifest available; tracking SHA-256 only: $hash"
    }

    # Structural readability with the target-version pg_restore --list using a
    # short-lived disposable container (no server touched).
    $docker = Resolve-Docker
    $listOut = Join-Path $env:TEMP ('neth-pgr-'+[guid]::NewGuid().ToString('N')+'.out')
    $listErr = Join-Path $env:TEMP ('neth-pgr-'+[guid]::NewGuid().ToString('N')+'.err')
    $code = Invoke-RawRedirect -FilePath $docker -ArgumentList @(
        'run', '--rm', '-v', ("{0}:/tmp/verify.dump:ro" -f (Resolve-Path -LiteralPath $DumpFile).Path),
        '--entrypoint', 'pg_restore', $Image, '--list', '/tmp/verify.dump'
    ) -StdoutFile $listOut -StderrFile $listErr
    if ($code -ne 0) {
        $msg = (Read-SanitizedOutput -Path $listErr -SecretNeedles (Get-RedactionNeedles))
        Remove-Item -LiteralPath $listOut,$listErr -Force -ErrorAction SilentlyContinue
        Fail "pg_restore --list rejected the dump (exit $code): $msg"
    }
    $list = Get-Content -LiteralPath $listOut -ErrorAction SilentlyContinue | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    Remove-Item -LiteralPath $listOut,$listErr -Force -ErrorAction SilentlyContinue
    if (-not $list -or $list.Count -lt 1) { Fail 'pg_restore --list produced no entries: no storable content.' }
    Pass "pg_restore --list read the archive ($($list.Count) object entries)"

    return [pscustomobject]@{ Hash = $hash; Sidecar = $sidecar; ListCount = ($list.Count) }
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

function Invoke-DisposableRestore {
    param([string]$DumpFile, [pscustomobject]$Validated)

    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ').Replace(':', '')
    $dbName = if ($VerificationDatabaseName) { $VerificationDatabaseName } else { "netherite_restore_verify_$stamp" }

    # --- target safety -------------------------------------------------------
    $reserved = @('netherite','netherite_verify','netherite_upgrade','netherite_distributed',
                  'netherite_ws4','netherite_ws4b','postgres','template0','template1')
    if (-not $dbName.StartsWith('netherite_restore_verify_')) {
        Fail "Refusing restore target name '$dbName': the disposable verification database must use the prefix 'netherite_restore_verify_'."
    }
    if ($reserved -contains $dbName) {
        Fail "Refusing restore target name '$dbName': it is a reserved production/verification database name."
    }

    $container = 'netherite-restore-verify-' + $stamp.ToLower()
    $volume    = 'netherite-restore-verify-vol-' + $stamp.ToLower()

    Write-Host "== restoring into disposable container '$container' (database '$dbName', volume '$volume')"
    Write-Host "== NOTE: this never touches a running/live PostgreSQL instance."

    $rng = New-Object byte[] 16
    $r = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $r.GetBytes($rng) } finally { $r.Dispose() }
    $pw = ($rng | ForEach-Object { $_.ToString('x2') }) -join ''

    $created = $false
    $containerUp = $false
    try {
        # --- disposable target --------------------------------------------------
        $vc = Invoke-Docker -DockerArgs @('volume', 'create', $volume)
        if ($vc.Code -ne 0) { Fail "Failed to create disposable volume '$volume'." }

        $rc = Invoke-Docker -DockerArgs @('run', '-d', '--name', $container,
            '-e', ("POSTGRES_PASSWORD={0}" -f $pw), '-v', ("{0}:/var/lib/postgresql/data" -f $volume), $Image)
        if ($rc.Code -ne 0) { Fail "Failed to start disposable restore container (exit $($rc.Code))." }
        $created = $true

        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Seconds 1
            $pr = Invoke-Docker -DockerArgs @('exec', $container, 'pg_isready', '-U', 'postgres', '-d', 'postgres')
            if ($pr.Code -eq 0) { $ready = $true; break }
        }
        if (-not $ready) { Fail 'Disposable container did not become ready (pg_isready timeout).' }
        $containerUp = $true
        Pass 'disposable PostgreSQL container ready'

        # --- copy + pre-create reproducible roles + database -------------------
        Invoke-Docker -DockerArgs @('exec', $container, 'mkdir', '-p', '/backups') | Out-Null
        $cp = Invoke-Docker -DockerArgs @('cp', (Resolve-Path -LiteralPath $DumpFile).Path, ("{0}:/backups/restore.dump" -f $container))
        if ($cp.Code -ne 0) { Fail "Failed to copy backup into the disposable container (exit $($cp.Code))." }
        Pass 'backup copied into disposable container'

        foreach ($roleSql in @("CREATE ROLE netherite_admin LOGIN", "CREATE ROLE netherite_app LOGIN")) {
            $rq = Invoke-Docker -DockerArgs @('exec', $container, 'psql', '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', $roleSql)
            if ($rq.Code -ne 0) { Fail "Failed to pre-create role ($roleSql) in the disposable target." }
        }
        Pass "reproducible roles (netherite_admin, netherite_app) pre-created"

        $dd = Invoke-Docker -DockerArgs @('exec', $container, 'createdb', '-U', 'postgres', '-O', 'netherite_admin', $dbName)
        if ($dd.Code -ne 0) { Fail "Failed to create the disposable database '$dbName'." }
        Pass "disposable database '$dbName' created"

# --- restore -----------------------------------------------------------------
        $pgErr = Join-Path $env:TEMP ('neth-pgr-'+[guid]::NewGuid().ToString('N')+'.err')
        $dr = Invoke-Docker -DockerArgs @('exec', $container, 'pg_restore', '-U', 'postgres', '-d', $dbName,
            '--exit-on-error', '/backups/restore.dump') -StderrFile $pgErr
        $pgErrMsg = Read-SanitizedOutput -Path $pgErr -SecretNeedles (Get-RedactionNeedles)
        Remove-Item -LiteralPath $pgErr -Force -ErrorAction SilentlyContinue
        if ($dr.Code -ne 0) {
            Fail ("pg_restore failed (exit {0}). {1} The disposable target has been discarded; the source backup was NOT modified." -f $dr.Code, $pgErrMsg)
        }
        Pass 'pg_restore completed successfully into the disposable target'

        # --- verification (schema, history, data, audit integrity) --------------------
        $schemaVersion = Invoke-CheckScalar -Container $container -Db $dbName -Sql "SELECT COALESCE(version, 'none') FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
        $historyRows   = Invoke-CheckScalar -Container $container -Db $dbName -Sql 'SELECT COUNT(*) FROM flyway_schema_history'
        $badChecksums  = Invoke-CheckScalar -Container $container -Db $dbName -Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE checksum = 0 OR checksum IS NULL"

        if ($Validated.Sidecar) {
            $expectedVersion = [string]$Validated.Sidecar.schema_version
            $expectedRows    = [string]$Validated.Sidecar.schema_history_rows
            if ($schemaVersion -ne $expectedVersion) { Fail "Schema version mismatch after restore: expected $expectedVersion, found $schemaVersion." }
            if ($historyRows -ne $expectedRows) { Fail "Migration-history row count mismatch after restore: expected $expectedRows, found $historyRows." }
            Pass "migration history matches manifest (schema v$schemaVersion, $historyRows rows)"
        }
        else {
            Write-Host "  (info) no manifest; asserting non-empty migration history only (version=$schemaVersion, rows=$historyRows)"
            if ([int]$historyRows -lt 1) { Fail 'Migration history is empty after restore.' }
        }
        if ([int]$badChecksums -ne 0) { Fail "Found $badChecksums migration(s) with a zero/empty checksum after restore." }
        Pass 'all migration checksums are non-zero'

        # representative data against the manifest (authoritative when present)
        $countChecks = @('organizations','users','audit_logs','audit_chain_heads','user_sessions','idempotency_records','approval_requests','agents')
        if ($Validated.Sidecar) {
            foreach ($tbl in $countChecks) {
                $actual = Invoke-CheckScalar -Container $container -Db $dbName -Sql ("SELECT COUNT(*) FROM $tbl")
                $expected = [string]$Validated.Sidecar.$tbl
                if ($actual -ne $expected) {
                    Fail "Row-count mismatch after restore: $tbl expected $expected, found $actual."
                }
                Pass "restored $tbl row count ($actual) matches manifest"
            }
        }

        # audit integrity objects
        $triggerCount = Invoke-CheckScalar -Container $container -Db $dbName -Sql "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'trg_audit_append_only'"
        if ([int]$triggerCount -lt 1) { Fail 'The append-only trigger trg_audit_append_only is missing after restore.' }
        Pass 'append-only trigger trg_audit_append_only present'

        $uniqueCount = Invoke-CheckScalar -Container $container -Db $dbName -Sql "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='public' AND table_name='audit_logs' AND constraint_name='uk_audit_request_event' AND constraint_type='UNIQUE'"
        if ([int]$uniqueCount -lt 1) { Fail 'The V9 tenant-scoped audit uniqueness constraint is missing after restore.' }
        Pass 'tenant-scoped audit uniqueness constraint (uk_audit_request_event) present'

        $auditRows = Invoke-CheckScalar -Container $container -Db $dbName -Sql 'SELECT COUNT(*) FROM audit_logs'
        if ([int]$auditRows -ge 1) {
            # negative probe: append-only must still be enforced (trigger fires)
            $negOut = Join-Path $env:TEMP ('neth-neg-'+[guid]::NewGuid().ToString('N')+'.out')
            $negErr = Join-Path $env:TEMP ('neth-neg-'+[guid]::NewGuid().ToString('N')+'.err')
            $neg = Invoke-RawRedirect -FilePath (Resolve-Docker) -ArgumentList @(
                'exec', $container, 'psql', '-U', 'postgres', '-d', $dbName,
                '-c', 'UPDATE audit_logs SET event_type = event_type'
            ) -StdoutFile $negOut -StderrFile $negErr
            $msg = (Read-SanitizedOutput -Path $negErr -SecretNeedles (Get-RedactionNeedles)) + (Read-SanitizedOutput -Path $negOut -SecretNeedles (Get-RedactionNeedles))
            Remove-Item -LiteralPath $negOut,$negErr -Force -ErrorAction SilentlyContinue
            if ($neg.Code -eq 0) { Fail 'Append-only protection is NOT enforced after restore: UPDATE on audit_logs succeeded.' }
            if ($msg -notmatch 'append-only') { Fail "Append-only protection errored for the wrong reason after restore: $msg" }
            Pass 'append-only protection still rejects UPDATE on audit_logs (negative probe)'
        }

        Pass ('restore verification complete: schema v{0}, {1} migrations, {2} audit rows' -f $schemaVersion, $historyRows, $auditRows)
        Write-Host "== RESTORE SUCCEEDED into disposable target '$container' (database '$dbName')."
        Write-Host "== The source backup and any live database were NOT modified."
    }
    finally {
        if (-not $KeepContainer) {
            if ($created) {
                $rr = Invoke-Docker -DockerArgs @('rm', '-f', $container)
                if ($rr.Code -eq 0) { Write-Host "== disposed container: $container" }
            }
            $vr = Invoke-Docker -DockerArgs @('volume', 'rm', '-f', $volume)
            if ($vr.Code -eq 0) { Write-Host "== disposed volume: $volume" }
        }
        else {
            Write-Host "== -KeepContainer: disposable container '$container' and volume '$volume' left for inspection."
        }
    }
}

function Invoke-CheckScalar {
    param([string]$Container, [string]$Db, [string]$Sql)
    $out = Join-Path $env:TEMP ('neth-chk-'+[guid]::NewGuid().ToString('N')+'.out')
    $err = Join-Path $env:TEMP ('neth-chk-'+[guid]::NewGuid().ToString('N')+'.err')
    $r = Invoke-Docker -DockerArgs @('exec', $Container, 'psql', '-U', 'postgres', '-d', $Db, '-X', '-q', '-tA', '-c', $Sql) -StdoutFile $out -StderrFile $err
    $val = (Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue).Trim()
    Remove-Item -LiteralPath $out,$err -Force -ErrorAction SilentlyContinue
    if ($r.Code -ne 0) { Fail "Verification query failed (exit $($r.Code)): $((Read-SanitizedOutput -Path $err -SecretNeedles (Get-RedactionNeedles)))" }
    return $val
}

# -------------------------------------------------------------------- main ---
if (-not (Get-Item -LiteralPath $BackupFile -ErrorAction SilentlyContinue)) { Fail "Backup file not found: '$BackupFile'." }

$validated = Invoke-Validation -DumpFile $BackupFile
Write-Host "== VALIDATED: $($BackupFile) (sha256 $($validated.Hash.Substring(0,12))..., $($validated.ListCount) archive entries)."
Write-Host "== Validation only; no database was created, modified, or dropped."

if ($PerformRestore) {
    $containerUp = Invoke-DisposableRestore -DumpFile $BackupFile -Validated $validated
    exit 0
}

if ($script:FailCount -gt 0) { exit 1 }
exit 0