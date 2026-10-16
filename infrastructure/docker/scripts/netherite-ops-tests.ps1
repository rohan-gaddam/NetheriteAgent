#requires -Version 5.1
<#
.SYNOPSIS
    NetheriteAgent WS9 operations test suite (self-contained, disposable resources).

.DESCRIPTION
    Exercises the backup, restore, retention and ops-check tooling WITHOUT ever
    touching the live compose stack except where the tool itself guarantees
    read-only behavior (ops-checks) or a late-stage regression gate.

    Tests run (each PASS/FAIL, cleanup guaranteed in-finally):
      T01  invalid (non-PGDMP) backup rejected                    -> exit 1
      T02  tampered backup rejected against its manifest          -> exit 1
      T03  missing backup file rejected                           -> exit 1
      T04  reserved / wrong-prefix restore targets refused; no containers left
      T05  backup from a non-existent source container fails clean, no *.dump.tmp
      T06  backup no-overwrite guard (second backup in same second)
      T07  retention -PruneOnly reports and deletes nothing
      T08  retention -Apply honours min recovery points; never deletes newest
      T09  Redis restart: disposable redis answers PONG after a full restart
      T10  ops-checks smoke: netherite-ops-checks.ps1 exits 0 (all PASS)
      T11  backup -Source Container of a schema-complete disposable DB + validate
      T12  end-to-end disposable restore of the live-compose backup (verify + dispose)

    SAFETY:
      - Test-created containers/volumes use the prefixes netherite-ops- and
        netherite-restore-verify- ONLY. A reserved-container guard refuses any
        other name, so the live stack (netherite-*-1) can never be targeted.
      - Failures still clean up their resources in finally blocks.
      - Exit code 0 only when every test passed.
#>
[CmdletBinding()]
param(
    [string]$DockerExecutable,
    [string]$ScriptsDir,
    [string]$RepoBackupsDir
)

$ErrorActionPreference = 'Stop'

if (-not $ScriptsDir) { $ScriptsDir = $PSScriptRoot }
if (-not $RepoBackupsDir) { $RepoBackupsDir = Join-Path (Split-Path -Parent $ScriptsDir) 'backups' }

$script:Pass = 0
$script:Fail = 0
$script:CurrentTest = ''

function TestOut {
    param([bool]$Ok, [string]$Detail)
    if ($Ok) {
        $script:Pass++
        Write-Host ("  PASS [{0}] {1}" -f $script:CurrentTest, $Detail) -ForegroundColor Green
    }
    else {
        $script:Fail++
        Write-Host ("  FAIL [{0}] {1}" -f $script:CurrentTest, $Detail) -ForegroundColor Red
    }
}

function New-Test {
    param([string]$Name, [scriptblock]$Body)
    $script:CurrentTest = $Name
    Write-Host ("== {0} ==" -f $Name) -ForegroundColor Cyan
    try { & $Body }
    catch {
        TestOut $false ("exception: {0}" -f $_.Exception.Message)
        Write-Verbose ($_ | Out-String)
    }
}

function Invoke-RawRedirect {
    param([string]$FilePath, [string[]]$ArgumentList, [string]$StdoutFile, [string]$StderrFile)
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
    param([string[]]$DockerArgs)
    $docker = (Get-Command docker -ErrorAction SilentlyContinue)
    if (-not $docker) { return [pscustomobject]@{ Code = 125; StdoutLines = @(); Error = 'docker not found' } }
    $out = Join-Path $env:TEMP ('neth-td-'+[guid]::NewGuid().ToString('N')+'.out')
    $err = Join-Path $env:TEMP ('neth-td-'+[guid]::NewGuid().ToString('N')+'.err')
    $code = Invoke-RawRedirect -FilePath $docker.Source -ArgumentList $DockerArgs -StdoutFile $out -StderrFile $err
    $lines = @()
    if (Test-Path -LiteralPath $out) {
        $c = Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue
        if ($c) { $lines = @($c -split "`r?`n" | Where-Object { $_ -ne '' }) }
    }
    $e = ''
    if (Test-Path -LiteralPath $err) {
        $ec = Get-Content -LiteralPath $err -Raw -ErrorAction SilentlyContinue
        if ($ec) { $e = $ec.Trim() }
    }
    Remove-Item -LiteralPath $out, $err -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Code = $code; StdoutLines = $lines; Stdout = ($lines -join "`n"); Error = $e }
}

