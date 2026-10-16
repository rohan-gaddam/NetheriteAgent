<#
.SYNOPSIS
    Enrich OSV advisory IDs with severity, summary, aliases, and fixed version info (WS5 Batch I).

.DESCRIPTION
    querybatch results store only id/modified. This script reads the raw OSV responses
    (osv-raw-<n>.json), collects unique advisory IDs, and fetches full advisories from
    https://api.osv.dev/v1/vulns/{id}. Output: osv-enriched.csv with:
       Artifact, Version, ID, Aliases, Severity (CVSS), Fixed (lowest fixed version), Summary
.NOTES
    Executed 2026-09-28.
#>
[CmdletBinding()]
param(
    [string]$InventoryDir
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not $InventoryDir) {
    $InventoryDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
}
$InventoryDir = (Resolve-Path -LiteralPath $InventoryDir).Path

# Rebuild the artifact -> vuln-id map from findings CSV (fast), then fetch details per advisory.
$findings = Import-Csv -LiteralPath (Join-Path $InventoryDir 'osv-findings.csv')
$uniqueIds = @($findings.ID | Sort-Object -Unique)
Write-Host "Unique advisory IDs to fetch: $($uniqueIds.Count)"

# Artifact:Version -> list of IDs
$map = @{}
foreach ($f in $findings) {
    $key = "$($f.Artifact)|$($f.Version)"
    if (-not $map.ContainsKey($key)) { $map[$key] = New-Object System.Collections.Generic.List[string] }
    $map[$key].Add($f.ID)
}

# Cache of advisory detail lookups
$detail = @{}
foreach ($id in $uniqueIds) {
    try {
        $resp = Invoke-RestMethod -Uri "https://api.osv.dev/v1/vulns/$id" -Method Get -TimeoutSec 90
        $detail[$id] = $resp
    } catch {
        Write-Warning "Failed to fetch $id : $($_.Exception.Message)"
    }
    Start-Sleep -Milliseconds 120
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($kv in $map.GetEnumerator()) {
    $parts = $kv.Key.Split('|')
    $artifact = $parts[0]
    $version = $parts[1]
    foreach ($id in $kv.Value) {
        $d = $detail[$id]
        if (-not $d) { continue }
        $sev = ''
        if ($d.database_specific -and $d.database_specific.severity) { $sev = $d.database_specific.severity }
        if (-not $sev -and $d.severity -and $d.severity.Count -gt 0) {
            $sev = ($d.severity | ForEach-Object { $_.score }) -join ' '
        }
        $aliases = if ($d.aliases) { $d.aliases -join ' ' } else { '' }
        $summary = if ($d.summary) { $d.summary } elseif ($d.details) { $d.details.Substring(0, [Math]::Min(180, $d.details.Length)) } else { '' }

        # Determine lowest fixed version for this affected package/ecosystem pair.
        $fixed = ''
        if ($d.affected) {
            foreach ($a in $d.affected) {
                $am = $a.package.name
                $ec = $a.package.ecosystem
                if ($ec -ne 'Maven') { continue }
                if ($am -ne $artifact) { continue }
                if ($a.ranges) {
                    foreach ($rg in $a.ranges) {
                        if ($rg.events) {
                            foreach ($ev in $rg.events) {
                                if ($ev.fixed) {
                                    if (-not $fixed) { $fixed = $ev.fixed; continue }
                                    try {
                                        if (([version]$ev.fixed) -lt ([version]$fixed)) { $fixed = $ev.fixed }
                                    } catch {
                                        # non-numeric/release-candidate version; keep first found
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        $rows.Add([pscustomobject]@{
            Artifact = $artifact; Version = $version; ID = $id; Aliases = $aliases;
            Severity = $sev; Fixed = $fixed; Summary = $summary
        })
    }
}

$out = Join-Path $InventoryDir 'osv-enriched.csv'
$rows | Export-Csv -Path $out -NoTypeInformation -Encoding UTF8
Write-Host "Enriched rows: $($rows.Count) -> $out"