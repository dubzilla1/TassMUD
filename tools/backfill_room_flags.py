#!/usr/bin/env python3
"""
Scan all MERC .are files, extract room flag bitfields, and update the
corresponding data/MERC/<area>/rooms.yaml files with a `flags:` field.

The existing converter stored legacy_flags (MERC names) but couldn't parse
pipe-notation bitfields like "4|8|1024", so all flags: fields are missing.
This script backfills them.

Usage:
    python tools/backfill_room_flags.py [--dry-run]

With --dry-run: prints planned changes but writes nothing.
"""

import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
ARE_DIR = os.path.join(PROJECT_ROOT, "src", "main", "resources", "MERC", "area")
MERC_DATA_DIR = os.path.join(PROJECT_ROOT, "src", "main", "resources", "data", "MERC")

# MERC room flag bits → TassMUD flag keys (in bit order)
FLAG_MAP = [
    (1,    "dark"),
    (4,    "no_mob"),
    (8,    "indoors"),
    (512,  "private"),
    (1024, "safe"),
    (2048, "solitary"),
    (4096, "pet_shop"),
    (8192, "no_recall"),
]


def parse_bits(flags_str):
    """Parse a MERC flags string like '4|8|1024' or '12' into integer bits."""
    bits = 0
    for part in flags_str.strip().split('|'):
        try:
            bits |= int(part.strip())
        except ValueError:
            pass
    return bits


def bits_to_keys(bits):
    """Convert flag bits to a list of TassMUD flag keys."""
    return [key for bit, key in FLAG_MAP if bits & bit]


def parse_are_rooms(are_path):
    """
    Parse a .are file's #ROOMS section.
    Returns {vnum: [flag_key, ...]} for rooms with at least one flag.
    """
    with open(are_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = [ln.rstrip('\n') for ln in f]

    n = len(lines)
    i = 0

    # Seek to #ROOMS
    while i < n:
        if lines[i].strip() == '#ROOMS':
            i += 1
            break
        i += 1

    result = {}

    while i < n:
        raw = lines[i]
        stripped = raw.strip()

        # End of #ROOMS section
        if stripped == '#0':
            break
        if stripped.startswith('#') and not stripped[1:].isdigit():
            break

        # Room vnum line: #<number>
        if stripped.startswith('#') and stripped[1:].isdigit():
            vnum = int(stripped[1:])
            i += 1

            # Skip name (single line; ends with ~)
            while i < n:
                if '~' in lines[i]:
                    i += 1
                    break
                i += 1

            # Skip long description (terminated by a line ending with ~)
            while i < n:
                if lines[i].rstrip().endswith('~'):
                    i += 1
                    break
                i += 1

            # Next line: "<area_num> <flags> <sector_type>"
            if i < n:
                parts = lines[i].strip().split()
                if len(parts) >= 2:
                    bits = parse_bits(parts[1])
                    if bits:
                        keys = bits_to_keys(bits)
                        if keys:
                            result[vnum] = keys
                i += 1
        else:
            i += 1

    return result


# Fields that can immediately follow a room's description in the YAML.
# We insert `flags:` before the first one we encounter.
INSERT_BEFORE = {'exits', 'sector_type', 'spawns', 'legacy_flags', 'doors', 'extras'}


def update_rooms_yaml(yaml_path, flag_map, dry_run=False):
    """
    Add `flags: [key1, key2]` lines to room entries in a rooms.yaml file.

    - Skips rooms that already have a `flags:` line.
    - Inserts the line right before exits:, sector_type:, spawns:, etc.

    Returns (rooms_updated, skipped_already_set) counts.
    """
    with open(yaml_path, 'r', encoding='utf-8') as f:
        lines = f.read().split('\n')

    n = len(lines)

    # Find room entry boundaries: "  - id: <vnum>"
    room_starts = []
    for idx, line in enumerate(lines):
        m = re.match(r'^  - id:\s*(\d+)', line)
        if m:
            room_starts.append((idx, int(m.group(1))))

    insertions = []       # (line_idx, text) — insert before this line
    rooms_updated = 0
    already_set = 0

    for entry_idx, (start_line, vnum) in enumerate(room_starts):
        if vnum not in flag_map:
            continue

        end_line = room_starts[entry_idx + 1][0] if entry_idx + 1 < len(room_starts) else n
        room_lines = lines[start_line:end_line]

        # Already has flags: — skip
        if any(re.match(r'^    flags:', rl) for rl in room_lines):
            already_set += 1
            continue

        # Find insertion point
        insert_at = None
        for rel_idx, rl in enumerate(room_lines):
            m = re.match(r'^    (\w+):', rl)
            if m and m.group(1) in INSERT_BEFORE:
                insert_at = start_line + rel_idx
                break

        if insert_at is None:
            print(f"    WARNING: no insertion point found for room {vnum}")
            continue

        keys = flag_map[vnum]
        flags_str = '[' + ', '.join(keys) + ']'
        insertions.append((insert_at, f'    flags: {flags_str}'))
        rooms_updated += 1

    if insertions:
        # Apply in reverse so earlier indices aren't shifted
        insertions.sort(key=lambda x: x[0], reverse=True)
        for line_idx, text in insertions:
            lines.insert(line_idx, text)

        if not dry_run:
            with open(yaml_path, 'w', encoding='utf-8') as f:
                f.write('\n'.join(lines))

    return rooms_updated, already_set


def main():
    dry_run = '--dry-run' in sys.argv
    if dry_run:
        print("[DRY RUN] No files will be written.\n")

    are_files = sorted(f for f in os.listdir(ARE_DIR) if f.endswith('.are'))

    total_areas_changed = 0
    total_rooms_updated = 0
    total_rooms_skipped = 0

    for are_file in are_files:
        area_name = os.path.splitext(are_file)[0]
        are_path = os.path.join(ARE_DIR, are_file)
        yaml_path = os.path.join(MERC_DATA_DIR, area_name, 'rooms.yaml')

        if not os.path.exists(yaml_path):
            print(f"SKIP  {area_name}: no rooms.yaml")
            continue

        flag_map = parse_are_rooms(are_path)

        if not flag_map:
            continue  # no flagged rooms in this area

        updated, skipped = update_rooms_yaml(yaml_path, flag_map, dry_run=dry_run)
        total_rooms_updated += updated
        total_rooms_skipped += skipped

        if updated > 0:
            total_areas_changed += 1
            prefix = 'DRY ' if dry_run else 'UPD '
            print(f"{prefix} {area_name}: {updated} room(s) flagged"
                  + (f", {skipped} already set" if skipped else ""))
            for vnum, keys in sorted(flag_map.items()):
                print(f"       room {vnum}: {keys}")
        elif skipped > 0:
            print(f"OK   {area_name}: {skipped} room(s) already up-to-date")

    print(f"\n{'=' * 50}")
    print(f"Areas modified : {total_areas_changed}")
    print(f"Rooms updated  : {total_rooms_updated}")
    print(f"Rooms skipped  : {total_rooms_skipped} (already had flags:)")


if __name__ == '__main__':
    main()
