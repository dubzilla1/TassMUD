#!/usr/bin/env python3
"""Orchestrate conversion+merge for all MERC area files.

Usage: python tools/process_all_areas.py

For each .are in src/main/resources/MERC/area it will:
 - create src/main/resources/data/MERC/<area>/ if missing
 - run tools/merc_object_converter.py on the area, producing items/effects
 - run tools/merge_generated_effects.py <area> to merge new effects and patch items
"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).parent.parent
AREAS_DIR = ROOT / 'src' / 'main' / 'resources' / 'MERC' / 'area'
OUT_BASE = ROOT / 'src' / 'main' / 'resources' / 'data' / 'MERC'

if not AREAS_DIR.exists():
    print(f"Area dir not found: {AREAS_DIR}")
    sys.exit(1)

areas = sorted([p for p in AREAS_DIR.glob('*.are')])
if not areas:
    print("No .are files found.")
    sys.exit(0)

for are in areas:
    area_name = are.stem
    out_dir = OUT_BASE / area_name
    out_dir.mkdir(parents=True, exist_ok=True)
    items_out = out_dir / 'items_from_converter.yaml'
    print(f"\n--- Processing area: {area_name} ({are})")
    # run converter
    cmd = [sys.executable, str(ROOT / 'tools' / 'merc_object_converter.py'), str(are), str(items_out)]
    print('Running converter:', ' '.join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(r.stdout)
    if r.returncode != 0:
        print('Converter failed:', r.stderr)
        continue
    # run merge
    cmd2 = [sys.executable, str(ROOT / 'tools' / 'merge_generated_effects.py'), area_name]
    print('Running merge:', ' '.join(cmd2))
    r2 = subprocess.run(cmd2, capture_output=True, text=True)
    print(r2.stdout)
    if r2.returncode != 0:
        print('Merge failed:', r2.stderr)
        continue
print('\nAll areas processed.')
