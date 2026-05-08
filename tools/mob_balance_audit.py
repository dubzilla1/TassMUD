"""
mob_balance_audit.py — Phase 1: Audit MERC mob damage dice loss

The MERC converter stored only `base_damage` (X) and `damage_bonus` (B) from
the original `NdX+B` damage field in legacy_raw, silently dropping N (the dice
count).  CombatCalculator always rolls 1dX+B, so any mob with N>1 hits far
below its intended average.

This script:
  1. Reads every MERC mobiles.yaml (plus the global one for reference).
  2. Parses token 8 (0-based) from legacy_raw — the damage dice string (NdX+B).
  3. Computes current_avg vs intended_avg per mob.
  4. Outputs:
       tmp/mob_balance_raw.csv    — one row per affected mob (N > 1)
       tmp/mob_balance_summary.txt — stats grouped by level bands 1-5, 6-10, …
"""

import glob
import os
import re
import csv
import sys
from collections import defaultdict
from pathlib import Path

try:
    import yaml
except ImportError:
    print("PyYAML not found. Install it: pip install pyyaml")
    sys.exit(1)

# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR   = Path(__file__).parent
REPO_ROOT    = SCRIPT_DIR.parent
DATA_DIR     = REPO_ROOT / "src" / "main" / "resources" / "data"
MERC_DIR     = DATA_DIR / "MERC"
OUTPUT_DIR   = REPO_ROOT / "tmp"
CSV_PATH     = OUTPUT_DIR / "mob_balance_raw.csv"
SUMMARY_PATH = OUTPUT_DIR / "mob_balance_summary.txt"

