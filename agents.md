# TassMUD – Agent Context Document

> **Last Updated**: January 2026  
> This document provides AI agents with comprehensive project context for effective assistance.

---

## Recent Updates (Jan 4, 2026)

Quick summary of changes made recently so agents and converters are aware:

- Spell MP Cost System
  - MP cost is now handled centrally in `CombatCommandHandler.handleCastCommand()` rather than per-handler.
  - Default MP cost = spell level (e.g., level 3 spell costs 3 MP). Previously was `2^level`.
  - Individual spells can override with custom `mpCost` field in `spells.yaml`.
  - MP is checked before casting (blocks if insufficient) but only **deducted on successful cast**.
  - `Spell.java` model now has `mpCost` field (0 = use spell level as default).
  - `spelltb` table has new `mp_cost INT DEFAULT 0` column (migration added).
  - Cast flow shows: "You begin casting X..." → success: "Spell completed! (-N MP)" or failure: "The spell fizzles."

- Spell Handler Architecture
  - `SpellHandler` interface: `boolean cast(Integer casterId, String args, SpellContext ctx)`
  - Return `true` for successful cast (MP deducted), `false` for failure (no MP cost).
  - `SpellRegistry` maps spell names to handlers; handlers register during static init.
  - Per-school handlers: `ArcaneSpellHandler`, `DivineSpellHandler`, `PrimalSpellHandler`, `OccultSpellHandler`.
  - `SpellContext` provides: `CommandContext`, `Combat`, targets, spell definition, proficiency, aggro helpers.

- Rogue Skill Line (NEW)
  - **Sneak** (id=300): Toggle mode. Suppresses arrival/departure room messages and prevents aggro from mobs.
  - **Backstab** (id=301): Out-of-combat attack. 2x damage on hit, 4x on crit. Cooldown scales 15s→3s with proficiency.
  - **Circle** (id=307): In-combat backstab variant. 2x damage hit, 4x crit. Cooldown scales 30s→6s with proficiency.
  - **Assassinate** (id=308): Out-of-combat. 4x damage on hit, **instant kill on crit**. Fixed 60s cooldown.
  - **Shadow Step** (id=309): Personal teleport. `shadow set` marks location, `shadow step`/`ss` teleports back. Blocked in PRISON rooms. Cooldown scales 60s→15s with proficiency.
  - All implemented in `CombatCommandHandler.java` with cooldown scaling, proficiency improvement, and debug output.

- Critical Hit System (Passive Skills)
  - **Improved Critical** (id=23): Mastered skill lowers crit threshold by 1 (crit on 19+).
  - **Greater Critical** (id=24): Stacks, lowers by additional 1 (crit on 18+).
  - **Superior Critical** (id=25): Stacks, lowers by additional 1 (crit on 17+).
  - Applied as permanent `CRITICAL_THRESHOLD_BONUS` modifier in `ClientHandler.buildCharacterForCombat()`.

- Riposte Rework
  - Riposte chance now scales from 25% (0 proficiency) to 75% (100 proficiency) via formula `25 + (proficiency / 2)`.
  - Defender receives feedback: `">>> You spot an opening and prepare to riposte!"`.
  - Riposte is now marked as `is_passive: true` in skills.yaml.

- Sneak & Aggro Integration
  - `ClientHandler.isSneaking(characterId)` checks `is_sneaking` flag.
  - `roomAnnounceFromActor()` suppresses messages for sneaking characters.
  - `MobileRoamingService.checkAggroOnRoomEntry()` and `processAggressiveMobs()` skip sneaking players.

- Class Balance (classes.yaml)
  - Fighter: MV increased 4→6, skill unlocks reorganized (shields/martial at L1, parry L20, second attack L15).
  - Wizard: MV increased 2→6.
  - Cleric: MV increased 3→4.
  - Rogue: HP 6→8, MP 2→0, MV 6→10. New skill progression includes critical skills at 10/25/40 and rogue skills.

---

## Previous Updates (Dec 23, 2025)

Quick summary of changes made recently so agents and converters are aware:

