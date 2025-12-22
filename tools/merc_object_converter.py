#!/usr/bin/env python3
"""Convert MERC .are #OBJECTS blocks into TASSMUD items.yaml entries.

Usage: python tools/merc_object_converter.py path/to/area.are out/items.yaml

This is a pragmatic converter: it parses #OBJECTS up to the #0 terminator,
extracts the standard MERC object fields, preserves the raw block in
`template_json.raw_block`, decodes basic flags using values found in
src/main/resources/MERC/src/merc.h (wear/extra flags), and emits a
minimal TASSMUD-friendly YAML structure.

The script intentionally keeps conversions conservative and preserves
raw text for manual refinement.
"""
import sys
import re
import html
from pathlib import Path

if len(sys.argv) < 3:
    print("Usage: merc_object_converter.py input.are output.yaml")
    sys.exit(2)

in_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])

text = in_path.read_text(encoding='utf-8', errors='replace')

# Find #OBJECTS section
obj_start = text.find('\n#OBJECTS')
if obj_start == -1:
    print("No #OBJECTS section found.")
    sys.exit(1)

# Slice from #OBJECTS to #0 terminator
slice_text = text[obj_start:]
term = slice_text.find('\n#0')
if term != -1:
    slice_text = slice_text[:term]

lines = slice_text.splitlines()

entries = []
idx = 0
n = len(lines)
effects = []

# Persistent counter to avoid reusing generated effect ids across runs
COUNTER_PATH = Path(__file__).parent / 'effect_id_counter.txt'

def read_counter():
    if COUNTER_PATH.exists():
        try:
            return int(COUNTER_PATH.read_text(encoding='utf-8').strip())
        except:
            return None
    return None

def write_counter(v):
    try:
        COUNTER_PATH.write_text(str(int(v)), encoding='utf-8')
    except:
        pass

counter_val = read_counter()
# next_effect_id will be initialized after we load canonical effects

# Load canonical effects to allow reuse/dedupe of identical parameterized effects.
def load_canonical_effects(path):
    """Parse a simple effects.yaml to build a map from (type,stat,op,value) -> id
    This is intentionally lightweight and only extracts the params.stat/op/value
    from each top-level effect entry. Non-numeric ids are ignored for numeric max.
    """
    eff_map = {}
    max_id = None
    p = Path(path)
    if not p.exists():
        return eff_map, None
    cur = None
    in_params = False
    cur_params = {}
    for raw in p.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith('- id:'):
            # finish previous
            if cur and cur_params:
                key = (cur.get('type'), cur_params.get('stat'), cur_params.get('op'), cur_params.get('value'))
                if all(key):
                    eff_map[key] = cur.get('id')
            # start new
            cur = {'id': None, 'type': None}
            in_params = False
            cur_params = {}
            # extract id (strip quotes)
            try:
                cid = line.split(':',1)[1].strip()
                cid = cid.strip('"')
                cur['id'] = cid
                try:
                    num = int(cid)
                    max_id = num if max_id is None or num > max_id else max_id
                except:
                    pass
            except Exception:
                pass
            continue
        if line.startswith('type:') and cur is not None:
            cur['type'] = line.split(':',1)[1].strip()
            continue
        if line.startswith('params:') and cur is not None:
            in_params = True
            continue
        if in_params and cur is not None:
            if ':' in line:
                k,v = [s.strip() for s in line.split(':',1)]
                # try to parse value as int when appropriate
                if k in ('stat','op'):
                    cur_params[k] = v
                elif k == 'value':
                    try:
                        cur_params[k] = int(v)
                    except:
                        cur_params[k] = v
            continue
    # finish last
    if cur and cur_params:
        key = (cur.get('type'), cur_params.get('stat'), cur_params.get('op'), cur_params.get('value'))
        if all(key):
            eff_map[key] = cur.get('id')
    return eff_map, max_id

# attempt to load canonical effects from the project's data folder
canonical_effects_path = Path(__file__).parent.parent / 'src' / 'main' / 'resources' / 'data' / 'effects.yaml'
canonical_effects_map, canonical_max_id = load_canonical_effects(canonical_effects_path)
# Initialize next_effect_id using persistent counter or canonical effects
if counter_val is not None:
    next_effect_id = counter_val + 1
else:
    if canonical_max_id is not None and canonical_max_id >= 9000:
        next_effect_id = canonical_max_id + 1
    else:
        next_effect_id = 9000

