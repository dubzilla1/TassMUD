"""
Merge per-area shops.yaml files (under src/main/resources/data/MERC/*/shops.yaml)
into a single `src/main/resources/data/shops.yaml` expected by ShopDAO.

Output format:
shops:
  - id: 1
    mob_template_id: 3000
    items: [3001,3002,...]

If multiple area files define the same keeper vnum, items are merged.
"""
import glob
import os
import yaml

DATA_DIR = os.path.join('src','main','resources','data')
MERC_DIR = os.path.join(DATA_DIR,'MERC')
OUT_FILE = os.path.join(DATA_DIR,'shops.yaml')


def load_area_shops(path):
    try:
        with open(path,'r',encoding='utf-8') as f:
            data = yaml.safe_load(f) or {}
            shops = data.get('shops')
            if shops is None:
                return []
            return shops
    except Exception:
        return []


def main():
    merged = {}
    # find all per-area shops
    pattern = os.path.join(MERC_DIR, '*', 'shops.yaml')
    for p in glob.glob(pattern):
        shops = load_area_shops(p)
        for s in shops:
            keeper = s.get('keeper') or s.get('mob_template_id')
            if keeper is None:
                continue
            items = s.get('items') or []
            if keeper not in merged:
                merged[keeper] = set()
            for it in items:
                merged[keeper].add(int(it))

    # write global shops.yaml
    out = {'shops': []}
    idx = 1
    for keeper, items in sorted(merged.items()):
        out['shops'].append({'id': idx, 'mob_template_id': int(keeper), 'items': sorted(list(items))})
        idx += 1

    os.makedirs(DATA_DIR, exist_ok=True)
    with open(OUT_FILE,'w',encoding='utf-8') as f:
        yaml.dump(out, f, sort_keys=False)
    print(f'Wrote {len(out["shops"])} shops to {OUT_FILE}')


if __name__ == '__main__':
    main()