- Converters
  - `tools/merc_room_converter.py`: now parses `D` resets inside `#RESETS` and emits `doors:` entries (state/locked/blocked/description) into per-area `rooms.yaml`.
  - `tools/merc_object_converter.py`: added `immobile` to item templates when MERC wear_flags == 0; emits `types:` lists.
  - `tools/merc_mob_converter.py`: improved mob level extraction (reads the first integer after the `S` stat line and clamps levels above 50 to 50).
  - `tools/merc_shop_converter.py`: new script to parse `#SHOPS` and `G`/`P` resets into per-area `shops.yaml` files.
  - `tools/merge_shops_to_global.py`: merges per-area `shops.yaml` into `src/main/resources/data/shops.yaml` consumed by the runtime `ShopDAO`.

- Runtime & persistence
  - Door data is stored/loaded via the existing `door` table and `CharacterDAO.upsertDoor` / `getDoor` methods; `DataLoader` seeds doors from YAML `doors:` sections.
  - Player movement now respects door state: `MovementCommandHandler` checks door metadata and blocks movement for `closed`, `locked`, `blocked`, or `hidden` doors. `showRoom()` hides such exits from the displayed list.
  - `open` and `close` player commands were added (registered in `CommandRegistry` and implemented in `MovementCommandHandler`) and update door state via `CharacterDAO.upsertDoor`.
  - Spawn-time persistence: runtime changes were added so that when mobs spawn with equipment/inventory, item instances are created and mobile↔item markers are persisted (via `MobileDAO`) so equipped/inventory items move into corpses on death and rehydrate after reload.

- Shops & economy
  - Shops are now extracted from MERC area files. Shop items are taken directly from `G`/`P` resets attached to shopkeeper mobs; `ShopDAO` loads the merged `data/shops.yaml` and exposes shop menus in memory (no pricing rules applied beyond preserving `profit_buy`/`profit_sell` metadata).

- Item behavior & loot
  - `immobile` templates prevent pickup; `ItemCommandHandler` was updated to deny `get` on immobile items.
  - `LootGenerator` and related code were adjusted to use the mob's true level for loot scaling (mob level parsing fix noted above).

These changes are reflected in the workspace under `tools/` and `src/main/java/...` and should be referenced by agents when generating or converting MERC data.



## Project Overview

TassMUD is a **Java 17 Maven project**: a multi-user dungeon (MUD) server exposing a telnet-like interface. It runs as a single JVM process, handles multiple concurrent player connections, and persists game state to an embedded H2 file database.

### Quick Reference

| Aspect | Details |
|--------|---------|
| **Build** | `mvn -DskipTests package` → `target/tass-mud-0.1.0-shaded.jar` |
| **Run (dev)** | `.\target\scripts\restart-mud.ps1` |
| **Port** | 4003 (default, configurable via `TASSMUD_PORT` env var) |
| **Database** | H2 file DB at `./data/tassmud` (`.mv.db`, `.trace.db`) |
| **DB URL** | `jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1` |

### Important Constraints

- **H2 file locking**: Only one process can access the DB at a time. Stop the server before running external DB tools.
- **Shaded JAR**: Always use the shaded jar for classpath operations to avoid H2 version mismatches.

---

## Project Structure

```
src/main/java/com/example/tassmud/
├── combat/           # Combat system (CombatManager, Combat, Combatant, etc.)
├── event/            # Event/spawn scheduling (EventScheduler, SpawnManager)
├── model/            # Data models (Character, Mobile, Item, Skill, Spell, etc.)
├── net/              # Network layer (Server, ClientHandler, CommandRegistry)
├── persistence/      # DAOs and data loading (CharacterDAO, ItemDAO, etc.)
├── scripts/          # PowerShell scripts (restart-mud.ps1)
├── tools/            # Dev utilities (SchemaInspector)
└── util/             # Services (TickService, RegenerationService, etc.)

src/main/resources/
├── asciiart/         # Title screen art
├── data/             # YAML seed data (items, skills, spells, classes, etc.)
└── help/             # YAML help pages
```

---

## Core Architecture

### Server & Client Handling (`net/`)

The server backbone consists of three key files:

#### `Server.java`
- Entry point; starts the TCP listener on port 4003
- Initializes all subsystems in order:
  1. `CharacterDAO.ensureTable()` – DB schema migrations
  2. `DataLoader.loadDefaults()` – Seed data from YAML resources
  3. `TickService` – Central scheduler for periodic tasks
  4. `GameClock` – In-game time tracking
  5. `CooldownManager` – Skill/spell cooldown tracking
  6. `CombatManager` – Combat tick processing
  7. `EventScheduler` + `SpawnManager` – Mob/item spawn scheduling
  8. `RegenerationService` – HP/MP/MV recovery
  9. `MobileRoamingService` – NPC wandering behavior
