# TassMUD – Agent Context Document

> **Last Updated**: February 2026  
> This document provides AI agents with comprehensive project context for effective assistance.

---

## Recent Updates (Feb 5, 2026) — Major Architectural Refactoring

A comprehensive audit and refactoring was completed covering bugs, thread safety, architecture, model design, and test coverage. Key outcomes:

- **Bug Fixes**: `Combat.containsMobile()` type mismatch, `CombatCalculator.isPlayerUsingMagicalWeapon()` copy-paste bug, `GameCharacter` dual stat storage inconsistency — all fixed.
- **Thread Safety**: `ConcurrentLinkedQueue` for command queues, `volatile` for combat state, `synchronizedSet` for status flags, `ConcurrentHashMap` for spell registry, double-checked locking for loot cache.
- **DaoProvider Singleton**: All 167 inline `new XxxDAO()` calls replaced with `DaoProvider.xxx()` across 38 files.
- **TransactionManager**: All 154 `DriverManager.getConnection()` calls replaced. Critical multi-step writes (shop buy/sell, player/mob death, XP+level-up, character creation) wrapped in `runInTransaction()`.
- **CharacterDAO Split**: 2,849-line god class → 1,240 lines + 5 new DAOs (`RoomDAO`, `SkillDAO`, `SpellDAO`, `EquipmentDAO`, `SettingsDAO`).
- **God Class Splits**: `CombatCommandHandler` 3,194→373, `GmCommandHandler` 1,638→103, `ItemCommandHandler` 2,263→1,305, `CombatManager` 1,675→934, `ClientHandler` extraction of `CharacterCreationHandler`.
- **Service Layer**: `MobileMatchingService`, `ExperienceService`, `ItemMatchingService` extract duplicated patterns.
- **MobileRegistry**: In-memory mob tracking replaces per-tick DB polling (13 call sites migrated).
- **Model Improvements**: `StatBlock` record, `Direction` enum, `SkillProgression` promoted to top-level, builders on 5 model classes, spell effects as `List<String>`, `ThreadLocalRandom` (54 `Math.random()` calls replaced).
- **Test Suite**: 540 tests, 0 failures — 7 new test files added (+217 tests), 13 pre-existing failures fixed.

See `refactor.md` for the complete checklist with per-item status.

---

## Previous Updates (Jan 4, 2026)

- Spell MP Cost System: Centralized in `SpellCastHandler`. Default cost = spell level; override via `mpCost` in `spells.yaml`. MP deducted only on successful cast.
- Spell Handler Architecture: `SpellHandler` interface with per-school handlers (`ArcaneSpellHandler`, `DivineSpellHandler`, `PrimalSpellHandler`, `OccultSpellHandler`). `SpellRegistry` uses `ConcurrentHashMap`.
- Rogue Skill Line: Sneak, Backstab, Circle, Assassinate, Shadow Step — implemented in `RogueSkillHandler` with cooldown scaling and proficiency improvement.
- Critical Hit System: Passive skills (Improved/Greater/Superior Critical) applied as permanent `CRITICAL_THRESHOLD_BONUS` modifiers.
- Riposte Rework: Scales 25%→75% with proficiency.
- Class Balance: Fighter MV 4→6, Wizard MV 2→6, Cleric MV 3→4, Rogue HP 6→8 / MP 2→0 / MV 6→10.

## Previous Updates (Dec 23, 2025)