# helpers
def read_until_tilde(start_idx):
    parts = []
    i = start_idx
    while i < n:
        line = lines[i]
        if line.strip().endswith('~'):
            parts.append(line.rstrip('~'))
            return ('\n'.join(parts)).strip(), i+1
        else:
            parts.append(line)
            i += 1
    return ('\n'.join(parts)).strip(), i

# basic flag decode maps (from merc.h)
EXTRA_FLAGS = {
    1: 'GLOW',
    2: 'HUM',
    4: 'DARK',
    8: 'LOCK',
    16: 'EVIL',
    32: 'INVIS',
    64: 'MAGIC',
    128: 'NODROP',
    256: 'BLESS',
    512: 'ANTI_GOOD',
    1024: 'ANTI_EVIL',
    2048: 'ANTI_NEUTRAL',
    4096: 'NOREMOVE',
    8192: 'INVENTORY',
}

WEAR_FLAGS = {
    1: 'TAKE',
    2: 'WEAR_FINGER',
    4: 'WEAR_NECK',
    8: 'WEAR_BODY',
    16: 'WEAR_HEAD',
    32: 'WEAR_LEGS',
    64: 'WEAR_FEET',
    128: 'WEAR_HANDS',
    256: 'WEAR_ARMS',
    512: 'WEAR_SHIELD',
    1024: 'WEAR_ABOUT',
    2048: 'WEAR_WAIST',
    4096: 'WEAR_WRIST',
    8192: 'WIELD',
    16384: 'HOLD',
}

