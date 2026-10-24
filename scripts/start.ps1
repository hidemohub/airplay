#Requires -Version 5.1

[CmdletBinding()]
param(
    [string]$JarPath,
    [string]$ConfigPath,
    [switch]$Background,
    [switch]$Console,
    [switch]$NoBuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ApplicationArguments
)

$ErrorActionPreference = 'Stop'
$Workspace = Split-Path -Parent $PSScriptRoot
$GStreamerBin = & (Join-Path $PSScriptRoot 'bootstrap-runtime.ps1') -Quick
$embeddedJava = Join-Path $Workspace 'runtime\bin\java.exe'
$embeddedJavaw = Join-Path $Workspace 'runtime\bin\javaw.exe'
$JavaExecutable = if (Test-Path -LiteralPath $embeddedJava) {
    (Resolve-Path -LiteralPath $embeddedJava).Path
} else {
    (Get-Command java -ErrorAction Stop).Source
}
$JavawExecutable = if (Test-Path -LiteralPath $embeddedJavaw) {
    (Resolve-Path -LiteralPath $embeddedJavaw).Path
} else {
    $javawCommand = Get-Command javaw -ErrorAction SilentlyContinue
    if ($javawCommand) { $javawCommand.Source } else { $JavaExecutable }
}

if (!$JarPath) {
    $rootJar = Get-ChildItem -LiteralPath $Workspace -Filter 'airplay-server-*.jar' -File |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($rootJar) {
        $JarPath = $rootJar.FullName
    }
    $libraryDirectory = Join-Path $Workspace 'player\app\build\libs'
    if (!$JarPath -and (Test-Path -LiteralPath $libraryDirectory)) {
        $existingJar = Get-ChildItem -LiteralPath $libraryDirectory -Filter 'airplay-server-*.jar' |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($existingJar) {
            $JarPath = $existingJar.FullName
        }
    }
    if (!$JarPath) {
        $JarPath = Join-Path $libraryDirectory 'airplay-server-1.1.2.jar'
    }
}
if (!(Test-Path -LiteralPath $JarPath)) {
    if ($NoBuild) {
        throw "JAR not found at '$JarPath' and -NoBuild was specified."
    }
    $gradleWrapper = Join-Path $Workspace 'gradlew.bat'
    if (!(Test-Path -LiteralPath $gradleWrapper)) {
        throw 'The executable JAR is missing and this release package does not contain source build files.'
    }
    Write-Host 'JAR is missing; building it with the Gradle Wrapper...'
    try {
        $clashConfig = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/configs' -TimeoutSec 5
        $mixedPort = [int]$clashConfig.'mixed-port'
        if ($mixedPort -gt 0) {
            $proxyOptions = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$mixedPort -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$mixedPort"
            $env:GRADLE_OPTS = (($env:GRADLE_OPTS, $proxyOptions) -join ' ').Trim()
            Write-Host "Using FlClash port $mixedPort for Gradle dependency downloads."
        }
    } catch {
        Write-Host "FlClash was not available for Gradle: $($_.Exception.Message)" -ForegroundColor Yellow
    }
    & $gradleWrapper ':player:app:bootJar'
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path

# Remove obsolete diagnostic files only; keep settings, identities and pairings.
$userData = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.java-airplay'
if (Test-Path -LiteralPath $userData) {
    Get-ChildItem -LiteralPath $userData -File -Filter 'java-airplay.log*' | Remove-Item -Force -ErrorAction SilentlyContinue
    $audioData = Join-Path $userData 'airplay2'
    if (Test-Path -LiteralPath $audioData) {
        foreach ($pattern in @('audio.debug.log*', 'control.log*', 'events.log*', 'bootstrap.log')) {
            Get-ChildItem -LiteralPath $audioData -File -Filter $pattern | Remove-Item -Force -ErrorAction SilentlyContinue
        }
    }
}


$javaArguments = @(
    '--enable-native-access=ALL-UNNAMED',
    '-Ddebug=false',
    "-Dgstreamer.path=$GStreamerBin",
    '-jar',
    $JarPath
)
if ($ConfigPath) {
    if (!(Test-Path -LiteralPath $ConfigPath)) {
        throw "Configuration file not found: '$ConfigPath'."
    }
    $resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path.Replace('\\', '/')
    $javaArguments += "--spring.config.additional-location=file:$resolvedConfig"
}
if ($ApplicationArguments) {
    $javaArguments += $ApplicationArguments
}

if (!$Console) {
    if ($JavawExecutable -eq $JavaExecutable) {
        throw 'javaw.exe is required for desktop startup. Use -Console for diagnostics.'
    }
    $logDirectory = Join-Path $Workspace 'logs'
    $null = New-Item -ItemType Directory -Path $logDirectory -Force
    $backgroundArguments = $javaArguments | ForEach-Object {
        if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }
    $process = Start-Process -FilePath $JavawExecutable -ArgumentList $backgroundArguments -WorkingDirectory $Workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'java.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'java.stderr.log') -PassThru
    $null = $process.WaitForExit(150)
    $process.Refresh()
    if ($process.HasExited -and $process.ExitCode -ne 0) {
        throw "airplay exited during startup with code $($process.ExitCode)."
    }
    [pscustomobject]@{
        ProcessId = $process.Id
        Jar = $JarPath
        GStreamer = $GStreamerBin
        Java = $JavaExecutable
    } | ConvertTo-Json -Compress
} else {
    Push-Location $Workspace
    try {
        & $JavaExecutable @javaArguments
        exit $LASTEXITCODE
    } finally {
        Pop-Location
    }
}