- Converters: Room converter parses door resets, object converter emits `immobile`/`types`, mob converter clamps levels, shop converter + merger scripts.
- Doors: Full open/close/lock/block support with `MovementCommandHandler` enforcement and DB persistence.
- Shops & Economy: Extracted from MERC area files, loaded via `ShopDAO`.
- Spawn persistence: Equipment/inventory items persist through mob spawn→death→corpse lifecycle.



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
├── combat/               # Combat system
│   ├── CombatManager.java         # Combat orchestration, tick processing, AI decisions
│   ├── Combat.java                # Single combat instance, state machine, combatant tracking
│   ├── Combatant.java             # Player/mobile wrapper (HP, command queue, status flags)
│   ├── CombatCalculator.java      # Hit/damage formulas, stat lookups, business logic
│   ├── DeathHandler.java          # Mob/player death, corpse creation, loot, autogold/autoloot
│   ├── CombatRewardService.java   # XP awards, weapon/armor proficiency tracking
│   ├── CombatMessagingService.java # Damage verbs, combat formatting, HP sync, prompts
│   ├── MultiAttackHandler.java    # Second/third/fourth attack skill processing
│   ├── BasicAttackCommand.java    # Default melee attack implementation
│   ├── CombatCommand.java         # Command interface for combat actions
│   ├── CombatResult.java          # Attack result data carrier
│   └── CombatState.java           # Combat state enum (STARTING, ACTIVE, ENDING)
│
├── effect/               # Status effects and buff/debuff system
│   ├── EffectDefinition.java      # Effect template loaded from YAML
│   ├── EffectInstance.java        # Active effect on a character (duration, stacks)
│   ├── EffectHandler.java         # Interface for effect tick/apply/remove
│   ├── EffectRegistry.java        # Maps effect names to handlers
│   ├── EffectScheduler.java       # Periodic effect tick processing
│   ├── ModifierEffect.java        # Stat modifier effects (ADD/MULTIPLY/OVERRIDE)
│   └── ... (spell-specific handlers: BlindEffect, DotEffect, etc.)
│
├── event/                # Event/spawn scheduling
│   ├── EventScheduler.java        # Manages recurring game events
│   ├── SpawnManager.java          # Registers and schedules mob/item spawns
│   ├── SpawnConfig.java           # Spawn configuration (type, template, room, interval)
│   ├── SpawnEvent.java            # Executes spawn, UUID tracking
│   └── GameEvent.java             # Event interface
│
├── model/                # Data models (immutable where possible, builders for complex types)
│   ├── GameCharacter.java         # Player character (stats, vitals, abilities, modifiers)
│   ├── Mobile.java                # NPC/monster instance (extends GameCharacter)
│   ├── MobileTemplate.java        # Blueprint for spawning mobiles
│   ├── ItemTemplate.java          # Item definition (uses Builder pattern)
│   ├── ItemInstance.java           # Spawned item in world (uses Builder pattern)
│   ├── Room.java                  # Game location with EnumMap<Direction, Integer> exits
│   ├── Area.java                  # Collection of rooms
│   ├── StatBlock.java             # ★ Immutable record for 10 stats (Builder + ZERO constant)
│   ├── Direction.java             # ★ Enum (N/E/S/W/U/D) with fromString(), opposite()
│   ├── SkillProgression.java      # ★ Top-level enum (INSTANT..LEGENDARY learning curves)
│   ├── Modifier.java              # Stat modifier (ADD/MULTIPLY/OVERRIDE ops, expiry, caching)
│   ├── CharacterClass.java        # Class definition with skill grants
│   ├── Skill.java                 # Skill definition (progression, traits, cooldown)
│   ├── Spell.java                 # Spell definition (school, level, target, mpCost)
│   ├── Cooldown.java / CooldownType.java  # Cooldown tracking model
│   ├── Stance.java / Stat.java    # Enums for character stance and stat types
│   └── ... (Door, Shop, Weather, Equipment/Armor/Weapon enums)
│
├── net/                  # Network layer and command dispatch
│   ├── Server.java                # Entry point, TCP listener, subsystem initialization
│   ├── ClientHandler.java         # Per-connection session, messaging utilities
│   ├── CharacterCreationHandler.java # ★ Login flow, char creation (extracted from ClientHandler)
│   ├── CommandRegistry.java       # Single source of truth for command metadata
│   ├── CommandParser.java         # Input → canonical command resolution
│   ├── CommandDefinition.java     # Command metadata record
│   └── commands/                  # ★ Per-category command handlers
│       ├── CommandDispatcher.java     # Routes commands to category handlers
│       ├── CommandHandler.java        # Handler interface (supports + handle)
│       ├── CommandContext.java        # Request context bundle (with helper methods)
│       ├── CombatCommandHandler.java  # Combat status, flee, kill (delegates below)
│       ├── RogueSkillHandler.java     # ★ Sneak, backstab, circle, assassinate, shadow step
│       ├── MeleeSkillHandler.java     # ★ Kick, bash, taunt, disarm, trip, heroic strike
│       ├── SpellCastHandler.java      # ★ Spell casting with smart matching, MP, cooldowns
│       ├── ItemCommandHandler.java    # Get, drop, put, sacrifice, quaff, use
│       ├── ShopCommandHandler.java    # ★ Buy, list, sell
│       ├── EquipmentCommandHandler.java # ★ Equip, remove with slot/proficiency logic
│       ├── MovementCommandHandler.java # Movement, doors, look
│       ├── CommunicationCommandHandler.java # Say, tell, yell, chat
│       ├── InformationCommandHandler.java # Score, who, inventory, affects, skills, spells
│       ├── GroupCommandHandler.java    # Group/party management
│       ├── GmCommandHandler.java      # GM routing only (delegates below)
│       ├── GmCharacterHandler.java    # ★ cflag, cset, cskill, cspell, promote, restore
│       ├── GmWorldHandler.java        # ★ spawn, slay, peace, goto, system, weather
│       ├── GmInfoHandler.java         # ★ dbinfo, debug, gmchat, mstat, istat, ilist, mlist
│       └── SystemCommandHandler.java  # Quit, save, prompt, password, toggle settings
│
├── persistence/          # DAOs, data loading, transaction management
│   ├── DaoProvider.java           # ★ Singleton with 10 static final DAO instances
│   ├── TransactionManager.java    # ★ ThreadLocal<Connection>, runInTransaction() API
│   ├── CharacterDAO.java          # Characters, flags, classes (reduced from 2,849→1,240 lines)
│   ├── RoomDAO.java               # ★ Rooms, areas, doors, exits (~490 lines)
│   ├── SkillDAO.java              # ★ Skills, character_skill (~280 lines)
│   ├── SpellDAO.java              # ★ Spells, character_spell (~310 lines)
│   ├── EquipmentDAO.java          # ★ Character equipment (~210 lines)
│   ├── SettingsDAO.java           # ★ Global settings (~55 lines)
│   ├── CharacterClassDAO.java     # Class definitions and skill grants
│   ├── ItemDAO.java               # Item templates and instances
│   ├── MobileDAO.java             # Mobile templates and instances
│   ├── ShopDAO.java               # Shop definitions and inventory
│   ├── DataLoader.java            # YAML seed data loading
│   └── MigrationManager.java      # Schema migration coordination
│
├── spell/                # Spell casting system
│   ├── SpellHandler.java          # Interface: boolean cast(casterId, args, SpellContext)
│   ├── SpellRegistry.java         # Maps spell names → handlers (ConcurrentHashMap)
│   ├── SpellContext.java          # Cast context (CommandContext, Combat, targets, spell def)
│   ├── SpellSchool.java           # School enum (ARCANE, DIVINE, PRIMAL, OCCULT)
│   └── *SpellHandler.java        # Per-school: Arcane, Divine, Primal, Occult
│
├── tools/                # Dev utilities (SchemaInspector)
│
└── util/                 # Services and utilities
    ├── MobileRegistry.java        # ★ In-memory mob tracking (replaces DB polling)
    ├── MobileMatchingService.java # ★ Centralized mob name/keyword resolution
    ├── ExperienceService.java     # ★ XP calculation and level-up processing
    ├── ItemMatchingService.java   # ★ 5-priority item matching
    ├── TickService.java           # Central scheduler (ScheduledExecutorService)
    ├── CooldownManager.java       # Skill/spell cooldown tracking
    ├── RegenerationService.java   # HP/MP/MV recovery based on stance
    ├── MobileRoamingService.java  # NPC wandering behavior
    ├── GameClock.java             # In-game time tracking
    ├── HelpManager.java           # Help page loading from YAML
    ├── LootGenerator.java         # Random loot generation from templates
    ├── GroupManager.java          # Player group/party management
    ├── WeatherService.java        # Weather state and transitions
    └── ... (AbilityCheck, OpposedCheck, ProficiencyCheck, PasswordUtil, etc.)

