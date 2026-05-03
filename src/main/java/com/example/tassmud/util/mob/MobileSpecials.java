package com.example.tassmud.util.mob;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.tassmud.util.GameClock;
import com.example.tassmud.model.Direction;
import com.example.tassmud.model.Room;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Registers all MERC spec_fun mobile special function handlers.
 * Call {@link #registerAll(MobileSpecialRegistry, CombatManager, GameClock)} once during server startup.
 */
public final class MobileSpecials {

    private static final Logger logger = LoggerFactory.getLogger(MobileSpecials.class);

    private MobileSpecials() {}

    public static void registerAll(MobileSpecialRegistry registry, CombatManager combatManager, GameClock gameClock) {

        // ── Breath weapons ────────────────────────────────────────────────────

        // spec_breath_fire: fire breath — Fireball-equivalent damage on a random combat target
        registry.register("spec_breath_fire", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant target = ctx.combat.getRandomTarget(ctx.combat.findByMobileInstanceId(mob.getInstanceId()));
            if (target == null) return false;
            int damage = breathDamage(mob.getLevel());
            GameCharacter tc = target.getCharacter();
            if (tc == null && target.getMobile() != null) tc = target.getMobile();
            if (tc == null) return false;
            tc.setHpCur(tc.getHpCur() - damage);
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " exhales a cone of fire at " + targetName(target) + ", dealing " + damage + " damage!");
            notifyIfPlayer(target, mob.getName() + "'s fire breath scorches you for " + damage + " damage!");
            return true;
        });

        // spec_breath_acid: acid breath — Acid Blast equivalent
        registry.register("spec_breath_acid", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant target = ctx.combat.getRandomTarget(ctx.combat.findByMobileInstanceId(mob.getInstanceId()));
            if (target == null) return false;
            int damage = breathDamage(mob.getLevel());
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            tc.setHpCur(tc.getHpCur() - damage);
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " sprays acid at " + targetName(target) + ", dealing " + damage + " damage!");
            notifyIfPlayer(target, mob.getName() + "'s acid breath burns you for " + damage + " damage!");
            return true;
        });

        // spec_breath_frost: frost breath — Cone of Cold equivalent
        registry.register("spec_breath_frost", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant target = ctx.combat.getRandomTarget(ctx.combat.findByMobileInstanceId(mob.getInstanceId()));
            if (target == null) return false;
            int damage = breathDamage(mob.getLevel());
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            tc.setHpCur(tc.getHpCur() - damage);
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " breathes a blast of frost at " + targetName(target) + ", dealing " + damage + " damage!");
            notifyIfPlayer(target, mob.getName() + "'s frost breath chills you for " + damage + " damage!");
            return true;
        });

        // spec_breath_lightning: lightning breath — Lightning Bolt equivalent
        registry.register("spec_breath_lightning", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant target = ctx.combat.getRandomTarget(ctx.combat.findByMobileInstanceId(mob.getInstanceId()));
            if (target == null) return false;
            int damage = breathDamage(mob.getLevel());
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            tc.setHpCur(tc.getHpCur() - damage);
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " unleashes a bolt of lightning at " + targetName(target) + ", dealing " + damage + " damage!");
            notifyIfPlayer(target, mob.getName() + "'s lightning breath shocks you for " + damage + " damage!");
            return true;
        });

        // spec_breath_gas: AoE gas breath — hits all enemies
        registry.register("spec_breath_gas", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            List<Combatant> targets = ctx.combat.getValidTargets(self);
            if (targets.isEmpty()) return false;
            int damage = breathDamage(mob.getLevel()) / 2; // AoE deals half
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " breathes a cloud of toxic gas, engulfing everyone!");
            for (Combatant t : targets) {
                GameCharacter tc = getCharacterFromCombatant(t);
                if (tc == null) continue;
                tc.setHpCur(tc.getHpCur() - damage);
                notifyIfPlayer(t, mob.getName() + "'s gas breath poisons you for " + damage + " damage!");
            }
            return true;
        });

        // spec_breath_any: randomly choose a breath type
        registry.register("spec_breath_any", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            int roll = ThreadLocalRandom.current().nextInt(8);
            String[] keys = {"spec_breath_fire", "spec_breath_lightning", "spec_breath_lightning",
                             "spec_breath_gas", "spec_breath_acid", "spec_breath_frost",
                             "spec_breath_frost", "spec_breath_frost"};
            MobileSpecialHandler delegate = registry.get(keys[roll]);
            return delegate != null && delegate.trigger(mob, ctx);
        });

        // ── Spellcasting mobs ─────────────────────────────────────────────────

        // spec_cast_mage: arcane combat caster, level-gated spell selection
        registry.register("spec_cast_mage", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(25)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            int level = mob.getLevel();
            int roll = ctx.rng.nextInt(16);
            for (int attempt = 0; attempt < 30; attempt++) {
                roll = ctx.rng.nextInt(16);
                int minLevel;
                switch (roll) {
                    case 0:  minLevel = 0;  break;  // blindness
                    case 1:  minLevel = 3;  break;  // chill touch
                    case 2:  minLevel = 7;  break;  // weaken
                    case 3:  minLevel = 8;  break;  // teleport
                    case 4:  minLevel = 11; break;  // colour spray → blindness
                    case 5:  minLevel = 12; break;  // reroll
                    case 6:  minLevel = 13; break;  // energy drain
                    case 7: case 8: case 9: minLevel = 15; break; // fireball
                    default: minLevel = 20; break;  // acid blast
                }
                if (level >= minLevel) break;
            }
            if (roll == 5) return false; // reroll slot
            Integer targetId = target.getCharacterId();
            String spellName;
            switch (roll) {
                case 0:  spellName = "blindness";    break;
                case 1:  spellName = "chill touch";  break;
                case 2:  spellName = "weaken";       break;
                case 3:  spellName = "teleport";     break;
                case 4:  spellName = "colour spray"; break;
                case 6:  spellName = "energy drain"; break;
                case 7: case 8: case 9: spellName = "fireball";   break;
                default: spellName = "acid blast";   break;
            }
            int proficiency = Math.min(100, 20 + level * 2);
            return com.example.tassmud.spell.MobileSpellCaster.cast(mob, spellName, targetId, ctx.roomId, ctx.combat, proficiency);
        });

        // spec_cast_cleric: divine combat caster
        registry.register("spec_cast_cleric", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(25)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            int level = mob.getLevel();
            int roll;
            for (int attempt = 0; ; attempt++) {
                roll = ctx.rng.nextInt(12);
                int minLevel;
                switch (roll) {
                    case 0:  minLevel = 0;  break;  // blindness
                    case 1:  minLevel = 3;  break;  // cause serious wounds
                    case 2:  minLevel = 7;  break;  // earthquake
                    case 3:  minLevel = 9;  break;  // cause critical wounds
                    case 4:  minLevel = 10; break;  // dispel evil
                    case 5:  minLevel = 12; break;  // curse
                    case 6:  minLevel = 12; break;  // reroll
                    case 7:  minLevel = 13; break;  // flame strike
                    case 8: case 9: case 10: minLevel = 15; break; // harm
                    default: minLevel = 16; break;  // dispel magic
                }
                if (level >= minLevel || attempt > 30) break;
            }
            if (roll == 6) return false; // reroll slot
            Integer targetId = target.getCharacterId();
            String spellName;
            switch (roll) {
                case 0:  spellName = "blindness";             break;
                case 1:  spellName = "cause serious wounds";  break;
                case 2:  spellName = "earthquake";            break;
                case 3:  spellName = "cause critical wounds"; break;
                case 4:  spellName = "dispel evil";           break;
                case 5:  spellName = "curse";                 break;
                case 7:  spellName = "flame strike";          break;
                case 8: case 9: case 10: spellName = "harm";  break;
                default: spellName = "dispel magic";          break;
            }
            int proficiency = Math.min(100, 20 + level * 2);
            return com.example.tassmud.spell.MobileSpellCaster.cast(mob, spellName, targetId, ctx.roomId, ctx.combat, proficiency);
        });

        // spec_cast_undead: undead combat spellcaster
        registry.register("spec_cast_undead", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(25)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            int level = mob.getLevel();
            int roll;
            for (int attempt = 0; ; attempt++) {
                roll = ctx.rng.nextInt(9);
                int minLevel;
                switch (roll) {
                    case 0:  minLevel = 0;  break;  // curse
                    case 1:  minLevel = 3;  break;  // weaken
                    case 2:  minLevel = 6;  break;  // chill touch
                    case 3:  minLevel = 9;  break;  // blindness
                    case 4:  minLevel = 12; break;  // poison
                    case 5:  minLevel = 15; break;  // energy drain
                    case 6:  minLevel = 18; break;  // harm
                    case 7:  minLevel = 21; break;  // teleport
                    default: minLevel = 24; break;  // gate (PINNED)
                }
                if (level >= minLevel || attempt > 30) break;
            }
            // gate: pinned stub — summoning system not yet implemented
            if (roll == 8) {
                ctx.sendToRoom.accept(ctx.roomId,
                    mob.getName() + " begins to summon a demon from the abyss!");
                return true;
            }
            Integer targetId = target.getCharacterId();
            String spellName;
            switch (roll) {
                case 0:  spellName = "curse";         break;
                case 1:  spellName = "weaken";        break;
                case 2:  spellName = "chill touch";   break;
                case 3:  spellName = "blindness";     break;
                case 4:  spellName = "poison";        break;
                case 5:  spellName = "energy drain";  break;
                case 6:  spellName = "harm";          break;
                default: spellName = "teleport";      break;
            }
            int proficiency = Math.min(100, 20 + level * 2);
            return com.example.tassmud.spell.MobileSpellCaster.cast(mob, spellName, targetId, ctx.roomId, ctx.combat, proficiency);
        });

        // spec_cast_judge: always casts "high explosive" — massive damage nuke
        registry.register("spec_cast_judge", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(25)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            int level = mob.getLevel();
            // high explosive: ~level*5 damage (TODO: add high_explosive to spells.yaml)
            int damage = level * 5 + ctx.rng.nextInt(level * 2 + 1);
            tc.setHpCur(tc.getHpCur() - damage);
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " detonates a high explosive on " + targetName(target) + "! BOOM!");
            notifyIfPlayer(target, "The high explosive detonates for " + damage + " damage!");
            return true;
        });

        // spec_cast_adept: out-of-combat healer, casts helpful spells on players in the room.
        // All spell logic is delegated to DivineSpellHandler via MobileSpellCaster so that
        // NPC casts share a single code path with player casts.
        registry.register("spec_cast_adept", (mob, ctx) -> {
            if (!ctx.chance(20)) return false;

            int roll = ctx.rng.nextInt(6);
            String spellName;
            String utterance;
            switch (roll) {
                case 0: spellName = "armor";       utterance = mob.getName() + " utters the words 'aegivex'."; break;
                case 1: spellName = "bless";       utterance = mob.getName() + " utters the words 'el-shavar'."; break;
                case 2: spellName = "stone skin";  utterance = mob.getName() + " utters the words 'basar-aven'."; break;
                case 3: spellName = "cure light";  utterance = mob.getName() + " utters the words 'rapha-or'."; break;
                case 4: spellName = "cure poison"; utterance = mob.getName() + " utters the words 'shuv-rosh'."; break;
                default: spellName = "refresh";    utterance = mob.getName() + " utters the words 'halak-shamai'."; break;
            }

            // Pick any PC in the room as the target
            Integer targetId = null;
            if (ctx.combat != null) {
                Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
                if (self == null) return false;
                List<Combatant> players = ctx.combat.getPlayerCombatants();
                if (!players.isEmpty()) {
                    targetId = players.get(ctx.rng.nextInt(players.size())).getCharacterId();
                }
            } else {
                List<Integer> roomPlayers = ClientHandler.getCharacterIdsInRoom(ctx.roomId);
                if (roomPlayers.isEmpty()) return false;
                targetId = roomPlayers.get(ctx.rng.nextInt(roomPlayers.size()));
            }
            if (targetId == null) return false;

            ctx.sendToRoom.accept(ctx.roomId, utterance);
            return com.example.tassmud.spell.MobileSpellCaster.cast(mob, spellName, targetId, ctx.roomId, ctx.combat);
        });

        // ── Non-combat specials ───────────────────────────────────────────────

        // spec_executioner: aggro vs criminals — PINNED (criminal system not yet implemented)
        // Simplified: no-op stub that logs a threat if a player is present
        registry.register("spec_executioner", (mob, ctx) -> {
            // PINNED: PLR_KILLER / PLR_THIEF detection requires criminal flag system
            // When implemented: shout crime, attack criminal, spawn 2 city guards
            return false;
        });

        // spec_guard: protects innocents from evil attackers and criminals
        registry.register("spec_guard", (mob, ctx) -> {
            // PINNED: criminal detection requires criminal flag system
            // Simplified: no-op; may attack most-evil fighter in room eventually
            return false;
        });

        // spec_fido: scavenges NPC corpses from the room floor
        registry.register("spec_fido", (mob, ctx) -> {
            if (ctx.combat != null) return false; // only out of combat
            if (!ctx.chance(50)) return false;
            try {
                List<ItemDAO.RoomItem> roomItems = DaoProvider.items().getItemsInRoom(ctx.roomId);
                for (ItemDAO.RoomItem ri : roomItems) {
                    if (ri.template.id == ItemDAO.CORPSE_TEMPLATE_ID) {
                        // Dump corpse contents to room
                        List<ItemDAO.RoomItem> contents =
                            DaoProvider.items().getItemsInContainer(ri.instance.instanceId);
                        for (ItemDAO.RoomItem content : contents) {
                            DaoProvider.items().moveInstanceToRoom(content.instance.instanceId, ctx.roomId);
                        }
                        // Delete the corpse
                        DaoProvider.items().deleteInstance(ri.instance.instanceId);
                        ctx.sendToRoom.accept(ctx.roomId,
                            mob.getName() + " savagely devours a corpse.");
                        return true; // one corpse per tick
                    }
                }
            } catch (Exception e) {
                logger.warn("spec_fido: error processing corpse for {} in room {}: {}",
                    mob.getName(), ctx.roomId, e.getMessage());
            }
            return false;
        });

        // spec_janitor: picks up cheap trash items from the floor
        registry.register("spec_janitor", (mob, ctx) -> {
            if (ctx.combat != null) return false;
            if (!ctx.chance(25)) return false;
            try {
                List<ItemDAO.RoomItem> roomItems = DaoProvider.items().getItemsInRoom(ctx.roomId);
                for (ItemDAO.RoomItem ri : roomItems) {
                    // Pick up trash (cost < 10) or drink containers
                    String type = ri.template.type;
                    int cost = ri.template.value;
                    boolean isTrash = "trash".equalsIgnoreCase(type);
                    boolean isCheap = cost < 10 && !"immobile".equalsIgnoreCase(type);
                    if (isTrash || isCheap) {
                        // Move to janitor's "inventory" by removing from room
                        // (no owner_character_id for mobs, so just delete cheap trash)
                        DaoProvider.items().deleteInstance(ri.instance.instanceId);
                        ctx.sendToRoom.accept(ctx.roomId,
                            mob.getName() + " picks up some trash.");
                        return true; // one item per tick
                    }
                }
            } catch (Exception e) {
                logger.warn("spec_janitor: error for {} in room {}: {}",
                    mob.getName(), ctx.roomId, e.getMessage());
            }
            return false;
        });

        // spec_mayor: timed path walk through Midgaard. Delegates to cleric spec if attacked.
        //
        // Path encoding (from MERC special.c):
        //   0=NORTH  1=EAST  2=SOUTH  3=WEST
        //   W=wake   S=sleep
        //   a='Hello Honey!'   b='What a view!...'  c='Vandals!...'  d='Good day, citizens!'
        //   e='...city open!'  E='...city closed!'
        //   O=unlock+open west gate (open path only)
        //   C=close+lock west gate  (close path only)
        //   .=end path
        //
        // Triggered: open_path at hour 6, close_path at hour 20.
        // Each MobileSpecialService tick (every 4s) advances the path by one step.
        final String MAYOR_OPEN_PATH  = "W3a3003b33000c111d0d111Oe333333Oe22c222112212111a1S.";
        final String MAYOR_CLOSE_PATH = "W3a3003b33000c111d0d111CE333333CE22c222112212111a1S.";

        // Per-instance state (keyed by mobile instance ID)
        final class MayorState {
            String path = null;
            int    pos  = 0;
            boolean moving = false;
            int lastTriggeredHour = -1;
        }
        Map<Long, MayorState> mayorStates = new ConcurrentHashMap<>();

        registry.register("spec_mayor", (mob, ctx) -> {
            if (ctx.combat != null) {
                // Fight like a cleric when attacked
                MobileSpecialHandler cleric = registry.get("spec_cast_cleric");
                return cleric != null && cleric.trigger(mob, ctx);
            }

            MayorState state = mayorStates.computeIfAbsent(mob.getInstanceId(), id -> new MayorState());
            int hour = gameClock.getHour();

            // Arm path when the trigger hour arrives (guard lastTriggeredHour to fire only once)
            if (!state.moving) {
                if (hour == 6 && state.lastTriggeredHour != 6) {
                    state.path = MAYOR_OPEN_PATH;
                    state.pos  = 0;
                    state.moving = true;
                    state.lastTriggeredHour = 6;
                } else if (hour == 20 && state.lastTriggeredHour != 20) {
                    state.path = MAYOR_CLOSE_PATH;
                    state.pos  = 0;
                    state.moving = true;
                    state.lastTriggeredHour = 20;
                }
            }
            if (!state.moving || state.path == null || state.pos >= state.path.length()) {
                state.moving = false;
                return false;
            }

            char step = state.path.charAt(state.pos++);
            int currentRoomId = mob.getCurrentRoom() != null ? mob.getCurrentRoom() : ctx.roomId;
            String mobName = mob.getName();

            switch (step) {
                case '0': case '1': case '2': case '3': {
                    // Move in cardinal direction: 0=N 1=E 2=S 3=W
                    Direction[] cardinals = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
                    Direction dir = cardinals[step - '0'];
                    try {
                        Room room = DaoProvider.rooms().getRoomById(currentRoomId);
                        if (room != null) {
                            String dirName = dir.name().toLowerCase();
                            Integer destId = room.getExit(dir);
                            if (destId != null) {
                                ClientHandler.roomAnnounce(currentRoomId, mobName + " leaves " + dirName + ".");
                                mob.setCurrentRoom(destId);
                                com.example.tassmud.util.MobileRegistry.getInstance().moveToRoom(
                                    mob.getInstanceId(), currentRoomId, destId);
                                ClientHandler.roomAnnounce(destId, mobName + " has arrived.");
                                DaoProvider.mobiles().updateInstance(mob);
                            } else {
                                logger.debug("spec_mayor: no exit {} from room {}", dirName, currentRoomId);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("spec_mayor: movement error: {}", e.getMessage());
                    }
                    break;
                }
                case 'W': // Wake
                    ctx.sendToRoom.accept(currentRoomId, mobName + " awakens and groans loudly.");
                    break;
                case 'S': // Sleep
                    ctx.sendToRoom.accept(currentRoomId, mobName + " lies down and falls asleep.");
                    break;
                case 'a':
                    ctx.sendToRoom.accept(currentRoomId, mobName + " says 'Hello Honey!'");
                    break;
                case 'b':
                    ctx.sendToRoom.accept(currentRoomId, mobName + " says 'What a view!  I must do something about that dump!'");
                    break;
                case 'c':
                    ctx.sendToRoom.accept(currentRoomId, mobName + " says 'Vandals!  Youngsters have no respect for anything!'");
                    break;
                case 'd':
                    ctx.sendToRoom.accept(currentRoomId, mobName + " says 'Good day, citizens!'");
                    break;
                case 'e':
                    ctx.sendToRoom.accept(currentRoomId, mobName + " says 'I hereby declare the city of Midgaard open!'");
                    break;
                case 'E':
                    ctx.sendToRoom.accept(currentRoomId, mobName + " says 'I hereby declare the city of Midgaard closed!'");
                    break;
                case 'O': // Unlock + open the west gate
                    try {
                        com.example.tassmud.model.Door gate = DaoProvider.rooms().getDoor(currentRoomId, "west");
                        if (gate != null) {
                            DaoProvider.rooms().upsertDoor(currentRoomId, "west", gate.toRoomId,
                                "OPEN", false, gate.hidden, gate.blocked, gate.keyItemId, gate.description);
                            ctx.sendToRoom.accept(currentRoomId, mobName + " unlocks and opens the west gate.");
                        }
                    } catch (Exception e) {
                        logger.warn("spec_mayor: gate open error: {}", e.getMessage());
                    }
                    break;
                case 'C': // Close + lock the west gate
                    try {
                        com.example.tassmud.model.Door gate = DaoProvider.rooms().getDoor(currentRoomId, "west");
                        if (gate != null) {
                            DaoProvider.rooms().upsertDoor(currentRoomId, "west", gate.toRoomId,
                                "CLOSED", true, gate.hidden, gate.blocked, gate.keyItemId, gate.description);
                            ctx.sendToRoom.accept(currentRoomId, mobName + " closes and locks the west gate.");
                        }
                    } catch (Exception e) {
                        logger.warn("spec_mayor: gate close error: {}", e.getMessage());
                    }
                    break;
                case '.': // End of path
                    state.moving = false;
                    break;
                default:
                    break;
            }
            return false;
        });

        // spec_poison: venomous bite in combat (2% * level chance)
        registry.register("spec_poison", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            int poisonChance = 2 * mob.getLevel();
            if (!ctx.chance(Math.min(poisonChance, 95))) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " bites " + targetName(target) + "!");
            notifyIfPlayer(target, mob.getName() + " bites you!");
            // TODO: apply poison status effect when effect registry supports offensive poison
            // For now, small venom damage
            int venom = mob.getLevel() / 4 + 1;
            tc.setHpCur(tc.getHpCur() - venom);
            return false; // bite doesn't consume the main attack action
        });

        // ── Summoned-undead specials ──────────────────────────────────────────

        // spec_undead_mummy: guaranteed bonus slam attack every combat tick (33% chance)
        registry.register("spec_undead_mummy", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            int damage = mob.getLevel() + ctx.rng.nextInt(mob.getLevel() / 2 + 1);
            tc.setHpCur(tc.getHpCur() - damage);
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " slams " + targetName(target) + " with a bandaged fist for " + damage + " damage!");
            notifyIfPlayer(target, mob.getName() + "'s crushing slam hits you for " + damage + " damage!");
            return false; // don't consume main attack — this is a bonus hit
        });

        // spec_undead_drain: vampiric bite that heals the vampire and its summoner 50/50
        registry.register("spec_undead_drain", (mob, ctx) -> {
            if (ctx.combat == null) return false;
            if (!ctx.chance(33)) return false;
            Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
            if (self == null) return false;
            Combatant target = ctx.combat.getRandomTarget(self);
            if (target == null) return false;
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            int drain = mob.getLevel() + ctx.rng.nextInt(mob.getLevel() / 2 + 1);
            tc.setHpCur(tc.getHpCur() - drain);
            // Heal the vampire (50%)
            int vampHeal = drain / 2;
            mob.setHpCur(Math.min(mob.getHpMax(), mob.getHpCur() + vampHeal));
            // Heal the summoner (50%) — look up owner from AllyManager
            int ownerHeal = drain - vampHeal; // remainder to owner
            com.example.tassmud.model.AllyBinding binding =
                    com.example.tassmud.util.AllyManager.getInstance().getBindingForMob(mob.getInstanceId());
            if (binding != null) {
                int ownerId = binding.getOwnerCharacterId();
                // Find the owner's combatant (if in same combat) or heal via DB
                Combatant ownerCombatant = null;
                for (Combatant c : ctx.combat.getPlayerCombatants()) {
                    if (c.getCharacterId() != null && c.getCharacterId() == ownerId) {
                        ownerCombatant = c;
                        break;
                    }
                }
                if (ownerCombatant != null && ownerCombatant.getCharacter() != null) {
                    GameCharacter ownerGc = ownerCombatant.getCharacter();
                    ownerGc.setHpCur(Math.min(ownerGc.getHpMax(), ownerGc.getHpCur() + ownerHeal));
                    ClientHandler.sendToCharacter(ownerId,
                        mob.getName() + "'s bite drains " + drain + " life! You absorb " + ownerHeal + " HP.");
                }
            }
            ctx.sendToRoom.accept(ctx.roomId,
                mob.getName() + " sinks its fangs into " + targetName(target) + ", draining " + drain + " life!");
            notifyIfPlayer(target, mob.getName() + " drains " + drain + " life from you!");
            return false; // bonus attack, doesn't consume main
        });

        // spec_undead_taunt: Death Knight periodically forces enemies to target it via massive aggro
        // Fires every ~5 combat ticks (~2.5s) at 100% chance = effective 20% per tick (fires every ~5th)
        {
            Map<Long, Integer> dkTickCounters = new ConcurrentHashMap<>();
            registry.register("spec_undead_taunt", (mob, ctx) -> {
                if (ctx.combat == null) return false;
                int count = dkTickCounters.merge(mob.getInstanceId(), 1, Integer::sum);
                if (count < 5) return false; // wait 5 ticks between taunts
                dkTickCounters.put(mob.getInstanceId(), 0);

                Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
                if (self == null) return false;
                List<Combatant> enemies = ctx.combat.getValidTargets(self);
                if (enemies.isEmpty()) return false;

                // Generate massive aggro for the Death Knight so all mobs target it
                // The DK isn't a player so aggro doesn't apply to it directly —
                // instead, taunt each enemy by dealing light damage + message
                ctx.sendToRoom.accept(ctx.roomId,
                    mob.getName() + " slams its shield and bellows a challenge that shakes the ground!");
                for (Combatant enemy : enemies) {
                    GameCharacter ec = getCharacterFromCombatant(enemy);
                    if (ec == null) continue;
                    int tauntDmg = 1 + ctx.rng.nextInt(3); // trivial damage, it's about the aggro
                    ec.setHpCur(ec.getHpCur() - tauntDmg);
                    // If enemy is a player, add massive negative aggro to other targets so DK is preferred
                    // Since aggro system tracks player→mob aggro, taunt for enemy mobs
                    // works by dealing light AoE damage to draw mob AI attention.
                }
                return false; // bonus action, doesn't consume main attack
            });
        }

        // spec_thief: steals gold from players in the room while standing
        registry.register("spec_thief", (mob, ctx) -> {
            if (ctx.combat != null) return false; // only while not fighting
            if (!ctx.chance(25)) return false;
            // Find players in room via ClientHandler
            // We can only reach players via sendToCharacter callbacks.
            // Gold theft requires CharacterDAO access; use DaoProvider.
            // Iterate players in room via room-scoped client iteration (not directly available here).
            // LIMITATION: We don't have direct access to connected player list by room here.
            // TODO: Wire ClientHandler.forEachInRoom() into MobileSpecialContext for spec_thief
            return false;
        });

        // ── Angel spec_funs (Divine Fury paladin spell — IDs 790/791/792) ─────

        // spec_angel_deva: healer/buffer — every ~6 ticks, heals the most-wounded ally.
        // Also restores MP on a secondary roll. Out-of-combat: buffs the owner instead.
        {
            Map<Long, Integer> devaTickCounters = new ConcurrentHashMap<>();
            registry.register("spec_angel_deva", (mob, ctx) -> {
                int count = devaTickCounters.merge(mob.getInstanceId(), 1, Integer::sum);
                if (count < 6) return false; // pulse every ~3 seconds (6 ticks × 500ms)
                devaTickCounters.put(mob.getInstanceId(), 0);

                if (!ctx.chance(80)) return false; // 80% chance to do something when pulse fires

                List<Combatant> allies = ctx.combat != null
                        ? ctx.combat.getPlayerCombatants()
                        : new java.util.ArrayList<>();

                // Find the most-wounded ally (lowest HP ratio)
                Combatant healTarget = null;
                double lowestRatio = 1.0;
                for (Combatant ally : allies) {
                    GameCharacter ac = getCharacterFromCombatant(ally);
                    if (ac == null || ac.getHpMax() <= 0) continue;
                    double ratio = (double) ac.getHpCur() / ac.getHpMax();
                    if (ratio < lowestRatio) {
                        lowestRatio = ratio;
                        healTarget = ally;
                    }
                }

                if (healTarget != null && lowestRatio < 0.95) {
                    GameCharacter tc = getCharacterFromCombatant(healTarget);
                    if (tc != null) {
                        // Heal amount: 30 + 1d(mob level) — solid but not overpowered
                        int healAmt = 30 + ctx.rng.nextInt(mob.getLevel() + 1);
                        tc.setHpCur(Math.min(tc.getHpMax(), tc.getHpCur() + healAmt));
                        ctx.sendToRoom.accept(ctx.roomId,
                                mob.getName() + " touches " + tc.getName()
                                + " with a wing of radiant light, restoring " + healAmt + " hit points!");
                        notifyIfPlayer(healTarget,
                                "\u001b[1;93mDivine warmth washes through you as the Deva heals you for "
                                + healAmt + " HP!\u001b[0m");
                        return true;
                    }
                }

                // Secondary: restore MV to a random ally
                if (!allies.isEmpty()) {
                    Combatant mvTarget = allies.get(ctx.rng.nextInt(allies.size()));
                    GameCharacter tc = getCharacterFromCombatant(mvTarget);
                    if (tc != null && tc.getMvCur() < tc.getMvMax()) {
                        int mvRestore = 10 + ctx.rng.nextInt(mob.getLevel() / 5 + 1);
                        tc.setMvCur(Math.min(tc.getMvMax(), tc.getMvCur() + mvRestore));
                        ctx.sendToRoom.accept(ctx.roomId,
                                mob.getName() + " sings a single bright note — "
                                + tc.getName() + " feels refreshed!");
                        notifyIfPlayer(mvTarget,
                                "\u001b[1;37mThe Deva's song renews your strength — +" + mvRestore + " MV!\u001b[0m");
                        return false; // bonus action
                    }
                }
                return false;
            });
        }

        // spec_angel_solar: tank taunt — every 5 ticks, bellows a challenge that draws
        // all enemy attacks onto itself for the next round (AoE light damage = aggro pull).
        {
            Map<Long, Integer> solarTickCounters = new ConcurrentHashMap<>();
            registry.register("spec_angel_solar", (mob, ctx) -> {
                if (ctx.combat == null) return false;
                int count = solarTickCounters.merge(mob.getInstanceId(), 1, Integer::sum);
                if (count < 5) return false; // every ~2.5 seconds
                solarTickCounters.put(mob.getInstanceId(), 0);

                Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
                if (self == null) return false;
                List<Combatant> enemies = ctx.combat.getValidTargets(self);
                if (enemies.isEmpty()) return false;

                ctx.sendToRoom.accept(ctx.roomId,
                        "\u001b[1;93m" + mob.getName()
                        + " raises its blazing sword and unleashes a thunderous divine challenge!\u001b[0m");

                for (Combatant enemy : enemies) {
                    GameCharacter ec = getCharacterFromCombatant(enemy);
                    if (ec == null) continue;
                    // Light shockwave damage — enough to mark the Solar as the threat
                    int tauntDmg = 2 + ctx.rng.nextInt(4);
                    ec.setHpCur(ec.getHpCur() - tauntDmg);
                    notifyIfPlayer(enemy,
                            "\u001b[1;91mThe Solar's challenge sends a shockwave through you for "
                            + tauntDmg + " damage — it wants YOUR attention!\u001b[0m");
                }
                return false; // bonus action
            });
        }

        logger.info("[MobileSpecials] Registered {} handlers", registry.count());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns breath weapon damage: level * 2 + 1d(level+1) */
    private static int breathDamage(int level) {
        return level * 2 + ThreadLocalRandom.current().nextInt(level + 1);
    }

    /** Get the GameCharacter from a Combatant (player or mobile). */
    private static GameCharacter getCharacterFromCombatant(Combatant c) {
        if (c.getCharacter() != null) return c.getCharacter();
        if (c.getMobile() != null) return c.getMobile();
        return null;
    }

    /** Get a display name for a combatant. */
    private static String targetName(Combatant c) {
        if (c.isPlayer() && c.getCharacter() != null) return c.getCharacter().getName();
        if (c.getMobile() != null) return c.getMobile().getName();
        return "someone";
    }

    /** Apply direct damage and send messages. */
    private static void castDirectDamage(com.example.tassmud.model.Mobile mob, Combatant target,
            GameCharacter tc, MobileSpecialContext ctx, int damage, String roomMsg) {
        tc.setHpCur(tc.getHpCur() - damage);
        ctx.sendToRoom.accept(ctx.roomId, roomMsg);
        notifyIfPlayer(target, "You take " + damage + " damage!");
    }

    /** Apply a named effect and send messages. The effect name must be registered in EffectRegistry. */
    private static void castInstantEffect(com.example.tassmud.model.Mobile mob, Combatant target,
            GameCharacter tc, MobileSpecialContext ctx, String effectName,
            String roomMsg, String victimMsg) {
        ctx.sendToRoom.accept(ctx.roomId, roomMsg);
        notifyIfPlayer(target, victimMsg);
        // TODO: apply effect via EffectRegistry when EffectScheduler integration is available
        // DaoProvider.effects().applyEffect(target.getCharacterId(), effectName, duration);
    }

    /** Send a message to a player combatant. */
    private static void notifyIfPlayer(Combatant c, String msg) {
        if (c.isPlayer() && c.getCharacterId() != null) {
            ClientHandler.sendToCharacter(c.getCharacterId(), msg);
        }
    }
}
