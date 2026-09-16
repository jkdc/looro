#!/usr/bin/env bash
# Pushes local GGUF models to the Mural app's private external storage.
#
# Target layout (app-private, writable by adb):
#   /sdcard/Android/data/chat.mural.android/files/models/llm/<file>.gguf
#
# Files are taken from apps/android/local-models/ (gitignored).
# Only changed files (size differs) are pushed, so re-deploys are cheap.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"   # apps/android
SRC="$ROOT/local-models"
PKG="chat.mural.android"
DEST="/sdcard/Android/data/$PKG/files/models/llm"
SERIAL="${SERIAL:-}"

ADB="adb"
[ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

mkdir -p "$SRC"
command -v "$ADB" >/dev/null || { echo "adb not found"; exit 1; }
"$ADB" shell mkdir -p "$DEST"

pushed=0
for f in "$SRC"/*.gguf; do
  [ -e "$f" ] || { echo "No .gguf files in $SRC"; echo "Place your model there first (e.g. qwen2.5-3b-instruct-q4_k_m.gguf, ~2 GB)."; exit 1; }
  name="$(basename "$f")"
  local_size="$(stat -c %s "$f")"
  remote_size="$("$ADB" shell stat -c %s "$DEST/$name" 2>/dev/null | tr -d '\r')" || remote_size=""
  if [[ "$remote_size" == "$local_size" ]]; then
    echo "[skip] $name already on device"
  else
    echo "[push] $name ($(du -h "$f" | cut -f1))..."
    "$ADB" push "$f" "$DEST/" >/dev/null
    echo "[ok]   $name pushed"
    pushed=$((pushed+1))
  fi
done
echo "Done ($pushed pushed). Restart the app to load models:"
echo "  $ADB shell am force-stop $PKG"
