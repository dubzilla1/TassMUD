"""
Simple MERC #ROOMS -> TASSMUD rooms.yaml converter for Midgaard
Produces `src/main/resources/data/MERC/midgaard/rooms.yaml`
"""
import os

import sys

# default paths (backwards compatible)
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
                    if i < n and lines[i].endswith('~'):
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
                'flags': [],  # RoomFlag keys that will be loaded into room_flag table
                'legacy_flags': [],  # raw bit flags for reference
                'doors': doors,
                'extras': extras
            }
            # Convert MERC room_flags bitfield to RoomFlag keys
            # MERC bit values from merc.h:
            #   1 = DARK, 4 = NO_MOB, 8 = INDOORS, 512 = PRIVATE
            #   1024 = SAFE, 2048 = SOLITARY, 4096 = PET_SHOP, 8192 = NO_RECALL
            if room_flags & 1:
                room['flags'].append('dark')
                room['legacy_flags'].append('DARK')
            if room_flags & 4:
                room['flags'].append('no_mob')
                room['legacy_flags'].append('NO_MOB')
            if room_flags & 8:
                room['flags'].append('indoors')
                room['legacy_flags'].append('INDOORS')
            if room_flags & 512:
                room['flags'].append('private')
                room['legacy_flags'].append('PRIVATE')
            if room_flags & 1024:
                room['flags'].append('safe')
                room['legacy_flags'].append('SAFE')
            if room_flags & 2048:
                room['flags'].append('solitary')
                room['legacy_flags'].append('SOLITARY')
            if room_flags & 4096:
                room['flags'].append('pet_shop')
                room['legacy_flags'].append('PET_SHOP')
            if room_flags & 8192:
                room['flags'].append('no_recall')
                room['legacy_flags'].append('NO_RECALL')

            rooms.append(room)
        else:
            i += 1
    # parse #RESETS and attach mob spawns/equipment into rooms
    try:
        parse_resets(lines, rooms)
    except Exception:
        # non-fatal: if resets parsing fails, still return rooms
        pass
    return rooms


