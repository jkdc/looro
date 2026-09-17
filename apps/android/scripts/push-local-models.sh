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
DEST="/sdcard/Android/data/$PKG/files/models"
SERIAL="${SERIAL:-}"

# Multilingual Whisper (STT leg). Downloaded on first run only.
WHISPER_NAME="ggml-base-q5_1.bin"
WHISPER_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$WHISPER_NAME"

command -v adb >/dev/null || { echo "adb not found"; exit 1; }
DEVICE="$(bash "$(dirname "$0")/adb-device.sh" "$SERIAL")"
ADB="adb -s $DEVICE"

mkdir -p "$SRC"
${ADB} shell mkdir -p "$DEST/llm" "$DEST/whisper"

if [ ! -f "$SRC/$WHISPER_NAME" ]; then
  echo "[down] $WHISPER_NAME (multilingual Whisper, ~60 MB)"
  curl -L --fail --retry 3 -o "$SRC/$WHISPER_NAME" "$WHISPER_URL"
fi

pushed=0
push_one() { # $1 local file, $2 remote subdir
  local f="$1" sub="$2"
  local name local_size remote_size
  name="$(basename "$f")"
  local_size="$(stat -c %s "$f")"
  remote_size="$(${ADB} shell stat -c %s "$DEST/$sub/$name" 2>/dev/null | tr -d '\r')" || remote_size=""
  if [[ "$remote_size" == "$local_size" ]]; then
    echo "[skip] $name already on device"
  else
    echo "[push] $name ($(du -h "$f" | cut -f1))..."
    ${ADB} push "$f" "$DEST/$sub/" >/dev/null
    echo "[ok]   $name pushed"
    pushed=$((pushed+1))
  fi
}

for f in "$SRC"/*.gguf; do
  [ -e "$f" ] || { echo "No .gguf files in $SRC"; echo "Place your model there first (e.g. qwen2.5-3b-instruct-q4_k_m.gguf, ~2 GB)."; exit 1; }
  push_one "$f" "llm"
done

# STT model
push_one "$SRC/$WHISPER_NAME" "whisper"
echo "Done ($pushed pushed). Restart the app to load models:"
echo "  $ADB shell am force-stop $PKG"
