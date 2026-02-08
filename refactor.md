# TassMUD Refactoring Plan

> **Created**: February 5, 2026
> **Status**: In Progress

---

## Codebase Stats

| Metric | Value |
|---|---|
| Java source files | ~75 |
| Estimated total LOC | ~22,000+ |
| Test files | 13 (models/math only) |
| Ad-hoc `new DAO()` calls | **128** |
| `Math.random()` calls | **54** |
| TODOs | 9 |
| `@SuppressWarnings("unused")` | 6 |

---

## P0 — Bugs (Fix Immediately)

- [x] **`Combat.containsMobile()` always returns false** — compares a `String` param against a `long` via `equals()`. Any code relying on mobile-by-string lookup in combat silently fails.

- [x] **`CombatCalculator.isPlayerUsingMagicalWeapon()` is copy-paste of ranged check** — checks `weaponFamily.isRanged()` instead of anything related to magical weapons. Magical weapon detection is broken.

- [x] **`GameCharacter` dual stat storage is inconsistent** — `initBaseStats()` copies field values into `baseStats` at construction, but `setStr()`/`setDex()`/etc. update the raw field *without* updating `baseStats`. The modifier system reads from `baseStats`, so post-construction stat changes via setters are silently ignored by `getStat()`.

---

## P1 — Thread Safety (High Risk) ✅

| Issue | Location | Fix | Status |
|---|---|---|---|
| `Combatant.commandQueue` is `LinkedList` | `Combatant.java` | Replaced with `ConcurrentLinkedQueue` | ✅ |
| `Combatant.statusFlags` is `EnumSet` | `Combatant.java` | Wrapped in `Collections.synchronizedSet()` | ✅ |
| `CombatManager.initiateCombat()` TOCTOU race | `CombatManager.java` | Used `compute()` on `combatsByRoom` | ✅ |
| `Combat` fields (`state`, `initiativeOrder`, `roundResults`) | `Combat.java` | `state` volatile; `roundResults`/`combatLog` synchronizedList; `initiativeOrder` volatile; getters return snapshots | ✅ |
| `SpellRegistry` uses plain `HashMap` | `SpellRegistry.java` | Switched to `ConcurrentHashMap` | ✅ |
| `LootGenerator.cachedTemplateIds` unsynchronized | `LootGenerator.java` | Double-checked locking with `volatile` | ✅ |

---

## P2 — Architectural Issues (Refactor When Able)

### 2a. God Classes

| Class | Lines | Concern |
|---|---|---|
| `CombatCommandHandler` | **~~3,194~~ 373** | Combat status, flee, kill (delegates rogue/melee/spell) |
| `CharacterDAO` | **2,849** | Characters + skills + spells + rooms + areas + settings + equipment + flags |
| `ItemCommandHandler` | **~~2,263~~ 1,305** | Get, drop, put, sacrifice, quaff, use (delegates shop/equip) |
| `CombatManager` | **~~1,677~~ 934** | Combat orchestration (delegates death/reward/messaging) |
| `GmCommandHandler` | **~~1,638~~ 103** | Routing only (delegates char/world/info) |
| `ClientHandler` | **1,420** | Session + login + char creation + messaging + formatting |

