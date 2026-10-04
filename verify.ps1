# LCForge test suite: type codec, golden problem set, live JDI trace.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

New-Item -ItemType Directory -Force -Path build | Out-Null

Write-Host "Building..." -ForegroundColor DarkGray
$src = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
& javac -d build -g -encoding UTF-8 --add-modules jdk.jdi $src
if ($LASTEXITCODE -ne 0) { Write-Host "Build failed." -ForegroundColor Red; exit 1 }

$tests = Get-ChildItem -Path test -Filter *.java | ForEach-Object { $_.FullName }
& javac -d build -g -encoding UTF-8 --add-modules jdk.jdi -cp build $tests (Get-ChildItem runtime -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { Write-Host "Test build failed." -ForegroundColor Red; exit 1 }

$failed = 0
foreach ($t in @('CodecTest', 'ImportHtmlTest', 'CompleteTest', 'AttemptLogTest', 'EmberTest', 'GoldenTest', 'TracerTest', 'GateTest')) {
    Write-Host ""
    Write-Host "--- $t ---" -ForegroundColor Cyan
    & java --add-modules jdk.jdi -cp build $t
    if ($LASTEXITCODE -ne 0) { $failed++ }
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "All suites passed." -ForegroundColor Green
} else {
    Write-Host "$failed suite(s) failed." -ForegroundColor Red
    exit 1
}
