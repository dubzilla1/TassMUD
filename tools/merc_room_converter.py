"""
Simple MERC #ROOMS -> TASSMUD rooms.yaml converter for Midgaard
Produces `src/main/resources/data/MERC/midgaard/rooms.yaml`
"""
import os

SRC = os.path.join('src','main','resources','MERC','area','midgaard.are')
OUT = os.path.join('src','main','resources','data','MERC','midgaard','rooms.yaml')

# simple mappings based on merc_mappings.yaml
SECTOR_MAP = {
    0: 'INSIDE',
    1: 'CITY',
    2: 'FIELD',
    3: 'FOREST',
    4: 'HILLS',
    5: 'MOUNTAIN',
    6: 'WATER_SWIM',
    7: 'WATER_NOSWIM',
    8: 'UNUSED',
    9: 'AIR',
    10: 'DESERT',
}

# exit flag bits
EX_CLOSED = 2
EX_LOCKED = 4
EX_PICKPROOF = 32

DIRECTIONS = {0:'north',1:'east',2:'south',3:'west',4:'up',5:'down'}


def parse_rooms(are_path):
    rooms = []
    with open(are_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = [l.rstrip('\n') for l in f]

    # derive area prefix from file name (e.g., air.are -> air)
    area_name = os.path.splitext(os.path.basename(are_path))[0]

    i = 0
    n = len(lines)
    # find #ROOMS
    while i < n:
        if lines[i].strip() == '#ROOMS':
            i += 1
            break
        i += 1
    # parse until #0
    while i < n:
        line = lines[i].strip()
        if line == '#0':
            break
        if line.startswith('#') and line[1:].isdigit():
            vnum = int(line[1:])
            i += 1
            # name
            name = ''
            if i < n:
                name_line = lines[i]
                name = name_line.rstrip('~').strip()
                i += 1
            # long desc (tilde-terminated)
            desc_lines = []
            while i < n and not lines[i].endswith('~'):
                desc_lines.append(lines[i])
                i += 1
            if i < n:
                # last line ending with ~ (may contain text before ~)
                last = lines[i]
                desc_lines.append(last[:-1])
                i += 1
            long_desc = '\n'.join(desc_lines).strip()
            # next: area room-flags sector-type
            if i < n:
                parts = lines[i].split()
                if len(parts) >= 3:
                    try:
                        # area = int(parts[0]) # ignored
                        room_flags = int(parts[1])
                        sector_type = int(parts[2])
                    except Exception:
                        room_flags = 0
                        sector_type = 0
                else:
                    room_flags = 0
                    sector_type = 0
                i += 1
            else:
                room_flags = 0
                sector_type = 0

            # parse optional blocks until 'S'
            doors = {}
            extras = {}
            exits = {}
            while i < n:
                ln = lines[i]
                if ln == 'S':
                    i += 1
                    break
                if ln.startswith('D') and ln[1:].isdigit():
                    dnum = int(ln[1:])
                    direction = DIRECTIONS.get(dnum, f'dir{dnum}')
                    i += 1
                    # description tilde-terminated
                    ddesc_lines = []
                    while i < n and not lines[i].endswith('~'):
                        ddesc_lines.append(lines[i])
                        i += 1
                    if i < n:
                        ddesc_lines.append(lines[i][:-1])
                        i += 1
                    ddesc = '\n'.join(ddesc_lines).strip()
                    # keywords (tilde-terminated)
                    kw = ''
                    if i < n:
                        if lines[i].endswith('~'):
                            kw = lines[i][:-1].strip()
                            i += 1
                        else:
                            # unlikely, but consume until ~
                            kw_lines = []
                            while i < n and not lines[i].endswith('~'):
                                kw_lines.append(lines[i])
                                i += 1
                            if i < n:
                                kw_lines.append(lines[i][:-1])
                                i += 1
                            kw = ' '.join(kw_lines).strip()
                    # locks line: '<locks:number> <key:number> <to_room:number>'
                    locks = 0
                    keyv = 0
                    to_room = None
                    if i < n:
                        parts = lines[i].split()
                        if len(parts) >= 3:
                            try:
                                locks = int(parts[0])
                                keyv = int(parts[1])
                                to_room = int(parts[2])
                            except Exception:
                                pass
                        i += 1
                    # populate doors and exits
                    exits[direction] = to_room
                    state = 'OPEN'
                    locked = False
                    blocked = False
                    if locks & EX_CLOSED:
                        state = 'CLOSED'
                    if locks & EX_LOCKED:
                        locked = True
                        state = 'CLOSED'
                    if locks & EX_PICKPROOF:
                        blocked = True
                    doors[direction] = {
                        'to': to_room,
                        'description': ddesc,
                        'keywords': kw,
                        'state': state,
                        'locked': locked,
                        'hidden': False,
                        'blocked': blocked,
                        'key': None if keyv == 0 else keyv
                    }
                    continue
                if ln == 'E':
                    i += 1
                    # keywords line
                    keyw = ''
                    if i < n and lines[i].endswith('~'):
                        keyw = lines[i][:-1].strip()
                        i += 1
                    else:
                        # collect until ~
                        kparts = []
                        while i < n and not lines[i].endswith('~'):
                            kparts.append(lines[i])
                            i += 1
                        if i < n:
                            kparts.append(lines[i][:-1])
                            i += 1
                        keyw = ' '.join(kparts).strip()
                    # description
                    ed_lines = []
                    while i < n and not lines[i].endswith('~'):
                        ed_lines.append(lines[i])
                        i += 1
                    if i < n:
                        ed_lines.append(lines[i][:-1])
                        i += 1
                    ed = '\n'.join(ed_lines).strip()
                    # use first keyword as extras key
                    extras_key = keyw.split()[0] if keyw else 'description'
                    extras[extras_key] = ed
                    continue
                # otherwise skip unknown lines
                i += 1

            # determine area id by vnum range
            if vnum >= 3200:
                area_id = 32
            elif vnum >= 3100:
                area_id = 31
            else:
                area_id = 30

            room = {
                'id': vnum,
                'key': f'{area_name}_room_{vnum}',
                'name': name,
                'area_id': area_id,
                'short_desc': name,
                'long_desc': long_desc,
                'exits': exits,
                'sector_type': SECTOR_MAP.get(sector_type, 'FIELD'),
                'legacy_flags': [],
                'doors': doors,
                'extras': extras
            }
            # convert some flags to names (simple mapping)
            if room_flags & 4:
                room['legacy_flags'].append('NO_MOB')
            if room_flags & 8:
                room['legacy_flags'].append('INDOORS')
            if room_flags & 1024:
                room['legacy_flags'].append('SAFE')

            rooms.append(room)
        else:
            i += 1
    return rooms


def write_yaml(rooms, out_path):
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('rooms:\n')
        for r in rooms:
            f.write(f"  - id: {r['id']}\n")
            f.write(f"    key: {r['key']}\n")
            safe_name = r['name'].replace('"', '\\"')
            f.write(f"    name: \"{safe_name}\"\n")
            f.write(f"    area_id: {r['area_id']}\n")
            safe_short = r['short_desc'].replace('"', '\\"')
            f.write(f"    short_desc: \"{safe_short}\"\n")
            f.write('    long_desc: |\n')
            for ld_line in r['long_desc'].split('\n'):
                f.write(f"      {ld_line}\n")
            # exits
            f.write('    exits:\n')
            for dname, dval in r['exits'].items():
                if dval is None:
                    f.write(f"      {dname}: null\n")
                else:
                    f.write(f"      {dname}: {dval}\n")
            f.write(f"    sector_type: {r['sector_type']}\n")
            if r['legacy_flags']:
                f.write('    legacy_flags:\n')
                for lf in r['legacy_flags']:
                    f.write(f"      - {lf}\n")
            # doors
            if r['doors']:
                f.write('    doors:\n')
                for dn, dv in r['doors'].items():
                    f.write(f"      {dn}:\n")
                    f.write(f"        to: {dv['to']}\n")
                    # escape double quotes in description
                    desc = dv['description'].replace('"', '\\"') if dv['description'] else ''
                    f.write(f"        description: \"{desc}\"\n")
                    kw = dv['keywords'].replace('"', '\\"') if dv['keywords'] else ''
                    f.write(f"        keywords: \"{kw}\"\n")
                    f.write(f"        state: {dv['state']}\n")
                    f.write(f"        locked: {str(dv['locked']).lower()}\n")
                    f.write(f"        hidden: {str(dv['hidden']).lower()}\n")
                    f.write(f"        blocked: {str(dv['blocked']).lower()}\n")
                    f.write(f"        key: {('null' if dv['key'] is None else dv['key'])}\n")
            # extras
            if r['extras']:
                f.write('    extras:\n')
                for ek, ev in r['extras'].items():
                    f.write(f"      {ek}: |\n")
                    for l in ev.split('\n'):
                        f.write(f"        {l}\n")
            f.write('\n')


if __name__ == '__main__':
    rooms = parse_rooms(SRC)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    write_yaml(rooms, OUT)
    print(f'Wrote {len(rooms)} rooms to {OUT}')
