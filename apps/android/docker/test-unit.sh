#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
IMAGE="looro-android-test:agp-8.10.1-sdk-36"
CACHE_VOLUME="looro-gradle-cache"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

docker build --pull=false \
  --file "${ROOT}/apps/android/docker/Dockerfile.test" \
  --tag "${IMAGE}" \
  "${ROOT}/apps/android/docker"
docker volume create "${CACHE_VOLUME}" >/dev/null
# Initialize/update the named volume for the invoking host user; only cache data is changed.
docker run --rm --pull never --network none \
  --user 0:0 \
  --env "HOST_UID=${HOST_UID}" --env "HOST_GID=${HOST_GID}" \
  --volume "${CACHE_VOLUME}:/gradle-cache" \
  --entrypoint bash "${IMAGE}" \
  -lc 'if [ "$(stat -c %u:%g /gradle-cache)" != "${HOST_UID}:${HOST_GID}" ]; then chown -R "${HOST_UID}:${HOST_GID}" /gradle-cache; fi'
docker run --rm --pull never --network bridge \
  --user "${HOST_UID}:${HOST_GID}" \
  --env GRADLE_USER_HOME=/gradle-cache \
  --env HOME=/tmp/gradle-home \
  --env ANDROID_USER_HOME=/tmp/gradle-home/.android \
  --volume "${ROOT}:/workspace" \
  --volume "${CACHE_VOLUME}:/gradle-cache" \
  --workdir /workspace/apps/android \
  --entrypoint bash "${IMAGE}" \
  -c 'mkdir -p "$HOME/.android" && exec ./gradlew --no-daemon :app:testDebugUnitTest :core:engine-local:testDebugUnitTest'
