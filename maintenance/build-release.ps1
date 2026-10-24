#Requires -Version 7.0
param([string]$JdkHome = $env:JAVA_HOME, [switch]$Publish)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$workRoot = if ($env:RUNNER_TEMP) { Join-Path $env:RUNNER_TEMP 'airplay-release' } else { Join-Path $sourceRoot '.release-build' }
if (Test-Path -LiteralPath $workRoot) { throw 'Use a clean build directory.' }
$null = New-Item -ItemType Directory -Path $workRoot
$upstreamZip = Join-Path $workRoot 'upstream.zip'
Invoke-WebRequest -Uri 'https://github.com/Arc-Lira/java-airplay/releases/download/v1.1.2/java-airplay-1.1.2.260917-windows-x64.zip' -OutFile $upstreamZip
if ((Get-FileHash -LiteralPath $upstreamZip -Algorithm SHA256).Hash -ne 'F3C72C469FD6B6B740DAAAC29BCAD3F16EDED3DE41E5F44391FF31B2FAC7345D') { throw 'Upstream package checksum mismatch.' }
$upstream = Join-Path $workRoot 'upstream'
Expand-Archive -LiteralPath $upstreamZip -DestinationPath $upstream
$original = Get-ChildItem -LiteralPath $upstream -Recurse -File -Filter 'java-airplay-server-1.1.2.jar' | Select-Object -First 1
if (!$original -or (Get-FileHash -LiteralPath $original.FullName -Algorithm SHA256).Hash -ne '6C3CA37E9069C59D197756B71D263C9CFAE433B3ED2F9F6053FA51BE69E9F81B') { throw 'Original JAR differs from the verified patch base.' }
$bundle = Join-Path $workRoot 'airplay'
$null = New-Item -ItemType Directory -Path $bundle
foreach ($name in @('README.md', 'LICENSE', 'config', 'scripts', 'maintenance', '.runtime')) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination (Join-Path $bundle $name) -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $original.Directory.FullName 'runtime') -Destination (Join-Path $bundle 'runtime') -Recurse
$gstreamer = Join-Path $original.Directory.FullName '.runtime\gstreamer'
Copy-Item -LiteralPath $gstreamer -Destination (Join-Path $bundle '.runtime\gstreamer') -Recurse
Copy-Item -LiteralPath $original.FullName -Destination (Join-Path $bundle 'maintenance\java-airplay-server-1.1.2.original.jar')