# iterate objects
while idx < n:
    line = lines[idx].strip()
    if not line or line.startswith('#OBJECTS'):
        idx += 1
        continue
    if not line.startswith('#'):
        idx += 1
        continue
    # expect #<vnum>
    m = re.match(r'#(\d+)', line)
    if not m:
        idx += 1
        continue
    vnum = int(m.group(1))
    idx += 1
    # name line (names separated by ~)
    name_line = lines[idx].rstrip('\n')
    idx += 1
    short_name = name_line.rstrip('~')
    # short description
    short_desc = lines[idx].rstrip('\n').rstrip('~')
    idx += 1
    # long description (terminated by ~)
    long_desc, idx = read_until_tilde(idx)
    # skip blank lines between the '~' terminator and the next data line
    while idx < n and lines[idx].strip() in ('', '~'):
        idx += 1
    # extra blank line then
    # next line often is type/extra/wear (e.g., "5 1024|2048 1|8192")
    # We'll parse numbers and '|' separated bitfields
    if idx >= n:
        break
    line2 = lines[idx].strip()
    type_line_raw = line2
    idx += 1
    # split into tokens
    tok = re.split(r'\s+', line2)
    # default placeholders
    item_type = None
    extra_raw = None
    wear_raw = None
    if len(tok) >= 1:
        try:
            item_type = int(tok[0])
        except:
            item_type = tok[0]
    if len(tok) >= 2:
        extra_raw = tok[1]
    if len(tok) >= 3:
        wear_raw = tok[2]
    # next line: values (4 ints)
    if idx >= n:
        break
    vals_line = lines[idx].strip()
    idx += 1
    vals = re.split(r'\s+', vals_line)
    # pad to 4
    while len(vals) < 4:
        vals.append('-1')
    try:
        vals = [int(x) for x in vals[:4]]
    except:
        # some malformed entries; keep as strings
        vals = [x for x in vals[:4]]
    # next line: weight/cost/?? sometimes three numbers
    if idx >= n:
        break
    line3 = lines[idx].strip()
    idx += 1
    parts = re.split(r'\s+', line3)
    # try to parse weight/cost/value
    weight = None
    cost = None
    cost2 = None
    if len(parts) >= 1:
        try:
            weight = int(parts[0])
        except:
            weight = parts[0]
    if len(parts) >= 2:
        try:
            cost = int(parts[1])
        except:
            cost = parts[1]
    if len(parts) >= 3:
        try:
            cost2 = int(parts[2])
        except:
            cost2 = parts[2]
    # optional E/A blocks until next # or end
    extra_desc = None
    applies = []
    # If next line starts with 'E' then extra desc block follows one E record: E then keyword~ then description~
    while idx < n:
        l = lines[idx].strip()
        if l == 'E':
            idx += 1
            # keyword line
            kw = lines[idx].rstrip('\n').rstrip('~')
            idx += 1
            # read until ~
            edesc, idx = read_until_tilde(idx)
            extra_desc = edesc
            continue
        # A apply blocks start with single letter 'A' or number? MERC uses 'A' or 'F' variants; many files don't include A blocks here
        if l.startswith('A'):
            # format: A
            idx += 1
            if idx < n:
                loc_line = lines[idx].strip()
                idx += 1
                # location modifier pair
                # e.g., apply type and modifier
                # attempt parse two ints
                mparts = re.split(r'\s+', loc_line)
                try:
                    loc = int(mparts[0])
                    mod = int(mparts[1])
                except:
                    loc = loc_line
                    mod = None
                applies.append({'location': loc, 'modifier': mod})
            continue
        # next object starts with '#'
        if l.startswith('#'):
            break
        # blank or other
        idx += 1
    # convert extra_raw numeric or pipe-separated into flags
    def decode_bitfield(raw, mapping):
        if not raw:
            return []
        flags = []
        # raw might be like '1024|2048' or '0'
        parts = re.split(r'\|', str(raw))
        for p in parts:
            try:
                v = int(p)
            except:
                continue
            for bit, name in mapping.items():
                if v & bit:
                    flags.append(name)
        return flags

    extra_flags = decode_bitfield(extra_raw, EXTRA_FLAGS)
    wear_flags = decode_bitfield(wear_raw, WEAR_FLAGS)

    # extract spell ids from values for scroll/potion (MERC uses values[0..3] as spell numbers)
    spell_ids = []
    if item_type in (2, 10):  # scroll or potion
        for sv in vals:
            if isinstance(sv, int) and sv >= 0:
                spell_ids.append(sv)

    # derive display name (prefer the short description which contains articles)
    display_name = short_desc if short_desc else short_name.rstrip('~')
    # key should be a slug of the MERC name line (short_name)
    key = re.sub(r"[^a-z0-9_]+","_", short_name.lower()).strip('_')

    # extract keywords from the MERC name line (preserve meaningful tokens)
    stopwords = set(['the','a','an','of','and','in','on','to','for'])
    name_tokens = re.findall(r"[a-zA-Z0-9]+", short_name.lower())
    keywords = [t for t in name_tokens if t not in stopwords]

    # Normalize item type to integer where possible
    # MERC -> TASSMUD mapping. Adjusted per project mapping rules:
    # ITEM_LIGHT -> held, ITEM_WAND -> 1-handed weapon, ITEM_STAFF -> 2-handed weapon
    type_map = {
        1: 'held',    # ITEM_LIGHT -> held
        2: 'inventory',# ITEM_SCROLL -> inventory (spell ids preserved)
        3: 'weapon',  # ITEM_WAND -> weapon (1-handed)
        4: 'weapon',  # ITEM_STAFF -> weapon (2-handed)
        5: 'weapon',  # ITEM_WEAPON -> weapon
        8: 'inventory',# ITEM_TREASURE -> inventory
        9: 'armor',   # ITEM_ARMOR -> armor
        10: 'inventory',# ITEM_POTION -> inventory (spell ids preserved)
        12: 'immobile',# ITEM_FURNITURE -> immobile
        13: 'trash',  # ITEM_TRASH -> trash
        15: 'container',# ITEM_CONTAINER -> container
        17: 'inventory',# ITEM_DRINK_CON -> inventory (types: drink)
        18: 'inventory',# ITEM_KEY -> inventory
        19: 'inventory',# ITEM_FOOD -> inventory
        20: 'inventory',# ITEM_MONEY -> inventory
        22: 'immobile',# ITEM_BOAT -> immobile
        23: 'container',# ITEM_CORPSE_NPC -> container+immobile (handled below)
        24: 'container',# ITEM_CORPSE_PC -> container+immobile
        25: 'immobile',# ITEM_FOUNTAIN -> immobile
        26: 'inventory',# ITEM_PILL -> inventory
    }
    item_type_num = None
    try:
        item_type_num = int(item_type)
    except:
        try:
            item_type_num = int(str(item_type).strip())
        except:
            item_type_num = None

    itype = type_map.get(item_type_num, 'unknown')

    # set special types for scrolls/potions/drinks and corpses
    # default: single 'type' unless we need multiple 'types'
    if item_type_num == 2:  # scroll
        item_types = ['scroll']
    elif item_type_num == 10:  # potion
        item_types = ['potion']
    elif item_type_num == 17:  # drink container
        item_types = ['drink']
    else:
        item_types = None

    item = {
        'id': vnum,
        'key': key,
        'name': display_name,
        'keywords': keywords if keywords else None,
        'description': long_desc,
        'weight': weight,
        'value': cost if cost is not None else 0,
        'type': itype,
        # use explicit 'types' when meaningful (potion/scroll/drink)
        **({'types': item_types} if item_types else {}),
        'template_json': {
            'raw_block': '\n'.join(lines[idx-8:idx]) if idx>=8 else '\n'.join(lines[:idx])
        }
    }
    # include the raw parsed type/flags line for easier debugging
    item['template_json']['type_line'] = type_line_raw

    # parse weapon damage and armor fields when possible
    if itype == 'weapon' and isinstance(vals, list) and len(vals) >= 4:
        # MERC weapon convention: vals[1]=num_dice, vals[2]=dice_size, vals[3]=bonus
        try:
            item['multiplier'] = int(vals[1])
            item['base_die'] = int(vals[2])
            item['damage_bonus'] = int(vals[3])
        except Exception:
            pass
        # set default hands for wands/staves/light mapping
        if item_type_num == 3 and not item.get('hands'):
            item['hands'] = 1
        if item_type_num == 4 and not item.get('hands'):
            item['hands'] = 2
        # MERC 'light' mapped to held earlier; if it's a held weapon ensure hands set
        if item_type_num == 1 and itype == 'weapon' and not item.get('hands'):
            item['hands'] = 1

        # Heuristic: determine weapon family/category from name/description keywords
        text = ' '.join(filter(None, [short_desc, item.get('name',''), long_desc])).lower()
        family = None
        # ordered checks (more specific first)
        fam_checks = [
            ('DAGGERS', ['dagger', 'knife', 'dirk', 'stiletto', 'daggers']),
            ('SWORDS', ['sword', 'rapier', 'scimitar', 'shortsword', 'longsword', 'bastard', 'greatsword']),
            ('AXES', ['axe', 'greataxe', 'battleaxe', 'hand axe', 'halberd']),
            ('CLUBS', ['club', 'mace', 'morningstar', 'warhammer', 'maul']),
            ('STAVES', ['staff', 'quarterstaff', 'wizard staff', 'quarter staff']),
            ('BOWS', ['bow', 'longbow', 'shortbow']),
            ('CROSSBOWS', ['crossbow']),
            ('SLINGS', ['sling']),
            ('GLAIVES', ['glaive', 'halberd', 'pike', 'polearm', 'scythe']),
            ('FLAILS', ['flail', 'morningstar', 'chain']),
            ('OTHER', ['trident', 'sickle', 'whip'])
        ]
        for fam, keys in fam_checks:
            for k in keys:
                if k in text:
                    family = fam
                    break
            if family:
                break
        if not family:
            family = 'OTHER'

        # map family -> category
        family_to_category = {
            'DAGGERS': 'SIMPLE', 'CLUBS': 'SIMPLE', 'STAVES': 'SIMPLE', 'SLINGS': 'SIMPLE', 'CROSSBOWS': 'SIMPLE',
            'SWORDS': 'MARTIAL', 'AXES': 'MARTIAL', 'GAUNTLETS': 'MARTIAL', 'BOWS': 'MARTIAL',
            'GLAIVES': 'EXOTIC', 'FLAILS': 'EXOTIC', 'OTHER': 'EXOTIC'
        }
        item['weapon_family'] = family
        item['weapon_category'] = family_to_category.get(family, 'EXOTIC')
    if itype == 'armor' and isinstance(vals, list) and len(vals) >= 1:
        # Use vals[0] as base armor value when present
        try:
            item['armor_class'] = int(vals[0])
        except Exception:
            pass
        # map MERC wear flags to TASSMUD armor slots
        WEAR_TO_SLOT = {
            'WEAR_FINGER': 'finger',
            'WEAR_NECK': 'neck',
            'WEAR_BODY': 'chest',
            'WEAR_HEAD': 'head',
            'WEAR_LEGS': 'legs',
            'WEAR_FEET': 'boots',
            'WEAR_HANDS': 'hands',
            'WEAR_ARMS': 'arms',
            'WEAR_SHIELD': 'off_hand',
            'WEAR_ABOUT': 'back',
            'WEAR_WAIST': 'waist',
            'WEAR_WRIST': 'wrist',
            'WIELD': 'main_hand',
            'HOLD': 'off_hand',
        }
        for wf in wear_flags:
            slot = WEAR_TO_SLOT.get(wf)
            if slot:
                item['slot'] = slot
                break
    # corpses should be both container and immobile
    if item_type_num in (23, 24):
        item.pop('type', None)
        item['types'] = ['container', 'immobile']
    if extra_desc:
        item['template_json']['extra_desc'] = extra_desc
    if extra_flags:
        item['extra_flags'] = extra_flags
    if wear_flags:
        item['wear_flags'] = wear_flags
    if spell_ids:
        item['spell_ids'] = spell_ids
    if applies:
        item['applies'] = applies
        # Convert common MERC applies into on-equip effects where possible
        on_equip_ids = []
        for a in applies:
            loc = a.get('location')
            mod = a.get('modifier')
            if not isinstance(loc, int) or not isinstance(mod, int):
                continue
            if loc == 18:  # APPLY_HITROLL -> ATTACK_HIT_BONUS
                stat = 'ATTACK_HIT_BONUS'
                op = 'ADD'
                value = mod
                # try to reuse an existing canonical effect
                lookup_key = ('MODIFIER', stat, op, value)
                reuse_id = canonical_effects_map.get(lookup_key)
                # also check already-generated effects in this run
                if not reuse_id:
                    for ge in effects:
                        gp = ge.get('params', {})
                        if ge.get('type') == 'MODIFIER' and gp.get('stat') == stat and gp.get('op') == op and gp.get('value') == value:
                            reuse_id = ge.get('id')
                            break
                if reuse_id:
                    on_equip_ids.append(str(reuse_id))
                else:
                    eid = str(next_effect_id)
                    next_effect_id += 1
                    eff = {
                        'id': eid,
                        'name': f"{key} hit +{mod}",
                        'type': 'MODIFIER',
                        'duration': 0,
                        'cooldown': 0,
                        'dice_multiplier': 0,
                        'profficiency_impact': [],
                        'stackPolicy': 'REFRESH',
                        'persistent': True,
                        'priority': 0,
                        'params': {
                            'stat': stat,
                            'op': op,
                            'value': value
                        }
                    }
                    effects.append(eff)
                    on_equip_ids.append(eid)
            elif loc == 19:  # APPLY_DAMROLL -> ATTACK_DAMAGE_BONUS
                stat = 'ATTACK_DAMAGE_BONUS'
                op = 'ADD'
                value = mod
                lookup_key = ('MODIFIER', stat, op, value)
                reuse_id = canonical_effects_map.get(lookup_key)
                if not reuse_id:
                    for ge in effects:
                        gp = ge.get('params', {})
                        if ge.get('type') == 'MODIFIER' and gp.get('stat') == stat and gp.get('op') == op and gp.get('value') == value:
                            reuse_id = ge.get('id')
                            break
                if reuse_id:
                    on_equip_ids.append(str(reuse_id))
                else:
                    eid = str(next_effect_id)
                    next_effect_id += 1
                    eff = {
                        'id': eid,
                        'name': f"{key} damage +{mod}",
                        'type': 'MODIFIER',
                        'duration': 0,
                        'cooldown': 0,
                        'dice_multiplier': 0,
                        'profficiency_impact': [],
                        'stackPolicy': 'REFRESH',
                        'persistent': True,
                        'priority': 0,
                        'params': {
                            'stat': stat,
                            'op': op,
                            'value': value
                        }
                    }
                    effects.append(eff)
                    on_equip_ids.append(eid)
        if on_equip_ids:
            item['on_equip_effect_ids'] = on_equip_ids

    entries.append(item)

