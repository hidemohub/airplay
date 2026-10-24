#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$logDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs'
$null = New-Item -ItemType Directory -Path $logDirectory -Force
try {
    & (Join-Path $PSScriptRoot 'start.ps1') @args *>&1 | Out-File -LiteralPath (Join-Path $logDirectory 'launcher.log') -Encoding utf8
} catch {
    $_ | Out-File -LiteralPath (Join-Path $logDirectory 'launcher.log') -Append -Encoding utf8
    exit 1
}
