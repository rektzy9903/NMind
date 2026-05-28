#!/usr/bin/env bash
# Convert any single-color SVG into an Android VectorDrawable for the
# brand-icon system. Designed for adding provider/model brand marks that
# aren't in Simple Icons (CC0) — Groq, Moonshot/Kimi, Cohere, etc.
#
# Usage:
#   scripts/add-brand-icon.sh <url-or-file>  <brand-name>
#
# Examples:
#   scripts/add-brand-icon.sh https://cdn.sanity.io/.../groq-mark.svg  groq
#   scripts/add-brand-icon.sh ~/Downloads/kimi.svg                     kimi
#
# Writes:
#   app/src/main/res/drawable/ic_brand_<brand-name>.xml
#
# After running, add the resource ID to Providers.kt:
#   iconResId = R.drawable.ic_brand_<brand-name>
# and to ModelPickerScreen.kt brandIconForModel() if it maps a model org.

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <url-or-file> <brand-name>"
  exit 1
fi

SRC="$1"
NAME="$2"
OUT="app/src/main/res/drawable/ic_brand_${NAME}.xml"

# Fetch (if URL) or copy (if file)
TMP="$(mktemp --suffix=.svg)"
if [[ "$SRC" =~ ^https?:// ]]; then
  curl -sL --max-time 15 "$SRC" -A "Mozilla/5.0" -o "$TMP"
else
  cp "$SRC" "$TMP"
fi

if [ ! -s "$TMP" ]; then
  echo "fetch failed or empty"
  exit 1
fi

python3 <<PYEOF
import re, sys
svg = open("$TMP").read()
vb = re.search(r'viewBox="([\d.\s\-]+)"', svg)
if vb:
    parts = vb.group(1).split()
    vw, vh = parts[2], parts[3]
else:
    vw = vh = "24"
paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
if not paths:
    print("no <path d=...> found in SVG", file=sys.stderr)
    sys.exit(1)
path_xml = "\n".join(
    f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{d}" />'
    for d in paths
)
open("$OUT", "w").write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Brand mark for ${NAME}. Sourced from the brand owner's published asset
     and used nominatively to identify ${NAME} in the provider/model picker. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
{path_xml}
</vector>
''')
print(f"wrote $OUT ({len(paths)} path(s), viewBox {vw}x{vh})")
PYEOF

rm -f "$TMP"
echo
echo "next: add iconResId = R.drawable.ic_brand_${NAME} to Providers.kt (or"
echo "tell me and I'll wire it up)"
