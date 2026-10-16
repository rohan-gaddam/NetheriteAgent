#requires -Version 5.1
<#
.SYNOPSIS
    Provision the file-based docker compose secrets for NetheriteAgent production.

.DESCRIPTION
Writes six files under infrastructure/docker/secrets/ (git-ignored):
      - db_password          : random 64-char hexadecimal (PostgreSQL bootstrap admin,
                               netherite_admin - used by the one-shot migrate service)
      - db_app_password      : random 64-char hexadecimal (least-privileged runtime role
                               netherite_app that the app service connects as)
      - redis_password       : random 64-char hexadecimal (Redis requirepass)
      - opa_policy_token     : random 64-char hexadecimal (bearer credential for the OPA
                               engine's system.authz token authorization: the app presents it
                               on every decision, health and policy-push call; the engine
                               admits exactly this token via its mounted data)
      - owner_password_hash  : BCrypt($10) of OWNER_PASSWORD via BcryptTool.java
      - gemini_api_key       : EMPTY by default. The compose file always declares this secret
      - openrouter_api_key   : EMPTY by default. The compose file always declares this secret
                               (its source file must exist for `docker compose up`), but the
                               app only reads it when .env sets LLM_PROVIDER=gemini. Supply a
                               real Google Gemini API key here only when enabling the LLM.
    The owner hash is computed with the application's own spring-security-crypto
    BCrypt implementation, so the hash is guaranteed to match at login time and
    is delivered to containers byte-for-byte (no '$' interpolation anywhere).

.EXAMPLE
    # Supply the password through OWNER_PASSWORD or use the hidden prompt.
    $env:OWNER_PASSWORD = '<owner-login-password>'; .\generate-secrets.ps1
    .\generate-secrets.ps1
#>
[CmdletBinding()]
param(
    [string]$AppJar,
    [string]$SecretsDir
)

$ErrorActionPreference = 'Stop'

# NOTE: $PSScriptRoot is unavailable during param-default evaluation under
# [CmdletBinding()] in Windows PowerShell -File invocation, so defaults are
# resolved here instead.
if (-not $AppJar) {
    $AppJar = Join-Path $PSScriptRoot '..\..\..\backend\netherite-app\target\netherite-app-0.1.0-SNAPSHOT.jar'
}
if (-not $SecretsDir) {
    $SecretsDir = Join-Path $PSScriptRoot '..\secrets'
}

