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
LIB_MAP="$RUNTIME_ASSET_DIR/native-libs.map"
NETWORK_PATCH_REPORT="$PROBE_DIR/resolver-patch.txt"

for cmd in readelf sha256sum python3 curl; do
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

# Android packaging expects native payload file names that look like lib*.so. Do not mutate
# DT_NEEDED inside glibc/Antigravity to achieve that: real-device testing showed the rewritten
# dynamic string table could yield corrupted dependency names and loader crashes. Instead keep
# every ELF byte-for-byte (except the fixed Termux network-config paths below), store it under an
# Android-safe file name, and generate a map. Kotlin recreates the original soname names as
# symlinks in a private runtime directory before invoking the glibc loader.
: > "$LIB_MAP"
declare -A SAFE_NAME=()
while IFS= read -r lib; do
  [[ -n "$lib" ]] || continue
  safe="libgecis_${lib//./_}.so"
  SAFE_NAME["$lib"]="$safe"
  src="$(find "$GLIBC_ROOT" \( -type f -o -type l \) -name "$lib" -print -quit)"
  [[ -n "$src" ]] || { echo "missing staged dependency: $lib" >&2; exit 3; }
  cp -L "$src" "$JNI_DIR/$safe"
  chmod 0755 "$JNI_DIR/$safe"
  printf '%s\t%s\n' "$lib" "$safe" >> "$LIB_MAP"
done < "$PROBE_DIR/found-libs.txt"

# Termux glibc is compiled with Termux-specific absolute paths for resolver/NSS files. A standalone
# APK cannot populate those paths. Patch only NUL-terminated path strings to short relative names;
# ProcessBuilder runs Antigravity from noBackupFilesDir, where Kotlin writes resolv.conf, hosts and
# nsswitch.conf. This leaves ELF headers, dynamic strings and DT_NEEDED untouched.
STAGED_LIBC="$JNI_DIR/${SAFE_NAME[libc.so.6]:-}"
[[ -f "$STAGED_LIBC" ]] || { echo "staged libc.so.6 alias missing" >&2; exit 5; }
python3 - "$STAGED_LIBC" "$NETWORK_PATCH_REPORT" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
report = Path(sys.argv[2])
data = bytearray(path.read_bytes())
original = bytes(data)

targets = {
    b"/resolv.conf": b"resolv.conf",
    b"/hosts": b"hosts",
    b"/nsswitch.conf": b"nsswitch.conf",
}
changes = []
counts = {suffix: 0 for suffix in targets}

cursor = 0
for chunk in original.split(b"\0"):
    pos = original.find(chunk, cursor)
    cursor = pos + len(chunk) + 1
    if pos < 0 or not chunk:
        continue
    for suffix, replacement in targets.items():
        if not chunk.endswith(suffix):
            continue
        if len(chunk) < len(replacement):
            raise SystemExit(f"network config path too short to rewrite safely: {chunk!r}")
        data[pos:pos + len(chunk)] = replacement + (b"\0" * (len(chunk) - len(replacement)))
        changes.append((chunk.decode("utf-8", errors="replace"), replacement.decode()))
        counts[suffix] += 1
        break

if counts[b"/resolv.conf"] == 0:
    raise SystemExit("no absolute resolv.conf path found in staged libc")
if counts[b"/hosts"] == 0:
    raise SystemExit("no absolute hosts path found in staged libc; localhost would be unresolved")

path.write_bytes(data)
for replacement in (b"resolv.conf", b"hosts"):
    if replacement not in path.read_bytes():
        raise SystemExit(f"network config rewrite verification failed for {replacement!r}")

report.write_text("".join(f"{old} -> {new}\n" for old, new in changes))
print("Patched glibc network config path(s):")
for old, new in changes:
    print(f"  {old} -> {new}")
PY

# Verify that every DT_NEEDED entry in the staged payload is resolvable through the generated
# original-name -> Android-safe-file map. Keeping original names here is intentional.
UNRESOLVED="$PROBE_DIR/unresolved-staged-needed.txt"
: > "$UNRESOLVED"
python3 - "$JNI_DIR" "$LIB_MAP" "$UNRESOLVED" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
map_path = Path(sys.argv[2])
out = Path(sys.argv[3])
lib_map = {}
for line in map_path.read_text().splitlines():
    if not line.strip():
        continue
    original, safe = line.split('\t', 1)
    lib_map[original] = safe

pat = re.compile(r'Shared library: \[(.+?)\]')
missing = []
for elf in sorted(root.glob('libgecis_*.so')):
    try:
        text = subprocess.check_output(['readelf', '-d', str(elf)], text=True, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        continue
    for line in text.splitlines():
        match = pat.search(line)
        if not match:
            continue
        dep = match.group(1)
        safe = lib_map.get(dep)
        if not safe or not (root / safe).is_file():
            missing.append(f'{elf.name} -> {dep}')

out.write_text(''.join(f'{item}\n' for item in missing))
if missing:
    raise SystemExit('staged runtime has unmapped DT_NEEDED entries:\n' + '\n'.join(missing))
PY

# Bundle the same pinned Mozilla CA roots used by the current Termux glibc ca-certificates recipe.
# This is a build-time download only.
CA_URL="https://curl.se/ca/cacert-${CA_BUNDLE_DATE}.pem"
echo "Bundling pinned CA roots ${CA_BUNDLE_DATE}"
curl -fL --retry 3 --retry-delay 2 "$CA_URL" -o "$CA_BUNDLE"
echo "${CA_BUNDLE_SHA256}  ${CA_BUNDLE}" | sha256sum -c -

python3 - "$STAGED_MANIFEST" "$REPORT" "$JNI_DIR" "$CA_BUNDLE" "$NETWORK_PATCH_REPORT" "$LIB_MAP" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path
manifest_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])
root_path = Path(sys.argv[3])
ca_path = Path(sys.argv[4])
network_report = Path(sys.argv[5])
map_path = Path(sys.argv[6])
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

lib_map = {}
for line in map_path.read_text().splitlines():
    if line.strip():
        original, safe = line.split('\t', 1)
        lib_map[original] = safe

manifest = {
    'source': report,
    'files': files,
    'runtime_library_map': lib_map,
    'network_config_paths_rewritten': [x for x in network_report.read_text().splitlines() if x],
    'ca_bundle': {
        'sha256': hashlib.sha256(ca_path.read_bytes()).hexdigest(),
        'size': ca_path.stat().st_size,
    },
}
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
PY

echo "Staged Android-safe runtime into $JNI_DIR"
echo "Runtime soname map: $LIB_MAP"
echo "Bundled CA roots into $CA_BUNDLE"
echo "Manifest: $STAGED_MANIFEST"
ls -lh "$JNI_DIR" "$CA_BUNDLE" "$LIB_MAP"