# write YAML
def indent(text, n):
    pad = ' ' * n
    return '\n'.join(pad + line if line else pad for line in text.splitlines())

def write_block(f, key, value, indent_level):
    if value is None:
        return
    if isinstance(value, (int, float)):
        f.write(f"{key}: {value}\n")
    elif isinstance(value, list):
        if not value:
            f.write(f"{key}: []\n")
            return
        # prefer inline form for keywords (compact, single-line)
        stripped = key.strip()
        if stripped == 'keywords':
            safe_items = [str(x) for x in value]
            f.write(f"{key}: [{', '.join(safe_items)}]\n")
            return
        f.write(f"{key}:\n")
        for it in value:
            f.write(' ' * indent_level + f"- {it}\n")
    else:
        # block scalar
        safe = str(value)
        if '\n' in safe:
            f.write(f"{key}: |\n")
            f.write(indent(safe, indent_level) + "\n")
        else:
            # single line
            f.write(f"{key}: {safe}\n")

out_path.parent.mkdir(parents=True, exist_ok=True)
with open(out_path, 'w', encoding='utf-8') as f:
    f.write("items:\n")
    for item in entries:
        f.write("  - id: {}\n".format(item.get('id')))
        write_block(f, "    key", item.get('key'), 6)
        write_block(f, "    name", item.get('name'), 6)
        write_block(f, "    description", item.get('description'), 6)
        write_block(f, "    weight", item.get('weight'), 6)
        write_block(f, "    value", item.get('value'), 6)
        write_block(f, "    type", item.get('type'), 6)
        # weapon/armor numeric fields
        write_block(f, "    multiplier", item.get('multiplier'), 6)
        write_block(f, "    base_die", item.get('base_die'), 6)
        write_block(f, "    damage_bonus", item.get('damage_bonus'), 6)
        write_block(f, "    hands", item.get('hands'), 6)
        write_block(f, "    weapon_family", item.get('weapon_family'), 6)
        write_block(f, "    weapon_category", item.get('weapon_category'), 6)
        write_block(f, "    slot", item.get('slot'), 6)
        write_block(f, "    armor_class", item.get('armor_class'), 6)
        if item.get('extra_flags'):
            write_block(f, "    extra_flags", item.get('extra_flags'), 6)
        if item.get('wear_flags'):
            write_block(f, "    wear_flags", item.get('wear_flags'), 6)
        if item.get('keywords'):
            write_block(f, "    keywords", item.get('keywords'), 6)
        if item.get('spell_ids'):
            write_block(f, "    spell_ids", item.get('spell_ids'), 6)
        if item.get('applies'):
            f.write("    applies:\n")
            for a in item.get('applies'):
                f.write("      - location: {}\n".format(a.get('location')))
                f.write("        modifier: {}\n".format(a.get('modifier')))
        if item.get('on_equip_effect_ids'):
            write_block(f, "    on_equip_effect_ids", item.get('on_equip_effect_ids'), 6)
        # template_json
        tj = item.get('template_json')
        if tj:
            f.write("    template_json:\n")
            for k, v in tj.items():
                if '\n' in str(v):
                    f.write(f"      {k}: |\n")
                    f.write(indent(str(v), 8) + "\n")
                else:
                    f.write(f"      {k}: {v}\n")
