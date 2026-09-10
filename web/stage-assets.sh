#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/web/payload.lock"

OUT_DIR="${WEB_OUT_DIR:-$ROOT/web/.stage}"
INSTALL_DIR="$OUT_DIR/npm"
VENDOR_DIR="$ROOT/app/src/main/assets/vendor"
MANIFEST="$OUT_DIR/web-assets-manifest.json"

for cmd in npm node python3 sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 2; }
done

rm -rf "$INSTALL_DIR" "$VENDOR_DIR"
mkdir -p "$INSTALL_DIR" "$VENDOR_DIR/katex" "$VENDOR_DIR/marked" "$VENDOR_DIR/dompurify" "$VENDOR_DIR/licenses"

echo "Staging local WebView renderer dependencies"
npm install \
  --prefix "$INSTALL_DIR" \
  --ignore-scripts \
  --no-audit \
  --no-fund \
  --omit=dev \
  --save=false \
  "katex@${KATEX_VERSION}" \
  "marked@${MARKED_VERSION}" \
  "dompurify@${DOMPURIFY_VERSION}"

node - "$INSTALL_DIR" "$KATEX_VERSION" "$MARKED_VERSION" "$DOMPURIFY_VERSION" <<'NODE'
const fs = require('fs');
const path = require('path');
const [root, katex, marked, purify] = process.argv.slice(2);
for (const [name, expected] of [['katex', katex], ['marked', marked], ['dompurify', purify]]) {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'node_modules', name, 'package.json'), 'utf8'));
  if (pkg.version !== expected) {
    throw new Error(`${name}: expected ${expected}, got ${pkg.version}`);
  }
}
NODE

KATEX="$INSTALL_DIR/node_modules/katex"
MARKED="$INSTALL_DIR/node_modules/marked"
PURIFY="$INSTALL_DIR/node_modules/dompurify"

cp "$KATEX/dist/katex.min.css" "$VENDOR_DIR/katex/katex.min.css"
cp "$KATEX/dist/katex.min.js" "$VENDOR_DIR/katex/katex.min.js"
cp "$KATEX/dist/contrib/auto-render.min.js" "$VENDOR_DIR/katex/auto-render.min.js"
cp -R "$KATEX/dist/fonts" "$VENDOR_DIR/katex/fonts"
cp "$MARKED/lib/marked.umd.js" "$VENDOR_DIR/marked/marked.umd.js"
cp "$PURIFY/dist/purify.min.js" "$VENDOR_DIR/dompurify/purify.min.js"

# Preserve license notices next to the bundled third-party code when the package contains them.
for spec in "katex:$KATEX" "marked:$MARKED" "dompurify:$PURIFY"; do
  name="${spec%%:*}"
  dir="${spec#*:}"
  license="$(find "$dir" -maxdepth 1 -type f \( -iname 'license' -o -iname 'license.*' \) -print -quit || true)"
  if [[ -n "$license" ]]; then
    cp "$license" "$VENDOR_DIR/licenses/${name}-$(basename "$license")"
  fi
done

mkdir -p "$OUT_DIR"
python3 - "$VENDOR_DIR" "$MANIFEST" "$KATEX_VERSION" "$MARKED_VERSION" "$DOMPURIFY_VERSION" <<'PY'
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
out = Path(sys.argv[2])
versions = {
    'katex': sys.argv[3],
    'marked': sys.argv[4],
    'dompurify': sys.argv[5],
}
files = {}
for path in sorted(root.rglob('*')):
    if path.is_file():
        rel = path.relative_to(root).as_posix()
        files[rel] = {
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'size': path.stat().st_size,
        }
out.write_text(json.dumps({'versions': versions, 'files': files}, indent=2) + '\n')
print(f'Staged {len(files)} local WebView assets')
print(f'Manifest: {out}')
PY