**Recommendations:**
- [x] Split `CharacterDAO` → `CharacterDAO`, `SkillDAO`, `SpellDAO`, `RoomDAO`, `EquipmentDAO`, `SettingsDAO` — CharacterDAO reduced from 2,849→1,240 lines. Created 5 new DAOs (RoomDAO ~490L/30 methods/5 tables, SkillDAO ~280L/14 methods/2 tables, SpellDAO ~310L/15 methods/2 tables, EquipmentDAO ~210L/10 methods/1 table, SettingsDAO ~55L/2 methods/1 table). Updated DaoProvider with 5 new accessors. Fixed ~110 call sites across 16 files (combat/, net/, persistence/, tools/ packages).
- [x] Extract `CharacterCreationHandler` from `ClientHandler` — extracted login flow, character creation, class selection, and class details display into new `CharacterCreationHandler` (344 lines). ClientHandler reduced from 1,387→1,006 lines. New `LoginResult` record carries session state back to ClientHandler cleanly.
- [ ] Extract `DeathService`, `RewardService`, `CombatMessagingService` from `CombatManager`
- [x] Extract `DeathHandler`, `CombatRewardService`, `CombatMessagingService` from `CombatManager` — CombatManager reduced from 1,675→934 lines. Created 3 new services: `DeathHandler` (306L — mob/player death, corpse creation, loot, autogold/autoloot/autosac, despawn), `CombatRewardService` (260L — XP awards, weapon skill proficiency, armor proficiency tracking/improvement), `CombatMessagingService` (203L — damage verbs, combat result formatting, HP sync, prompt dispatch). CombatManager retains combat orchestration (tick, turns, initiation, AI, autoflee, lifecycle).
- [x] Split `CombatCommandHandler` → `RogueSkillHandler`, `MeleeSkillHandler`, `SpellCastHandler` — CombatCommandHandler reduced from 3,194→373 lines. Created 3 delegates: `RogueSkillHandler` (~1,076L — hide, sneak, backstab, circle, assassinate, shadow step), `MeleeSkillHandler` (~1,346L — taunt, feign, infuse, heroic strike, bash, kick, disarm, trip), `SpellCastHandler` (~398L — spell casting with smart matching, MP cost, cooldown, effect dispatch). CombatCommandHandler retains combat status, flee, kill + routing switch.
- [x] Split `ItemCommandHandler` → `ShopCommandHandler`, `EquipmentCommandHandler` — ItemCommandHandler reduced from 2,263→1,305 lines. Created 2 delegates: `ShopCommandHandler` (~550L — buy, list, sell), `EquipmentCommandHandler` (~490L — equip, remove with slot/proficiency logic). ItemCommandHandler retains get, drop, put, sacrifice, quaff, use.
- [x] Split `GmCommandHandler` → `GmCharacterHandler`, `GmWorldHandler`, `GmInfoHandler` — GmCommandHandler reduced from 1,638→103 lines (routing only). Created 3 delegates: `GmCharacterHandler` (~600L — cflag, cset, cskill, cspell, promote, restore), `GmWorldHandler` (~700L — spawn, slay, peace, goto, gminvis, system, setweather, seedtemplates, checktemplate), `GmInfoHandler` (~340L — dbinfo, debug, gmchat, genmap, mstat, istat, ilist, mlist, mfind, ifind).

### 2b. ~~No~~ Service Layer ✅

Command handlers ~~call DAOs directly and contain business logic~~ now delegate to centralized service utilities for common patterns. Previously, XP formulas, item matching, and mobile matching were duplicated 4–12 times across handlers.

**Done:**
- [x] Created `MobileMatchingService` — centralized mobile name/keyword resolution (7 call sites wired, replaces duplicated 3-pass matching)
- [x] Created `ExperienceService` — centralized XP calculation and level-up processing (4 sites wired: CombatRewardService, RogueSkillHandler backstab+assassinate, DeathHandler autosac — also fixed missing level-up bug in autosac)
- [x] Created `ItemMatchingService` — centralized 5-priority item matching (3 inline sites replaced + canonical method delegated; includes `findMatchingTemplate` overload for shop buy)
- [x] Fixed P2c regression: replaced 2× `new ItemDAO()` in RogueSkillHandler with `DaoProvider.items()`
- [x] Removed unused `classDao` parameter from `handleAssassinationKill()`

**Deferred (low ROI):**
- [ ] `ShopService` (pricing in 2 places — profit_buy/profit_sell metadata loaded but unused)
- [ ] `CharacterService` / `CombatService` — stat calcs and damage rolls are tightly coupled to combat flow; extraction adds indirection without clear testability gain until DI is introduced

### 2c. ~~128~~ 167 Ad-hoc DAO Constructions ✅

Every DAO method opens and closes its own DB connection via `DriverManager.getConnection()`. DAOs ~~are~~ were instantiated inline at call sites (`new CharacterDAO()`, `new ItemDAO()`, etc.) — sometimes 3+ times within a single method.

**Done:**
- [x] Created `DaoProvider` singleton with `characters()`, `items()`, `mobiles()`, `classes()`, `shops()` accessors
- [x] Replaced all 167 `new XxxDAO()` calls across 38 files with `DaoProvider.xxx()`

### 2d. No Transactions ✅

Multi-step writes (buy item = deduct gold + create item; level up = update vitals + grant skills + insert class row) run as individual auto-commit statements. A crash between steps corrupts game state.

