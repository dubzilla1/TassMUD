#!/usr/bin/env python3
"""
mob_hp_backfill.py — Replace garbage hp_max values in MERC mobiles.yaml files
with values derived from MERC's actual level-based formula.

MERC generates mob HP from level alone (the area-file field is ignored):
    hp = level*8 + number_range(level²/4, level²)
    average = level*8 + level²*5/8

This script replaces any hp_max that exceeds twice the MERC theoretical maximum
(level * (level + 8) * 2) with the MERC formula average. If hp_max is 0 or
absent, it is also filled in.

Usage:
    python tools/mob_hp_backfill.py [--dry-run]
"""

import sys
import re
import os
import glob
import argparse

# Root of the project (two levels up from tools/)
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MERC_DATA_DIR = os.path.join(PROJECT_ROOT, "src", "main", "resources", "data", "MERC")


def merc_hp_average(level: int) -> int:
    """
    MERC formula average HP for a mob of the given level.
    number_range(level²/4, level²) average = level² * 5/8
    Total average = level*8 + level²*5//8
    Minimum 1.
    """
    if level <= 0:
        return 1
    base = level * 8
    bonus = (level * level * 5) // 8
    return max(1, base + bonus)


def merc_hp_max(level: int) -> int:
    """MERC theoretical maximum HP (level*8 + level²)."""
    if level <= 0:
        return 1
    return level * 8 + level * level


def backfill_file(path: str, dry_run: bool) -> int:
    """
    Process a single mobiles.yaml file.  Returns the number of mobs patched.
    """
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # Parse level + hp_max pairs from YAML.
    # We process mob blocks delimited by top-level "- id:" markers.
    # Strategy: find each mob's level, then patch its hp_max.

    patched = 0
    lines = content.splitlines(keepends=True)

    # State machine: track current mob's level and hp_max line
    current_level = None
    hp_max_line_idx = None
    current_hp_max = None

    # We'll do two-pass: first collect (mob_start, level, hp_max_line_idx, hp_max_value)
    # then apply patches in reverse order
    patches = []  # list of (line_idx, new_line)

    i = 0
    while i < len(lines):
        line = lines[i]

        # New mob block
        if re.match(r'^- id:', line) or re.match(r'^  id:', line):
            current_level = None
            hp_max_line_idx = None
            current_hp_max = None

        # Level field
        m = re.match(r'^  level:\s*(\d+)', line)
        if m:
            current_level = int(m.group(1))

        # hp_max field
        m = re.match(r'^  hp_max:\s*(-?\d+)', line)
        if m:
            hp_max_line_idx = i
            current_hp_max = int(m.group(1))

        # When we've seen both level and hp_max for this mob, decide if patching needed
        if current_level is not None and hp_max_line_idx is not None and current_hp_max is not None:
            threshold = merc_hp_max(current_level) * 2
            needs_patch = (current_hp_max <= 0) or (current_hp_max > threshold)
            if needs_patch:
                new_val = merc_hp_average(current_level)
                old_line = lines[hp_max_line_idx]
                new_line = re.sub(r'(hp_max:\s*)-?\d+', f'\\g<1>{new_val}', old_line)
                patches.append((hp_max_line_idx, new_line, current_hp_max, new_val, current_level))
            # Reset hp tracking so we don't double-patch
            hp_max_line_idx = None
            current_hp_max = None

        i += 1

    if patches:
        print(f"  {path}")
        # Apply patches in reverse order to preserve line indices
        for (idx, new_line, old_val, new_val, level) in sorted(patches, key=lambda x: x[0], reverse=True):
            lines[idx] = new_line
            print(f"    L{idx+1}: hp_max {old_val} -> {new_val}  (level {level})")
        patched = len(patches)
        if not dry_run:
            with open(path, "w", encoding="utf-8") as f:
                f.writelines(lines)

    return patched


def main():
    parser = argparse.ArgumentParser(description="Backfill MERC mob hp_max values from level formula")
    parser.add_argument("--dry-run", action="store_true", help="Print changes without writing files")
    args = parser.parse_args()

    yaml_files = sorted(glob.glob(os.path.join(MERC_DATA_DIR, "**", "mobiles.yaml"), recursive=True))

    if not yaml_files:
        print(f"No mobiles.yaml files found under {MERC_DATA_DIR}")
        sys.exit(1)

    total_patched = 0
    for path in yaml_files:
        n = backfill_file(path, args.dry_run)
        total_patched += n

    mode = "(dry run)" if args.dry_run else ""
    print(f"\nTotal mobs patched {mode}: {total_patched}")
    if args.dry_run:
        print("Run without --dry-run to apply changes.")


if __name__ == "__main__":
    main()