# ── Regex for dice notation ───────────────────────────────────────────────────
DICE_RE = re.compile(r'^(\d+)d(\d+)(?:\+(\d+))?$', re.IGNORECASE)


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
    Extract damage dice (NdX+B) from a legacy_raw string.

    Field layout (0-based token indices):
      0  flags1
      1  flags2
      2  gold
      3  S
      4  level
      5  hitbonus
      6  ac
      7  HPdice+HPbonus
      8  NdX+B  ← damage dice  <-- THIS IS WHAT WE WANT
      9  xpmin
      10 xpmax
      ...
    """
    tokens = legacy_raw.split()
    if len(tokens) < 9:
        return None
    return parse_damage_token(tokens[8])


def level_band(level: int) -> str:
    """Return a label like '01-05' for the level's 5-level band."""
    lo = ((level - 1) // 5) * 5 + 1
    hi = lo + 4
    return f"{lo:02d}-{hi:02d}"


def avg_damage(n: int, x: int, b: int) -> float:
    """Expected value of NdX+B (each die is uniform [1, X])."""
    return n * (x + 1) / 2.0 + b


def current_avg_damage(base_damage: int, damage_bonus: int) -> float:
    """Expected value of the buggy 1d(base_damage)+damage_bonus."""
    if base_damage <= 0:
        return float(damage_bonus)
    return (base_damage + 1) / 2.0 + damage_bonus


def collect_mobs():
    """
    Yield dicts for every mob in every MERC mobiles.yaml.
    Each dict has: area, id, name, level, base_damage, damage_bonus,
                   attack_bonus, hp_max, armor, legacy_raw,
                   dice_n, dice_x, dice_b  (None if no valid legacy_raw)
    """
    yaml_files = sorted(MERC_DIR.glob("*/mobiles.yaml"))
    if not yaml_files:
        print(f"WARNING: No MERC mobiles.yaml files found under {MERC_DIR}")

    for yaml_path in yaml_files:
        area_name = yaml_path.parent.name
        try:
            with open(yaml_path, encoding="utf-8") as f:
                mobs = yaml.safe_load(f)
        except Exception as e:
            print(f"ERROR reading {yaml_path}: {e}")
            continue

        if not isinstance(mobs, list):
            continue

        for mob in mobs:
            if not isinstance(mob, dict):
                continue

            raw = mob.get("legacy_raw", "")
            dice = parse_legacy_raw_damage(str(raw)) if raw else None

            yield {
                "area":         area_name,
                "id":           mob.get("id", "?"),
                "name":         mob.get("name", "?"),
                "level":        int(mob.get("level", 0)),
                "base_damage":  int(mob.get("base_damage", 0)),
                "damage_bonus": int(mob.get("damage_bonus", 0)),
                "attack_bonus": int(mob.get("attack_bonus", 0)),
                "hp_max":       int(mob.get("hp_max", 0)),
                "armor":        int(mob.get("armor", 0)),
                "legacy_raw":   str(raw),
                "dice_n":       dice[0] if dice else None,
                "dice_x":       dice[1] if dice else None,
                "dice_b":       dice[2] if dice else None,
            }


def run_audit():
    OUTPUT_DIR.mkdir(exist_ok=True)

    all_mobs    = list(collect_mobs())
    total_mobs  = len(all_mobs)

    # ── Mobs where N > 1 (actually broken) ───────────────────────────────────
    broken = []
    ok     = 0
    no_raw = 0

    for m in all_mobs:
        if m["dice_n"] is None:
            no_raw += 1
            continue
        if m["dice_n"] <= 1:          # N=0 (no dice damage) or N=1 (not buggy)
            ok += 1
            continue
        # N > 1 — mob is under-powered
        cur  = current_avg_damage(m["base_damage"], m["damage_bonus"])
        want = avg_damage(m["dice_n"], m["dice_x"], m["dice_b"])
        m["current_avg"]  = cur
        m["intended_avg"] = want
        m["deficit"]      = want - cur        # how much damage per swing is missing
        m["ratio"]        = (want / cur) if cur > 0 else 0.0
        broken.append(m)

    # ── Write CSV ─────────────────────────────────────────────────────────────
    csv_fields = [
        "area", "id", "name", "level", "hp_max", "armor",
        "dice_n", "dice_x", "dice_b",
        "base_damage", "damage_bonus", "attack_bonus",
        "current_avg", "intended_avg", "deficit", "ratio",
    ]
    with open(CSV_PATH, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=csv_fields, extrasaction="ignore")
        w.writeheader()
        for m in sorted(broken, key=lambda x: x["level"]):
            w.writerow({k: (f"{m[k]:.2f}" if isinstance(m[k], float) else m[k])
                        for k in csv_fields})

    # ── Group by level band ───────────────────────────────────────────────────
    by_band = defaultdict(list)
    for m in broken:
        by_band[level_band(m["level"])].append(m)

    # ── Write summary ─────────────────────────────────────────────────────────
    lines = []
    lines.append("=" * 70)
    lines.append("  TassMUD MERC Mob Damage Audit — Phase 1")
    lines.append("=" * 70)
    lines.append(f"  Total MERC mobs scanned : {total_mobs}")
    lines.append(f"  No legacy_raw / unparsed: {no_raw}")
    lines.append(f"  N<=1 (unaffected)       : {ok}")
    lines.append(f"  N>1  (under-powered)    : {len(broken)}")
    lines.append("")

    if broken:
        lines.append("By level band (mobs with N>1 dice — currently under-powered)")
        lines.append("-" * 70)
        hdr = f"{'Band':>8}  {'Count':>5}  {'AvgN':>5}  {'AvgCurDmg':>9}  {'AvgWantDmg':>10}  {'AvgDeficit':>10}  {'AvgRatio':>8}"
        lines.append(hdr)
        lines.append("-" * 70)

        for band in sorted(by_band.keys()):
            mobs_in_band = by_band[band]
            count        = len(mobs_in_band)
            avg_n        = sum(m["dice_n"] for m in mobs_in_band) / count
            avg_cur      = sum(m["current_avg"]  for m in mobs_in_band) / count
            avg_want     = sum(m["intended_avg"] for m in mobs_in_band) / count
            avg_def      = sum(m["deficit"]      for m in mobs_in_band) / count
            avg_rat      = sum(m["ratio"]        for m in mobs_in_band) / count
            lines.append(
                f"{band:>8}  {count:>5}  {avg_n:>5.1f}  {avg_cur:>9.1f}  {avg_want:>10.1f}  {avg_def:>10.1f}  {avg_rat:>8.2f}x"
            )

        lines.append("")
        lines.append("Top 20 most under-powered mobs (by deficit — damage per swing lost)")
        lines.append("-" * 70)
        top = sorted(broken, key=lambda x: -x["deficit"])[:20]
        lines.append(f"{'Lvl':>4}  {'N':>2}  {'Cur':>6}  {'Want':>6}  {'Deficit':>7}  {'Ratio':>6}  {'Area':<12}  Name")
        lines.append("-" * 70)
        for m in top:
            lines.append(
                f"{m['level']:>4}  {m['dice_n']:>2}  {m['current_avg']:>6.1f}  {m['intended_avg']:>6.1f}"
                f"  {m['deficit']:>7.1f}  {m['ratio']:>6.2f}x  {m['area']:<12}  {m['name']}"
            )

        lines.append("")
        lines.append("Worst ratio mobs (most relatively under-powered)")
        lines.append("-" * 70)
        top_ratio = sorted(broken, key=lambda x: -x["ratio"])[:20]
        lines.append(f"{'Lvl':>4}  {'N':>2}  {'Cur':>6}  {'Want':>6}  {'Deficit':>7}  {'Ratio':>6}  {'Area':<12}  Name")
        lines.append("-" * 70)
        for m in top_ratio:
            lines.append(
                f"{m['level']:>4}  {m['dice_n']:>2}  {m['current_avg']:>6.1f}  {m['intended_avg']:>6.1f}"
                f"  {m['deficit']:>7.1f}  {m['ratio']:>6.2f}x  {m['area']:<12}  {m['name']}"
            )

    else:
        lines.append("No mobs with N>1 found — no damage bug detected.")

    summary_text = "\n".join(lines) + "\n"
    with open(SUMMARY_PATH, "w", encoding="utf-8") as f:
        f.write(summary_text)

    print(summary_text)
    print(f"CSV written to  : {CSV_PATH}")
    print(f"Summary written : {SUMMARY_PATH}")


if __name__ == "__main__":
    run_audit()
