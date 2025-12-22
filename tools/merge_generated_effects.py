#!/usr/bin/env python3
"""Merge generated effects into canonical effects.yaml and patch item files.

Usage: python tools/merge_generated_effects.py 

This script:
- reads src/main/resources/data/effects.yaml (canonical)
- reads generated effects at src/main/resources/data/MERC/midgaard/effects_from_converter.yaml
- for each generated effect, reuses canonical effect if params match, else appends a new effect with a new numeric id
- updates src/main/resources/data/MERC/midgaard/items_from_converter.yaml to replace on_equip_effect_ids with canonical/new ids
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).parent.parent
CANONICAL = ROOT / 'src' / 'main' / 'resources' / 'data' / 'effects.yaml'
area = 'midgaard'
if len(sys.argv) > 1:
    area = sys.argv[1]

GEN = ROOT / 'src' / 'main' / 'resources' / 'data' / 'MERC' / area / 'effects_from_converter.yaml'
ITEMS = ROOT / 'src' / 'main' / 'resources' / 'data' / 'MERC' / area / 'items_from_converter.yaml'


def load_canonical(path):
    eff_map = {}
    max_id = 0
    cur = None
    in_params = False
    cur_params = {}
    if not path.exists():
        return eff_map, 0
    for raw in path.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith('- id:'):
            if cur and cur_params:
                key = (cur.get('type'), cur_params.get('stat'), cur_params.get('op'), cur_params.get('value'))
                if all(key):
                    eff_map[key] = cur.get('id')
            cur = {'id': None, 'type': None}
            in_params = False
            cur_params = {}
            m = re.match(r'- id:\s*"?(\d+)"?', line)
            if m:
                cid = m.group(1)
                cur['id'] = cid
                try:
                    num = int(cid)
                    if num > max_id:
                        max_id = num
                except:
                    pass
            else:
                # non-numeric id, store raw
                cid = line.split(':',1)[1].strip().strip('"')
                cur['id'] = cid
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
                if k in ('stat','op'):
                    cur_params[k] = v
                elif k == 'value':
                    try:
                        cur_params[k] = int(v)
                    except:
                        cur_params[k] = v
            continue
    if cur and cur_params:
        key = (cur.get('type'), cur_params.get('stat'), cur_params.get('op'), cur_params.get('value'))
        if all(key):
            eff_map[key] = cur.get('id')
    return eff_map, max_id


def parse_generated(path):
    effects = []
    if not path.exists():
        return effects
    cur = None
    in_params = False
    for raw in path.read_text(encoding='utf-8').splitlines():
        line = raw.rstrip('\n')
        s = line.strip()
        if not s:
            continue
        if s.startswith('- id:'):
            if cur:
                effects.append(cur)
            cur = {'id': None, 'name': None, 'type': None, 'params': {}}
            in_params = False
            m = re.match(r'- id:\s*"?([^\"]+)"?', s)
            if m:
                cur['id'] = m.group(1)
            continue
        if cur is None:
            continue
        if s.startswith('name:'):
            cur['name'] = s.split(':',1)[1].strip().strip('"')
            continue
        if s.startswith('type:'):
            cur['type'] = s.split(':',1)[1].strip()
            continue
        if s.startswith('params:'):
            in_params = True
            continue
        if in_params:
            if ':' in s:
                k,v = [t.strip() for t in s.split(':',1)]
                if k in ('stat','op'):
                    cur['params'][k] = v
                elif k == 'value':
                    try:
                        cur['params'][k] = int(v)
                    except:
                        cur['params'][k] = v
    if cur:
        effects.append(cur)
    return effects


def append_effects_to_canonical(path, new_effects):
    # append at end of file with proper formatting
    with open(path, 'a', encoding='utf-8') as f:
        for e in new_effects:
            f.write('\n  - id: "{}"\n'.format(e['id']))
            f.write('    name: "{}"\n'.format(e.get('name','')))
            f.write('    type: {}\n'.format(e.get('type','')))
            f.write('    duration: {}\n'.format(e.get('duration',0)))
            f.write('    cooldown: {}\n'.format(e.get('cooldown',0)))
            f.write('    dice_multiplier: {}\n'.format(e.get('dice_multiplier',0)))
            f.write('    level_multiplier: 0\n')
            f.write('    profficiency_impact: []\n')
            f.write('    stackPolicy: {}\n'.format(e.get('stackPolicy','REFRESH')))
            f.write('    persistent: {}\n'.format(str(e.get('persistent',True)).lower()))
            f.write('    priority: {}\n'.format(e.get('priority',0)))
            f.write('    params:\n')
            for pk,pv in e.get('params',{}).items():
                f.write('      {}: {}\n'.format(pk, pv))


def update_items_file(path, id_map):
    txt = path.read_text(encoding='utf-8')
    out_lines = []
    lines = txt.splitlines()
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        out_lines.append(line)
        if line.strip().startswith('on_equip_effect_ids:'):
            # determine indent
            indent = line[:line.index('on_equip_effect_ids:')]
            item_indent = indent + '  '
            # collect following - lines
            j = i+1
            vals = []
            while j < n and lines[j].startswith(item_indent + '-'):
                m = re.match(r'\s*-\s*(\S+)', lines[j])
                if m:
                    orig = m.group(1).strip().strip('"')
                    vals.append(orig)
                j += 1
            # write mapped values
            for v in vals:
                newv = id_map.get(v, v)
                # if newv is numeric string, write unquoted number
                if re.fullmatch(r'\d+', str(newv)):
                    out_lines.append(item_indent + f"- {newv}")
                else:
                    out_lines.append(item_indent + f"- \"{newv}\"")
            i = j
            continue
        i += 1
    path.write_text('\n'.join(out_lines) + '\n', encoding='utf-8')


def main():
    canon_map, max_id = load_canonical(CANONICAL)
    gens = parse_generated(GEN)
    id_map = {}  # gen id -> canonical/new id
    new_effects = []
    # Start assigning persistent generated effect ids at 9000 and carry forward.
    if max_id >= 9000:
        next_id = max_id + 1
    else:
        next_id = 9000
    for g in gens:
        t = g.get('type')
        p = g.get('params',{})
        key = (t, p.get('stat'), p.get('op'), p.get('value'))
        if key in canon_map:
            id_map[g['id']] = canon_map[key]
        else:
            # assign new numeric id
            nid = str(next_id)
            next_id += 1
            id_map[g['id']] = nid
            # create a copy to append
            ne = {
                'id': nid,
                'name': g.get('name',''),
                'type': g.get('type'),
                'duration': g.get('duration',0),
                'cooldown': g.get('cooldown',0),
                'dice_multiplier': g.get('dice_multiplier',0),
                'stackPolicy': g.get('stackPolicy','REFRESH'),
                'persistent': g.get('persistent',True),
                'priority': g.get('priority',0),
                'params': g.get('params',{})
            }
            new_effects.append(ne)
    if new_effects:
        append_effects_to_canonical(CANONICAL, new_effects)
        print(f"Appended {len(new_effects)} new effects to {CANONICAL}")
    else:
        print("No new effects to append; all reused from canonical.")
    # update persistent counter based on newly assigned canonical ids
    counter_file = Path(__file__).parent / 'effect_id_counter.txt'
    try:
        if new_effects:
            highest = max(int(e['id']) for e in new_effects if str(e['id']).isdigit())
            if counter_file.exists():
                try:
                    cur = int(counter_file.read_text(encoding='utf-8').strip())
                except:
                    cur = 0
            else:
                cur = 0
            if highest > cur:
                counter_file.write_text(str(highest), encoding='utf-8')
    except Exception:
        pass
    # update items file
    update_items_file(ITEMS, id_map)
    print(f"Patched items file {ITEMS} with {len(id_map)} id mappings")
    # show mapping
    for k,v in id_map.items():
        print(f"{k} -> {v}")

if __name__ == '__main__':
    main()
