#!/usr/bin/env python3
"""Conservative gate validator; standard library only. No automatic gate transitions."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATES = ROOT / "docs/governance/gates.yaml"
CHECKLIST = ROOT / "docs/governance/G2-acceptance.md"


def read_flag(text, key):
    for line in text.splitlines():
        if line.strip().startswith(key + ":"):
            return line.split(":", 1)[1].strip().lower()
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-build", action="store_true")
    args = parser.parse_args()
    if not GATES.exists() or not CHECKLIST.exists():
        print("BLOCKED: missing governance artifacts")
        return 2
    text = GATES.read_text(encoding="utf-8")
    checklist = CHECKLIST.read_text(encoding="utf-8")
    print("Nomad Tax Calculation Engine — gate status")
    for line in text.splitlines():
        if "status:" in line or "implementation_" in line:
            print(" ", line.strip())
    open_items = sum(1 for line in checklist.splitlines() if line.lstrip().startswith("- [ ]"))
    print(f"G2 technical checklist: {open_items} unchecked items")
    if args.require_build:
        if read_flag(text, "implementation_unlocked") != "true" or open_items:
            print("BLOCKED: G2 technical evidence is incomplete or implementation_unlocked is not true")
            return 3
        print("Gate flag and checklist satisfied; verify supporting evidence and authorization independently.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
