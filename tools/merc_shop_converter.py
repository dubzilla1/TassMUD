"""
Parse MERC #SHOPS and #RESETS to emit per-area shops.yaml

Produces `src/main/resources/data/MERC/<area>/shops.yaml` containing:

shops:
  - mob_template_id: <mob_vnum>
    trades: [t0,t1,...]
    profit_buy: <int>
    profit_sell: <int>
    open: <int>
    close: <int>
    comment: "..."
    items: [item_vnum,...]

This script collects items given to mobs via G and P resets in #RESETS
and attaches them to the shop definitions for mob_template_id vnums listed in
#SHOPS. It keeps things simple: no pricing math, no limits, just lists.
"""
import os
import sys

EX_CLOSED = 2
EX_LOCKED = 4
EX_PICKPROOF = 32


def parse_resets_for_shops(lines):
    """Return mapping mob_vnum -> list of item_vnums assigned via G/P."""
    i = 0
    n = len(lines)
    # find #RESETS
    while i < n and lines[i].strip() != '#RESETS':
        i += 1
    if i >= n:
        return {}
    i += 1

    mob_map = {}  # mob_vnum -> list of item_vnums
    last_mob_vnum = None

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
            try:
                mob_vnum = int(parts[2])
            except Exception:
                last_mob_vnum = None
                continue
            last_mob_vnum = mob_vnum
            mob_map.setdefault(mob_vnum, [])
            continue

        if cmd == 'G' and len(parts) >= 3:
            try:
                obj_vnum = int(parts[2])
            except Exception:
                continue
            if last_mob_vnum is not None:
                mob_map.setdefault(last_mob_vnum, []).append(obj_vnum)
            continue

        if cmd == 'P' and len(parts) >= 5:
            # P <chance> <obj-vnum> <max_in_container> <container-vnum>
            try:
                obj_vnum = int(parts[2])
                container_vnum = int(parts[4])
            except Exception:
                continue
            if last_mob_vnum is not None:
                mob_map.setdefault(last_mob_vnum, []).append(obj_vnum)
                # also include the container template as sellable if not present
                mob_map.setdefault(last_mob_vnum, []).append(container_vnum)
            continue

        # ignore other reset types for shop population
    # dedupe lists
    for k, v in list(mob_map.items()):
        seen = []
        for it in v:
            if it not in seen:
                seen.append(it)
        mob_map[k] = seen
    return mob_map


def parse_shops(lines):
    """Return list of shop entries parsed from #SHOPS.

    Each entry: dict with keys mob_template_id, trades(list), profit_buy, profit_sell, open, close, comment
    """
    i = 0
    n = len(lines)
    while i < n and lines[i].strip() != '#SHOPS':
        i += 1
    if i >= n:
        return []
    i += 1

    shops = []
    while i < n:
        ln = lines[i].strip()
        i += 1
        if not ln:
            continue
        if ln == '0':
            break
        if ln.startswith('*'):
            continue
        # shop lines are space/tab separated; comment may follow a semicolon
        # split off comment after ';' or after tabbed comment
        raw = ln
        comment = ''
        if ';' in ln:
            parts = ln.split(';', 1)
            raw = parts[0].strip()
            comment = parts[1].strip()
        toks = raw.split()
        if len(toks) < 9:
            continue
        try:
            mob_template_id = int(toks[0])
            trades = [int(toks[1]), int(toks[2]), int(toks[3]), int(toks[4]), int(toks[5])]
            profit_buy = int(toks[6])
            profit_sell = int(toks[7])
            open_h = int(toks[8])
            close_h = int(toks[9]) if len(toks) > 9 else 23
        except Exception:
            continue
        shops.append({'mob_template_id': mob_template_id, 'trades': trades, 'profit_buy': profit_buy,
                      'profit_sell': profit_sell, 'open': open_h, 'close': close_h, 'comment': comment})
    return shops


def write_yaml(area_name, shops, mob_map, out_path):
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('shops:\n')
        for s in shops:
            f.write('  - id: %d\n' % s['mob_template_id'])
            f.write('    mob_template_id: %d\n' % s['mob_template_id'])
            f.write('    trades: [%s]\n' % (', '.join(str(t) for t in s['trades'])))
            f.write('    profit_buy: %d\n' % s['profit_buy'])
            f.write('    profit_sell: %d\n' % s['profit_sell'])
            f.write('    open: %d\n' % s['open'])
            f.write('    close: %d\n' % s['close'])
            safe = s['comment'].replace('"', '\\"') if s.get('comment') else ''
            f.write('    comment: "%s"\n' % safe)
            items = mob_map.get(s['mob_template_id'], [])
            f.write('    items: [%s]\n' % (', '.join(str(it) for it in items)))
            f.write('\n')


def process_area(are_path, out_path):
    with open(are_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = [l.rstrip('\n') for l in f]
    area_name = os.path.splitext(os.path.basename(are_path))[0]
    mob_map = parse_resets_for_shops(lines)
    shops = parse_shops(lines)
    write_yaml(area_name, shops, mob_map, out_path)
    print(f'Wrote {len(shops)} shops to {out_path}')


if __name__ == '__main__':
    in_path = None
    out_path = None
    if len(sys.argv) >= 2:
        in_path = sys.argv[1]
    if len(sys.argv) >= 3:
        out_path = sys.argv[2]
    if not in_path:
        print('Usage: python tools/merc_shop_converter.py <input.are> [output.yaml]')
        sys.exit(1)
    if not out_path:
        area = os.path.splitext(os.path.basename(in_path))[0]
        out_path = os.path.join('src', 'main', 'resources', 'data', 'MERC', area, 'shops.yaml')
    process_area(in_path, out_path)
