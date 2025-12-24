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
        # Accept a tilde terminated line even if there is trailing whitespace
        # (e.g. "~ "), and accept appended tildes like "name~".
        if line.rstrip().endswith('~'):
            # Use the rstripped version to remove any trailing spaces/newlines
            trimmed = line.rstrip()
            out_lines.append(trimmed[:-1])
            return ("\n".join(out_lines).strip(), i + 1)
        else:
            out_lines.append(line)
            i += 1
    return ("\n".join(out_lines).strip(), i)


def parse_shops(text: str):
    """Parse a #SHOPS section and return a set of mob vnums that are shopkeepers.

    Lines in the #SHOPS section typically start with the mob vnum followed by
    other numeric fields. We capture the first integer on each non-comment
    line until the next section header.
    """
    vnums = set()
    lines = text.splitlines()
    in_shops = False
    for raw in lines:
        line = raw.strip()
        if not in_shops:
            if line.upper() == '#SHOPS':
                in_shops = True
            continue
        # stop when next section header encountered
        if line.startswith('#') and not line.upper().startswith('#SHOPS'):
            break
        # skip blank lines and comments
        if not line or line.startswith(';'):
            continue
        # remove trailing comments after ';'
        parts = line.split(';', 1)[0].strip()
        if not parts:
            continue
        # first token should be the mob vnum
        m = re.match(r"^(\d+)", parts)
        if m:
            try:
                v = int(m.group(1))
                vnums.add(v)
            except:
                pass
    return vnums


def parse_are_mobs(text: str):
    lines = text.splitlines()
    # collect shopkeeper vnums from #SHOPS section (if present)
    shop_vnums = parse_shops(text)
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
            # next four tilde-terminated strings (common MERC layout):
            # keywords~, name~, short_desc~, long_desc~
            keywords_raw, i = read_tilde_string(lines, i)
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
                'keywords_raw': keywords_raw,
                'name': name,
                'short_desc': short_desc,
                'long_desc': long_desc,
                'raw_block': raw_block,
                'is_shopkeeper': (vnum in shop_vnums)
            })
        else:
            i += 1

    return mobs