src/main/resources/
├── asciiart/         # Title screen art
├── data/             # YAML seed data (items, skills, spells, classes, etc.)
└── help/             # YAML help pages

src/test/java/com/example/tassmud/
├── ModifierSystemTest.java    # ★ 38 tests — ADD/MULTIPLY/OVERRIDE, expiry, caching, clamping
├── CombatantTest.java         # ★ 45 tests — Status flags, advantage, alliance, autoflee
├── CombatLifecycleTest.java   # ★ 47 tests — State machine, targeting, aggro, combat log
├── StatBlockTest.java         # ★ 6 tests — Builder, ZERO constant, equality
├── CooldownTest.java          # ★ 20 tests — Cooldown model + CooldownManager lifecycle
├── SpawnConfigTest.java       # ★ 11 tests — Construction, clamping, ID generation
├── DirectionTest.java         # ★ 20 tests — Opposites, fromString, parameterized
├── BoundaryTest.java          # ★ 30 tests — Zero-HP, vital extremes, modifier extremes
├── CombatCalculatorTest.java  # Hit/damage formula tests
├── CommandParserTest.java     # Prefix matching, alias resolution
├── CommandRegistryTest.java   # Registration, collision, category tests
└── ... (CharacterClassTest, SkillTest, SpellTest, EquipmentSlotTest, etc.)
```

★ = Created or significantly changed during Feb 2026 refactoring

---

## Core Architecture

### Server & Startup (`net/Server.java`)

Entry point; starts the TCP listener on port 4003. Initializes all subsystems in order:
1. `MigrationManager` / DAO `ensureTable()` calls – DB schema migrations
2. `DataLoader.loadDefaults()` – Seed data from YAML resources
3. `MobileRegistry.clear()` – Reset in-memory mob tracking
4. `TickService` – Central scheduler for periodic tasks
5. `GameClock` – In-game time tracking
6. `CooldownManager` – Skill/spell cooldown tracking
7. `CombatManager` – Combat tick processing
8. `EventScheduler` + `SpawnManager` – Mob/item spawn scheduling
9. `RegenerationService` – HP/MP/MV recovery
10. `MobileRoamingService` – NPC wandering behavior

Accepts connections and spawns `ClientHandler` threads.

### Session Handling (`net/ClientHandler.java`)

Manages per-connection state (`playerName`, `characterId`, `currentRoomId`, `promptFormat`, `debugChannelEnabled`). Delegates login/character creation to `CharacterCreationHandler`. Main command loop reads input, passes to `CommandDispatcher`, and falls back to legacy handling for any unclaimed commands.

**Static messaging utilities** (used by handlers throughout the codebase):
- `broadcastRoomMessage()` – Send to all players in a room
- `sendToCharacter()` – Send to specific player by character ID
- `roomAnnounce()` – Arrival/departure announcements (respects sleep state)
- `sendPromptToCharacter()` – Refresh player's prompt
- `forEachInRoom()` – Iterate over all connected clients in a room
- `triggerAutoflee()` – Combat system callback for auto-flee

### Command System

**Registration**: `CommandRegistry` is the single source of truth for all commands. Commands are registered with `register()` (normal), `registerCombat()` (allowed during combat), or `registerGm()` (GM-only). Each command has: name, description, category, aliases, GM flag, combat flag.

**Parsing**: `CommandParser` resolves raw input into a canonical command name via prefix matching and alias resolution.

**Dispatch**: `CommandDispatcher` looks up the parsed command's `CommandDefinition`, finds the `CommandHandler` for its category, and calls `handle(CommandContext)` if `supports()` returns true. If no handler claims the command, it falls through to legacy ClientHandler handling.

**CommandContext**: Bundles everything a handler needs — parsed command, `playerName`, `characterId`, `currentRoomId`, `CharacterRecord`, DAO access (via `DaoProvider`), `PrintWriter out`, permission flags (`isGm`, `inCombat`), and a reference to `ClientHandler` for messaging helpers. Includes convenience methods:
- `resolveCharacterId()` — null-safe character ID lookup
- `requireRecord()` / `requireRecordInRoom()` — fail-fast record loading with error messages
- `freshRecord()` — reload character record from DB mid-handler

**Handler hierarchy**: Category-based dispatch:

| Category | Handler | Delegates to |
|----------|---------|-------------|
| `COMBAT` | `CombatCommandHandler` | `RogueSkillHandler`, `MeleeSkillHandler`, `SpellCastHandler` |
| `ITEMS` | `ItemCommandHandler` | `ShopCommandHandler`, `EquipmentCommandHandler` |
| `GM` | `GmCommandHandler` | `GmCharacterHandler`, `GmWorldHandler`, `GmInfoHandler` |
| `MOVEMENT` | `MovementCommandHandler` | — |
| `COMMUNICATION` | `CommunicationCommandHandler` | — |
| `INFORMATION` | `InformationCommandHandler` | — |
| `GROUP` | `GroupCommandHandler` | — |
| `SYSTEM` | `SystemCommandHandler` | — |

### Adding a New Command

1. Register in `CommandRegistry.java` (name, category, aliases, flags).
2. Implement in the appropriate `CommandHandler` (or create a new handler for a new category). Use `CommandContext` for DAO access (`DaoProvider`), output, record, etc.
3. Add a help page in `src/main/resources/help/global_commands.yaml` (or `gm_commands.yaml` for GM commands).
4. If the command affects persistence, update the relevant DAO and add migration logic in `ensureTables()`.

---

## Data Models (`model/`)

### Core Entities

| Model | Description |
|-------|-------------|
| `GameCharacter` | Player character with stats, vitals (HP/MP/MV), abilities, room location |
| `Mobile` | NPC/monster instance (extends GameCharacter, adds combat AI, spawn tracking) |
| `MobileTemplate` | Blueprint for spawning mobiles (stats via `StatBlock`, behaviors, loot, respawn) |
| `ItemTemplate` | Item definition (type, stats, effects, requirements) — uses Builder pattern |
| `ItemInstance` | Spawned item in world (location: room, inventory, or container) — uses Builder pattern |
| `Room` | Game location with `EnumMap<Direction, Integer>` exits, description, sector type |
| `Area` | Collection of rooms with shared properties (name, level range) |

### StatBlock (Immutable Record)

`StatBlock` is an immutable Java record grouping 10 core stats: `str`, `dex`, `con`, `intel`, `wis`, `cha`, `armor`, `fortitude`, `reflex`, `will`.

- **`StatBlock.ZERO`** — constant with all stats 0, used as a safe default.
- **Builder**: `StatBlock.builder().str(16).dex(14).build()` — unset fields default to 0.
- Used by `MobileTemplate` (replaces 10 individual fields), `CharacterRecord` (base stats), `GameCharacter` (constructor), `Mobile` (DB loading via `DbBuilder`).

### Direction Enum

`Direction` enum: `NORTH`, `EAST`, `SOUTH`, `WEST`, `UP`, `DOWN`.

- `Direction.fromString("north")` — case-insensitive, also accepts "n", "e", etc.
- `Direction.opposite()` — returns the reverse direction.
- `Room` stores exits as `EnumMap<Direction, Integer>` with backward-compatible legacy getters (`getExitN()`, etc.).
- Consumer code iterates `room.getExits().entrySet()` instead of 6 individual null checks.

### Modifier System

`Modifier` supports three operations for stat modifications:

| Operation | Behavior | Example |
|-----------|----------|---------|
| `ADD` | Flat +/- bonus | `+5 strength` from equipment |
| `MULTIPLY` | Percentage multiplier | `1.5x damage` from buff |
| `OVERRIDE` | Replaces base entirely | `Set armor to 100` |

- Modifiers have optional duration/expiry (for timed buffs).
- `GameCharacter.getStat(Stat)` applies modifiers with **lazy caching** — cache invalidated on modifier add/remove. Evaluation order: base → ADDs → MULTIPLYs → OVERRIDEs (last OVERRIDE wins).
- Vitals (HP/MP/MV) are clamped: 0 ≤ current ≤ max.

### SkillProgression

Top-level `SkillProgression` enum (promoted from inner enum in `Skill`). Learning curves control how fast proficiency (1-100%) increases on use:

| Curve | Speed |
|-------|-------|
| `INSTANT` | Immediate 100% mastery (armor/weapon proficiencies) |
| `TRIVIAL` | Very fast |
| `EASY` | Fast |
| `AVERAGE` | Normal |
| `HARD` | Slow |
| `VERY_HARD` | Very slow |
| `LEGENDARY` | Extremely slow |

Both `Skill` and `Spell` reference `SkillProgression`.

### Builder Pattern

Five model classes use the Builder pattern for construction (10+ fields each):

| Class | Builder Style | Notes |
|-------|--------------|-------|
| `ItemTemplate` | `ItemTemplate.builder()...build()` | 35+ fields |
| `ItemInstance` | `ItemInstance.builder()...build()` | 20+ fields |
| `CharacterRecord` | `CharacterRecord.builder()...build()` | 40+ fields; DAO uses `extractCharacterRecord()` |
| `MobileTemplate` | `MobileTemplate.builder()...build()` | 31 fields |
| `Mobile` | `Mobile.DbBuilder` | DB-loading only; `MobileDAO` call site |

### Skills & Spells

**`Skill`**:
- Has `id`, `name`, `description`, `progression` curve, `traits`, `cooldown`
- **Traits**: `INNATE` (known by all), `COMBAT` (requires combat), etc.
- Characters have proficiency 1-100% in each known skill

**`Spell`**:
- Has `school` (ARCANE, DIVINE, PRIMAL, OCCULT), `level` (1-10), `target` type
- Spell effects stored as `List<String> spellEffectIds` (replaces old `spellEffectId1..4` individual fields)
- Target types: `SELF`, `CURRENT_ENEMY`, `EXPLICIT_MOB_TARGET`, `ALL_ENEMIES`, etc.
- **MP Cost**: Default = spell level. Override via `mpCost` field in YAML.

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
| `CombatManager` | Singleton managing all active combats; tick processing, AI, autoflee, lifecycle |
| `Combat` | Single combat instance with combatants and state machine |
| `Combatant` | Wrapper for player/mobile in combat (HP, command queue, status flags) |
| `CombatCalculator` | Hit/damage formulas, stat lookups, mob damage/flee logic |
| `DeathHandler` | Mob/player death, corpse creation, loot, autogold/autoloot/autosac, despawn |
| `CombatRewardService` | XP awards (via `ExperienceService`), weapon/armor proficiency tracking |
| `CombatMessagingService` | Damage verbs, combat result formatting, HP sync, prompt dispatch |
| `MultiAttackHandler` | Second/third/fourth attack skill processing |
| `BasicAttackCommand` | Default melee attack implementation |

### Combat Command Handlers

| Handler | Commands |
|---------|----------|
| `CombatCommandHandler` | `kill`, `flee`, `autoflee`, `combat` (routes to delegates below) |
| `RogueSkillHandler` | `sneak`, `backstab`, `circle`, `assassinate`, `shadow step` |
| `MeleeSkillHandler` | `kick`, `bash`, `taunt`, `disarm`, `trip`, `heroic strike`, `feign`, `infuse` |
| `SpellCastHandler` | `cast` — spell matching, MP cost, cooldown, effect dispatch |

### Combat Flow

1. Player uses `kill <target>` → `CombatManager.initiateCombat()`
2. Combat enters `STARTING` state, adds combatants to appropriate alliance
3. `CombatManager.tick()` runs every 500ms:
   - Processes each combat's round
   - Checks autoflee thresholds
   - Handles death (`DeathHandler`) and combat end conditions
4. Combat cycles through states: `STARTING` → `ACTIVE` → `ENDING`
5. On mob death: XP awarded (`CombatRewardService`), corpse created (`DeathHandler`), loot dropped

### Thread Safety in Combat

- `Combatant.commandQueue` — `ConcurrentLinkedQueue` (multiple threads enqueue commands)
- `Combatant.statusFlags` — `Collections.synchronizedSet(EnumSet)` (accessed from tick + command threads)
- `Combat.state` — `volatile` (checked by tick thread + command threads)
- `Combat.roundResults` / `combatLog` — `Collections.synchronizedList` (written by tick, read by display)
- `CombatManager.combatsByRoom` — `ConcurrentHashMap` with `compute()` for initiation (prevents TOCTOU race)

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
| `MobileRegistry` | ★ In-memory mob tracking (spawn/death lifecycle) | N/A |
| `MobileMatchingService` | ★ Centralized mob name/keyword resolution | N/A |
| `ExperienceService` | ★ XP calculation, level-up, skill/spell learning | N/A |
| `ItemMatchingService` | ★ 5-priority item matching (exact→keyword→partial) | N/A |
| `RegenerationService` | HP/MP/MV recovery based on stance | 10 seconds |
| `CooldownManager` | Track skill/spell cooldowns | 100ms |
| `MobileRoamingService` | NPC wandering behavior (uses `MobileRegistry`) | Varies |
| `GameClock` | In-game time tracking and date persistence | Custom |
| `HelpManager` | Load and serve help pages from YAML | N/A |
| `LootGenerator` | Random loot generation from templates | N/A |
| `GroupManager` | Player group/party management | N/A |
| `WeatherService` | Weather state and transitions | Custom |

### MobileRegistry

In-memory tracking of all spawned mobs, replacing per-tick DB polling. Mobs are added on spawn (`SpawnEvent`, `GmWorldHandler`) and removed on death (`DeathHandler`, combat handlers).

- `MobileRegistry.getByRoom(roomId)` — returns mobs in a room without DB queries
- `MobileRegistry.getAll()` — all live mobs (used by `RegenerationService`, `MobileRoamingService`)
- Cleared on server startup before spawns re-trigger

### Stance System

Players have stances affecting regen and actions:
- `STANDING` – Normal (1% regen/tick)
- `SITTING` – Resting (5% regen/tick), can't move
- `SLEEPING` – Asleep (10% regen/tick), can't move, suppresses announcements

---

## Persistence Layer (`persistence/`)

### DaoProvider (Singleton)

All DAO access goes through `DaoProvider` — a singleton with 10 `static final` DAO instances:

```java
DaoProvider.characters()   // CharacterDAO
DaoProvider.items()        // ItemDAO
DaoProvider.mobiles()      // MobileDAO
DaoProvider.classes()      // CharacterClassDAO
DaoProvider.shops()        // ShopDAO
DaoProvider.rooms()        // RoomDAO
DaoProvider.skills()       // SkillDAO
DaoProvider.spells()       // SpellDAO
DaoProvider.equipment()    // EquipmentDAO
DaoProvider.settings()     // SettingsDAO
```

**Never** instantiate DAOs directly (`new CharacterDAO()`, etc.) — always use `DaoProvider`.

### TransactionManager

Centralized connection management with transaction support:

- **All DB connections** come from `TransactionManager.getConnection()` (replaced 154 `DriverManager` calls).
- DB URL/USER/PASS constants are centralized here; removed from all DAOs.
- **`runInTransaction(Runnable)`** — wraps a block of work in a single DB transaction:
  - Disables auto-commit, commits on success, rolls back on failure.
  - Uses `ThreadLocal<Connection>` — all DAO calls within the block share one connection.
  - Returns a non-closing proxy so individual DAO methods' `try-with-resources` don't close the shared connection.
  - **Re-entrant**: nested `runInTransaction()` calls share the outer transaction.

**Critical operations wrapped in transactions**:
- Shop buy/sell, player/mob death, XP+level-up, character creation, loot pickup, sacrifice.

### DAOs

| DAO | Tables Managed |
|-----|----------------|
| `CharacterDAO` | `characters`, `character_flags`, `character_class` |
| `RoomDAO` | `rooms`, `areas`, `door`, `room_extra_descriptions` |
| `SkillDAO` | `skilltb`, `character_skill` |
| `SpellDAO` | `spelltb`, `character_spell` |
| `EquipmentDAO` | `character_equipment` |
| `SettingsDAO` | `settings` |
| `CharacterClassDAO` | `class`, `class_skill_grants` |
| `ItemDAO` | `item_template`, `item_instance` |
| `MobileDAO` | `mobile_template`, `mobile_instance` |
| `ShopDAO` | `shop`, `shop_inventory` |

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

# Run tests
mvn test

# Run schema inspector (server must be stopped)
java -cp .\target\tass-mud-0.1.0-shaded.jar com.example.tassmud.tools.SchemaInspector
```