# Resolve the owner password from the environment or a hidden prompt.
$OwnerPassword = $env:OWNER_PASSWORD
if (-not $OwnerPassword) {
    if ([Console]::IsInputRedirected) {
        throw 'No owner password available: set $env:OWNER_PASSWORD or run interactively (stdin is redirected).'
    }
    $secure = Read-Host 'Owner bootstrap login password' -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $OwnerPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
if (-not $OwnerPassword) {
    throw 'Owner password resolved to empty; refusing to provision an unusable bootstrap hash.'
}

function Write-SecretFile {
    param([string]$Path, [string]$Value)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    # No BOM, no trailing newline: container readers ('*_FILE', cat) get exactly the value.
    [System.IO.File]::WriteAllText($Path, $Value, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-RandomHex {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''
}

function Get-BcryptHash {
    param([string]$Password)

    if (-not (Test-Path -LiteralPath $AppJar)) {
        throw "App jar not found at '$AppJar'. Build it first:  mvn -pl netherite-app -am -DskipTests package"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("netherite-bcrypt-" + [guid]::NewGuid().ToString('N'))
    $tmpDirCreated = $false
    $previousOwnPassword = $null
    $hadPreviousOwnPassword = $false
    $javaExitCode = -1
    $out = $null
    try {
        New-Item -ItemType Directory -Path $tmpDir -ErrorAction Stop | Out-Null
        $tmpDirCreated = $true

        $zip = [System.IO.Compression.ZipFile]::OpenRead($AppJar)
        try {
            $crypto = $zip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/spring-security-crypto-[0-9][^/]*\.jar$' } |
                Select-Object -First 1
            $jcl = $zip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/spring-jcl-[0-9][^/]*\.jar$' } |
                Select-Object -First 1
            if (-not $crypto -or -not $jcl) {
                throw "spring-security-crypto / spring-jcl not found inside '$AppJar'."
            }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($crypto, "$tmpDir\spring-security-crypto.jar", $true)
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($jcl, "$tmpDir\spring-jcl.jar", $true)
        }
        finally { $zip.Dispose() }

        $java = 'java'
        if (-not (Get-Command $java -ErrorAction SilentlyContinue)) {
            $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
            if (-not (Test-Path -LiteralPath $java)) { throw "java not found on PATH or JAVA_HOME." }
        }

        $root = Split-Path -Parent $PSScriptRoot       # infrastructure/docker
        $root = Split-Path -Parent $root               # infrastructure
        $root = Split-Path -Parent $root               # repo root
        $tool = Join-Path $root 'infrastructure\docker\scripts\BcryptTool.java'
        $classpath = "$tmpDir\spring-security-crypto.jar;$tmpDir\spring-jcl.jar"

        # The password reaches BcryptTool.java ONLY via its OWNER_PASSWORD env
        # variable - never as a command-line argument (process-list visible).
        $hadPreviousOwnPassword = Test-Path Env:OWNER_PASSWORD
        if ($hadPreviousOwnPassword) { $previousOwnPassword = $env:OWNER_PASSWORD }
        try {
            $env:OWNER_PASSWORD = $Password
            $out = & $java -cp $classpath $tool 2>&1 | ForEach-Object { $_ }
            $javaExitCode = $LASTEXITCODE
        }
        finally {
            if ($hadPreviousOwnPassword) { $env:OWNER_PASSWORD = $previousOwnPassword }
            else { Remove-Item Env:OWNER_PASSWORD -ErrorAction SilentlyContinue }
        }
        if ($javaExitCode -ne 0) {
            throw "BCrypt generation failed (exit $javaExitCode): $($out -join ' ')"
        }
    }
    finally {
        # Never leave the extracted BCrypt runtime jars (or a stray OWNER_PASSWORD
        # env value) behind on a success or failure path.
        if ($tmpDirCreated -and (Test-Path -LiteralPath $tmpDir)) {
            Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    return (($out -join '') -replace "`r|`n", '')
}

try {
    $dbPassword = Get-RandomHex
    $dbAppPassword = Get-RandomHex
    $redisPassword = Get-RandomHex
    $opaPolicyToken = Get-RandomHex
    $ownerHash = Get-BcryptHash -Password $OwnerPassword

New-Item -ItemType Directory -Path $SecretsDir -Force | Out-Null
    Write-SecretFile -Path "$SecretsDir\db_password" -Value $dbPassword
    Write-SecretFile -Path "$SecretsDir\db_app_password" -Value $dbAppPassword
    Write-SecretFile -Path "$SecretsDir\redis_password" -Value $redisPassword
    Write-SecretFile -Path "$SecretsDir\opa_policy_token" -Value $opaPolicyToken
    Write-SecretFile -Path "$SecretsDir\owner_password_hash" -Value $ownerHash
    # gemini_api_key is declared by compose (so up resolves the source file) but
    # only read by the app when LLM_PROVIDER=gemini. Empty is the correct default.
    Write-SecretFile -Path "$SecretsDir\gemini_api_key" -Value ''
    # openrouter_api_key likewise: declared by compose, read only when
    # LLM_PROVIDER=openrouter. Empty is the correct default.
    Write-SecretFile -Path "$SecretsDir\openrouter_api_key" -Value ''

    Write-Host ""
    Write-Host "Secrets provisioned (git-ignored, never committed):" -ForegroundColor Green
    Write-Host "  $SecretsDir\db_password          ($($dbPassword.Length) hex chars, netherite_admin / migrate)"
    Write-Host "  $SecretsDir\db_app_password      ($($dbAppPassword.Length) hex chars, netherite_app / runtime app)"
    Write-Host "  $SecretsDir\redis_password       ($($redisPassword.Length) hex chars)"
    Write-Host "  $SecretsDir\opa_policy_token     ($($opaPolicyToken.Length) hex chars, OPA bearer authorization)"
    Write-Host "  $SecretsDir\owner_password_hash  (BCrypt, verifies at login)"
    Write-Host "  $SecretsDir\gemini_api_key       (EMPTY - add a real key only for LLM_PROVIDER=gemini)"
    Write-Host "  $SecretsDir\openrouter_api_key    (EMPTY - add a real key only for LLM_PROVIDER=openrouter)"
    Write-Host ""
    Write-Host "Owner bootstrap: sign in to the running app with user '$env:USER' email-agnostic"
    Write-Host "flow using email from OWNER_EMAIL (.env) and the password you just supplied."
    Write-Host "Next: docker compose run --rm migrate   then   docker compose up -d --scale app=2"
    exit 0
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}