& (Join-Path $bundle 'maintenance\rebuild-runtime.ps1') -JdkHome $JdkHome
if ($LASTEXITCODE -ne 0) { throw 'Runtime build failed.' }
$runtimePath = [IO.Path]::GetFullPath((Join-Path $bundle 'runtime'))
$runtimeBuild = [IO.Path]::GetFullPath((Join-Path $bundle '.runtime\runtime-build'))
foreach ($path in @($runtimePath, $runtimeBuild)) {
    if (!$path.StartsWith([IO.Path]::GetFullPath($bundle) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime path escaped bundle.' }
}
Move-Item -LiteralPath $runtimePath -Destination (Join-Path $workRoot 'runtime-original')
Move-Item -LiteralPath $runtimeBuild -Destination $runtimePath

$pythonZip = Join-Path $workRoot 'python.zip'
Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip' -OutFile $pythonZip
if ((Get-FileHash -LiteralPath $pythonZip -Algorithm SHA256).Hash -ne '4ACBED6DD1C744B0376E3B1CF57CE906F9DC9E95E68824584C8099A63025A3C3') { throw 'Embedded Python checksum mismatch.' }
$audioRuntime = Join-Path $bundle '.runtime\airplay2'
Expand-Archive -LiteralPath $pythonZip -DestinationPath $audioRuntime -Force
[IO.File]::WriteAllText((Join-Path $audioRuntime 'python312._pth'), "python312.zip`n.`nsite-packages`nreceiver`nimport site`n")
& (Join-Path $bundle 'maintenance\download-airplay2-dependencies.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Audio dependencies failed.' }
& (Join-Path $bundle 'maintenance\rebuild.ps1') -JdkBin (Join-Path $JdkHome 'bin')
if ($LASTEXITCODE -ne 0) { throw 'Application build failed.' }

Push-Location $bundle
try {
    $env:PATH = (Join-Path $bundle '.runtime\gstreamer\bin') + ';' + $env:PATH
    $java = Join-Path $bundle 'runtime\bin\java.exe'
    & $java --enable-native-access=ALL-UNNAMED '-Ddebug=false' "-Dgstreamer.path=$bundle\.runtime\gstreamer\bin" "-Duser.home=$workRoot\validation-home" -jar airplay-server-1.1.2.jar --java-airplay.validation=true --player.gstreamer.videoDecoder=avdec_h264 --player.tray.enabled=false
    if ($LASTEXITCODE -ne 0) { throw 'Packaged startup and shutdown validation failed.' }
} finally { Pop-Location }

$env:AIRPLAY_RELEASE_BUNDLE = $bundle
$env:AIRPLAY_RELEASE_OUTPUT = Join-Path $sourceRoot 'dist'
@'
from pathlib import Path
import hashlib, os, zipfile
root=Path(os.environ['AIRPLAY_RELEASE_BUNDLE'])
out=Path(os.environ['AIRPLAY_RELEASE_OUTPUT']); out.mkdir(exist_ok=True)
archive=out/'airplay-1.1.2-windows-x64.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for file in sorted(root.rglob('*')):
        rel=file.relative_to(root)
        if not file.is_file() or any(x in rel.parts for x in ('inspect','logs','__pycache__','.java-airplay','pairings')) or file.suffix in ('.pyc','.class'):
            continue
        z.write(file,'airplay/'+rel.as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for required in ('start.exe','airplay-server-1.1.2.jar','runtime/bin/javaw.exe','.runtime/airplay2/pythonw.exe','.runtime/gstreamer/bin/gstreamer-1.0-0.dll'):
        assert 'airplay/'+required in z.namelist(), required
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
(out/'SHA256SUMS.txt').write_text(digest+'  '+archive.name+'\n',encoding='ascii')
print('RELEASE_ARCHIVE_VERIFIED',archive.stat().st_size,digest)
'@ | python -
if ($LASTEXITCODE -ne 0) { throw 'Archive verification failed.' }

if ($Publish) {
    if (!$env:GH_TOKEN) { throw 'Publishing requires GH_TOKEN.' }
    Push-Location $sourceRoot
    try {
        $notes = @'
Windows x64 portable build. Extract the archive and run airplay/start.exe.

Includes Java 25, GStreamer, experimental AirPlay 2 audio, source patches and the original build JAR. No separate runtime installation is needed.

Detached-window resolution/FPS/codec title, simplified logging and startup/shutdown improvements are included. AirPlay 2 audio is experimental; precise multiroom synchronization is not implemented. Real iPhone/iPad end-to-end playback has not been reverified.

The upstream archive, original JAR and embedded Python are checksum-verified. The package passes startup/shutdown and archive integrity checks. Use SHA256SUMS.txt to verify your download.
'@
        $notes | Set-Content -LiteralPath dist/RELEASE_NOTES.md -Encoding utf8
        gh release view v1.1.2 *> $null
        if ($LASTEXITCODE -ne 0) {
            gh release create v1.1.2 --target $env:GITHUB_SHA --title 'airplay 1.1.2 - Windows x64' --notes-file dist/RELEASE_NOTES.md
            if ($LASTEXITCODE -ne 0) { throw 'Release creation failed.' }
        }
        gh release upload v1.1.2 dist/airplay-1.1.2-windows-x64.zip dist/SHA256SUMS.txt --clobber
        if ($LASTEXITCODE -ne 0) { throw 'Release asset upload failed.' }
        gh release edit v1.1.2 --draft=false --latest
        if ($LASTEXITCODE -ne 0) { throw 'Release publication failed.' }
    } finally { Pop-Location }
}
