#!/usr/bin/env python3
"""Ensure the local mobile lab has its two account secrets in a .env file.

`ACCOUNTS_ENABLED=true` requires two different 32-byte hex secrets. This
script generates whichever ones are missing, empty or malformed, and never
prints the values. It is idempotent: a valid pair is left untouched, so
running it again does not rotate sessions or restart anything.

Usage: python3 scripts/lab_env_keys.py [path-to-env]
"""

from __future__ import annotations

import re
import secrets
import sys
from pathlib import Path

REQUIRED = ("ACCOUNTS_HMAC_KEY", "ACCOUNTS_PROXY_TOKEN")
VALID = re.compile(r"^[a-f0-9]{64}$")


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "services/api/.env")
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    index_of: dict[str, int] = {}
    values: dict[str, str] = {}
    for index, line in enumerate(lines):
        key, separator, value = line.partition("=")
        if separator and key in REQUIRED:
            index_of[key] = index
            values[key] = value.strip()

    changed = False
    for key in REQUIRED:
        if VALID.fullmatch(values.get(key, "")):
            continue
        values[key] = secrets.token_hex(32)
        if key in index_of:
            lines[index_of[key]] = f"{key}={values[key]}"
        else:
            lines.append(f"{key}={values[key]}")
        changed = True
        print(f"generated {key}")

    # The API refuses to start when both secrets are equal, so a hand-edited
    # file that copied one into the other is repaired here instead of at boot.
    if values["ACCOUNTS_HMAC_KEY"] == values["ACCOUNTS_PROXY_TOKEN"]:
        key = "ACCOUNTS_PROXY_TOKEN"
        values[key] = secrets.token_hex(32)
        lines[index_of[key]] = f"{key}={values[key]}"
        changed = True
        print(f"regenerated {key} (it must differ from ACCOUNTS_HMAC_KEY)")

    if changed:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"updated {path}")
    else:
        print(f"{path} already has a valid lab secret pair")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
