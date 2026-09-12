# Stage locked vendor assets into windows/dist/vendor from the Android tree.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$repo = Resolve-Path (Join-Path $root "..")
$src = Join-Path $repo "app\src\main\assets\vendor"
$dst = Join-Path $root "dist\vendor"
if (-not (Test-Path $src)) {
  throw "Missing vendor assets at $src. Run bash web/stage-assets.sh in the Android tree first."
}
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item -Recurse -Force (Join-Path $src "*") $dst
Write-Output "Staged vendor assets -> $dst"