### Adding New Features

**New Command**:
1. `CommandRegistry.java` – Add registration (name, category, aliases, flags)
2. Implement in the appropriate `CommandHandler` (or add a new handler + register in `CommandDispatcher`)
3. `global_commands.yaml` or `gm_commands.yaml` – Add help entry

**New Skill**:
1. `skills.yaml` – Add skill definition
2. `classes.yaml` – Add to class skill grants (if class-specific)
3. Implement in appropriate handler (e.g., `MeleeSkillHandler`, `RogueSkillHandler`)

**New Item Type**:
1. `items.yaml` – Add template with appropriate type/stats
2. `ItemTemplate.java` – Add any new fields (with migrations in `ItemDAO`)
3. Handle in `ItemCommandHandler` / `EquipmentCommandHandler`

**New Mobile**:
1. `mobiles.yaml` – Add template with stats, behaviors, spawn config
2. Restart server – spawns auto-register and trigger

**DB Schema Change**:
1. Add migration in DAO's `ensureTables()`: `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
2. Update model class with new field
3. Update SQL queries to use new column

---

## Test Infrastructure

**540 tests, 0 failures** across 21 test files. JUnit 5.10.0. No Mockito.

### Test Files (★ = created Feb 2026)

| Test File | Tests | Coverage |
|-----------|-------|----------|
| `ModifierSystemTest` ★ | 38 | ADD/MULTIPLY/OVERRIDE ops, expiry, cache invalidation, vital clamping |
| `CombatLifecycleTest` ★ | 47 | Combat state machine, combatant management, targeting, aggro |
| `CombatantTest` ★ | 45 | Status flags, advantage/disadvantage, alliance, autoflee, command queue |
| `BoundaryTest` ★ | 30 | Zero-HP, vital extremes, extreme levels, modifier edge cases |
| `CooldownTest` ★ | 20 | Cooldown model + CooldownManager lifecycle |
| `DirectionTest` ★ | 20 | Opposites, fromString, parameterized tests |
| `SpawnConfigTest` ★ | 11 | Construction, clamping, ID generation |
| `StatBlockTest` ★ | 6 | Builder, ZERO constant, equality |
| `CombatCalculatorTest` | 41 | Hit/damage formula correctness |
| `CommandParserTest` | 30 | Prefix matching, alias resolution |
| `CommandRegistryTest` | 27 | Registration, collision, category |
| + 10 more | ~225 | Models, skills, spells, equipment, proficiency, etc. |