def extract_fields_from_raw(raw: str):
    out = {}
    lines = (raw or '').splitlines()
    # Find the stat line containing the 'S' marker, parse act_flags/affected/alignment
    # from numeric tokens before the 'S', then parse the subsequent line for level
    # and dice fields where present.
    for idx, line in enumerate(lines):
        parts = line.split()
        if not parts:
            continue
        if 'S' in parts or (parts[-1].upper().startswith('S')):
            try:
                if 'S' in parts:
                    s_idx = parts.index('S')
                else:
                    s_idx = len(parts) - 1
                nums = []

                def parse_flag_token(tok: str):
                    # Accept pipe-delimited tokens like '2|64|128' and sum them.
                    if '|' in tok:
                        total = 0
                        for sub in tok.split('|'):
                            sub = sub.strip()
                            if re.match(r"-?\d+", sub):
                                total += int(sub)
                        return total
                    if re.match(r"-?\d+", tok):
                        return int(tok)
                    return None

                for p in parts[:s_idx]:
                    val = parse_flag_token(p)
                    if val is not None:
                        nums.append(val)
                # expected layout: <act-flags> <affected-flags> <alignment> S
                if len(nums) >= 1:
                    out['act_flags'] = nums[0]
                if len(nums) >= 3:
                    out['alignment'] = nums[2]
            except Exception:
                pass

            # Look for the next non-empty line after the 'S' line which contains
            # level and dice info (common MERC layout).
            for j in range(idx + 1, len(lines)):
                nl = lines[j].strip()
                if not nl:
                    continue
                nparts = nl.split()
                # level is the first numeric token on this line
                if nparts and re.match(r"-?\d+", nparts[0]):
                    try:
                        lv = int(nparts[0])
                        if lv < 1:
                            lv = 1
                        if lv > 50:
                            lv = 50
                        out['level'] = lv
                    except Exception:
                        pass
                # hitroll is often the second token, armor the third
                if len(nparts) >= 2 and re.match(r"-?\d+", nparts[1]):
                    try:
                        out['hitroll'] = int(nparts[1])
                    except Exception:
                        pass
                if len(nparts) >= 3 and re.match(r"-?\d+", nparts[2]):
                    try:
                        merc_armor = int(nparts[2])
                        # Convert MERC THAC0-like armor to TASSMUD: 20 - merc
                        out['armor'] = 20 - merc_armor
                    except Exception:
                        pass
                # hit_dice commonly appears as the 4th token (index 3)
                if len(nparts) >= 4 and DICE_RE.search(nparts[3]):
                    out['hit_dice'] = nparts[3]
                else:
                    # fallback: search for any dice token on this line
                    for tok in nparts:
                        if DICE_RE.search(tok):
                            out.setdefault('hit_dice', tok)
                            break
                # damage dice commonly appears as the 5th token (index 4)
                if len(nparts) >= 5 and DICE_RE.search(nparts[4]):
                    out['damage_dice'] = nparts[4]
                else:
                    # fallback: search remaining tokens for another dice token
                    for tok in nparts[1:]:
                        if DICE_RE.search(tok):
                            out.setdefault('damage_dice', tok)
                            break
                break
            break

    # If level not found, fallback to previous heuristics
    if 'level' not in out:
        # pick first reasonable small integer (<=50)
        ints = re.findall(r"\b(\d{1,3})\b", raw)
        if ints:
            for s in ints:
                n = int(s)
                if 0 < n <= 50:
                    out['level'] = n
                    break

    # if hit/damage dice still not set, attempt to find anywhere in raw
    if 'hit_dice' not in out:
        hd = re.search(r"(\d+d\d+(?:\+\d+)?)", raw)
        if hd:
            out['hit_dice'] = hd.group(1)
    if 'damage_dice' not in out:
        dd = re.search(r"(\d+d\d+(?:\+\d+)?)", raw)
        if dd:
            out['damage_dice'] = dd.group(1)

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

    # keywords: use the first tilde line (split on spaces/commas)
    raw_keys = m.get('keywords_raw', '') or ''
    keys = [k.strip() for k in re.split(r"[,\s]+", raw_keys) if k.strip()]

    # key: slugified version of the name (underscored)
    raw_name = m.get('name', '').strip().strip('~')
    # normalize readable name (replace underscores with spaces)
    def humanize(s: str) -> str:
        s = s.replace('_', ' ').strip()
        # remove stray leading block indicators or trailing colons
        s = s.lstrip('|').rstrip(':')
        return s

    name_text = humanize(raw_name)
    # short/long descriptions from MERC fields; humanize underscores
    short_text_raw = m.get('short_desc', '').strip().strip('~')
    long_text_raw = m.get('long_desc', '').strip().strip('~')
    short_text = humanize(short_text_raw)
    long_text = humanize(long_text_raw)

    # Prefer a full `name` like 'the executioner' when short_desc carries it
    if short_text.lower().startswith('the ') and not name_text.lower().startswith('the '):
        name_text = short_text

    key_text = slugify(name_text) + "_" + str(m['vnum'])

    # Map act_flags bits to behaviors. If no flags are present we keep
    # an empty behavior list (the caller or runtime can decide defaults).
    behaviors = []
    act = parsed.get('act_flags', 0) or 0
    alignment = parsed.get('alignment', 0) or 0
    # bit definitions (from MERC)
    # ACT_SENTINEL = 2
    if act & 2:
        behaviors.append('SENTINEL')
    # ACT_SCAVENGER = 4
    if act & 4:
        behaviors.append('SCAVENGER')
    # ACT_AGGRESSIVE = 32 -> GUARD if alignment >=0 else AGGRESSIVE
    if act & 32:
        try:
            if int(alignment) >= 0:
                behaviors.append('GUARD')
            else:
                behaviors.append('AGGRESSIVE')
        except Exception:
            behaviors.append('AGGRESSIVE')
    # ACT_STAY_AREA = 64 -> map to 'LOCAL'
    if act & 64:
        behaviors.append('LOCAL')
    # ACT_WIMPY = 128
    if act & 128:
        behaviors.append('COWARDLY')
    # preserve SHOPKEEPER behavior if detected from #SHOPS
    if m.get('is_shopkeeper'):
        behaviors.append('SHOPKEEPER')

    entry = {
        'id': m['vnum'],
        'key': key_text,
        'name': name_text,
        'short_desc': short_text,
        'long_desc': long_text,
        'keywords': keys,
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
        'behaviors': behaviors,
        'aggro_range': 0,
        'experience_value': max(1, level * 100),
        'gold_min': 0,
        'gold_max': 0,
        'respawn_seconds': 0,
        # preserve raw block for human review but do NOT emit a nested
        # `template_json` mapping which has caused YAML parsing issues
        # when it used Python-style flow maps. Use a simple top-level
        # multiline field instead.
        'legacy_raw': raw
    }
    return entry


def dump_yaml(entries, outpath: Path):
    # Write entries in the project's existing list style (one item per leading '- id:')
    with open(outpath, 'w', encoding='utf-8') as f:
        for e in entries:
            f.write('- id: %s\n' % e['id'])
            for k in [k for k in e.keys() if k != 'id']:
                v = e[k]
                # format long_desc as block scalar
                if k == 'long_desc' and isinstance(v, str):
                    f.write('  long_desc: |\n')
                    for line in v.splitlines():
                        f.write('    %s\n' % line)
                elif k == 'keywords' and isinstance(v, list):
                    # inline list like ['executioner']
                    f.write("  keywords: %s\n" % (repr(v)))
                elif isinstance(v, str):
                    # plain string, write without quotes where safe
                    safe = v.replace('\n',' ')
                    f.write('  %s: %s\n' % (k, safe))
                elif isinstance(v, list):
                    # fallback list formatting
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
