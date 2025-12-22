import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from merc_room_converter import parse_rooms, write_yaml

ARE_DIR = os.path.join('src', 'main', 'resources', 'MERC', 'area')
OUT_BASE = os.path.join('src', 'main', 'resources', 'data', 'MERC')

def convert_area(area_filename):
    are_path = os.path.join(ARE_DIR, area_filename)
    if not os.path.exists(are_path):
        print(f"Area file not found: {are_path}")
        return 1
    area_name = os.path.splitext(area_filename)[0]
    out_dir = os.path.join(OUT_BASE, area_name)
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, 'rooms.yaml')
    rooms = parse_rooms(are_path)
    write_yaml(rooms, out_path)
    print(f'Wrote {len(rooms)} rooms to {out_path}')
    return 0

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('Usage: run_merc_area.py <areafile.are>')
        sys.exit(2)
    sys.exit(convert_area(sys.argv[1]))
