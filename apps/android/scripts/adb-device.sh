#!/usr/bin/env bash
# Resolves the adb device a Make target should use.
#   $1 optional explicit serial (validated against connected devices)
# With exactly one device connected, it is auto-selected.
# Prints the serial to stdout; errors to stderr with guidance and exits 1.
set -euo pipefail

serial="${1:-}"
mapfile -t devs < <(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')

if [ -n "$serial" ]; then
  for d in "${devs[@]:-}"; do
    if [ "$d" = "$serial" ]; then
      echo "$serial"
      exit 0
    fi
  done
  echo "Device '$serial' is not connected." >&2
  echo "Connected devices: ${devs[*]:-none}" >&2
  exit 1
fi

if [ "${#devs[@]}" -eq 1 ]; then
  selected="${devs[0]}"
  echo "Using device: $selected" >&2
  if [[ "$selected" == emulator-* ]]; then
    echo "NOTE: this is an emulator. To target a physical phone: make deploy SERIAL=<serial>" >&2
  fi
  echo "$selected"
  exit 0
fi

if [ "${#devs[@]}" -eq 0 ]; then
  echo "No device connected. Plug the phone (USB debugging on) or start an emulator." >&2
else
  echo "Multiple devices connected. Pick one:" >&2
  for d in "${devs[@]}"; do
    echo "  make deploy SERIAL=$d" >&2
  done
fi
exit 1
