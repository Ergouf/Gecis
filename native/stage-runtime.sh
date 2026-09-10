#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE_DIR="${OUT_DIR:-$ROOT/native/.probe}"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
REPORT="$PROBE_DIR/runtime-probe.json"
ENGINE="$PROBE_DIR/antigravity/agy.va39"
GLIBC_ROOT="$PROBE_DIR/glibc-root"
STAGED_MANIFEST="$PROBE_DIR/staged-runtime-manifest.json"

for cmd in patchelf readelf sha256sum python3; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 2; }
done

[[ -f "$REPORT" ]] || { echo "runtime probe report missing; run native/probe-runtime.sh first" >&2; exit 2; }
[[ -f "$ENGINE" ]] || { echo "patched engine missing" >&2; exit 2; }
[[ ! -s "$PROBE_DIR/missing-libs.txt" ]] || { echo "runtime probe still reports missing dependencies" >&2; exit 2; }

LOADER="$(find "$GLIBC_ROOT" -type f \( -name 'ld-linux-aarch64.so.1' -o -name 'ld-*.so' \) -print -quit)"
[[ -n "$LOADER" ]] || { echo "glibc loader missing" >&2; exit 2; }

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"

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

python3 - "$STAGED_MANIFEST" "$REPORT" "$JNI_DIR" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path
manifest_path, report_path, root_path = map(Path, sys.argv[1:])
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
manifest = {'source': report, 'files': files}
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
PY

echo "Staged Android-safe runtime into $JNI_DIR"
echo "Manifest: $STAGED_MANIFEST"
ls -lh "$JNI_DIR"
