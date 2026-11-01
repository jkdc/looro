"""Apply a product rebrand from branding.properties to live content.

The brand lives in one file: branding.properties at the repo root. After
editing it, run:

    python3 scripts/apply_branding.py --from-brand <OldName>

Then regenerate the Xcode project and run the suites:

    python3 scripts/generate_project.py
    (apps/android) ./gradlew :app:testDebugUnitTest :app:lintDebug
    (services/api) docker suite — see services/api/README.md
    python3 scripts/check_cross_platform.py

Only user-visible content and identity values are rewritten. Files, classes,
packages, resources, build targets and backend identifiers are generic on
purpose (see openspec/changes/brand-constants) and never change on a rename.
Historical records, license notices, vendored third-party trees and this
repo's working-analysis informes are exempt.
"""

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

# Never rewritten: dated historical records, license notices, vendored
# third-party trees, closed change records, working informes, the constants
# file itself (it is the source, not a copy).
EXCLUDE_PARTS = {
    "verification", "evidence", "third_party", "node_modules", ".gradle",
    ".cxx", "build", ".git",
}
EXCLUDE_FILE_PATTERNS = [
    r"^LICENSE", r"^informe-", r"^branding\.properties$",
    r".*-audit.*\.md$", r"App-LICENSE\.txt$",
]
EXCLUDE_PATH_PATTERNS = [
    r"^release/android/",
    r"^release/screenshots/",
    r"^openspec/changes/",
]


def load_branding() -> dict:
    props = {}
    for line in (REPO / "branding.properties").read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    required = ["APP_NAME", "IOS_BUNDLE_ID", "ANDROID_APPLICATION_ID", "SUPPORT_EMAIL"]
    missing = [key for key in required if not props.get(key)]
    if missing:
        sys.exit(f"branding.properties is missing: {', '.join(missing)}")
    return props


def is_text(path: pathlib.Path) -> bool:
    try:
        return b"\x00" not in path.read_bytes()
    except OSError:
        return False


def excluded(rel: str) -> bool:
    parts = pathlib.PurePosixPath(rel).parts
    if EXCLUDE_PARTS & set(parts):
        return True
    if any(re.search(pattern, rel) for pattern in EXCLUDE_FILE_PATTERNS):
        return True
    if any(re.match(pattern, rel) for pattern in EXCLUDE_PATH_PATTERNS):
        return True
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-brand", required=True, help="current brand name, e.g. Palique")
    args = parser.parse_args()

    branding = load_branding()
    new_name = branding["APP_NAME"]
    old = args.from_brand
    if old == new_name:
        sys.exit(f"--from-brand is already APP_NAME ({new_name}); nothing to do")

    old_lower = old.lower()
    new_lower = new_name.lower()
    base = branding.get("REVERSE_DOMAIN_BASE", "com.example")
    old_bundle = f"{base}.{old_lower}"
    old_app_id = f"{base}.{old_lower}.android"

    # Ordered: identity literals first, then word tokens.
    rules = [
        (re.escape(old_app_id), branding["ANDROID_APPLICATION_ID"]),
        (re.escape(old_bundle), branding["IOS_BUNDLE_ID"]),
        (rf"\b{re.escape(old)}\b", new_name),
        (rf"\b{re.escape(old_lower)}\b", new_lower),
        (rf"\b{re.escape(old.upper())}_", f"{new_name.upper()}_"),
    ]

    changed = 0
    for path in sorted(REPO.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(REPO).as_posix()
        if excluded(rel) or not is_text(path):
            continue
        text = path.read_text(errors="surrogateescape")
        new_text = text
        for pattern, replacement in rules:
            new_text = re.sub(pattern, replacement, new_text)
        if new_text != text:
            path.write_text(new_text, errors="surrogateescape")
            changed += 1
            print(f"rewrote {rel}")

    print(f"\n{changed} files rebranded to {new_name!r}.")
    print("Follow-ups:")
    print("  python3 scripts/generate_project.py")
    print("  (apps/android) ./gradlew :app:testDebugUnitTest :app:lintDebug")
    print("  backend docker suite (services/api/README.md)")
    print("  python3 scripts/check_cross_platform.py")


if __name__ == "__main__":
    main()