- Accepts connections and spawns `ClientHandler` threads

#### `ClientHandler.java`
- **The central command dispatcher** – most game logic lives here
- Handles login/character creation flow
- Main command loop with switch statement for all commands
- Manages per-session state:
  - `playerName`, `characterId`, `currentRoomId`
  - `promptFormat` (customizable prompt)
  - `debugChannelEnabled` (GM debug output)
- Static messaging utilities:
  - `broadcastRoomMessage()` – Send to all players in a room
  - `sendToCharacter()` – Send to specific player by character ID
  - `roomAnnounce()` – Arrival/departure announcements (respects sleep state)
  - `sendPromptToCharacter()` – Refresh player's prompt
  - `triggerAutoflee()` – Combat system callback for auto-flee

#### `CommandRegistry.java` & `CommandParser.java`
- `CommandRegistry` is the **single source of truth** for all commands
- Commands are registered with:
  - `register()` – Normal commands (blocked in combat)
  - `registerCombat()` – Allowed during combat
  - `registerGm()` – GM-only commands
- Each command has: name, description, category, aliases, GM flag, combat flag
- `CommandParser` resolves input to canonical command names (supports prefix matching and aliases)

**Command dispatch (refactored)**

- `CommandRegistry` remains the single source of truth for metadata about every command (name, description, category, aliases, GM/combat flags).
- `CommandParser` still resolves raw input into a canonical command name (supports prefix matching and aliases).
- A new `CommandDispatcher` inspects the parsed command, looks up its `CommandDefinition` in `CommandRegistry`, and routes execution to a per-category `CommandHandler` implementation (e.g., `ItemCommandHandler`, `MovementCommandHandler`, `CommunicationCommandHandler`, `CombatCommandHandler`, `GroupCommandHandler`, `GmCommandHandler`, `InformationCommandHandler`, `SystemCommandHandler`).
- Each `CommandHandler` implements a small `supports(String)` check and a `handle(CommandContext)` method. The `CommandContext` bundles everything a handler needs: parsed `Command`, `playerName`, `characterId`, `currentRoomId`, `CharacterRecord`, `CharacterDAO`, `PrintWriter out`, permission flags (`isGm`, `inCombat`) and a reference to the `ClientHandler` instance for helper calls.
- Dispatch behavior: if the dispatcher finds a handler for the command category and the handler reports it `supports()` the command, the handler's `handle()` is invoked and the main loop in `ClientHandler` does not run the old large switch body for that command. If no handler claims the command, the input falls back to the legacy handling path (or a handler stub), preserving backward compatibility during the refactor.

**Categories**: `INFORMATION`, `MOVEMENT`, `COMMUNICATION`, `ITEMS`, `COMBAT`, `GROUP`, `SYSTEM`, `GM` (handlers are mapped by category in `CommandDispatcher`).

**Adding a new command (refactor-aware)**:
1. Add the command definition in `CommandRegistry.java` (name, category, aliases, flags).
2. Implement the command in the appropriate `CommandHandler` class (or add a new handler if creating a new category). Use `CommandContext` to access `dao`, `out`, `rec`, etc., and call `ClientHandler` helper methods if needed.
3. Add a help page in `src/main/resources/help/global_commands.yaml`.
4. If the command affects persistence or schema, update the relevant DAO and the data migration logic.

---

## Data Models (`model/`)

### Core Entities

| Model | Description |
|-------|-------------|
| `Character` | Player character with stats, vitals (HP/MP/MV), abilities, room location |
| `Mobile` | NPC/monster instance (extends Character, adds combat AI, spawn tracking) |
| `MobileTemplate` | Blueprint for spawning mobiles (stats, behaviors, loot, respawn) |
| `ItemTemplate` | Item definition (type, stats, effects, requirements) |
| `ItemInstance` | Spawned item in world (location: room, character inventory, or container) |
| `Room` | Game location with exits (N/E/S/W/U/D), description, sector type |
| `Area` | Collection of rooms with shared properties (name, level range) |

### Skills & Spells

