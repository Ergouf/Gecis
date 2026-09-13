$ErrorActionPreference = "Stop"
$windowsRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Resolve-Path (Join-Path $windowsRoot "..")
$sharedAssets = Join-Path $repoRoot "app\src\main\assets"
$dist = Join-Path $windowsRoot "dist"

New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item -Force (Join-Path $sharedAssets "index.html") (Join-Path $dist "index.html")
Copy-Item -Force (Join-Path $sharedAssets "app.js") (Join-Path $dist "app.js")

& (Join-Path $PSScriptRoot "stage-vendor.ps1")
Write-Output "Staged shared frontend -> $dist"