**Implemented:**
- [x] Created `TransactionManager` with `ThreadLocal<Connection>` and non-closing proxy wrapper
- [x] Replaced all 154 `DriverManager.getConnection(URL, USER, PASS)` calls across 9 DAOs with `TransactionManager.getConnection()`
- [x] Removed duplicated `URL`/`USER`/`PASS` constants from all DAOs (centralized in `TransactionManager`)
- [x] `TransactionManager.runInTransaction()` — lambda-based API with auto-commit disable, commit-on-success, rollback-on-failure
- [x] Nested transaction support (re-entrant — inner calls share the outer transaction)
- [x] Wrapped critical multi-step operations:
  - Shop buy (gold deduction + item creation)
  - Shop sell / sell junk (item deletion + gold award)
  - Player death (corpse + equip/inventory move + gold zero + XP deduct + room teleport + vitals)
  - Mob death (corpse + loot generation + equipped item transfer)
  - Auto-pickup (autogold take + credit, autoloot item moves)
  - XP award + level-up (XP grant + skill/spell learning + vitals + talents via ExperienceService)
  - Character creation (character insert + class assignment + class progress)
  - Get gold from container (take gold + add to player)
  - Sacrifice (item delete + flat XP award)

### 2e. Performance: DB Polling in Tick Services ✅

`MobileRoamingService` loads ALL mobs from DB every 1-second tick. `RegenerationService` does the same every 10 seconds. Both should use an in-memory registry instead.

**Recommendation:**
- [x] Create `MobileRegistry` singleton (in-memory mob tracking, add on spawn, remove on death)
- [x] Refactor `MobileRoamingService` and `RegenerationService` to use the registry
- [x] Replace all `getMobilesInRoom()` DB calls (13 call sites) with `MobileRegistry.getByRoom()`
- [x] Wire registry into spawn lifecycle (SpawnEvent, GmCommandHandler spawn)
- [x] Wire registry into death lifecycle (CombatManager, CombatCommandHandler, GmCommandHandler slay)
- [x] Wire registry clear into Server startup

---

## P3 — Model & Design Smells

### 3a. Encapsulation Inconsistency

- [ ] `ItemTemplate` — all 35+ fields are `public final`. Add `private` access + getters to match rest of codebase.
- [ ] `ItemInstance` — same issue, all fields `public final`.
- [ ] `CharacterClass` — mixed visibility (`id`/`name` public, `skillGrants` private).

### 3b. Numbered Spell Effect Fields ✅

- [x] `spellEffectId1..4` → `List<String> spellEffectIds` on `ItemTemplate`; `spellEffect1Override..4Override` → `List<String> spellEffectOverrides` on `ItemInstance`; 4 individual getter methods collapsed to single `getEffectiveSpellEffects()`; `ItemDAO` helpers for DB column ↔ List mapping; `LootGenerator.GeneratedItem` list-based; consumers updated (ItemCommandHandler, GmInfoHandler). DB schema unchanged (4 VARCHAR columns).

### 3c. Telescoping Constructors ✅

- [x] Add builder pattern for classes with 10+ constructor parameters:
  - `ItemTemplate` (35+ params) — Builder + `extractItemTemplate()` DAO helper, 5 call sites collapsed
  - `ItemInstance` (20+ params) — Builder + `extractItemInstance()` updated
  - `CharacterRecord` (40+ params) — Builder + `extractCharacterRecord()` DAO helper, 2 call sites collapsed
  - `MobileTemplate` (31 params) — Builder, 9 call sites updated (2 prod + 7 test)
  - `Mobile` (34 params) — DbBuilder for DB-loading constructor, MobileDAO call site updated
  - `GameCharacter` (20/21 params) — Skipped: minimal 1-param telescoping, internal-only via `super()`

### 3d. Duplicated Stat Patterns ✅

- [x] Created `StatBlock` record (10 fields: str/dex/con/intel/wis/cha/armor/fortitude/reflex/will) with Builder and `ZERO` constant.
- [x] `MobileTemplate` — replaced 10 individual final fields with `StatBlock stats`; delegate getters preserved; `getStats()` accessor added.
- [x] `CharacterRecord` — replaced 10 base stat fields with `StatBlock baseStats`; `getXxxTotal()` convenience methods delegate through `baseStats`.
- [x] `GameCharacter` — constructors now accept `StatBlock` (unpacks to mutable fields for modifier system); added `toStatBlock()` snapshot method.
- [x] `Mobile` — template constructor passes `template.getStats()`; DB-loading constructor accepts `StatBlock`; `DbBuilder.build()` constructs StatBlock internally.
- [x] Consumer sites updated: ClientHandler, CharacterCreationHandler (StatBlock in constructor), MeleeSkillHandler/SystemCommandHandler (`rec.baseStats.xxx()`).

### 3e. Room Exit Model ✅

- [x] Replace 6 individual nullable exit fields (`exitN/exitE/.../exitD`) with `Map<Direction, Integer>`. Created `Direction` enum with `fromString()`, `opposite()`, `shortName()`. Room now stores `EnumMap<Direction, Integer>` with backward-compat legacy getters.
- [x] Eliminate duplicated exit-building boilerplate in `executeAutoflee`, `MovementCommandHandler`, `MobileRoamingService`, `CombatManager`, `ClientHandler`, `CombatCommandHandler` (6 consumer sites simplified).