### Testing Patterns

- **Pure unit tests only** — no mocking framework, no DB integration tests
- Model classes and calculation logic are the primary test targets
- Tests use `@ParameterizedTest` with `@EnumSource` for enum coverage
- Thread safety tests verify `ConcurrentLinkedQueue`, `synchronizedSet` behavior

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
- Verify the handler's `supports()` method claims the command
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
4. **Category-based dispatch**: `CommandDispatcher` → `CommandHandler` → delegates. No god-class switches.
5. **DaoProvider singleton**: All DAO access via `DaoProvider.xxx()`. Never instantiate DAOs inline.
6. **TransactionManager**: All DB connections via `TransactionManager.getConnection()`. Multi-step writes wrapped in `runInTransaction()`.
7. **MobileRegistry**: In-memory mob tracking. Tick services read from registry, not DB.
8. **Service extraction**: Duplicated business logic extracted to `ExperienceService`, `MobileMatchingService`, `ItemMatchingService`.
9. **Handler delegation**: Large handlers delegate to focused sub-handlers (e.g., `CombatCommandHandler` → `RogueSkillHandler` / `MeleeSkillHandler` / `SpellCastHandler`).
10. **StatBlock record**: Immutable 10-stat bundle. Pass stats as `StatBlock`, not 10 individual parameters.
11. **Direction enum**: Room exits use `EnumMap<Direction, Integer>`. Iterate the map, don't check 6 nullable fields.
12. **Builder pattern**: Model classes with 10+ fields use builders. DAO `extractXxx()` methods construct via builder.
13. **Modifier system**: ADD → MULTIPLY → OVERRIDE evaluation with lazy caching and automatic invalidation.
14. **Skill proficiency**: 1-100% system with `SkillProgression` curves for organic learning.
15. **Stance-based regen**: Encourages rest/recovery gameplay loop.
16. **UUID spawn tracking**: Prevents duplicate spawns across server restarts.
17. **ThreadLocalRandom**: All randomness via `ThreadLocalRandom.current()`, never `Math.random()`.
18. **Shaded JAR**: Bundles all dependencies for simple deployment.
19. **No DI framework**: Singletons and static access by design. No Spring, Guice, etc.
20. **Thread safety**: `ConcurrentHashMap` for shared maps, `volatile` for state flags, `synchronizedSet` for mutation-heavy enum sets, `ConcurrentLinkedQueue` for producer/consumer queues.

