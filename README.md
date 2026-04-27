# TassMUD

A full-featured multi-user dungeon (MUD) server written in Java, inspired by the classic [DIKU](https://en.wikipedia.org/wiki/DikuMUD) and [MERC](https://en.wikipedia.org/wiki/MERC) lineage — but rebuilt from the ground up with a modern architecture, original class designs, and a custom ability system.

Connect via any telnet client on port **4003**.

---

## Background

TassMUD draws its world content from converted MERC `.are` files (Midgaard and beyond), but the engine is a complete Java rewrite. Nothing from the original C codebase carries over — the goal was to preserve the *feel* of classic MUDs (text combat, exploration, skill progression) while building a clean, maintainable foundation with modern design patterns.

---

## Features

### World
- Hundreds of rooms across multiple areas converted from classic MERC area files
- Sector types, room flags, doors (open/close/lock), and extra descriptions
- Full day/night cycle and dynamic weather system
- NPC roaming, mob special behaviors (`spec_fun`), and timed events (e.g. the Mayor's patrol route)

### Character System
- 6 playable classes: **Fighter, Wizard, Cleric, Rogue, Ranger, Necromancer**
- Each class has a distinct skill and spell progression, stat growth, and capstone abilities unlocked at level 50
- 50 normal levels + 5 hero levels (51–55)
- Skills improve organically through use, following configurable progression curves (Trivial → Legendary)

### Combat
- Real-time tick-based combat (500ms rounds)
- Multi-attack system: second, third, and fourth attacks as proficiency grows
- Full status effect engine: buffs, debuffs, DoTs, stat modifiers (ADD / MULTIPLY / OVERRIDE)
- Stances affect regeneration and available actions
- Autoflee, grouped combat, and mob aggro/fleeing AI

### Class Abilities

**Fighter**
Kick, Bash, Taunt, Disarm, Trip, Heroic Strike, and passive critical hit upgrades (Improved → Greater → Superior Critical).

**Rogue**
Sneak, Backstab, Circle, Assassinate, and Shadow Step — a full stealth-and-burst skill line with cooldown scaling.

**Cleric**
Divine spells across healing, protection, and smiting. *Holy Avenger* capstone grants a temporary divine weapon enchantment.

**Wizard**
Arcane damage and utility spells with a deep spell progression system and mana scaling.

**Ranger**
- **Rapid Shot** — fires a bonus ranged attack each round
- **Multishot** / **Improved Multishot** / **Greater Multishot** — escalating multi-projectile attacks
- **Primal Volley** — unleashes a volley that strikes all enemies in the room
- **Bestial Wrath** *(level 50 capstone)* — you and your animal companion enter a savage frenzy: the companion cannot miss and all its hits become critical strikes; you fire bonus ranged shots every 3 seconds; all multishot abilities operate at maximum effectiveness for 30 seconds

**Necromancer**
- **Animate Dead** — raise a corpse as a bound undead minion, scaling from humble Skeletons up through Ghouls, Mummies, Vampires, Death Knights, and the mighty Lich
- Each undead tier has unique combat abilities (slam attacks, HP drain, AoE taunt)
- Liches resurrect once before truly dying

### Spell System
- 4 schools: **Arcane, Divine, Primal, Occult**
- Per-spell incantations (school-appropriate constructed languages)
- MP cost, cooldowns, target types, and proficiency-gated learning
- Effect system handles all spell outcomes (damage, healing, buffs, summons)

### Economy & Shops
- Shop buy/sell with gold economy
- Loot tables on mobs, auto-loot/auto-gold/auto-sacrifice toggles
- Containers, equipped gear, slot-based equipment system (12 slots)

### Persistence
- H2 file-based database — full game state persists across restarts
- Additive schema migrations (no destructive changes)
- All multi-step writes are wrapped in transactions

---

## Quick Start

**Requirements:** Java 25, Maven 3.x

```powershell
# Build
mvn -DskipTests package

# Start (Windows)
.\target\scripts\restart-mud.ps1

# Connect
telnet localhost 4003
```

---

## Architecture Highlights

- **Thread-per-connection** network model with a central tick scheduler
- **Command registry** + category-based dispatch (no monolithic switch statements)
- **DaoProvider singleton** — all database access through a single access point
- **TransactionManager** — `ThreadLocal<Connection>` with re-entrant transaction support
- **MobileRegistry** — in-memory mob tracking; tick services never poll the DB
- **Effect engine** — stackable, expiring status effects with tick callbacks
- **AllyManager** — timed companion/summon bindings with automatic expiry sweep
- 647 unit tests, 0 failures

---

## Project Layout

```
src/main/java/com/example/tassmud/
├── combat/          # Combat engine, calculators, death handling
├── effect/          # Status effect system and handlers
├── event/           # Spawn scheduling and world events
├── model/           # Game entities (Character, Mobile, Room, Item, Spell, ...)
├── net/             # Server, session handling, command dispatch
├── persistence/     # DAOs, DataLoader, migrations, TransactionManager
├── spell/           # Spell handlers per school
└── util/            # Services (XP, loot, regen, cooldowns, weather, ...)

src/main/resources/data/
├── skills.yaml / spells.yaml / effects.yaml / classes.yaml
├── items.yaml / mobiles.yaml / rooms.yaml / areas.yaml
└── MERC/            # Per-area converted world data (rooms, mobs, items)
```

---

## Licensing

TassMUD uses a split license reflecting its two distinct components.

### Engine (GPLv3)

The Java engine — all source code under `src/main/java/` — is original work and is released under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

You are free to use, study, modify, and distribute it under those terms. Any distributed derivative must also be GPLv3.

### World Content (DIKU / MERC Terms)

The area files (rooms, mobs, items) under `src/main/resources/data/MERC/` are derived from **MERC Diku Mud** (Furey, Hatchet & Kahn, 1993), which is itself a derivative of **DIKU Mud** (Hammer, Seifert, Stærfeldt, Madsen & Nyboe, 1990–1991).

This content is subject to the DIKU and MERC license terms, which require:

- **Non-commercial use only** — no resale or operation for profit
- The original **DIKU authors must appear in the login sequence**
- A working `credits` command must acknowledge the original authors
- The DIKU team must be notified if you operate a public MUD using this content

The full MERC license is at `src/main/resources/MERC/doc/license.txt`.

### In Practice

If you want to run or fork TassMUD:
- You may freely use, modify, and redistribute the **engine** under GPLv3
- The **world content** is non-commercial by the DIKU/MERC terms — if you replace all MERC-derived area files with original content, the non-commercial restriction no longer applies to your fork

---

## Lineage

TassMUD is inspired by the DIKU → MERC → ROM family of MUDs. World content is derived from MERC area files and carries their original license terms. The engine code is a complete Java rewrite — no original C code is included.
