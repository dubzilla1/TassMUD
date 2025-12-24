import yaml,sys
p='src/main/resources/data/MERC/midgaard/rooms.yaml'
try:
    with open(p,'r',encoding='utf-8') as f:
        data=yaml.safe_load(f)
    print('OK: parsed', 'rooms' in data and len(data.get('rooms',[])), 'rooms')
except Exception as e:
    print('YAML ERROR', e)
    sys.exit(1)
