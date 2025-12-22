#!/usr/bin/env python3
"""
Simple MERC .are -> TASSMUD mobile YAML converter (partial/heuristic).

Usage:
  python tools/merc_mob_converter.py input.are output.yaml

Notes:
- This is a conservative, heuristic converter: it extracts mobile blocks starting
  with lines like `#<vnum>` then reads the next three tilde-terminated strings
  (name~, short_desc~, long_desc~). Everything after the long desc up to the
  next `#<vnum>` is preserved as `template_json.raw_block` and lightly parsed
  for `level`, `hit_dice`, and `damage_dice` where possible.
- The script computes `hp_max` from hit dice (average) when the hit-dice is
  of the form NdM+K or NxM+K. If parsing fails, it preserves the raw string
  and sets a conservative default.
- The generated YAML follows the TASSMUD mobile template shape used in
  `src/main/resources/data/mobiles.yaml`.

This script intentionally keeps mappings simple so human review can refine
converted entries later.
"""

import re
import sys
import math
from pathlib import Path

try:
    import yaml
except Exception:
    yaml = None

DICE_RE = re.compile(r"(\d+)d(\d+)(?:\+(\d+))?")
VNUM_RE = re.compile(r"^#(\d+)")

def slugify(s: str) -> str:
    s = s.lower().strip()
    s = re.sub(r"[^a-z0-9]+", "_", s)
    s = re.sub(r"_+", "_", s)
    return s.strip("_")


def parse_dice(dice_str: str):
    """Return (n, m, k) for NdM+K or None."""
    if dice_str is None:
        return None
    m = DICE_RE.search(dice_str)
    if not m:
        return None
    n = int(m.group(1))
    die = int(m.group(2))
    add = int(m.group(3)) if m.group(3) else 0
    return (n, die, add)


def avg_hp_from_dice(n, die, add):
    # average of dM = (1+M)/2
    return int(math.floor(n * ((1 + die) / 2.0) + add))


def read_tilde_string(lines, start_idx):
    """Read a tilde-terminated string from lines starting at start_idx.
    Returns (string_without_trailing_tilde, next_index)."""
    out_lines = []
    i = start_idx
    while i < len(lines):
        line = lines[i]
        if line.endswith('~'):
            out_lines.append(line[:-1])
            return ("\n".join(out_lines).strip(), i + 1)
        else:
            out_lines.append(line)
            i += 1
    return ("\n".join(out_lines).strip(), i)


def parse_are_mobs(text: str):
    lines = text.splitlines()
    mobs = []

    # Find the #MOBILES section if present. If not found, fall back to scanning whole file.
    start_idx = None
    for idx, raw_line in enumerate(lines):
        if raw_line.strip().upper() == '#MOBILES':
            start_idx = idx + 1
            break

    if start_idx is None:
        # no explicit MOBILES section; scan whole file as before
        start_idx = 0

    i = start_idx
    while i < len(lines):
        line = lines[i].strip()
        # If we encounter a new section header (e.g. #OBJECTS, #ROOMS, etc.) stop parsing
        if line.startswith('#') and not VNUM_RE.match(line):
            break

        vmatch = VNUM_RE.match(line)
        if vmatch:
            vnum = int(vmatch.group(1))
            # MERC uses #0 to terminate lists (end of #MOBILES). Stop parsing on #0.
            if vnum == 0:
                break
            i += 1
            # next three tilde-terminated strings: name~, short_desc~, long_desc~
            name, i = read_tilde_string(lines, i)
            short_desc, i = read_tilde_string(lines, i)
            long_desc, i = read_tilde_string(lines, i)
            # collect raw block until next #vnum or section header
            raw_lines = []
            while i < len(lines):
                nxt = lines[i].strip()
                if VNUM_RE.match(nxt) or (nxt.startswith('#') and not VNUM_RE.match(nxt)):
                    break
                raw_lines.append(lines[i])
                i += 1
            raw_block = "\n".join(raw_lines).strip()
            mobs.append({
                'vnum': vnum,
                'name': name,
                'short_desc': short_desc,
                'long_desc': long_desc,
                'raw_block': raw_block
            })
        else:
            i += 1

    return mobs


