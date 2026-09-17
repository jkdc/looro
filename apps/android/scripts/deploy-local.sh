#!/usr/bin/env bash
# Full Mural Android deployment: build -> install -> grant mic -> models -> launch.
# Called by the Makefile; prefer `make deploy ENGINE=… SERIAL=…`.
#
# Env:
#   ENGINE            openai (default) | deepseek | glm
#   SERIAL            optional device serial (validated; auto-picked when only one)
#   DEPLOY_NO_BUILD   1 = reuse the existing APK
#
# The on-device llama.cpp engine (ENGINE=local) was removed on 2026-09-17.
# deepseek/glm voice sessions still run whisper STT + system TTS on device.
#
# API keys: remote engines read keys from api-keys.properties (untracked).
# Copy api-keys.properties.example and fill in your key before deploying.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="chat.mural.android"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
ENGINE="${ENGINE:-${MURAL_CONVERSATION_ENGINE:-openai}}"
SERIAL="${SERIAL:-}"
DEPLOY_NO_BUILD="${DEPLOY_NO_BUILD:-0}"

command -v adb >/dev/null || { echo "adb not found in PATH"; exit 1; }
DEVICE="$(bash "$(dirname "$0")/adb-device.sh" "$SERIAL")"
ADB="adb -s $DEVICE"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✔\033[0m %s\n' "$*"; }

bold "[1/4] Building (ENGINE=$ENGINE)"
if [ "$DEPLOY_NO_BUILD" != "1" ]; then
  (cd "$ROOT" && ./gradlew -q :app:assembleDebug -Pmural.conversationEngine="$ENGINE") \
    || { echo "Build failed"; exit 1; }
  ok "Built $(basename "$APK")"
else
  [ -f "$APK" ] || { echo "--skip-build but no APK found"; exit 1; }
  ok "Skipped (existing APK)"
fi

bold "[2/4] Installing"
${ADB} install -r -t "$APK" >/dev/null
${ADB} shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
ok "Installed on $DEVICE + RECORD_AUDIO granted"

bold "[3/4] Models"
if [ "$ENGINE" = "openai" ]; then
  ok "No on-device models needed (engine=$ENGINE uses WebRTC voice)"
else
  # deepseek/glm voice runs whisper STT on device. The model dirs must be
  # created by the app itself (FUSE), so launch once first.
  ${ADB} shell am start -n "$PKG/chat.mural.MainActivity" >/dev/null
  sleep 6
  ${ADB} shell am force-stop "$PKG"
  SERIAL="$DEVICE" bash "$ROOT/scripts/push-local-models.sh"
fi
# Remote engines: push the API keys file if it exists locally.
KEYS_LOCAL="$ROOT/api-keys.properties"
KEYS_REMOTE="/sdcard/Android/data/$PKG/files/api_keys.properties"
if [ -f "$KEYS_LOCAL" ]; then
  ${ADB} push "$KEYS_LOCAL" "$KEYS_REMOTE" >/dev/null
  ok "api_keys.properties pushed ($(awk -F= 'NF>1{print $1}' "$KEYS_LOCAL" | paste -sd,))"
else
  echo "  NOTE: $KEYS_LOCAL not found. Remote engines need their key at runtime;"
  echo "        copy api-keys.properties.example, fill in your key (ZHIPUAI_API_KEY=... /"
  echo "        DEEPSEEK_API_KEY=...) and push: ${ADB} push $KEYS_LOCAL $KEYS_REMOTE"
fi

bold "[4/4] Launching"
${ADB} shell am force-stop "$PKG"
${ADB} shell am start -n "$PKG/chat.mural.MainActivity" >/dev/null
ok "App started with ENGINE=$ENGINE"

echo
echo "Metrics:  $ADB logcat -s MuralLocalEngine"
