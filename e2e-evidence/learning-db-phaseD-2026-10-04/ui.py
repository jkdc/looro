#!/usr/bin/env python3
"""UI driver for the phase-D backup e2e (emulator-5554, uiautomator-backed).

Usage: ui.py <command> [args]
  elements                 print text|bounds for every visible text node
  tap <text> [exact]       tap the first node whose text contains (or equals) <text>
  swipe                    scroll down (finger swipe up)
  shot <path>              screenshot to <path>
  wait <text> [seconds]    poll until <text> is visible
"""
import re
import subprocess
import sys
import time

SERIAL = "emulator-5554"


def adb(*args, binary=False):
    result = subprocess.run(
        ["adb", "-s", SERIAL, *args], capture_output=True, check=False)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    return adb("shell", "cat", "/sdcard/uidump.xml")


def elements():
    xml = dump()
    rows = []
    for m in re.finditer(r'<node[^>]*?text="([^"]+)"[^>]*?bounds="(\[[^"]*\])"', xml):
        text, bounds = m.groups()
        if text.strip():
            rows.append((text, bounds))
    return rows


def center(bounds):
    xs = bounds.replace("][", " ").strip("[]").replace(",", " ").split()
    return (int(xs[0]) + int(xs[2])) // 2, (int(xs[1]) + int(xs[3])) // 2


def find(want, exact=False):
    for text, bounds in elements():
        if (text == want) if exact else (want.lower() in text.lower()):
            return bounds
    return None


def tap(want, exact=False):
    bounds = find(want, exact)
    if bounds is None:
        print(f"MISS: {want}")
        return False
    x, y = center(bounds)
    adb("shell", "input", "tap", str(x), str(y))
    print(f"tapped {want!r} @ {x},{y}")
    return True


def swipe():
    adb("shell", "input", "swipe", "540", "1500", "540", "500", "400")
    print("scrolled")


def shot(path):
    open(path, "wb").write(adb("exec-out", "screencap", "-p", binary=True))
    print(f"shot {path}")


def wait(want, seconds=15):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if find(want):
            print(f"found {want!r}")
            return True
        time.sleep(1)
    print(f"TIMEOUT waiting for {want!r}")
    return False


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "elements":
        for text, bounds in elements():
            print(f"{text}|{bounds}")
    elif command == "tap":
        tap(sys.argv[2], len(sys.argv) > 3 and sys.argv[3] == "exact")
    elif command == "swipe":
        swipe()
    elif command == "shot":
        shot(sys.argv[2])
    elif command == "wait":
        wait(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 15)
    else:
        print(f"unknown command {command}")
        sys.exit(2)