---

## Do's and Don'ts

### DAO Access
- ✅ `DaoProvider.characters().getCharacter(id)`
- ❌ `new CharacterDAO().getCharacter(id)`

### DB Connections
- ✅ `TransactionManager.getConnection()`
- ❌ `DriverManager.getConnection(URL, USER, PASS)`

### Transactions
- ✅ `TransactionManager.runInTransaction(() -> { ... })` for multi-step writes
- ❌ Multiple auto-commit statements for related writes (buy = deduct gold + create item)

### Mobile Lookups
- ✅ `MobileRegistry.getByRoom(roomId)` for finding mobs in a room
- ❌ `DaoProvider.mobiles().getMobilesInRoom(roomId)` in tick services (per-tick DB polling)

### Randomness
- ✅ `ThreadLocalRandom.current().nextInt(20) + 1`
- ❌ `(int)(Math.random() * 20) + 1`

### Stat Bundles
- ✅ Pass `StatBlock` for 10-stat groups
- ❌ 10 individual `int` parameters (str, dex, con, ...)

### Room Exits
- ✅ `room.getExits().entrySet()` — iterate the `EnumMap<Direction, Integer>`
- ❌ `if (room.getExitN() != null) ... if (room.getExitE() != null) ...` — 6 individual null checks

### Model Construction
- ✅ `ItemTemplate.builder().name("Sword").damage(5).build()`
- ❌ `new ItemTemplate(null, "Sword", null, null, 5, null, ...)`  — 35-param constructor