### 3f. `SkillProgression` Coupling ✅

- [x] Promote `Skill.SkillProgression` to a top-level `SkillProgression` enum — both `Skill` and `Spell` depend on it. Created `model/SkillProgression.java`, removed inner enum from `Skill.java`, updated 8 files (Spell, CharacterDAO, DataLoader, SpellTest, SkillTest, ProficiencyCheckTest).

### 3g. Business Logic in Models ✅

- [x] Move `Mobile.rollDamage()` → `CombatCalculator.rollMobileDamage(Mobile)` (Mobile method now @Deprecated delegate)
- [x] Move `Mobile.shouldFlee()` → `CombatCalculator.shouldMobFlee(Mobile)` (Mobile method now @Deprecated delegate)
- [x] Remove `GameCharacter.regenerate()` — dead code, never called from production (`RegenerationService` does its own inline regen)
- [ ] Move `CharacterClass.xpRequiredForLevel()`/`levelFromXp()` → `CharacterService` *(deferred — static pure functions tied to class constants, fine where they are)*

### 3h. `Math.random()` × 54 ✅

- [x] Replace all `Math.random()` calls with `ThreadLocalRandom.current().nextDouble()` in production code (54 replacements across 22 files)
- [ ] Consider injectable `Random` for deterministic testing

---

## P4 — Housekeeping

- [x] **Dead code**: Removed `ClientHandler.broadcastRoom()` and `whisperTo()`
- [ ] **Orphaned JavaDoc**: Remove empty comment blocks in `ClientHandler` for moved handlers
- [x] **Empty catch block**: Fixed `TODO Auto-generated catch block` in `SystemCommandHandler.java` (→ SLF4J debug log)
- [ ] **Misspelling**: `ItemTemplate.indestructable` → `indestructible` *(deferred — touches DB column, YAML, model, DAO)*
- [ ] **`EffectDefinition.Type` enum**: Refactor to separate generic types (`MODIFIER`, `DOT`) from spell-specific ones (`BURNING_HANDS`, `CALL_LIGHTNING`) — violates open/closed principle
- [ ] **Duplicate behavior methods**: `isAggressive()`/`isImmortal()`/`isShopkeeper()` duplicated between `Mobile` and `MobileTemplate` *(deferred — convenience methods aren't called by production code; callers use `hasBehavior()` directly)*
- [x] **Duplicate ability score switch**: `getAbilityBonus()` and `getStatModifier()` had identical 6-case switch — extracted `resolveAbilityValue()` in `ClientHandler`
- [x] **Room-iteration messaging**: Extracted `forEachInRoom(roomId, Consumer<ClientHandler>)` helper; simplified `broadcastRoomMessage`, `sendDebugToRoom`, `sendPromptsToRoom`
- [x] **Auto-toggle commands**: Unified into single `handleAutoToggle()` method + generic `CharacterDAO.setAutoFlag()`
- [x] **`charId` null-guard + fallback**: Added `resolveCharacterId()`, `requireRecord()`, `requireRecordInRoom()`, `freshRecord()` to `CommandContext`
- [x] **`rec == null` guard**: Added pre-check in `CommandDispatcher.dispatch()` before handler invocation — guards all 8 handlers centrally

---

## P5 — Test Coverage

- [ ] Add Mockito to `pom.xml` dependencies
- [ ] Write DAO integration tests (at minimum `CharacterDAO`, `ItemDAO`)
- [ ] Write handler tests for critical paths (buy, equip, combat initiation)
- [ ] Write `CombatManager` integration tests
- [ ] Write effect lifecycle tests

---

## Recommended Phasing

### Phase 1 — Safety (1-2 days)
Fix P0 bugs and P1 thread-safety issues.

### Phase 2 — DAO Consolidation (2-3 days)
`DaoProvider` singleton, replace 128 `new XxxDAO()` calls, add transaction support.

### Phase 3 — Extract Services (3-5 days)
`MobileRegistry`, `ItemMatcher`/`ItemService`, `CharacterCreationHandler`, split `CharacterDAO`.

### Phase 4 — Model Cleanup (2-3 days)
`StatBlock`, Room exit map, `ItemTemplate`/`ItemInstance` encapsulation, builders, promote `SkillProgression`.

### Phase 5 — Quality (ongoing)
Replace `Math.random()`, add tests, clean dead code, split god classes as they grow.
