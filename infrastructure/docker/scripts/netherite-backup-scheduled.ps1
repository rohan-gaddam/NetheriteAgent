#requires -Version 5.1
<#
.SYNOPSIS
    Scheduled-task entry point for the daily NetheriteAgent production backup.

.DESCRIPTION
    Executes the existing netherite-backup.ps1 (Compose postgres source) and,
    only on backup success, applies the retention prune pass keeping the newest
    14 verified recovery points. All output is timestamped into a persistent
    log; the backup script's exit code propagates so scheduler failures are
    visible (Task Scheduler Last Run Result + log RESULT line + stale-backup
    ops-checks failure). A failed backup never reaches the prune step, so the
    previous recovery points are untouched.

    Retention configuration (actually enforced, not merely documented):
      -RetentionCount 14 is passed explicitly below. RetentionDays keeps its
    script default (14). The prune runs only after the new backup is published
    and verified, never deletes the newest backup, and never deletes below the
    minimum count.

    Testability: -ComposeDir / -BackupDir override the backup script's targets,
    so the failure path can be exercised safely (e.g. a nonexistent ComposeDir
    fails fast with exit 1 and creates nothing).
#>
[CmdletBinding()]
param(
    [string]$LogDir,
    [string]$ComposeDir,
    [string]$BackupDir
)

$ErrorActionPreference = 'Stop'

$scriptsDir = $PSScriptRoot
if (-not $LogDir) { $LogDir = Join-Path (Split-Path -Parent $scriptsDir) 'backups\logs' }
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$logFile = Join-Path $LogDir 'netherite-backup-schedule.log'

function Write-TaskLog {
    param([string]$Message)
    Add-Content -LiteralPath $logFile -Value ("[{0:yyyy-MM-dd HH:mm:ss}] {1}" -f [DateTime]::Now, $Message)
}

Write-TaskLog '===== scheduled backup run started ====='

$backupArgs = @{ Apply = $true; RetentionCount = 14 }
if ($ComposeDir) { $backupArgs['ComposeDir'] = $ComposeDir }
if ($BackupDir)  { $backupArgs['BackupDir'] = $BackupDir }

# A terminating error inside the backup script must still produce a RESULT
# line and a non-zero exit (not an unwritten crash): catch it explicitly.
$lines = @()
$code = 0
try {
    $lines = & (Join-Path $scriptsDir 'netherite-backup.ps1') @backupArgs 2>&1
    $code = $LASTEXITCODE
}
catch {
    $lines += $_.Exception.Message
    $code = 1
}
foreach ($l in $lines) { Write-TaskLog ('  ' + [string]$l) }

if ($code -eq 0) {
    Write-TaskLog 'RESULT: SUCCESS (backup published and verified; retention pruned to newest 14)'
}
else {
    Write-TaskLog ("RESULT: FAILURE (exit {0}) - prune step never ran; previous recovery points untouched" -f $code)
}
exit $code
