#requires -Version 5.1
<#
.SYNOPSIS
    Registers (or updates) the daily NetheriteAgent production backup task.

.DESCRIPTION
    Idempotent: if \NetheriteAgent\DailyBackup already exists it is updated in
    place (action, trigger, settings, principal); otherwise it is created. No
    duplicates are ever created.

    Schedule: daily at 02:00 production-host local time (Task Scheduler daily
    triggers evaluate in local time). Overlap prevention: a new instance is
    skipped while a previous run is still active (IgnoreNew). Missed starts
    (machine off/asleep) run as soon as possible afterwards; the machine may
    wake for the run. A 1-hour execution cap guards hung runs.

    Account: the current user, "run only when logged on" (interactive logon).
    No password is stored and no elevation is used: the backup needs only
    Docker access plus read/write inside the repository, and Docker Desktop on
    this host requires the interactive session anyway. A logged-off host fails
    the run loudly (non-zero exit + log line) instead of silently skipping.

    Failure visibility: the backup exit code becomes the task Last Run Result
    (non-zero on failure), every run appends timestamped lines plus a RESULT
    line to backups\logs\netherite-backup-schedule.log, and the next
    netherite-ops-checks run fails on stale backup. No email/external alerting
    is configured or claimed.

    Requires an elevated shell for Register/Set-ScheduledTask. Supports
    -WhatIf for dry-run validation of the task definition.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$TaskPath = '\NetheriteAgent\',
    [string]$TaskName = 'DailyBackup',
    [int]$Hour = 2,
    [int]$Minute = 0
)

$ErrorActionPreference = 'Stop'

$scriptsDir = $PSScriptRoot
$wrapper = Join-Path $scriptsDir 'netherite-backup-scheduled.ps1'
if (-not (Test-Path -LiteralPath $wrapper)) { throw "Backup wrapper not found: $wrapper" }

$psExe = Join-Path ([System.Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe'
$action = New-ScheduledTaskAction -Execute $psExe `
    -Argument ("-NoProfile -ExecutionPolicy Bypass -File `"{0}`"" -f $wrapper) `
    -WorkingDirectory $scriptsDir
$trigger = New-ScheduledTaskTrigger -Daily -At ([DateTime]::Today.AddHours($Hour).AddMinutes($Minute))
# Note: on-demand start is allowed by default; no parameter needed.
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -WakeToRun `
    -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 1)
$principal = New-ScheduledTaskPrincipal `
    -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) `
    -LogonType Interactive -RunLevel Limited
$task = New-ScheduledTask -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal `
    -Description 'NetheriteAgent daily production PostgreSQL backup (02:00 local) + retain newest 14 recovery points. See netherite-backup-scheduled.ps1.'

$existing = Get-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) {
    if ($PSCmdlet.ShouldProcess("$TaskPath$TaskName", 'Update scheduled task')) {
        Set-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName `
            -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
        Write-Host "Updated scheduled task $TaskPath$TaskName (daily $( '{0:D2}:{1:D2}' -f $Hour, $Minute ) local)."
    }
}
else {
    if ($PSCmdlet.ShouldProcess("$TaskPath$TaskName", 'Register scheduled task')) {
        Register-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName `
            -Action $task.Actions -Trigger $task.Triggers -Settings $task.Settings `
            -Principal $task.Principal -Description $task.Description | Out-Null
        Write-Host "Registered scheduled task $TaskPath$TaskName (daily $( '{0:D2}:{1:D2}' -f $Hour, $Minute ) local)."
    }
}
