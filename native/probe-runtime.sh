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

write_lines() {
  local file="$1"
  shift
  : > "$file"
  if (($#)); then
    printf '%s\n' "$@" > "$file"
  fi
}

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
for key in ['Filename','SHA256','Version']:
    if key not in row:
        raise SystemExit(f'missing {key} in Packages index')
Path(sys.argv[3]).write_text('\n'.join([
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

echo "[5/6] Computing transitive DT_NEEDED closure"
python3 - "$ENGINE" "$GLIBC_ROOT" "$OUT_DIR" <<'PY'
import re, subprocess, sys
from pathlib import Path
engine = Path(sys.argv[1])
root = Path(sys.argv[2])
out = Path(sys.argv[3])
pat = re.compile(r'Shared library: \[(.+?)\]')

def needed(path):
    try:
        text = subprocess.check_output(['readelf','-d',str(path)], text=True, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return []
    return sorted({m.group(1) for m in map(pat.search, text.splitlines()) if m})

index = {}
for p in root.rglob('*'):
    if p.is_file() or p.is_symlink():
        index.setdefault(p.name, p)

queue = list(needed(engine))
seen = set()
found = {}
missing = set()
while queue:
    lib = queue.pop(0)
    if not lib or lib in seen:
        continue
    seen.add(lib)
    path = index.get(lib)
    if not path:
        missing.add(lib)
        continue
    found[lib] = path
    for dep in needed(path.resolve()):
        if dep not in seen:
            queue.append(dep)

(out / 'needed-libs.txt').write_text(''.join(f'{x}\n' for x in sorted(seen)))
(out / 'found-libs.txt').write_text(''.join(f'{x}\n' for x in sorted(found)))
(out / 'missing-libs.txt').write_text(''.join(f'{x}\n' for x in sorted(missing)))
(out / 'closure-paths.txt').write_text(''.join(f'{name}\t{path}\n' for name, path in sorted(found.items())))
PY

ENGINE_SHA256="$(sha256sum "$ENGINE" | awk '{print $1}')"
LOADER_SHA256="$(sha256sum "$LOADER" | awk '{print $1}')"
python3 - "$REPORT" <<PY
import json
from pathlib import Path

def lines(path):
    return [line for line in Path(path).read_text().splitlines() if line]

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
  "closure_needed": lines(${OUT_DIR@Q} + "/needed-libs.txt"),
  "closure_found": lines(${OUT_DIR@Q} + "/found-libs.txt"),
  "closure_missing": lines(${OUT_DIR@Q} + "/missing-libs.txt"),
}
Path(${REPORT@Q}).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
PY

cat "$REPORT"
if [[ -s "$OUT_DIR/missing-libs.txt" ]]; then
  echo "[FAIL] transitive dependency closure is incomplete" >&2
  exit 6
fi

echo "[6/6] Probe succeeded"
