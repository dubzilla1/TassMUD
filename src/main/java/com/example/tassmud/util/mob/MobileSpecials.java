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
            // level-gated: keep rolling until we find a qualifying spell
            for (int attempt = 0; attempt < 30; attempt++) {
                roll = ctx.rng.nextInt(16);
                int minLevel;
                switch (roll) {
                    case 0:  minLevel = 0; break;  // blindness
                    case 1:  minLevel = 3; break;  // chill touch
                    case 2:  minLevel = 7; break;  // weaken (TODO: add to spells.yaml)
                    case 3:  minLevel = 8; break;  // teleport / dimension door
                    case 4:  minLevel = 11; break; // colour spray
                    case 5:  minLevel = 12; break; // reroll (skip change_sex)
                    case 6:  minLevel = 13; break; // energy drain
                    case 7: case 8: case 9: minLevel = 15; break; // fireball
                    default: minLevel = 20; break; // acid blast
                }
                if (level >= minLevel) break;
            }
            // Apply chosen spell inline
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            switch (roll) {
                case 0: // blindness
                    castInstantEffect(mob, target, tc, ctx, "blindness",
                        mob.getName() + " mutters an incantation and blindness descends upon " + targetName(target) + "!",
                        "You have been blinded!");
                    break;
                case 1: // chill touch
                    castDirectDamage(mob, target, tc, ctx, level * 2 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " launches a chill touch at " + targetName(target) + "!");
                    break;
                case 2: // weaken — TODO: add weaken spell/effect; for now small STR penalty via flat damage
                    castDirectDamage(mob, target, tc, ctx, level + ctx.rng.nextInt(level + 1),
                        mob.getName() + " saps the strength of " + targetName(target) + "! (weaken)");
                    break;
                case 3: // teleport / dimension door — eject target from combat
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " tears a portal open beneath " + targetName(target) + "!");
                    notifyIfPlayer(target, "You are hurled through a dimensional rift!");
                    // TODO: move target to a random room when teleport mechanics are added
                    break;
                case 4: // colour spray
                    castInstantEffect(mob, target, tc, ctx, "blindness",
                        mob.getName() + " sprays a prismatic cascade at " + targetName(target) + "!",
                        "The prismatic colours blind you!");
                    break;
                case 5: // reroll (skip change_sex)
                    return false;
                case 6: // energy drain — Enervation
                    int drain = level + ctx.rng.nextInt(level + 1);
                    tc.setHpCur(tc.getHpCur() - drain);
                    tc.setMpCur(Math.max(0, tc.getMpCur() - drain / 2));
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " drains the life energy of " + targetName(target) + "!");
                    notifyIfPlayer(target, mob.getName() + "'s energy drain saps " + drain + " HP and " + (drain/2) + " MP!");
                    break;
                case 7: case 8: case 9: // fireball
                    castDirectDamage(mob, target, tc, ctx, level * 3 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " hurls a fireball at " + targetName(target) + "!");
                    break;
                default: // acid blast
                    castDirectDamage(mob, target, tc, ctx, level * 3 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " blasts " + targetName(target) + " with corrosive acid!");
                    break;
            }
            return true;
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
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            int roll;
            for (int attempt = 0; ; attempt++) {
                roll = ctx.rng.nextInt(12);
                int minLevel;
                switch (roll) {
                    case 0:  minLevel = 0; break;  // blindness
                    case 1:  minLevel = 3; break;  // cause serious
                    case 2:  minLevel = 7; break;  // earthquake
                    case 3:  minLevel = 9; break;  // cause critical
                    case 4:  minLevel = 10; break; // dispel evil (TODO)
                    case 5:  minLevel = 12; break; // curse
                    case 6:  minLevel = 12; break; // reroll (skip change_sex)
                    case 7:  minLevel = 13; break; // flamestrike
                    case 8: case 9: case 10: minLevel = 15; break; // harm (TODO)
                    default: minLevel = 16; break; // dispel magic (TODO)
                }
                if (level >= minLevel || attempt > 30) break;
            }
            switch (roll) {
                case 0: // blindness
                    castInstantEffect(mob, target, tc, ctx, "blindness",
                        mob.getName() + " invokes blindness upon " + targetName(target) + "!",
                        "You have been blinded!");
                    break;
                case 1: // cause serious wounds
                    castDirectDamage(mob, target, tc, ctx, level * 2 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " calls upon dark powers to wound " + targetName(target) + "!");
                    break;
                case 2: // earthquake — AoE damage
                    Combatant clericSelf = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
                    if (clericSelf != null) {
                        ctx.sendToRoom.accept(ctx.roomId, mob.getName() + " calls down an earthquake!");
                        for (Combatant t : ctx.combat.getValidTargets(clericSelf)) {
                            GameCharacter qtc = getCharacterFromCombatant(t);
                            if (qtc == null) continue;
                            int dmg = level + ctx.rng.nextInt(level + 1);
                            qtc.setHpCur(qtc.getHpCur() - dmg);
                            notifyIfPlayer(t, "The earthquake shakes the ground, dealing " + dmg + " damage!");
                        }
                    }
                    break;
                case 3: // cause critical
                    castDirectDamage(mob, target, tc, ctx, level * 3 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " channels devastating holy fire into " + targetName(target) + "!");
                    break;
                case 4: // dispel evil — TODO add alignment check; for now just damage
                    castDirectDamage(mob, target, tc, ctx, level * 2 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " calls down righteous fire upon " + targetName(target) + "! (dispel evil)");
                    break;
                case 5: // curse
                    castInstantEffect(mob, target, tc, ctx, "curse",
                        mob.getName() + " lays a curse upon " + targetName(target) + "!",
                        "You feel a dark curse settle over you!");
                    break;
                case 6: // reroll
                    return false;
                case 7: // flamestrike
                    castDirectDamage(mob, target, tc, ctx, level * 3 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " calls down a pillar of divine flame on " + targetName(target) + "!");
                    break;
                case 8: case 9: case 10: // harm — massive damage
                    int harmDmg = Math.max(1, tc.getHpCur() - 1 - ctx.rng.nextInt(4));
                    tc.setHpCur(tc.getHpCur() - harmDmg);
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " invokes Harm upon " + targetName(target) + ", leaving them near death!");
                    notifyIfPlayer(target, "You have been Harmed! (" + harmDmg + " damage)");
                    break;
                default: // dispel magic — TODO: strip effects; for now no-op
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " gestures and waves of anti-magic wash over " + targetName(target) + "! (dispel magic)");
                    break;
            }
            return true;
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
            GameCharacter tc = getCharacterFromCombatant(target);
            if (tc == null) return false;
            int roll;
            for (int attempt = 0; ; attempt++) {
                roll = ctx.rng.nextInt(9);
                int minLevel;
                switch (roll) {
                    case 0:  minLevel = 0; break;  // curse
                    case 1:  minLevel = 3; break;  // weaken (TODO)
                    case 2:  minLevel = 6; break;  // chill touch
                    case 3:  minLevel = 9; break;  // blindness
                    case 4:  minLevel = 12; break; // poison (TODO)
                    case 5:  minLevel = 15; break; // energy drain
                    case 6:  minLevel = 18; break; // harm
                    case 7:  minLevel = 21; break; // teleport
                    default: minLevel = 24; break; // gate (PINNED)
                }
                if (level >= minLevel || attempt > 30) break;
            }
            switch (roll) {
                case 0: // curse
                    castInstantEffect(mob, target, tc, ctx, "curse",
                        mob.getName() + " hisses a dark invocation at " + targetName(target) + "!",
                        "A foul curse grips your soul!");
                    break;
                case 1: // weaken (TODO: real debuff)
                    castDirectDamage(mob, target, tc, ctx, level + ctx.rng.nextInt(level + 1),
                        mob.getName() + " saps the strength of " + targetName(target) + "! (weaken)");
                    break;
                case 2: // chill touch
                    castDirectDamage(mob, target, tc, ctx, level * 2 + ctx.rng.nextInt(level + 1),
                        mob.getName() + " reaches out with a chill touch at " + targetName(target) + "!");
                    break;
                case 3: // blindness
                    castInstantEffect(mob, target, tc, ctx, "blindness",
                        mob.getName() + " howls and darkness descends upon " + targetName(target) + "!",
                        "You have been blinded!");
                    break;
                case 4: // poison (TODO: real poison effect)
                    castDirectDamage(mob, target, tc, ctx, level + ctx.rng.nextInt(level + 1),
                        mob.getName() + " infects " + targetName(target) + " with a virulent poison! (poison)");
                    break;
                case 5: // energy drain
                    int drain = level + ctx.rng.nextInt(level + 1);
                    tc.setHpCur(tc.getHpCur() - drain);
                    tc.setMpCur(Math.max(0, tc.getMpCur() - drain / 2));
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " drains the life essence from " + targetName(target) + "!");
                    notifyIfPlayer(target, mob.getName() + " drains " + drain + " HP and " + (drain/2) + " MP from you!");
                    break;
                case 6: // harm
                    int harmDmg = Math.max(1, tc.getHpCur() - 1 - ctx.rng.nextInt(4));
                    tc.setHpCur(tc.getHpCur() - harmDmg);
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " corrupts the life force of " + targetName(target) + "!");
                    notifyIfPlayer(target, "Your life is corrupted for " + harmDmg + " damage!");
                    break;
                case 7: // teleport
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " tears a rift beneath " + targetName(target) + "!");
                    notifyIfPlayer(target, "You are yanked through a dimensional rift!");
                    // TODO: actually move target when teleport system is added
                    break;
                default: // gate (PINNED — summoning system not yet implemented)
                    ctx.sendToRoom.accept(ctx.roomId,
                        mob.getName() + " begins to summon a demon from the abyss!");
                    break;
            }
            return true;
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

        // spec_cast_adept: out-of-combat healer, casts helpful spells on allies in room
        registry.register("spec_cast_adept", (mob, ctx) -> {
            // Works both in and out of combat (healer)
            if (!ctx.chance(20)) return false;

            // Pick a target: prefer combat ally, else random player in room
            Combatant healTarget = null;
            if (ctx.combat != null) {
                // Pick ally with lowest HP ratio
                Combatant self = ctx.combat.findByMobileInstanceId(mob.getInstanceId());
                if (self != null) {
                    List<Combatant> allies = ctx.combat.getPlayerCombatants();
                    // Just pick a random ally that isn't fully healed
                    for (Combatant ally : allies) {
                        GameCharacter ac = getCharacterFromCombatant(ally);
                        if (ac != null && ac.getHpCur() < ac.getHpMax()) {
                            healTarget = ally;
                            break;
                        }
                    }
                }
            }

            int roll = ctx.rng.nextInt(6);
            String utterance;
            switch (roll) {
                case 0: utterance = mob.getName() + " utters the words 'aegivex'."; break;
                case 1: utterance = mob.getName() + " utters the words 'el-shavar'."; break;
                case 2: utterance = mob.getName() + " utters the words 'el-ayin'."; break;
                case 3: utterance = mob.getName() + " utters the words 'rapha-or'."; break;
                case 4: utterance = mob.getName() + " utters the words 'shuv-rosh'."; break;
                default: utterance = mob.getName() + " utters the words 'halak-shamai'."; break;
            }
            ctx.sendToRoom.accept(ctx.roomId, utterance);

            if (healTarget != null) {
                GameCharacter tc = getCharacterFromCombatant(healTarget);
                if (tc != null) {
                    switch (roll) {
                        case 1: // bless — small HP boost (real bless TODO: add to effect system)
                        case 3: // cure light
                            int healAmt = mob.getLevel() + ctx.rng.nextInt(mob.getLevel() + 1);
                            tc.setHpCur(tc.getHpCur() + healAmt);
                            notifyIfPlayer(healTarget, mob.getName() + " heals you for " + healAmt + " HP!");
                            break;
                        case 5: // refresh — restore MV
                            tc.setMvCur(Math.min(tc.getMvMax(), tc.getMvCur() + mob.getLevel()));
                            notifyIfPlayer(healTarget, mob.getName() + " refreshes your movement!");
                            break;
                        default:
                            // armor/cure_blindness/cure_poison: TODO add effect system integration
                            break;
                    }
                }
            }
            return healTarget != null; // only consume action if we actually did something
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
