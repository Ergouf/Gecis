# Build Gecis APK script
param(
    [switch]$Debug,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$buildType = if ($Debug) { "Debug" } else { "Release" }
$taskName = "assemble$buildType"
$apkRelPath = if ($Debug) { "app\build\outputs\apk\debug\app-debug.apk" } else { "app\build\outputs\apk\release\app-release.apk" }

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  Building Gecis Android APK ($buildType)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Push-Location $ProjectDir

if ($Clean) {
    Write-Host "[INFO] Cleaning previous build..." -ForegroundColor Yellow
    & .\gradlew.bat clean --console=plain
}

Write-Host "[INFO] Running $taskName..." -ForegroundColor Yellow
& .\gradlew.bat $taskName --console=plain
$buildExit = $LASTEXITCODE

Pop-Location

if ($buildExit -eq 0) {
    $apkPath = Join-Path $ProjectDir $apkRelPath
    if (Test-Path $apkPath) {
        $apk = Get-Item $apkPath
        $sizeMb = [math]::Round($apk.Length / 1MB, 2)
        Write-Host "`n[SUCCESS] $buildType APK built and signed successfully!" -ForegroundColor Green
        Write-Host "  Path: $($apk.FullName)" -ForegroundColor White
        Write-Host "  Size: $sizeMb MB ($($apk.Length) bytes)" -ForegroundColor White
    } else {
        Write-Host "`n[FAIL] APK not found at expected path: $apkPath" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "`n[FAIL] Build failed with exit code: $buildExit" -ForegroundColor Red
    exit 1
}
