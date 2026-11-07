#!/usr/bin/env bash
# Deploy the debug app to a connected device against the LOCAL LAB API:
# build (lab origin + conversation engine), install, grant the microphone,
# push the test engine keys, push on-device models when needed, launch.
#   --origin URL    lab API origin, e.g. http://192.168.0.18:8080 (required)
#   --engine E      conversation engine (default glm; valid: openai|deepseek|glm)
#   --serial S      adb device serial (auto-selected when only one device)
#   --account       adopt a fresh lab account after launch (needs the lab stack up)
#
# Engine keys come from apps/android/api-keys.properties (untracked) pushed to
# the app's external files dir, exactly like deploy-local.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"            # apps/android
REPO="$(cd "$ROOT/../.." && pwd)"
PKG="$(sed -n 's/^ANDROID_APPLICATION_ID=//p' "$REPO/branding.properties")"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

origin=""; engine="glm"; serial=""; account=0
while [ $# -gt 0 ]; do
  case "$1" in
    --origin) origin="$2"; shift 2 ;;
    --engine) engine="$2"; shift 2 ;;
    --serial) serial="$2"; shift 2 ;;
    --account) account=1; shift ;;
    *) echo "unknown flag '$1'" >&2; exit 1 ;;
  esac
done
[ -n "$origin" ] || { echo "--origin is required (e.g. http://192.168.0.18:8080)" >&2; exit 1; }
case "$engine" in openai|deepseek|glm) ;; *) echo "unknown engine '$engine' (valid: openai, deepseek, glm)" >&2; exit 2 ;; esac

command -v adb >/dev/null || { echo "adb not found in PATH" >&2; exit 1; }
DEVICE="$(bash "$ROOT/scripts/adb-device.sh" "$serial")"
ADB="adb -s $DEVICE"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✔\033[0m %s\n' "$*"; }

bold "[1/4] Building (ENGINE=$engine, ORIGIN=$origin)"
(cd "$ROOT" && ./gradlew -q :app:assembleDebug -Papp.apiOrigin="$origin" -Papp.conversationEngine="$engine") \
  || { echo "Build failed" >&2; exit 1; }
ok "Built $(basename "$APK")"

bold "[2/4] Installing on $DEVICE"
"$ADB" install -r -t "$APK" >/dev/null
"$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
ok "Installed + RECORD_AUDIO granted"

KEYS_LOCAL="$ROOT/api-keys.properties"
if [ -f "$KEYS_LOCAL" ]; then
  "$ADB" push "$KEYS_LOCAL" "/sdcard/Android/data/$PKG/files/api_keys.properties" >/dev/null
  ok "Engine keys pushed ($(awk -F= 'NF>1{print $1}' "$KEYS_LOCAL" | paste -sd,))"
else
  echo "  NOTE: apps/android/api-keys.properties not found; remote engines will lack their key."
  echo "        Copy api-keys.properties.example and fill ZHIPUAI_API_KEY=... before deploying."
fi

bold "[3/4] Models"
if [ "$engine" = "openai" ]; then
  ok "No on-device models needed (engine=$engine uses WebRTC voice)"
else
  # deepseek/glm voice runs whisper STT on device; the model dirs must be
  # created by the app itself (FUSE), so launch once first.
  "$ADB" shell am start -n "$PKG/com.jkdc.app.MainActivity" >/dev/null
  sleep 6
  "$ADB" shell am force-stop "$PKG"
  SERIAL="$DEVICE" bash "$ROOT/scripts/push-local-models.sh"
fi

bold "[4/4] Launching"
"$ADB" shell am force-stop "$PKG"
if [ "$account" = "1" ]; then
  token="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  hash="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())' "$token")"
  id="$(cd "$REPO" && docker compose --project-directory services/api -f services/api/compose.yaml -f services/api/compose.lab.yaml \
    exec -T database psql -U app -d app -tA -v ON_ERROR_STOP=1 -v hash="$hash" -f - < services/api/lab-account.sql | tr -d '[:space:]')"
  case "$id" in
    ????????-????-????-????-????????????) ok "Lab account $id" ;;
    *) echo "Could not seed the lab account (is the lab stack up? make lab-up)" >&2; exit 1 ;;
  esac
  "$ADB" shell am start -n "$PKG/com.jkdc.app.MainActivity" --es debug.accountID "$id" --es debug.accountToken "$token" >/dev/null
else
  "$ADB" shell am start -n "$PKG/com.jkdc.app.MainActivity" >/dev/null
fi
ok "App started (ENGINE=$engine, ORIGIN=$origin)"
echo
echo "Logs:     adb -s $DEVICE logcat --pid=\$(adb -s $DEVICE shell pidof -s $PKG)"