def extract_fields_from_raw(raw: str):
    out = {}
    # level: first integer that looks like reasonable (<=1000)
    ints = re.findall(r"\b(\d{1,4})\b", raw)
    if ints:
        # Heuristics: MERC often places level early; choose first number > 0 and < 1000
        for s in ints:
            n = int(s)
            if 0 < n < 1000:
                out['level'] = n
                break
    # hit_dice and damage_dice
    hd = re.search(r"(\d+d\d+(?:\+\d+)?)", raw)
    if hd:
        out['hit_dice'] = hd.group(1)
    # look for damage dice separately (may appear twice, try to find pattern like 1d8+32)
    dd = re.search(r"(\d+d\d+(?:\+\d+)?)", raw)
    if dd:
        out['damage_dice'] = dd.group(1)
    # act/affected flags numeric group near beginning
    # capture first line of raw block
    first_line = raw.splitlines()[0] if raw else ''
    flags_nums = re.findall(r"\b(\d+)\b", first_line)
    if flags_nums:
        try:
            out['act_flags'] = int(flags_nums[0])
        except:
            pass
    return out


def convert_mob_entry(m):
    raw = m['raw_block']
    parsed = extract_fields_from_raw(raw)
    level = parsed.get('level', 1)
    hit_dice = parsed.get('hit_dice')
    damage_dice = parsed.get('damage_dice')

    # hp_max heuristic
    hp_max = None
    if hit_dice:
        d = parse_dice(hit_dice)
        if d:
            hp_max = avg_hp_from_dice(*d)
    if hp_max is None:
        hp_max = 10 * max(1, level)

    # damage breakdown
    base_damage = 4
    damage_bonus = 0
    if damage_dice:
        d = parse_dice(damage_dice)
        if d:
            n, die, add = d
            # if multiplier >1, treat base_damage as die, damage_bonus store add
            base_damage = die
            damage_bonus = add
    else:
        # fallback: try to parse "1d8+32" style anywhere in raw
        m2 = DICE_RE.search(raw)
        if m2:
            n, die, add = int(m2.group(1)), int(m2.group(2)), int(m2.group(3) or 0)
            base_damage = die
            damage_bonus = add

    # default abilities
    name_lower = m['name'].lower()
    if 'wizard' in name_lower or 'mage' in name_lower or 'sorcerer' in name_lower:
        intel = 16
        wis = 12
        strv = 10
        dex = 10
        con = 10
        cha = 10
    else:
        strv = 10
        dex = 10
        con = 10
        intel = 10
        wis = 10
        cha = 10

    entry = {
        'id': m['vnum'],
        'key': slugify(m['short_desc'] or m['name']),
        'name': m['name'].strip().strip('~'),
        'short_desc': m['short_desc'].strip().strip('~'),
        'long_desc': m['long_desc'].strip().strip('~'),
        'keywords': [k.strip() for k in re.split(r"[, ]+", m['name']) if k.strip()],
        'level': level,
        'hp_max': hp_max,
        'mp_max': 0,
        'mv_max': 100,
        'str': strv,
        'dex': dex,
        'con': con,
        'intel': intel,
        'wis': wis,
        'cha': cha,
        'armor': 10 if 'armor' not in parsed else parsed.get('armor', 10),
        'fortitude': 0,
        'reflex': 0,
        'will': 0,
        'base_damage': base_damage,
        'damage_bonus': damage_bonus,
        'attack_bonus': parsed.get('attack_bonus', 0) if parsed.get('attack_bonus') else 0,
        'behaviors': ['SENTINEL'],
        'aggro_range': 0,
        'experience_value': max(1, level * 100),
        'gold_min': 0,
        'gold_max': 0,
        'respawn_seconds': 0,
        'template_json': parsed | {'raw_block': raw}
    }
    return entry


def dump_yaml(entries, outpath: Path):
    out = {'mobs': entries}
    if yaml:
        with open(outpath, 'w', encoding='utf-8') as f:
            yaml.safe_dump(entries, f, sort_keys=False, allow_unicode=True)
    else:
        # fallback: write simple list form
        with open(outpath, 'w', encoding='utf-8') as f:
            for e in entries:
                f.write('- id: %s\n' % e['id'])
                for k, v in e.items():
                    if k == 'id':
                        continue
                    # naive formatting
                    if isinstance(v, str):
                        f.write('  %s: "%s"\n' % (k, v.replace('\n','\\n')))
                    elif isinstance(v, list):
                        f.write('  %s: %s\n' % (k, v))
                    else:
                        f.write('  %s: %s\n' % (k, v))
                f.write('\n')


def main(argv):
    if len(argv) < 3:
        print('Usage: python merc_mob_converter.py input.are output.yaml')
        return 1
    inp = Path(argv[1])
    out = Path(argv[2])
    if not inp.exists():
        print('Input file not found:', inp)
        return 2
    text = inp.read_text(encoding='utf-8')
    mobs = parse_are_mobs(text)
    entries = []
    for m in mobs:
        ent = convert_mob_entry(m)
        entries.append(ent)
    dump_yaml(entries, out)
    print(f'Wrote {len(entries)} converted mobs to {out}')
    return 0

if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