def parse_resets(lines, rooms):
    # Build quick lookup by room id
    room_map = {r['id']: r for r in rooms}

    # find #RESETS
    i = 0
    n = len(lines)
    while i < n and lines[i].strip() != '#RESETS':
        i += 1
    if i >= n:
        return
    i += 1

    # mapping from MERC wear_loc to our EquipmentSlot keys
    wear_map = {
        3: 'NECK', 4: 'NECK', 5: 'CHEST', 6: 'HEAD', 7: 'LEGS', 8: 'BOOTS',
        9: 'HANDS', 10: 'ARMS', 11: 'OFF_HAND', 12: 'BACK', 13: 'WAIST',
        14: 'HANDS', 15: 'HANDS', 16: 'MAIN_HAND', 17: 'OFF_HAND'
    }

    last_mob_ref = None

    # process until 'S'
    while i < n:
        ln = lines[i].strip()
        i += 1
        if not ln or ln.startswith('*'):
            continue
        if ln == 'S':
            break
        parts = ln.split()
        cmd = parts[0]
        if cmd == 'M' and len(parts) >= 5:
            # M <ignore> <mob-vnum> <limit> <room-vnum>
            try:
                mob_vnum = int(parts[2])
                limit = int(parts[3])
                room_vnum = int(parts[4])
            except Exception:
                continue
            room = room_map.get(room_vnum)
            spawn = {'mob_vnum': mob_vnum, 'limit': limit, 'equipment': [], 'inventory': []}
            if room is not None:
                room.setdefault('mobs', []).append(spawn)
                last_mob_ref = spawn
            else:
                last_mob_ref = None
            continue

        if cmd == 'E' and len(parts) >= 5:
            # E <ignore> <obj-vnum> <ignore> <wear_loc>
            try:
                obj_vnum = int(parts[2])
                wear_loc = int(parts[4])
            except Exception:
                continue
            if last_mob_ref is not None:
                slot = wear_map.get(wear_loc, None)
                last_mob_ref['equipment'].append({'item_vnum': obj_vnum, 'wear_loc': wear_loc, 'slot': slot})
            continue

        if cmd == 'G' and len(parts) >= 3:
            # G <ignore> <obj-vnum> <ignore?> - give object to last mob
            try:
                obj_vnum = int(parts[2])
            except Exception:
                continue
            if last_mob_ref is not None:
                last_mob_ref.setdefault('inventory', []).append({'item_vnum': obj_vnum})
            continue

        if cmd == 'P' and len(parts) >= 5:
            # P <chance> <obj-vnum> <max_in_container> <container-vnum>
            # Put object inside a container (container vnum is parts[4])
            try:
                obj_vnum = int(parts[2])
                # parts[3] is max/count in original MERC format; ignore for now
                container_vnum = int(parts[4])
            except Exception:
                continue
            # Prefer attaching to last mob if present (common pattern: place into mob-carried container)
            if last_mob_ref is not None:
                last_mob_ref.setdefault('inventory', []).append({'item_vnum': obj_vnum, 'container_vnum': container_vnum})
            else:
                # No preceding mob reference found; skip this P reset since we don't
                # have a reliable target room/mob to attach the contained object to.
                # This is safer than attempting to reference an undefined room id.
                continue
            continue

        if cmd == 'O' and len(parts) >= 5:
            # O <ignore> <obj-vnum> <count> <room-vnum>
            try:
                obj_vnum = int(parts[2])
                count = int(parts[3])
                room_vnum = int(parts[4])
            except Exception:
                continue
            room = room_map.get(room_vnum)
            if room is not None:
                # Represent as a SpawnConfig-compatible entry for a room ITEM
                room.setdefault('spawns', []).append({'type': 'ITEM', 'id': obj_vnum, 'quantity': count, 'frequency': 1})
            continue

        if cmd == 'D' and len(parts) >= 5:
            # D <ignore> <room-vnum> <direction> <locks> [description...]
            try:
                room_vnum = int(parts[2])
                dirnum = int(parts[3])
                locks = int(parts[4])
            except Exception:
                continue
            direction = DIRECTIONS.get(dirnum, f'dir{dirnum}')
            room = room_map.get(room_vnum)
            if room is None:
                continue
            # derive to_room from existing exits if available
            to_room = room.get('exits', {}).get(direction)
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
            # optional textual description after the numeric fields
            desc = ''
            if len(parts) > 5:
                desc = ' '.join(parts[5:]).strip()
            # merge or create door entry
            room.setdefault('doors', {})
            room['doors'][direction] = {
                'to': to_room,
                'description': desc,
                'keywords': '',
                'state': state,
                'locked': locked,
                'hidden': False,
                'blocked': blocked,
                'key': None
            }
            continue

        # Ignore other reset types (D, R, etc.)
    return


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
            # spawns (from #RESETS -> emit as SpawnConfig compatible with DataLoader)
            # Combine explicit room ITEM spawns (from O resets) with MOB spawns parsed earlier
            spawn_entries = []
            # room-level spawns created from O records (type: ITEM)
            if r.get('spawns'):
                for s in r['spawns']:
                    spawn_entries.append(s)
            # mob spawns collected under 'mobs' - convert to SpawnConfig-like dicts
            if r.get('mobs'):
                for m in r['mobs']:
                    me = {'type': 'MOB', 'id': m['mob_vnum'], 'quantity': m.get('limit', 1), 'frequency': 1}
                    if m.get('equipment'):
                        me['equipment'] = []
                        for eq in m['equipment']:
                            me['equipment'].append({'item_vnum': eq['item_vnum'], 'wear_loc': eq.get('wear_loc')})
                    if m.get('inventory'):
                        me['inventory'] = []
                        for it in m['inventory']:
                            inv_entry = {'item_vnum': it['item_vnum']}
                            if it.get('container_vnum') is not None:
                                inv_entry['container_vnum'] = it['container_vnum']
                            me['inventory'].append(inv_entry)
                    spawn_entries.append(me)
            if spawn_entries:
                f.write('    spawns:\n')
                for sp in spawn_entries:
                    if sp.get('type') == 'ITEM':
                        f.write('      - type: ITEM\n')
                        f.write(f"        id: {sp['id']}\n")
                        f.write(f"        quantity: {sp.get('quantity', 1)}\n")
                        f.write(f"        frequency: {sp.get('frequency', 1)}\n")
                        # room-level container key expected by DataLoader is 'container'
                        if sp.get('container') is not None:
                            f.write(f"        container: {sp.get('container')}\n")
                    elif sp.get('type') == 'MOB':
                        f.write('      - type: MOB\n')
                        f.write(f"        id: {sp['id']}\n")
                        f.write(f"        quantity: {sp.get('quantity', 1)}\n")
                        f.write(f"        frequency: {sp.get('frequency', 1)}\n")
                        if sp.get('equipment'):
                            f.write('        equipment:\n')
                            for eq in sp['equipment']:
                                f.write('          -\n')
                                f.write(f"              item_vnum: {eq['item_vnum']}\n")
                                f.write(f"              wear_loc: {eq.get('wear_loc', 'null')}\n")
                        if sp.get('inventory'):
                            f.write('        inventory:\n')
                            for it in sp['inventory']:
                                f.write('          -\n')
                                f.write(f"              item_vnum: {it['item_vnum']}\n")
                                if it.get('container_vnum') is not None:
                                    f.write(f"              container_vnum: {it['container_vnum']}\n")
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
                    # Use block scalar for potentially multiline door descriptions
                    if dv['description'] and '\n' in dv['description']:
                        f.write('        description: |\n')
                        for dl in dv['description'].split('\n'):
                            f.write(f"          {dl}\n")
                    else:
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
    # Accept optional args: <input.are> <output.yaml>
    in_path = SRC
    out_path = OUT
    if len(sys.argv) >= 2:
        in_path = sys.argv[1]
    if len(sys.argv) >= 3:
        out_path = sys.argv[2]

    rooms = parse_rooms(in_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    write_yaml(rooms, out_path)
    print(f'Wrote {len(rooms)} rooms to {out_path}')
