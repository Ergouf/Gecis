#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/native/payload.lock"

PROBE_DIR="${OUT_DIR:-$ROOT/native/.probe}"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
RUNTIME_ASSET_DIR="$ROOT/app/src/main/assets/runtime"
REPORT="$PROBE_DIR/runtime-probe.json"
ENGINE="$PROBE_DIR/antigravity/agy.va39"
GLIBC_ROOT="$PROBE_DIR/glibc-root"
STAGED_MANIFEST="$PROBE_DIR/staged-runtime-manifest.json"
CA_BUNDLE="$RUNTIME_ASSET_DIR/cacert.pem"

for cmd in patchelf readelf sha256sum python3 curl; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 2; }
done

[[ -f "$REPORT" ]] || { echo "runtime probe report missing; run native/probe-runtime.sh first" >&2; exit 2; }
[[ -f "$ENGINE" ]] || { echo "patched engine missing" >&2; exit 2; }
[[ ! -s "$PROBE_DIR/missing-libs.txt" ]] || { echo "runtime probe still reports missing dependencies" >&2; exit 2; }

LOADER="$(find "$GLIBC_ROOT" -type f \( -name 'ld-linux-aarch64.so.1' -o -name 'ld-*.so' \) -print -quit)"
[[ -n "$LOADER" ]] || { echo "glibc loader missing" >&2; exit 2; }

rm -rf "$JNI_DIR" "$RUNTIME_ASSET_DIR"
mkdir -p "$JNI_DIR" "$RUNTIME_ASSET_DIR"

cp "$ENGINE" "$JNI_DIR/libgecis_agy.so"
cp "$LOADER" "$JNI_DIR/libgecis_ld.so"
chmod 0755 "$JNI_DIR/libgecis_agy.so" "$JNI_DIR/libgecis_ld.so"

declare -A RENAME=()
while IFS= read -r lib; do
  [[ -n "$lib" ]] || continue
  safe="libgecis_${lib//./_}.so"
  RENAME["$lib"]="$safe"
  src="$(find "$GLIBC_ROOT" \( -type f -o -type l \) -name "$lib" -print -quit)"
  [[ -n "$src" ]] || { echo "missing staged dependency: $lib" >&2; exit 3; }
  cp -L "$src" "$JNI_DIR/$safe"
done < "$PROBE_DIR/found-libs.txt"

rewrite_needed() {
  local elf="$1"
  local old new
  mapfile -t needed < <(readelf -d "$elf" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
  for old in "${needed[@]}"; do
    new="${RENAME[$old]:-}"
    if [[ -n "$new" ]]; then
      patchelf --replace-needed "$old" "$new" "$elf"
    fi
  done
}

rewrite_needed "$JNI_DIR/libgecis_agy.so"
for elf in "$JNI_DIR"/libgecis_*.so; do
  [[ "$elf" == "$JNI_DIR/libgecis_ld.so" ]] && continue
  rewrite_needed "$elf"
done

# Termux glibc is compiled with a Termux-specific resolver path. Gecis cannot write
# there, and the absolute Android app-data path varies across users/devices. Rewrite
# every NUL-terminated resolver pathname inside the staged libc to the relative
# "resolv.conf". ProcessBuilder runs Antigravity from noBackupFilesDir, where the
# Android native layer writes the active network DNS servers before launch.
STAGED_LIBC="$JNI_DIR/${RENAME[libc.so.6]:-}"
[[ -f "$STAGED_LIBC" ]] || { echo "staged libc.so.6 alias missing" >&2; exit 5; }
python3 - "$STAGED_LIBC" "$PROBE_DIR/resolver-patch.txt" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
report = Path(sys.argv[2])
data = bytearray(path.read_bytes())
replacement = b"resolv.conf"
changes = []

cursor = 0
original = bytes(data)
for chunk in original.split(b"\0"):
    pos = original.find(chunk, cursor)
    cursor = pos + len(chunk) + 1
    if not chunk.endswith(b"/resolv.conf"):
        continue
    if len(chunk) < len(replacement):
        raise SystemExit(f"resolver path too short to rewrite safely: {chunk!r}")
    data[pos:pos + len(chunk)] = replacement + (b"\0" * (len(chunk) - len(replacement)))
    changes.append(chunk.decode("utf-8", errors="replace"))

if not changes:
    raise SystemExit("no absolute resolv.conf path found in staged libc; refusing an unverified DNS runtime")

path.write_bytes(data)
if replacement not in path.read_bytes():
    raise SystemExit("resolver rewrite verification failed")
report.write_text("\n".join(changes) + "\n")
print("Patched glibc resolver path(s):")
for value in changes:
    print(f"  {value} -> resolv.conf")
PY

UNRESOLVED="$PROBE_DIR/unresolved-staged-needed.txt"
: > "$UNRESOLVED"
for elf in "$JNI_DIR"/libgecis_*.so; do
  while IFS= read -r dep; do
    [[ -n "$dep" ]] || continue
    if [[ -n "${RENAME[$dep]:-}" ]]; then
      printf '%s -> %s\n' "$(basename "$elf")" "$dep" >> "$UNRESOLVED"
    fi
  done < <(readelf -d "$elf" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
done
if [[ -s "$UNRESOLVED" ]]; then
  cat "$UNRESOLVED" >&2
  echo "unresolved original glibc DT_NEEDED entries remain" >&2
  exit 4
fi

# Bundle the same pinned Mozilla CA roots used by the current Termux glibc
# ca-certificates recipe. This is a build-time download only.
CA_URL="https://curl.se/ca/cacert-${CA_BUNDLE_DATE}.pem"
echo "Bundling pinned CA roots ${CA_BUNDLE_DATE}"
curl -fL --retry 3 --retry-delay 2 "$CA_URL" -o "$CA_BUNDLE"
echo "${CA_BUNDLE_SHA256}  ${CA_BUNDLE}" | sha256sum -c -

python3 - "$STAGED_MANIFEST" "$REPORT" "$JNI_DIR" "$CA_BUNDLE" "$PROBE_DIR/resolver-patch.txt" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path
manifest_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])
root_path = Path(sys.argv[3])
ca_path = Path(sys.argv[4])
resolver_report = Path(sys.argv[5])
report = json.loads(report_path.read_text())
files = {}
for path in sorted(root_path.iterdir()):
    if not path.is_file():
        continue
    needed = []
    try:
        out = subprocess.check_output(['readelf', '-d', str(path)], text=True, stderr=subprocess.DEVNULL)
        for line in out.splitlines():
            if 'Shared library:' in line and '[' in line and ']' in line:
                needed.append(line.split('[', 1)[1].split(']', 1)[0])
    except subprocess.CalledProcessError:
        pass
    files[path.name] = {
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'size': path.stat().st_size,
        'needed': needed,
    }
manifest = {
    'source': report,
    'files': files,
    'resolver_paths_rewritten': [x for x in resolver_report.read_text().splitlines() if x],
    'ca_bundle': {
        'sha256': hashlib.sha256(ca_path.read_bytes()).hexdigest(),
        'size': ca_path.stat().st_size,
    },
}
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
PY

echo "Staged Android-safe runtime into $JNI_DIR"
echo "Bundled CA roots into $CA_BUNDLE"
echo "Manifest: $STAGED_MANIFEST"
ls -lh "$JNI_DIR" "$CA_BUNDLE"