### Command Handlers
- ✅ Add commands to the appropriate `CommandHandler` / delegate handler
- ❌ Add more cases to `ClientHandler`'s legacy switch

### Business Logic
- ✅ Keep business logic in calculators/services (`CombatCalculator`, `ExperienceService`)
- ❌ Put complex formulas in model classes (`Mobile.rollDamage()` is deprecated)

### Thread Safety
- ✅ `ConcurrentHashMap` for shared maps, `volatile` for state accessed by multiple threads
- ❌ Plain `HashMap` or unsynchronized `EnumSet` for cross-thread state

### God Classes
- ✅ Delegate to focused handlers/services; keep routing classes thin
- ❌ Add more logic to already-large files; grow a class beyond ~1,000 lines without splitting

---

## Contact Points for Common Edits

| Task | Primary Files |
|------|---------------|
| Add command | `CommandRegistry.java`, appropriate `CommandHandler`, help YAML |
| Add combat skill | `RogueSkillHandler` / `MeleeSkillHandler`, `skills.yaml`, `classes.yaml` |
| Add spell | `spells.yaml`, per-school `SpellHandler`, `SpellCastHandler` |
| Change combat | `CombatManager.java`, `Combat.java`, `CombatCalculator.java` |
| Death/loot | `DeathHandler.java`, `CombatRewardService.java`, `LootGenerator.java` |
| Modify items | `items.yaml`, `ItemTemplate.java`, `ItemDAO.java` |
| Equipment | `EquipmentCommandHandler.java`, `EquipmentDAO.java` |
| Shops | `ShopCommandHandler.java`, `ShopDAO.java`, `shops.yaml` |
| Change classes | `classes.yaml`, `CharacterClass.java`, `CharacterClassDAO.java` |
| XP/level-up | `ExperienceService.java`, `CombatRewardService.java` |
| Mob logic | `MobileRegistry.java`, `MobileMatchingService.java`, `MobileDAO.java` |
| Modify spawns | `mobiles.yaml`, `SpawnManager.java`, `SpawnEvent.java` |
| GM commands | `GmCharacterHandler` / `GmWorldHandler` / `GmInfoHandler`, `gm_commands.yaml` |
| DB schema | DAO `ensureTables()` methods, `DaoProvider` (if new DAO) |
| Help text | `src/main/resources/help/*.yaml` |
| Character creation | `CharacterCreationHandler.java` |
| Session/messaging | `ClientHandler.java` |

---

*End of agents.md*