print(f"Wrote {len(entries)} items to {out_path}")

# write effects file for any generated on-equip effects
if effects:
    effects_out = out_path.parent / 'effects_from_converter.yaml'
    with open(effects_out, 'w', encoding='utf-8') as ef:
        ef.write("effects:\n")
        for e in effects:
            ef.write(f"  - id: \"{e.get('id')}\"\n")
            ef.write(f"    name: \"{e.get('name')}\"\n")
            ef.write(f"    type: {e.get('type')}\n")
            ef.write(f"    duration: {e.get('duration')}\n")
            ef.write(f"    cooldown: {e.get('cooldown')}\n")
            ef.write(f"    dice_multiplier: {e.get('dice_multiplier')}\n")
            ef.write(f"    level_multiplier: 0\n")
            ef.write(f"    profficiency_impact: []\n")
            ef.write(f"    stackPolicy: {e.get('stackPolicy')}\n")
            ef.write(f"    persistent: {str(e.get('persistent')).lower()}\n")
            ef.write(f"    priority: {e.get('priority')}\n")
            # params block
            ef.write(f"    params:\n")
            for pk, pv in e.get('params', {}).items():
                ef.write(f"      {pk}: {pv}\n")
    print(f"Wrote {len(effects)} effects to {effects_out}")
    # update persistent counter to the highest assigned effect id
    try:
        assigned_ids = [int(e.get('id')) for e in effects if str(e.get('id')).isdigit()]
        if assigned_ids:
            max_assigned = max(assigned_ids)
            # write back counter so next run does not reuse ids
            try:
                prev = read_counter() or 0
            except:
                prev = None
            if prev is None or max_assigned > prev:
                write_counter(max_assigned)
    except Exception:
        pass
