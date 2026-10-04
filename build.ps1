# LCForge -- build the distributable jar.   Usage:  .\build.ps1 [-Monaco DIR]
#
# Output: dist\lcforge.jar -- one file that runs anywhere a JDK 17+ does:   java -jar dist\lcforge.jar
#
# JDK tools only: javac and jar, nothing to install. The web interface and the two shared runtime
# sources travel inside the jar; your data never does. Same steps as build.sh, for Windows without bash.
#
#   -Monaco DIR   bundle a monaco-editor `min/vs` folder so the editor works offline. The release
#                 workflow fetches it in CI; a local build normally leaves it out and uses the CDN.
param([string]$Monaco = "")

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# jar.exe is not always on PATH even when javac is (the Oracle installer's javapath shim only carries
# java, javac, javaw and jshell), so look beside javac for it.
$javac = (Get-Command javac -ErrorAction SilentlyContinue).Source
if (-not $javac) { Write-Host "javac not found -- LCForge needs a JDK (17 or newer)." -ForegroundColor Red; exit 1 }
$jar = (Get-Command jar -ErrorAction SilentlyContinue).Source
if (-not $jar -and $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jar.exe")) { $jar = "$env:JAVA_HOME\bin\jar.exe" }
if (-not $jar) {
    # Ask the JDK where it lives. Through cmd, because in Windows PowerShell a native program's
    # stderr redirected with 2>&1 becomes an error record -- and with 'Stop' set, a fatal one.
    $line = cmd /c "java -XshowSettings:properties -version 2>&1" | Select-String 'java.home'
    if ($line) { $jar = Join-Path ((($line -split '=', 2)[1]).Trim()) 'bin\jar.exe' }
}
if (-not $jar -or -not (Test-Path $jar)) { Write-Host "jar.exe not found -- is a full JDK installed? Set JAVA_HOME to it." -ForegroundColor Red; exit 1 }

$stage = 'dist\stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
if (Test-Path 'dist\lcforge.jar') { Remove-Item 'dist\lcforge.jar' -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Write-Host "Compiling (for Java 17 and newer)..." -ForegroundColor DarkGray
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -g -d $stage $sources
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed." -ForegroundColor Red; exit 1 }

Write-Host "Packing the interface..." -ForegroundColor DarkGray
Copy-Item web "$stage\web" -Recurse
New-Item -ItemType Directory -Force -Path "$stage\runtime" | Out-Null
Copy-Item runtime\*.java "$stage\runtime\"
# The status window's icons (packaging\IconGen.java draws them).
New-Item -ItemType Directory -Force -Path "$stage\icons" | Out-Null
foreach ($n in 16, 32, 48, 256) { Copy-Item "packaging\icons\icon-$n.png" "$stage\icons\" }

if ($Monaco) {
    if (-not (Test-Path (Join-Path $Monaco 'loader.js'))) { Write-Host "-Monaco must point at a monaco-editor min/vs folder (it has loader.js)" -ForegroundColor Red; exit 1 }
    New-Item -ItemType Directory -Force -Path "$stage\web\vendor\monaco" | Out-Null
    Copy-Item $Monaco "$stage\web\vendor\monaco\vs" -Recurse
    # Monaco is MIT-licensed, so its notices travel with it. In the npm package they sit two levels
    # above min/vs.
    foreach ($f in 'LICENSE', 'ThirdPartyNotices.txt') {
        $src = Join-Path $Monaco "..\..\$f"
        if (Test-Path $src) { Copy-Item $src "$stage\web\vendor\monaco\" }
    }
    Write-Host "  bundled Monaco from $Monaco"
}

& $jar --create --file dist\lcforge.jar --main-class lcforge.App -C $stage .
if ($LASTEXITCODE -ne 0) { Write-Host "jar failed." -ForegroundColor Red; exit 1 }
Remove-Item $stage -Recurse -Force

$size = [math]::Round((Get-Item dist\lcforge.jar).Length / 1KB)
Write-Host ""
Write-Host "  dist\lcforge.jar  ($size KB)" -ForegroundColor Green
Write-Host "  run it with:  java -jar dist\lcforge.jar"