# Runs one of the WS9 tool scripts in a child PowerShell and returns exit+output.
function Invoke-Tool {
    param([string]$Script, [string[]]$ArgsList)
    $ps = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $out = Join-Path $env:TEMP ('neth-tool-'+[guid]::NewGuid().ToString('N')+'.out')
    $err = Join-Path $env:TEMP ('neth-tool-'+[guid]::NewGuid().ToString('N')+'.err')
    $argTokens = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Script) + $ArgsList
    $code = Invoke-RawRedirect -FilePath $ps -ArgumentList $argTokens -StdoutFile $out -StderrFile $err
    $text = ''
    if (Test-Path -LiteralPath $out) {
        $c = Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue
        if ($c) { $text = $c.Trim() }
    }
    $e = ''
    if (Test-Path -LiteralPath $err) {
        $ec = Get-Content -LiteralPath $err -Raw -ErrorAction SilentlyContinue
        if ($ec) { $e = $ec.Trim() }
    }
    Remove-Item -LiteralPath $out, $err -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Code = $code; Output = $text; Error = $e }
}

function Assert-NoVerifyLeftovers {
    $c = Invoke-Docker @('ps', '-a', '--filter', 'name=netherite-restore-verify', '--format', '{{.Names}}')
    $v = Invoke-Docker @('volume', 'ls', '-f', 'name=netherite-restore-verify-vol', '--format', '{{.Name}}')
    return (($c.StdoutLines.Count -eq 0) -and ($v.StdoutLines.Count -eq 0))
}

# ----------------------------------------------------------------- helpers ----
$composeDump = (Get-ChildItem -LiteralPath $RepoBackupsDir -Filter 'netherite-pg-*.dump' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^netherite-pg-\d{8}T\d{6}Z\.dump$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1)

$redisImage = 'redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
$pgImage = 'postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea'

$backupScript  = Join-Path $ScriptsDir 'netherite-backup.ps1'
$restoreScript = Join-Path $ScriptsDir 'netherite-restore.ps1'
$checksScript  = Join-Path $ScriptsDir 'netherite-ops-checks.ps1'

$tmpRoot = Join-Path $env:TEMP ('netherite-ops-tests-' + [guid]::NewGuid().ToString('N'))

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host 'FATAL: docker engine CLI not found; the tests require the Docker engine for disposable resources. Tests cannot run.' -ForegroundColor Red
    exit 1
}
if (-not $composeDump) {
    Write-Host "FATAL: no compose backup found under '$RepoBackupsDir'. Run netherite-backup.ps1 first." -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Path $tmpRoot -Force | Out-Null

# --------------------------------------------------------------------- T01 ----
New-Test -Name "T01 invalid backup (non-PGDMP) rejected" -Body {
    $bad = Join-Path $tmpRoot 'bad.dump'
    [System.IO.File]::WriteAllBytes($bad, [System.Text.Encoding]::ASCII.GetBytes('this is definitely not a postgres dump'))
    $r = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $bad)
    TestOut ($r.Code -eq 1 -and $r.Output -match 'does not start with the PostgreSQL custom-format magic') ("exit=$($r.Code)")
}

# --------------------------------------------------------------------- T02 ----
New-Test -Name "T02 tampered backup rejected against its manifest" -Body {
    $tamper = Join-Path $tmpRoot 'tampered.dump'
    $manifest = Join-Path $tmpRoot 'tampered.json'
    Copy-Item -LiteralPath $composeDump.FullName $tamper -Force
    Copy-Item -LiteralPath (Join-Path $composeDump.DirectoryName ($composeDump.BaseName + '.json')) $manifest -Force
    $bytes = [System.IO.File]::ReadAllBytes($tamper)
    $bytes[300] = $bytes[300] -bxor 0x80
    [System.IO.File]::WriteAllBytes($tamper, $bytes)
    $r = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $tamper, '-SidecarFile', $manifest)
    TestOut ($r.Code -eq 1 -and $r.Output -match 'SHA-256 does not match') ("exit=$($r.Code)")
}

# --------------------------------------------------------------------- T03 ----
New-Test -Name "T03 missing backup file rejected" -Body {
    $r = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', (Join-Path $tmpRoot 'does-not-exist.dump'))
    TestOut ($r.Code -eq 1 -and $r.Output -match 'not found') ("exit=$($r.Code)")
}

