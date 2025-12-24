from pathlib import Path
p=Path('src/main/resources/data/MERC/moria/rooms.yaml')
lines=p.read_text(encoding='utf-8').splitlines()
for i in range(1293,1299):
    print(f"{i+1:5}: {lines[i]!r}")
