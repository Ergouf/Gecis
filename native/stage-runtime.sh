#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE_DIR="${OUT_DIR:-$ROOT/native/.probe}"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
REPORT="$PROBE_DIR/runtime-probe.json"
ENGINE="$PROBE_DIR/antigravity/agy.va39"
GLIBC_ROOT="$PROBE_DIR/glibc-root"

[[ -f "$REPORT" ]] || { echo "runtime probe report missing; run native/probe-runtime.sh first" >&2; exit 2; }
[[ -f "$ENGINE" ]] || { echo "patched engine missing" >&2; exit 2; }

LOADER="$(find "$GLIBC_ROOT" -type f \( -name 'ld-linux-aarch64.so.1' -o -name 'ld-*.so' \) -print -quit)"
[[ -n "$LOADER" ]] || { echo "glibc loader missing" >&2; exit 2; }

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"

cp "$ENGINE" "$JNI_DIR/libgecis_agy.so"
cp "$LOADER" "$JNI_DIR/libgecis_ld.so"
chmod 0755 "$JNI_DIR/libgecis_agy.so" "$JNI_DIR/libgecis_ld.so"

while IFS= read -r lib; do
  [[ -n "$lib" ]] || continue
  src="$(find "$GLIBC_ROOT" \( -type f -o -type l \) -name "$lib" -print -quit)"
  [[ -n "$src" ]] || { echo "missing staged dependency: $lib" >&2; exit 3; }
  cp -L "$src" "$JNI_DIR/$lib"
done < "$PROBE_DIR/found-libs.txt"

python3 - "$JNI_DIR/runtime-manifest.json" "$REPORT" "$JNI_DIR" <<'PY'
import hashlib, json, sys
from pathlib import Path
manifest_path, report_path, root_path = map(Path, sys.argv[1:])
report = json.loads(report_path.read_text())
files = {}
for path in sorted(root_path.iterdir()):
    if path.is_file() and path.name != manifest_path.name:
        files[path.name] = {
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "size": path.stat().st_size,
        }
manifest = {
    "source": report,
    "files": files,
}
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
PY

echo "Staged runtime into $JNI_DIR"
ls -lh "$JNI_DIR"