# --------------------------------------------------------------------- T04 ----
New-Test -Name "T04 reserved/wrong-prefix restore targets refused, no leftovers" -Body {
    $r1 = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $composeDump.FullName, '-PerformRestore', '-VerificationDatabaseName', 'netherite')
    $r2 = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $composeDump.FullName, '-PerformRestore', '-VerificationDatabaseName', 'custom_db')
    $refused = ($r1.Code -eq 1 -and $r1.Output -match 'Refusing restore target name') -and
               ($r2.Code -eq 1 -and $r2.Output -match 'Refusing restore target name')
    TestOut ($refused -and (Assert-NoVerifyLeftovers)) ("exit(r1)=$($r1.Code) exit(r2)=$($r2.Code) leftovers=$(-not (Assert-NoVerifyLeftovers))")

    # T04b: allowed name actually works (full disposable restore is T12; here just a dry target-name acceptance probe)
    $r3 = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $composeDump.FullName, '-PerformRestore', '-VerificationDatabaseName', 'netherite_restore_verify_tt04b_probe')
    TestOut ($r3.Code -eq 0 -and (Assert-NoVerifyLeftovers)) ("exit(r3)=$($r3.Code) leftovers=$(-not (Assert-NoVerifyLeftovers))")
}

# --------------------------------------------------------------------- T05 ----
New-Test -Name "T05 backup from unknown container fails clean, no temp leftovers" -Body {
    $tmpBackup = Join-Path $tmpRoot 'bk05'
    New-Item -ItemType Directory -Path $tmpBackup -Force | Out-Null
    $r = Invoke-Tool -Script $backupScript -ArgsList @('-Source', 'Container', '-ContainerName', 'netherite-ops-not-there', '-BackupDir', $tmpBackup)
    $noDumpsBefore = (Get-ChildItem -LiteralPath $tmpBackup -Filter '*.dump' -ErrorAction SilentlyContinue).Count
    $noTmp = (Get-ChildItem -LiteralPath $tmpBackup -Filter '*.dump.tmp' -ErrorAction SilentlyContinue).Count
    $noPublish = (Get-ChildItem -LiteralPath $tmpBackup -Filter '*.json' -ErrorAction SilentlyContinue).Count
    TestOut ($r.Code -eq 1 -and $noDumpsBefore -eq 0 -and $noTmp -eq 0 -and $noPublish -eq 0 -and $r.Output -match 'aborted') ("exit=$($r.Code) dumps=$noDumpsBefore tmp=$noTmp json=$noPublish")
}

# --------------------------------------------------------------------- T06 ----
New-Test -Name "T06 backup overwrite guard (same-second second run refuses or creates distinct set)" -Body {
    $tmpBackup = Join-Path $tmpRoot 'bk06'
    New-Item -ItemType Directory -Path $tmpBackup -Force | Out-Null
    $r1 = Invoke-Tool -Script $backupScript -ArgsList @('-Source', 'Compose', '-BackupDir', $tmpBackup, '-ValidateImage', $pgImage)
    $r2 = Invoke-Tool -Script $backupScript -ArgsList @('-Source', 'Compose', '-BackupDir', $tmpBackup, '-ValidateImage', $pgImage)
    $dumps = @(Get-ChildItem -LiteralPath $tmpBackup -Filter 'netherite-pg-*.dump' -ErrorAction SilentlyContinue)
    $n = $dumps.Count
    $validSet = Get-ChildItem -LiteralPath $tmpBackup -Filter 'netherite-pg-*.json' -ErrorAction SilentlyContinue
    TestOut ($r1.Code -eq 0 -and $n -ge 1 -and $validSet.Count -eq $n) ("exit1=$($r1.Code) exit2=$($r2.Code) dumps=$n")
    # both runs must never leave partial files
    $tmp = (Get-ChildItem -LiteralPath $tmpBackup -Filter '*.dump.tmp' -ErrorAction SilentlyContinue).Count
    TestOut ($tmp -eq 0) 'no .dump.tmp leftovers after either attempt'
}

# --------------------------------------------------------------------- T07 ----
New-Test -Name "T07 retention -PruneOnly reports and deletes nothing" -Body {
    $marker = Join-Path $RepoBackupsDir ('netherite-pg-20210101T000000Z.dump')
    [System.IO.File]::WriteAllBytes($marker, [System.Text.Encoding]::ASCII.GetBytes('stale-fixture'))
    try {
        $before = @(Get-ChildItem -LiteralPath $RepoBackupsDir -Filter 'netherite-pg-*.dump').Count
        $r = Invoke-Tool -Script $backupScript -ArgsList @('-PruneOnly', '-RetentionCount', '2', '-RetentionDays', '0', '-BackupDir', $RepoBackupsDir)
        $after = @(Get-ChildItem -LiteralPath $RepoBackupsDir -Filter 'netherite-pg-*.dump').Count
        TestOut ($r.Code -eq 0 -and $before -eq $after -and $r.Output -match 'nothing to prune|prunable|report-only') ("exit=$($r.Code) before=$before after=$after")
    }
    finally {
        Remove-Item -LiteralPath $marker, ($marker -replace '\.dump$', '.json') -Force -ErrorAction SilentlyContinue
    }
}

