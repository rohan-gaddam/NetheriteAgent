<#
.SYNOPSIS
    OSV vulnerability lookup over the resolved Maven dependency inventory (WS5 Batch I).

.DESCRIPTION
    Reads the maven-dependency-list-app.txt file (produced by
    `mvn -o -pl netherite-app dependency:list`), extracts every unique
    group:artifact:version, and queries the OSV API (https://api.osv.dev/v1/querybatch)
    for known vulnerabilities in that exact version.

    Output:
      - Raw API JSON per query to osv-raw-<n>.json (chunked).
      - A condensed CSV osv-findings.csv (group:artifact:version | vulnerability id |
        source | severity | summary) for human review.

    No secret material is involved; this script never reads configuration files.
.NOTES
    Executed 2026-09-28. Database freshness is whatever OSV serves at query time;
    the raw responses are preserved for re-analysis.
#>
[CmdletBinding()]
param(
    [string]$InventoryDir,
    [string]$ListFile = 'maven-dependency-list-app.txt'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not $InventoryDir) {
    $InventoryDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
}
$InventoryDir = (Resolve-Path -LiteralPath $InventoryDir).Path

$list = Join-Path $InventoryDir $ListFile
if (-not (Test-Path -LiteralPath $list)) { throw "Inventory file not found: $list" }

$artifacts = New-Object System.Collections.Generic.HashSet[string]
Get-Content -LiteralPath $list | ForEach-Object {
    if ($_ -match '^\s*\[INFO\]\s+([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+):(?:[^:]+):([^:]+):') {
        $pair = "$($Matches[1]):$($Matches[2])"
        [void]$artifacts.Add($pair)
    }
}
$items = @($artifacts)
Write-Host "Unique resolved artifacts to query: $($items.Count)"

$queries = @()
foreach ($a in $items) {
    $parts = $a.Split(':')
    if ($parts.Count -ne 3) { continue }
    $queries += @{
        package = @{ name = "$($parts[0]):$($parts[1])"; ecosystem = 'Maven' }
        version = $parts[2]
    }
}

$chunkSize = 500
$chunk = 0
$findings = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $queries.Count; $i += $chunkSize) {
    $batch = @($queries[$i..([Math]::Min($i + $chunkSize - 1, $queries.Count - 1))])
    $payload = @{ queries = $batch } | ConvertTo-Json -Depth 6 -Compress
    $resp = Invoke-RestMethod -Uri 'https://api.osv.dev/v1/querybatch' -Method Post `
        -ContentType 'application/json' -Body $payload -TimeoutSec 120
    $chunkOut = Join-Path $InventoryDir ('osv-raw-' + $chunk + '.json')
    $resp | ConvertTo-Json -Depth 12 | Set-Content -Path $chunkOut
    Write-Host "OSV chunk $chunk written: $chunkOut"
    for ($q = 0; $q -lt $batch.Count; $q++) {
        $name = $batch[$q].package.name
        $ver = $batch[$q].version
        $res = $resp.results[$q]
        if (-not $res -or -not $res.vulns -or $res.vulns.Count -eq 0) { continue }
        foreach ($v in $res.vulns) {
            $sev = ''
            $summary = ''
            if ($v.database_specific -and $v.database_specific.severity) { $sev = $v.database_specific.severity }
            if (-not $sev) { $sev = $v.severity }   # array of {type,score}
            if (-not $sev) { $sev = $v.aliases }
            if ($v.summary) { $summary = $v.summary }
            $findings.Add([pscustomobject]@{
                Artifact = $name; Version = $ver; ID = $v.id; Aliases = ($v.aliases -join ' ');
                Severity = $sev; Summary = $summary
            })
        }
    }
    $chunk++
    if ($chunk -ge 40) { break }
}

$outCsv = Join-Path $InventoryDir 'osv-findings.csv'
$findings | Export-Csv -Path $outCsv -NoTypeInformation -Encoding UTF8
Write-Host "Findings entries: $($findings.Count) -> $outCsv"