**`Skill`**:
- Has `id`, `name`, `description`, `progression` curve, `traits`, `cooldown`
- **Progression curves** (how fast proficiency increases on use):
  - `INSTANT` – Immediate 100% mastery (armor/weapon proficiencies)
  - `TRIVIAL` through `LEGENDARY` – Varying learning speeds
- **Traits**: `INNATE` (known by all), `COMBAT` (requires combat), etc.
- Characters have proficiency 1-100% in each known skill

**`Spell`**:
- Has `school` (ARCANE, DIVINE, PRIMAL, OCCULT), `level` (1-10), `target` type
- Progression works like skills
- Target types: `SELF`, `CURRENT_ENEMY`, `EXPLICIT_MOB_TARGET`, `ALL_ENEMIES`, etc.
- **MP Cost**: Default = spell level (level 3 = 3 MP). Override via `mpCost` field in YAML.
- MP checked before cast, deducted only on successful cast (handler returns `true`).

**`CharacterClass`**:
- Defines HP/MP/MV gains per level
- Contains `ClassSkillGrant` list – which skills unlock at which class levels
- Max level 50 (normal) + 5 hero levels (51-55)

### Equipment & Items

**Item Types** (can have multiple via `types` field):
- `trash`, `inventory`, `container`, `immobile`, `armor`, `shield`, `weapon`, `held`

**Equipment Slots** (`EquipmentSlot` enum):
- HEAD, NECK, SHOULDERS, BACK, CHEST, ARMS, HANDS, WAIST, LEGS, BOOTS, MAIN_HAND, OFF_HAND

**Armor Categories** (`ArmorCategory` enum):
- `CLOTH`, `LEATHER`, `MAIL`, `PLATE`, `OTHER`
- Each maps to a skill key (e.g., `skill_cloth_armor`) for proficiency checks

**Weapon Categories/Families**:
- Categories: `SIMPLE`, `MARTIAL`, `EXOTIC`
- Families: `SWORDS`, `AXES`, `CLUBS`, `DAGGERS`, `BOWS`, `STAVES`, etc.

---

## Combat System (`combat/`)

### Key Components

| Class | Purpose |
|-------|---------|
| `CombatManager` | Singleton managing all active combats; processes ticks |
| `Combat` | Single combat instance with combatants and state machine |
| `Combatant` | Wrapper for player/mobile in combat (tracks HP, attacks, prone status) |
| `CombatCalculator` | Hit/damage calculations using stats and proficiencies |
| `MultiAttackHandler` | Handles second/third/fourth attack skills |
| `BasicAttackCommand` | Default melee attack implementation |

### Combat Flow

1. Player uses `kill <target>` → `CombatManager.initiateCombat()`
2. Combat enters `STARTING` state, adds combatants to appropriate alliance
3. `CombatManager.tick()` runs every 500ms:
   - Processes each combat's round
   - Checks autoflee thresholds
   - Handles death and combat end conditions
4. Combat cycles through states: `STARTING` → `ACTIVE` → `ENDING`
5. On mob death: XP awarded, corpse created, loot dropped

### Combat Commands

- `kill <target>` – Initiate combat
- `flee` – Attempt to escape (opposed check vs highest-level enemy)
- `autoflee <0-100>` – Set HP% threshold for automatic flee
- `combat` – View current combat status
- `kick`, `bash`, `cast` – Combat skills (require proficiency)

---

## Event & Spawn System (`event/`)

### Components

| Class | Purpose |
|-------|---------|
| `TickService` | Central scheduler using `ScheduledExecutorService` |
| `EventScheduler` | Manages recurring game events |
| `SpawnManager` | Registers and schedules mob/item spawns |
| `SpawnConfig` | Configuration for a spawn (type, template, room, interval) |
| `SpawnEvent` | Executes spawn, tracks instance UUIDs to prevent duplicates |

### Spawn System Design

- Spawns are registered during `DataLoader.loadDefaults()` from `mobiles.yaml`
- Each spawn has a unique `spawnId` (e.g., `"area_1_room_1001_mob_1"`)
- `SpawnEvent` creates instances and logs to `SpawnEventLogger`
- On server restart: existing instances are cleared, spawns re-triggered
- UUID-based tracking prevents duplicate spawns

---

## Utility Services (`util/`)