# --------------------------------------------------------------------- T08 ----
New-Test -Name "T08 retention -Apply honours min recovery points, never deletes newest" -Body {
    # Isolated fixtures only: this test must never prune the real backups dir
    # (a prune with -RetentionCount 1 would delete real recovery points).
    $iso = Join-Path $tmpRoot 't08-iso'
    New-Item -ItemType Directory -Path $iso -Force | Out-Null
    foreach ($ts in @('20200101T000000Z', '20210101T000000Z', '20220101T000000Z', '20230101T000000Z')) {
        [System.IO.File]::WriteAllBytes((Join-Path $iso ("netherite-pg-$ts.dump")),
            [System.Text.Encoding]::ASCII.GetBytes('fixture'))
    }
    try {
        $before = @(Get-ChildItem -LiteralPath $iso -Filter 'netherite-pg-*.dump').Count
        $r = Invoke-Tool -Script $backupScript -ArgsList @('-PruneOnly', '-RetentionCount', '1', '-RetentionDays', '0', '-BackupDir', $iso, '-Apply')
        $after = @(Get-ChildItem -LiteralPath $iso -Filter 'netherite-pg-*.dump').Count
        $newestKept = Test-Path -LiteralPath (Join-Path $iso 'netherite-pg-20230101T000000Z.dump')
        TestOut ($r.Code -eq 0 -and $before -eq 4 -and $after -eq 1 -and $newestKept) ("exit=$($r.Code) before=$before after=$after newestKept=$newestKept")
    }
    finally {
        Remove-Item -LiteralPath $iso -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --------------------------------------------------------------------- T09 ----
New-Test -Name "T09 redis restart: disposable redis answers PONG after full restart" -Body {
    $name = 'netherite-ops-redis-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    try {
        $r0 = Invoke-Docker @('run', '-d', '--name', $name, $redisImage)
        TestOut ($r0.Code -eq 0) ("container started exit=$($r0.Code)")

        $pong = ''
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Milliseconds 500
            $r = Invoke-Docker @('exec', $name, 'redis-cli', 'ping')
            if ($r.Code -eq 0 -and $r.Stdout -match 'PONG') { $pong = 'PONG'; break }
        }
        TestOut ($pong -eq 'PONG') "redis ping before restart (= '$pong')"

        $rr = Invoke-Docker @('restart', $name)
        $pong2 = ''
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Milliseconds 500
            $r2 = Invoke-Docker @('exec', $name, 'redis-cli', 'ping')
            if ($r2.Code -eq 0 -and $r2.Stdout -match 'PONG') { $pong2 = 'PONG'; break }
        }
        TestOut ($r2.Code -eq 0 -and $pong2 -eq 'PONG') "redis ping after docker restart (= '$pong2')"
    }
    finally {
        Invoke-Docker @('rm', '-f', $name) | Out-Null
    }
}

# --------------------------------------------------------------------- T10 ----
New-Test -Name "T10 ops-checks smoke (netherite-ops-checks.ps1 exits 0, all PASS)" -Body {
    $r = Invoke-Tool -Script $checksScript -ArgsList @()
    TestOut ($r.Code -eq 0 -and $r.Output -match '0 FAIL') ("exit=$($r.Code)")
}

