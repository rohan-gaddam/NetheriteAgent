param (
    [string]$Goals = "clean test"
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$jdkDir = Join-Path $projectDir '.tools\jdk-21'
$mavenDir = Join-Path $projectDir '.tools\maven'
$mvnCmd = Join-Path $mavenDir 'bin\mvn.cmd'

$env:JAVA_HOME = $jdkDir
$env:PATH = "$jdkDir\bin;$mavenDir\bin;$env:PATH"

Write-Host "Running Maven with project-local JDK 21: $jdkDir"
Set-Location (Join-Path $projectDir 'backend')
& $mvnCmd ($Goals -split '\s+')
