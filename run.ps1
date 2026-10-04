# LCForge — compile and start. Usage:  .\run.ps1  [-Port 7070]  [-Clean]
param(
    [int]$Port = 7070,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Host "javac not found on PATH." -ForegroundColor Red
    Write-Host "LCForge needs a JDK (17 or newer). Install one, then reopen this terminal."
    exit 1
}

if ($Clean -and (Test-Path build)) {
    Remove-Item build -Recurse -Force
}
New-Item -ItemType Directory -Force -Path build, data, workspace | Out-Null

# Recompile only when a source is newer than the last build.
$newest = Get-ChildItem -Recurse -Path src, runtime -Filter *.java |
          Sort-Object LastWriteTime -Descending |
          Select-Object -First 1
$stamp = Join-Path build '.buildstamp'
$needsBuild = $true
if ((Test-Path $stamp) -and $newest) {
    $needsBuild = $newest.LastWriteTime -gt (Get-Item $stamp).LastWriteTime
}

if ($needsBuild) {
    Write-Host "Compiling LCForge..." -ForegroundColor DarkGray
    $sources = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
    & javac -d build -g -encoding UTF-8 --add-modules jdk.jdi $sources
    if ($LASTEXITCODE -ne 0) { Write-Host "Build failed." -ForegroundColor Red; exit 1 }
    Set-Content -Path $stamp -Value (Get-Date).ToString('o') -Encoding utf8
}

# --console: this terminal is the window. Ctrl+C stops it.
& java --add-modules jdk.jdi -cp build lcforge.App --console --root "$PSScriptRoot" --port $Port
