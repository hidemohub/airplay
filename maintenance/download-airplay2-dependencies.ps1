#Requires -Version 5.1
param(
    [string]$Python = 'python',
    [string]$IndexUrl = 'https://pypi.org/simple/'
)
$ErrorActionPreference = 'Stop'
$runtime = Join-Path (Split-Path -Parent $PSScriptRoot) '.runtime/airplay2'
$packages = Join-Path $runtime 'site-packages'
# Use Python 3.12 x64. Retain all third-party notices installed by pip.
& $Python -c "import sys,struct; assert sys.version_info[:2] == (3,12) and struct.calcsize('P') == 8, 'Python 3.12 x64 is required'"
if ($LASTEXITCODE -ne 0) { throw 'Wrong Python architecture or version' }
& $Python -m pip install --disable-pip-version-check --index-url $IndexUrl --target $packages -r (Join-Path $PSScriptRoot 'airplay2-requirements.lock')
if ($LASTEXITCODE -ne 0) { throw 'AirPlay 2 dependency installation failed' }
