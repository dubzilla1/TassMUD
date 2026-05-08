"""
mob_damage_backfill.py — Phase 3: Inject `damage_count` into MERC mobiles.yaml files

For every mob in src/main/resources/data/MERC/*/mobiles.yaml whose legacy_raw
token 8 has N > 1 (e.g. "4d6+5"), this script inserts a `damage_count: N` key
into that mob's YAML block — placed immediately before `base_damage`.

The edit is done with a line-by-line text pass rather than parse+re-serialise
so that comments, ordering, and formatting are preserved.

Safety:
  - Only writes files that actually need changes.
  - Prints a dry-run summary when called with --dry-run.
  - Skips mobs that already have damage_count set.
"""

import glob
import re
import sys
from pathlib import Path

# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
REPO_ROOT  = SCRIPT_DIR.parent
MERC_DIR   = REPO_ROOT / "src" / "main" / "resources" / "data" / "MERC"

# ── Regex ────────────────────────────────────────────────────────────────────
# Matches the damage token at legacy_raw position 8: NdX or NdX+B
DICE_RE      = re.compile(r'^(\d+)d(\d+)(?:\+(\d+))?$', re.IGNORECASE)
# Matches a legacy_raw field line (with optional leading spaces)
LEGACY_RE    = re.compile(r'^(\s*)legacy_raw:\s*(.+)$')
# Matches base_damage field line
BASE_DMG_RE  = re.compile(r'^(\s*)base_damage:\s*')
# Matches an existing damage_count line (skip already-patched mobs)
DMGCOUNT_RE  = re.compile(r'^\s*damage_count:\s*')
# Detects start of a new top-level list item (mob block separator)
MOB_START_RE = re.compile(r'^- ')

DRY_RUN = "--dry-run" in sys.argv


def parse_damage_token(raw_token: str):
    """Return (N, X, B) from 'NdX+B', or None if unrecognised."""
    m = DICE_RE.match(raw_token.strip())
    if not m:
        return None
    n = int(m.group(1))
    x = int(m.group(2))
    b = int(m.group(3)) if m.group(3) else 0
    return n, x, b


def parse_legacy_raw_damage(legacy_raw: str):
    """
    Extract (N, X, B) from legacy_raw token index 8.

    Token layout (0-based, whitespace-split):
      0  flags1
      1  flags2
      2  gold
      3  S
      4  level
      5  hitbonus
      6  ac
      7  HPdice+HPbonus
      8  NdX+B   ← damage dice
      9  xpmin
      10 xpmax
    """
    tokens = legacy_raw.strip().split()
    if len(tokens) < 9:
        return None
    return parse_damage_token(tokens[8])


def build_damage_count_for_mob_block(lines: list[str]) -> int | None:
    """
    Given lines belonging to one mob block, find legacy_raw and return N if N > 1.
    Returns None if not applicable (N==1, no legacy_raw, or already has damage_count).
    """
    has_damage_count = any(DMGCOUNT_RE.match(l) for l in lines)
    if has_damage_count:
        return None  # already patched

    for line in lines:
        m = LEGACY_RE.match(line)
        if not m:
            continue
        legacy_raw = m.group(2).strip().strip('"').strip("'")
        result = parse_legacy_raw_damage(legacy_raw)
        if result is None:
            return None
        n, _x, _b = result
        if n > 1:
            return n
        return None

    return None


def process_file(yaml_path: Path) -> tuple[int, int]:
    """
    Process one mobiles.yaml file.
    Returns (mobs_patched, mobs_skipped).
    """
    original_text = yaml_path.read_text(encoding="utf-8")
    lines = original_text.splitlines(keepends=True)

    # Split lines into mob blocks (each starting with "- ")
    # We keep track of block boundaries so we can patch each block independently.
    blocks: list[list[str]] = []
    current: list[str] = []
    for line in lines:
        if MOB_START_RE.match(line) and current:
            blocks.append(current)
            current = []
        current.append(line)
    if current:
        blocks.append(current)

    patched = 0
    skipped = 0
    output_blocks: list[list[str]] = []

    for block in blocks:
        n = build_damage_count_for_mob_block(block)
        if n is None:
            output_blocks.append(block)
            skipped += 1
            continue

        # Insert `damage_count: N` immediately before the `base_damage:` line.
        new_block: list[str] = []
        inserted = False
        for line in block:
            if not inserted and BASE_DMG_RE.match(line):
                indent = BASE_DMG_RE.match(line).group(1)
                new_block.append(f"{indent}damage_count: {n}\n")
                inserted = True
            new_block.append(line)
        if not inserted:
            # base_damage not found — append at end of block as fallback
            new_block.append(f"  damage_count: {n}\n")
        output_blocks.append(new_block)
        patched += 1

    new_text = "".join("".join(block) for block in output_blocks)

    if new_text != original_text:
        if not DRY_RUN:
            yaml_path.write_text(new_text, encoding="utf-8")

    return patched, skipped


def main():
    yaml_files = sorted(MERC_DIR.glob("*/mobiles.yaml"))
    if not yaml_files:
        print(f"No MERC mobiles.yaml files found under {MERC_DIR}")
        sys.exit(1)

    total_files   = 0
    total_patched = 0
    total_skipped = 0

    print(f"{'DRY RUN — ' if DRY_RUN else ''}Processing {len(yaml_files)} MERC area files...\n")

    for yaml_path in yaml_files:
        area = yaml_path.parent.name
        patched, skipped = process_file(yaml_path)
        total_files   += 1
        total_patched += patched
        total_skipped += skipped
        if patched:
            print(f"  {area:20s}  patched={patched:3d}  skipped={skipped:3d}")

    print(f"\nDone. {total_files} files, {total_patched} mobs patched, {total_skipped} mobs skipped.")
    if DRY_RUN:
        print("(No files were written — dry-run mode)")


if __name__ == "__main__":
    main()
