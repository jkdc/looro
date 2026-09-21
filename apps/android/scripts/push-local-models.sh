#!/usr/bin/env bash
# Pushes the whisper STT model to the Looro app's private external storage.
#
# The on-device LLM (.gguf) push was removed on 2026-09-17 together with the
# local llama.cpp engine; this script is now whisper-only (deepseek/glm voice
# runs STT on device, the LLM remotely).
#
# Target layout (app-private, writable by adb):
#   /sdcard/Android/data/com.jkdc.looro.android/files/models/whisper/<file>.bin
#
# Files are taken from apps/android/local-models/ (gitignored).
# Only changed files (size differs) are pushed, so re-deploys are cheap.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"   # apps/android
SRC="$ROOT/local-models"
PKG="com.jkdc.looro.android"
DEST="/sdcard/Android/data/$PKG/files/models"
SERIAL="${SERIAL:-}"

# Multilingual Whisper (STT leg). Downloaded on first run only.
WHISPER_NAME="ggml-base-q5_1.bin"
WHISPER_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$WHISPER_NAME"

command -v adb >/dev/null || { echo "adb not found"; exit 1; }
DEVICE="$(bash "$(dirname "$0")/adb-device.sh" "$SERIAL")"
ADB="adb -s $DEVICE"

mkdir -p "$SRC"
${ADB} shell mkdir -p "$DEST/whisper"

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

# STT model
push_one "$SRC/$WHISPER_NAME" "whisper"
echo "Done ($pushed pushed). Restart the app to load models:"
echo "  $ADB shell am force-stop $PKG"
