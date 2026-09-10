#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/native/payload.lock"

OUT_DIR="${OUT_DIR:-$ROOT/native/.probe}"
REPORT="$OUT_DIR/runtime-probe.json"
ARCHIVE="$OUT_DIR/antigravity-termux-standalone.tar.gz"
EXTRACT="$OUT_DIR/antigravity"
PACKAGES_FILE="$OUT_DIR/Packages"
GLIBC_DEB="$OUT_DIR/glibc.deb"
GLIBC_ROOT="$OUT_DIR/glibc-root"

mkdir -p "$OUT_DIR"
rm -rf "$EXTRACT" "$GLIBC_ROOT"
mkdir -p "$EXTRACT" "$GLIBC_ROOT"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 2; }; }
for cmd in curl tar sha256sum readelf python3 awk sed dpkg-deb; do need "$cmd"; done

AGY_URL="https://github.com/wallentx/antigravity-cli-termux/releases/download/${AGY_TAG}/antigravity-termux-standalone.tar.gz"
echo "[1/6] Downloading pinned Antigravity payload ${AGY_TAG}"
curl -fL --retry 3 --retry-delay 2 "$AGY_URL" -o "$ARCHIVE"
echo "${AGY_ARCHIVE_SHA256}  ${ARCHIVE}" | sha256sum -c -

tar -xzf "$ARCHIVE" -C "$EXTRACT" agy agy.va39

echo "[2/6] Verifying engine ELF"
ENGINE="$EXTRACT/agy.va39"
MACHINE="$(readelf -h "$ENGINE" | awk -F: '/Machine:/{gsub(/^[ \t]+/,"",$2); print $2}')"
CLASS="$(readelf -h "$ENGINE" | awk -F: '/Class:/{gsub(/^[ \t]+/,"",$2); print $2}')"
INTERP="$(readelf -l "$ENGINE" | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p')"
[[ "$MACHINE" == "AArch64" ]] || { echo "unexpected engine machine: $MACHINE" >&2; exit 3; }
[[ "$CLASS" == "ELF64" ]] || { echo "unexpected engine class: $CLASS" >&2; exit 3; }

# Resolve the current aarch64 glibc package from the official Termux glibc repo.
# This stage is a probe only; the final release pipeline must pin the exact .deb checksum.
echo "[3/6] Resolving Termux glibc ${GLIBC_VERSION_PREFIX} package"
CANDIDATE_PATHS=(
  "dists/glibc/stable/binary-aarch64/Packages"
  "dists/stable/main/binary-aarch64/Packages"
  "dists/glibc/main/binary-aarch64/Packages"
)
PACKAGES_URL=""
for rel in "${CANDIDATE_PATHS[@]}"; do
  url="${GLIBC_REPO_BASE}/${rel}"
  if curl -fsSL "$url" -o "$PACKAGES_FILE"; then
    PACKAGES_URL="$url"
    break
  fi
done
[[ -n "$PACKAGES_URL" ]] || { echo "could not resolve Termux glibc Packages index" >&2; exit 4; }

python3 - "$PACKAGES_FILE" "$GLIBC_VERSION_PREFIX" "$OUT_DIR/glibc-meta.env" <<'PY'
import sys
from pathlib import Path
packages = Path(sys.argv[1]).read_text(errors='replace').split('\n\n')
prefix = sys.argv[2]
rows = []
for block in packages:
    fields = {}
    for line in block.splitlines():
        if ': ' in line:
            k, v = line.split(': ', 1)
            fields[k] = v
    if fields.get('Package') == 'glibc' and fields.get('Architecture') == 'aarch64' and fields.get('Version','').startswith(prefix):
        rows.append(fields)
if not rows:
    raise SystemExit('glibc package not found')
row = rows[-1]
required = ['Filename','SHA256','Version']
for key in required:
    if key not in row:
        raise SystemExit(f'missing {key} in Packages index')
out = Path(sys.argv[3])
out.write_text('\n'.join([
    f"GLIBC_FILENAME={row['Filename']}",
    f"GLIBC_SHA256={row['SHA256']}",
    f"GLIBC_VERSION={row['Version']}",
]) + '\n')
PY
# shellcheck source=/dev/null
source "$OUT_DIR/glibc-meta.env"

echo "[4/6] Downloading and verifying glibc $GLIBC_VERSION"
curl -fL --retry 3 --retry-delay 2 "${GLIBC_REPO_BASE}/${GLIBC_FILENAME}" -o "$GLIBC_DEB"
echo "${GLIBC_SHA256}  ${GLIBC_DEB}" | sha256sum -c -
dpkg-deb -x "$GLIBC_DEB" "$GLIBC_ROOT"

LOADER="$(find "$GLIBC_ROOT" -type f \( -name 'ld-linux-aarch64.so.1' -o -name 'ld-*.so' \) -print -quit)"
[[ -n "$LOADER" ]] || { echo "glibc loader not found in package" >&2; exit 5; }

LIB_ROOT="$(dirname "$LOADER")"
echo "[5/6] Computing direct DT_NEEDED closure"
mapfile -t NEEDED < <(readelf -d "$ENGINE" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort -u)
MISSING=()
FOUND=()
for lib in "${NEEDED[@]}"; do
  path="$(find "$GLIBC_ROOT" -type f -o -type l | grep "/${lib}$" | head -n 1 || true)"
  if [[ -n "$path" ]]; then
    FOUND+=("$lib")
  else
    MISSING+=("$lib")
  fi
done

printf '%s\n' "${FOUND[@]:-}" > "$OUT_DIR/found-libs.txt"
printf '%s\n' "${MISSING[@]:-}" > "$OUT_DIR/missing-libs.txt"

ENGINE_SHA256="$(sha256sum "$ENGINE" | awk '{print $1}')"
LOADER_SHA256="$(sha256sum "$LOADER" | awk '{print $1}')"

python3 - "$REPORT" <<PY
import json
from pathlib import Path
report = {
  "antigravity": {
    "tag": ${AGY_TAG@Q},
    "archive_sha256": ${AGY_ARCHIVE_SHA256@Q},
    "engine_sha256": ${ENGINE_SHA256@Q},
    "machine": ${MACHINE@Q},
    "class": ${CLASS@Q},
    "interpreter": ${INTERP@Q},
  },
  "glibc": {
    "version": ${GLIBC_VERSION@Q},
    "package_sha256": ${GLIBC_SHA256@Q},
    "loader_sha256": ${LOADER_SHA256@Q},
    "packages_index": ${PACKAGES_URL@Q},
  },
  "direct_needed": Path(${OUT_DIR@Q} + "/found-libs.txt").read_text().splitlines(),
  "missing_needed": Path(${OUT_DIR@Q} + "/missing-libs.txt").read_text().splitlines(),
}
Path(${REPORT@Q}).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
PY

cat "$REPORT"

if [[ -s "$OUT_DIR/missing-libs.txt" ]]; then
  echo "[FAIL] direct dependency closure is incomplete" >&2
  exit 6
fi

echo "[6/6] Probe succeeded"