| Service | Purpose | Tick Interval |
|---------|---------|---------------|
| `TickService` | Central scheduler for all timed tasks | N/A (scheduler) |
| `RegenerationService` | HP/MP/MV recovery based on stance | 10 seconds |
| `CooldownManager` | Track skill/spell cooldowns | 100ms |
| `MobileRoamingService` | NPC wandering behavior | Varies |
| `GameClock` | In-game time tracking and date persistence | Custom |
| `HelpManager` | Load and serve help pages from YAML | N/A |

### Stance System

Players have stances affecting regen and actions:
- `STANDING` – Normal (1% regen/tick)
- `SITTING` – Resting (5% regen/tick), can't move
- `SLEEPING` – Asleep (10% regen/tick), can't move, suppresses announcements

---

## Persistence Layer (`persistence/`)

### DAOs

| DAO | Tables Managed |
|-----|----------------|
| `CharacterDAO` | `characters`, `character_flags`, `character_skill`, `character_spell`, `character_equipment`, `character_class`, `skilltb`, `spelltb`, `rooms`, `areas`, `settings` |
| `CharacterClassDAO` | `class`, `class_skill_grants` |
| `ItemDAO` | `item_template`, `item_instance` |
| `MobileDAO` | `mobile_template`, `mobile_instance` |

### Migration Pattern

DAOs use additive migrations in `ensureTables()`:
```java
s.execute("ALTER TABLE tablename ADD COLUMN IF NOT EXISTS colname TYPE DEFAULT val");
```

This ensures schema evolves safely without breaking existing data.

### `DataLoader`

Loads seed data from YAML resources on startup:
- `/data/skills.yaml` → `skilltb`
- `/data/spells.yaml` → `spelltb`
- `/data/areas.yaml` → `areas`
- `/data/rooms.yaml` → `rooms`
- `/data/items.yaml` → `item_template`
- `/data/classes.yaml` → `class` + `class_skill_grants`
- `/data/mobiles.yaml` → `mobile_template` + spawn registration

---

## Data Files (`src/main/resources/data/`)

| File | Contents |
|------|----------|
| `skills.yaml` | Skill definitions (id, key, name, description, progression, traits) |
| `spells.yaml` | Spell definitions (id, name, school, level, target, effects) |
| `classes.yaml` | Character classes with skill grants per level |
| `items.yaml` | Item templates (armor, weapons, containers, etc.) |
| `mobiles.yaml` | Mobile templates with stats, behaviors, spawn configs |
| `areas.yaml` | Area definitions (id, name, description, level range) |
| `rooms.yaml` | Room definitions with exits and descriptions |

---

## MERC Conversion Tools

- Location: `tools/merc_room_converter.py`, `tools/run_merc_area.py`, `tools/batch_convert_merc.py`.
- Purpose: Parse legacy MERC `.are` files and convert rooms/areas into TassMUD YAML (`src/main/resources/data/`), using project-specific mappings and parsing rules.
- Usage examples:
  - Single area:
    - `python tools/run_merc_area.py path/to/area_file.are`
  - Batch conversion:
    - `python tools/batch_convert_merc.py path/to/are_directory`
- Mapping and instructions:
  - `src/main/resources/data/MERC/merc_mappings.yaml` — maps MERC numeric codes and bitfields (sector types, room flags, door bits) to TassMUD equivalents used by the converter.
  - `src/main/resources/data/MERC/room_parser_instructions.md` — guidance on how fields from MERC map to TassMUD YAML, special cases, and recommended handling for ambiguous flags (preserve as `legacy_flags` where mapping is lossy).
  - `src/main/resources/data/MERC/doc/area.txt` — included MERC core documentation used as a reference for parsing area/room syntax; consult when updating or extending the parser.
- Notes:
  - The converter uses `merc_mappings.yaml` for lookups; update that file if you need different mappings or to add custom translations.
  - Stop the running TassMUD server before writing converted YAML into `src/main/resources/data/` if you plan to reload seed data on startup.


## Help System

### Files

- `src/main/resources/help/global_commands.yaml` – Player command help
- `src/main/resources/help/gm_commands.yaml` – GM command help
- `src/main/resources/help/classes_help.yaml` – Class information

### Format

```yaml
commandname:
  keywords: [keyword1, keyword2]
  see_also: [othercommand]
  category: COMBAT
  usage:
    - "commandname <arg>"
  content: |
    NAME
        commandname - short description
    
    SYNOPSIS
        commandname <arg>
    
    DESCRIPTION
        Detailed description...
```

