#Requires -Version 5.1
param(
    [string]$JdkHome = 'C:\Program Files\Java\jdk-25.0.5.0.2',
    [string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) '.runtime\runtime-build')
)
$ErrorActionPreference = 'Stop'
$releaseRoot = Split-Path -Parent $PSScriptRoot
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose an empty output path for jlink.' }
$moduleLine = Get-Content (Join-Path $releaseRoot 'runtime\release') | Where-Object { $_ -like 'MODULES=*' }
$modules = @($moduleLine.Substring(9).TrimEnd('"').Split(' ')) + 'jdk.crypto.mscapi'
$moduleList = ($modules | Select-Object -Unique) -join ','
& (Join-Path $JdkHome 'bin\jlink.exe') --add-modules $moduleList --strip-debug --no-header-files --no-man-pages --compress zip-6 --output $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }
# Prefer the OS CSPRNG rather than the slow fallback entropy collector.
# Use the standard Windows provider, retaining the rest of the JDK providers.
$securityPath = Join-Path $OutputDirectory 'conf\security\java.security'
$securityText = [IO.File]::ReadAllText($securityPath)
$providerMatches = [regex]::Matches($securityText, '(?m)^security.provider\.\d+=(.+)$')
$providers = @('SunMSCAPI') + @($providerMatches | ForEach-Object { $_.Groups[1].Value.Trim() } | Where-Object { $_ -ne 'SunMSCAPI' })
for ($index = 0; $index -lt $providerMatches.Count; $index++) {
    $securityText = $securityText.Replace($providerMatches[$index].Value, "security.provider.$($index + 1)=$($providers[$index])")
}
[IO.File]::WriteAllText($securityPath, $securityText, (New-Object Text.UTF8Encoding($false)))
