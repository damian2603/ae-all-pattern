#!/usr/bin/env python3
"""Fail CI if the no-GUI GameTest log reports fewer tests than the source declares.

The previous gate only required *any* positive count, so a profile that silently
skipped most mod-specific tests could still pass. Optional-mod tests call
helper.succeed() when their mod is absent, so every declared @GameTest should be
reported by Forge regardless of the active runtime profile.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ANNOTATION = re.compile(r"@GameTest\b")
PASSED = re.compile(r"All\s+(\d+)\s+required tests passed\s*:\)")


def declared_count(source_root: Path) -> int:
    return sum(len(ANNOTATION.findall(path.read_text(encoding="utf-8")))
               for path in source_root.rglob("*.java"))


def reported_count(log_path: Path) -> int:
    text = log_path.read_text(encoding="utf-8", errors="replace")
    matches = [int(match) for match in PASSED.findall(text)]
    if not matches:
        raise ValueError(f"no successful GameTest summary found in {log_path}")
    return max(matches)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--source", type=Path, default=Path("src/main/java"))
    args = parser.parse_args()
    try:
        expected = declared_count(args.source)
        actual = reported_count(args.log)
    except (OSError, ValueError) as error:
        print(f"GameTest count verification failed: {error}", file=sys.stderr)
        return 1
    if expected <= 0:
        print("GameTest count verification failed: no @GameTest methods declared", file=sys.stderr)
        return 1
    if actual != expected:
        print(f"GameTest count mismatch: log reports {actual}, source declares {expected}", file=sys.stderr)
        return 1
    print(f"GameTest count verified: {actual}/{expected}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