---

## GM Commands

GMs are identified by the `is_gm` character flag.

| Command | Purpose |
|---------|---------|
| `cflag` | Get/set character flags |
| `cskill` | Grant skills to characters |
| `cspell` | Grant spells to characters |
| `goto` | Teleport to room or player |
| `spawn` | Create mob/item instances |
| `restore` | Refill HP/MP/MV |
| `promote` | Level up a character |
| `peace` | End combat in room |
| `dbinfo` | Show database schema |
| `debug` | Toggle debug channel |
| `ilist` | Search item templates |
| `ifind` | Find item instances |
| `system` | Broadcast system message |
| `gmchat` | GM-only chat channel |

---

## Development Workflow

### Common Commands

```powershell
# Build (skip tests for speed)
mvn -DskipTests package

# Full restart (stops server, builds, starts)
.\target\scripts\restart-mud.ps1

# View server output (live)
Get-Content .\logs\server.out -Wait -Tail 200

# View server errors
Get-Content .\logs\server.err -Tail 200

# Run schema inspector (server must be stopped)
java -cp .\target\tass-mud-0.1.0-shaded.jar com.example.tassmud.tools.SchemaInspector
```

### Adding New Features

**New Command**:
1. `CommandRegistry.java` – Add registration
2. `ClientHandler.java` – Add case handler
3. `global_commands.yaml` – Add help entry

**New Skill**:
1. `skills.yaml` – Add skill definition
2. `classes.yaml` – Add to class skill grants (if class-specific)
3. `ClientHandler.java` – Add execution logic (if active skill)

**New Item Type**:
1. `items.yaml` – Add template with appropriate type/stats
2. `ItemTemplate.java` – Add any new fields (with migrations in `ItemDAO`)
3. `ClientHandler.java` – Handle in equip/use logic

**New Mobile**:
1. `mobiles.yaml` – Add template with stats, behaviors, spawn config
2. Restart server – spawns auto-register and trigger

**DB Schema Change**:
1. Add migration in DAO's `ensureTables()`: `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
2. Update model class with new field
3. Update SQL queries to use new column

---

## Troubleshooting

### H2 Connection Errors

```
Database may be already in use / Connection is broken
```
- Stop the running server before using external DB tools
- Use the shaded jar for consistent H2 version

### Command Not Found

- Ensure command is registered in `CommandRegistry.java`
- Check for typos in case label in `ClientHandler.java`
- Verify help entry exists in YAML files

### Skill/Spell Not Working

- Check character has the skill/spell: `cskill list`, `cspell list`
- Verify skill_key matches between `skills.yaml` and code references
- Check proficiency requirements

### Spawn Duplication

- Clear instances: `MobileDAO.clearAllInstances()` runs on startup
- Check spawn UUID tracking in `SpawnEvent`
- Review `SpawnEventLogger` output in `logs/`

---

## Key Design Decisions

1. **Single-threaded tick service**: Serializes all periodic tasks to avoid race conditions
2. **Additive migrations**: Only `ADD COLUMN IF NOT EXISTS`, never destructive changes
3. **Command registry**: Central source of truth for command metadata
4. **Skill proficiency**: 1-100% system with progression curves for organic learning
5. **Stance-based regen**: Encourages rest/recovery gameplay loop
6. **UUID spawn tracking**: Prevents duplicate spawns across server restarts
7. **Shaded JAR**: Bundles all dependencies for simple deployment

---

## Contact Points for Common Edits

| Task | Primary Files |
|------|---------------|
| Add command | `CommandRegistry.java`, `ClientHandler.java`, help YAML |
| Change combat | `CombatManager.java`, `Combat.java`, `CombatCalculator.java` |
| Modify items | `items.yaml`, `ItemTemplate.java`, `ItemDAO.java` |
| Add skills | `skills.yaml`, `Skill.java`, `ClientHandler.java` |
| Change classes | `classes.yaml`, `CharacterClass.java`, `CharacterClassDAO.java` |
| Modify spawns | `mobiles.yaml`, `SpawnManager.java`, `SpawnEvent.java` |
| DB schema | DAO `ensureTables()` methods |
| Help text | `src/main/resources/help/*.yaml` |

---

*End of agents.md*