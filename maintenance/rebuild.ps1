#Requires -Version 5.1
param([string]$JdkBin = (Split-Path (Get-Command javac -ErrorAction Stop).Source), [string]$OutputJar)
$ErrorActionPreference = 'Stop'
$release = Split-Path -Parent $PSScriptRoot
python (Join-Path $PSScriptRoot 'package_patch.py') extract
if ($LASTEXITCODE -ne 0) { throw 'Dependency extraction failed' }
$classPath = "$PSScriptRoot\inspect;$PSScriptRoot\inspect\libs\*"
$javaSources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File | ForEach-Object { $_.FullName })
& "$JdkBin\javac.exe" -encoding UTF-8 -cp $classPath -d "$PSScriptRoot\inspect" @javaSources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed (JDK 25 required)' }
& "$JdkBin\java.exe" -cp $classPath PatchTray "$PSScriptRoot\inspect\wtf\nanoka\airplay\app\menu\SystemTrayMenu.class"
if ($LASTEXITCODE -ne 0) { throw 'Tray patch failed' }
& "$JdkBin\java.exe" -cp $classPath PatchBranding "$PSScriptRoot\inspect\wtf\nanoka\airplay\app\config\UserSettingsPropertySource.class"
if ($LASTEXITCODE -ne 0) { throw 'Branding patch failed' }
& "$JdkBin\java.exe" -cp $classPath PatchWindow "$PSScriptRoot\inspect\wtf\nanoka\airplay\player\gstreamer\ui\VisionPlayerWindow.class" "$PSScriptRoot\inspect\wtf\nanoka\airplay\player\gstreamer\ui\DetachedVideoWindow.class"
if ($LASTEXITCODE -ne 0) { throw 'Window patch failed' }
& "$JdkBin\java.exe" -cp $classPath PatchVideo "$PSScriptRoot\inspect\wtf\nanoka\airplay\player\gstreamer\GstPlayerSwing.class" "$PSScriptRoot\inspect\wtf\nanoka\airplay\server\internal\handler\video\VideoCodecConfiguration.class"
if ($LASTEXITCODE -ne 0) { throw 'Video patch failed' }
if ($OutputJar) {
    python (Join-Path $PSScriptRoot 'package_patch.py') package $OutputJar
} else {
    python (Join-Path $PSScriptRoot 'package_patch.py') package
}
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
if (-not $OutputJar) {
    & "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /reference:System.Windows.Forms.dll "/out:$release\start.exe" "$PSScriptRoot\DesktopLauncher.cs"
    if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed' }
}
