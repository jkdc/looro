#!/usr/bin/env bash
# Deployment gate for the remote (Coolify) APK: refuses to ship a build that
# does not point at the deployed backend, carry a format-valid Google client ID
# (without it ManagedAccountConfiguration.parse returns null and the app never
# talks to the server), and use the api proxy engine (whisper STT stays on
# device; only the LLM call goes through the backend, which resolves the
# provider/model from its admin panel — never a direct provider connection).
#
# Greps the extracted dex files directly (never through a shell variable:
# echoing a ~14MB blob truncates and makes grep -q unreliable).
#
# Usage: verify-remote-apk.sh <apk> <origin-host> <client-id-suffix> <engine>
#   origin-host       e.g. palique-api.46.202.135.91.sslip.io
#   client-id         full id, e.g. 1234567890-paliquetest.apps.googleusercontent.com
#   engine            e.g. api (the marker proves the engine wiring exists;
#                        the SELECTED engine is enforced by the build command
#                        itself: always pass -Papp.conversationEngine=api)
set -euo pipefail

APK="${1:?Usage: verify-remote-apk.sh <apk> <origin-host> <client-id-suffix> <engine>}"
ORIGIN_HOST="${2:?Missing origin host}"
CLIENT_ID="${3:?Missing client id}"
CLIENT_SUFFIX="${CLIENT_ID#*-}"; CLIENT_SUFFIX="${CLIENT_SUFFIX%%.*}"
ENGINE="${4:?Missing engine}"

fail() { echo "[verify-remote-apk] FAIL: $1" >&2; exit 1; }
[ -f "$APK" ] || fail "APK not found: $APK"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q "$APK" 'classes*.dex' -d "$tmp"

found_in_dex() {
  # grep -q sobre dex binarios es no determinista (a veces no ve el match);
  # -a -c por fichero lee siempre el contenido completo y es estable.
  local count
  count=$(grep -a -c "$1" "$tmp"/classes*.dex 2>/dev/null | awk -F: '{ s += $NF } END { print s + 0 }')
  [ "${count:-0}" -ge 1 ]
}

found_in_dex "$ORIGIN_HOST" || fail "APK does not contain the API origin host '$ORIGIN_HOST' (build without -Papp.apiOrigin?)"
found_in_dex "${CLIENT_SUFFIX}.apps.googleusercontent.com" || fail "APK does not contain the Google client ID (build without -Papp.googleServerClientID? the account client silently disables)"
ENGINE_MARKER=""; case "$ENGINE" in
  api) ENGINE_MARKER="api engine needs the app account session source" ;;
  hosted-proxy) ENGINE_MARKER="hosted-proxy engine needs the app account session source" ;;
  glm) ENGINE_MARKER="Zhipu GLM 5.3 Flash" ;;
  *) fail "No known marker for engine '"$ENGINE"' (valid: api, hosted-proxy, glm)" ;;
esac
found_in_dex "$ENGINE_MARKER" || fail "APK does not wire the '"$ENGINE"' conversation engine (build without -Papp.conversationEngine='"$ENGINE"'?)"

echo "[verify-remote-apk] OK: origin, client id and engine present in $APK"