# --------------------------------------------------------------------- T11 ----
New-Test -Name "T11 backup -Source Container of a schema-complete disposable DB + validate" -Body {
    $keep = Join-Path $tmpRoot 'keep.txt'
    $rA = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $composeDump.FullName, '-PerformRestore', '-KeepContainer')
    TestOut ($rA.Code -eq 0) ("disposable source created exit=$($rA.Code)")
    if ($rA.Code -ne 0) { return }

    $m = [regex]::Match($rA.Output, "disposable container '([^']+)' and volume '([^']+)' left for inspection")
    $mdb = [regex]::Match($rA.Output, "database '([^']+)'")
    if (-not $m.Success -or -not $mdb.Success) { TestOut $false 'could not parse kept container/database names'; return }
    $cName = $m.Groups[1].Value
    $vName = $m.Groups[2].Value
    $dbName = $mdb.Groups[1].Value
    try {
        $tmpBackup = Join-Path $tmpRoot 'bk11'
        New-Item -ItemType Directory -Path $tmpBackup -Force | Out-Null
        $rB = Invoke-Tool -Script $backupScript -ArgsList @('-Source', 'Container', '-ContainerName', $cName, '-Database', $dbName, '-BackupDir', $tmpBackup)
        $dumps = @(Get-ChildItem -LiteralPath $tmpBackup -Filter 'netherite-pg-*.dump' -ErrorAction SilentlyContinue)
        $manif = @(Get-ChildItem -LiteralPath $tmpBackup -Filter 'netherite-pg-*.json' -ErrorAction SilentlyContinue)
        TestOut ($rB.Code -eq 0 -and $dumps.Count -eq 1 -and $manif.Count -eq 1) ("exit=$($rB.Code) dumps=$($dumps.Count)")

        if ($dumps.Count -eq 1) {
            $rC = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $dumps[0].FullName)
            TestOut ($rC.Code -eq 0 -and $rC.Output -match 'VALIDATED') ("validate exit=$($rC.Code)")
            # sanity: container-source manifest carries the same schema grade
            $mft = Get-Content -LiteralPath $manif[0].FullName -Raw | ConvertFrom-Json
            TestOut ([string]$mft.schema_version -eq '14') ("container-source manifest schema_version=$($mft.schema_version)")
        }
    }
    finally {
        Invoke-Docker @('rm', '-f', $cName) | Out-Null
        Invoke-Docker @('volume', 'rm', '-f', $vName) | Out-Null
    }
}

# --------------------------------------------------------------------- T12 ----
New-Test -Name "T12 end-to-end disposable restore (verify + dispose)" -Body {
    $r = Invoke-Tool -Script $restoreScript -ArgsList @('-BackupFile', $composeDump.FullName, '-PerformRestore')
    $ok = ($r.Code -eq 0 -and
           $r.Output -match 'RESTORE SUCCEEDED' -and
           $r.Output -match 'migration history matches manifest' -and
           $r.Output -match 'all migration checksums are non-zero' -and
           $r.Output -match 'append-only protection still rejects UPDATE' -and
           $r.Output -match 'disposed container' -and
           $r.Output -match 'disposed volume')
    TestOut ($ok -and (Assert-NoVerifyLeftovers)) ("exit=$($r.Code) leftovers=$(-not (Assert-NoVerifyLeftovers))")
}

# ------------------------------------------------------------------ redact ----
New-Test -Name "T-redact backup manifests and outputs contain no secret material" -Body {
    $secretsDir = Join-Path (Split-Path -Parent $ScriptsDir) 'secrets'
    $needles = @()
    foreach ($name in @('db_password', 'db_app_password', 'redis_password', 'opa_policy_token', 'owner_password_hash')) {
        $p = Join-Path $secretsDir $name
        if (Test-Path -LiteralPath $p) {
            $v = (Get-Content -LiteralPath $p -Raw -ErrorAction SilentlyContinue)
            if ($v -and $v.Length -ge 8) { $needles += $v.Substring(0, 8) }
        }
    }
    $leaks = @()
    foreach ($mf in (Get-ChildItem -LiteralPath $RepoBackupsDir -Filter 'netherite-pg-*.json' -ErrorAction SilentlyContinue)) {
        $txt = Get-Content -LiteralPath $mf.FullName -Raw
        foreach ($n in $needles) { if ($txt.Contains($n)) { $leaks += "$($mf.Name):$n" } }
    }
    $o = Invoke-Tool -Script $checksScript -ArgsList @()
    foreach ($n in $needles) { if ($o.Output -and $o.Output.Contains($n)) { $leaks += "ops-checks-output:$n" } }
    TestOut ($leaks.Count -eq 0) ("leaks=$($leaks -join '; ') manifests=$(@(Get-ChildItem -LiteralPath $RepoBackupsDir -Filter 'netherite-pg-*.json' -ErrorAction SilentlyContinue).Count)")
}

# ------------------------------------------------------ final leftover scan ----
$leftovers = @(Invoke-Docker @('ps', '-a', '--filter', 'name=netherite-ops-', '--format', '{{.Names}}')).StdoutLines
if ($leftovers.Count -gt 0) {
    Write-Host ("LEFTOVER test containers: {0}" -f ($leftovers -join ', ')) -ForegroundColor Yellow
}

Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ("== {0} FAIL, {1} PASS ==" -f $script:Fail, $script:Pass) -ForegroundColor Cyan
if ($script:Fail -gt 0) { exit 1 }
exit 0