import os
from pathlib import Path
from merc_room_converter import parse_rooms, write_yaml

ARE_DIR = Path('src') / 'main' / 'resources' / 'MERC' / 'area'
OUT_BASE = Path('src') / 'main' / 'resources' / 'data' / 'MERC'

def convert_all():
    are_files = sorted(ARE_DIR.glob('*.are'))
    if not are_files:
        print('No .are files found in', ARE_DIR)
        return 1
    for are in are_files:
        print('Converting', are.name)
        rooms = parse_rooms(str(are))
        area_name = are.stem
        out_dir = OUT_BASE / area_name
        out_dir.mkdir(parents=True, exist_ok=True)
        rooms_out = out_dir / 'rooms.yaml'
        write_yaml(rooms, str(rooms_out))

        # build heuristic areas.yaml
        if rooms:
            vmin = min(r['id'] for r in rooms)
            vmax = max(r['id'] for r in rooms)
            area_id = vmin // 100
            sector = rooms[0].get('sector_type', 'FIELD')
        else:
            vmin = 0
            vmax = 0
            area_id = 0
            sector = 'FIELD'

        areas_out = out_dir / 'areas.yaml'
        with open(areas_out, 'w', encoding='utf-8') as f:
            f.write('areas:\n')
            f.write(f'  - id: {area_id}\n')
            f.write(f'    name: "{area_name.capitalize()}"\n')
            f.write(f'    description: "Converted from {are.name}"\n')
            f.write(f'    sector_type: "{sector}"\n')
            f.write(f'    author: "MERC"\n')
            f.write(f'    source_file: "{are.name}"\n')
            f.write(f'    vnum_range: "{vmin}-{vmax}"\n\n')

        print(f'  -> wrote {len(rooms)} rooms to {rooms_out} and areas to {areas_out}')
    return 0

if __name__ == '__main__':
    raise SystemExit(convert_all())
