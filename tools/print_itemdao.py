from pathlib import Path
p=Path('src/main/java/com/example/tassmud/persistence/ItemDAO.java')
lines=p.read_text(encoding='utf-8').splitlines()
for i in range(1,401):
    print(f"{i:4}: {lines[i-1]}")
