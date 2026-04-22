package com.example.tassmud.spell;


import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.Group;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.net.commands.MovementCommandHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.util.GroupManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central dispatcher for ARCANE spells. Registers handlers for all arcane
 * spells and provides dedicated handle<SpellName>() methods for each.
 *
 * Spell handlers receive a SpellContext containing:
 * - Resolved target IDs
 * - Caster proficiency and other params
 * - Command context for I/O
 * - Combat instance if applicable
 *
 * Most spells apply effects via EffectRegistry using the spell's effectIds.
 * Custom logic can be added before/after effect application as needed.
 */
public class ArcaneSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(ArcaneSpellHandler.class);

    static {
        // Register ARCANE spells with the SpellRegistry. Each registration
        // captures the spell name and dispatches to our shared dispatcher.
        registerArcane("acid blast");
        registerArcane("blindness");
        registerArcane("burning hands");
        registerArcane("call lightning");
        registerArcane("charm person");
        registerArcane("chill touch");
        registerArcane("colour spray");
        registerArcane("curse");
        registerArcane("dispel evil");
        registerArcane("dispel magic");
        registerArcane("earthquake");
        registerArcane("energy drain");
        registerArcane("faerie fire");
        registerArcane("fireball");
        registerArcane("flamestrike");
        registerArcane("harm");
        registerArcane("know alignment");
        registerArcane("lightning bolt");
        registerArcane("magic missile");
        registerArcane("poison");
        registerArcane("shocking grasp");
        registerArcane("sleep");
        registerArcane("weaken");
        registerArcane("disintegrate");
        registerArcane("meteor swarm");
        registerArcane("teleport");
        registerArcane("mass teleport");

        // Dragon breath spells
        registerArcane("acid breath");
        registerArcane("fire breath");
        registerArcane("frost breath");
        registerArcane("gas breath");
        registerArcane("lightning breath");
    }

    private static void registerArcane(String spellName) {
        SpellRegistry.register(spellName, (casterId, args, ctx) -> dispatch(spellName, casterId, args, ctx), SpellSchool.ARCANE);
    }

    private static boolean dispatch(String spellName, Integer casterId, String args, SpellContext ctx) {
        switch (spellName) {
            case "acid blast": return handleAcidBlast(casterId, args, ctx);
            case "blindness": return handleBlindness(casterId, args, ctx);
            case "burning hands": return handleBurningHands(casterId, args, ctx);
            case "call lightning": return handleCallLightning(casterId, args, ctx);
            case "charm person": return handleCharmPerson(casterId, args, ctx);
            case "chill touch": return handleChillTouch(casterId, args, ctx);
            case "colour spray": return handleColourSpray(casterId, args, ctx);
            case "curse": return handleCurse(casterId, args, ctx);
            case "dispel evil": return handleDispelEvil(casterId, args, ctx);
            case "dispel magic": return handleDispelMagic(casterId, args, ctx);
            case "earthquake": return handleEarthquake(casterId, args, ctx);
            case "energy drain": return handleEnergyDrain(casterId, args, ctx);
            case "faerie fire": return handleFaerieFire(casterId, args, ctx);
            case "fireball": return handleFireball(casterId, args, ctx);
            case "flamestrike": return handleFlamestrike(casterId, args, ctx);
            case "harm": return handleHarm(casterId, args, ctx);
            case "know alignment": return handleKnowAlignment(casterId, args, ctx);
            case "lightning bolt": return handleLightningBolt(casterId, args, ctx);
            case "magic missile": return handleMagicMissile(casterId, args, ctx);
            case "poison": return handlePoison(casterId, args, ctx);
            case "shocking grasp": return handleShockingGrasp(casterId, args, ctx);
            case "sleep": return handleSleep(casterId, args, ctx);
            case "weaken": return handleWeaken(casterId, args, ctx);
            case "meteor swarm": return handleMeteorSwarm(casterId, args, ctx);
            case "teleport": return handleTeleport(casterId, args, ctx);
            case "disintegrate": return handleDisintegrate(casterId, args, ctx);
            case "mass teleport": return handleMassTeleport(casterId, args, ctx);
            case "acid breath": return handleAcidBreath(casterId, args, ctx);
            case "fire breath": return handleFireBreath(casterId, args, ctx);
            case "frost breath": return handleFrostBreath(casterId, args, ctx);
            case "gas breath": return handleGasBreath(casterId, args, ctx);
            case "lightning breath": return handleLightningBreath(casterId, args, ctx);
            default:
                return notImplemented(spellName, casterId, args, ctx);
        }
    }

    // --- Per-spell handlers ---
    // Each handler applies effects from the spell's effectIds via EffectRegistry.
    // Custom pre/post logic can be added as needed for special spell behavior.
    
    private static boolean handleAcidBlast(Integer casterId, String args, SpellContext ctx) {
        // Acid Blast: DOT effect "1001" (Acid Burn)
        return applySpellEffects(ctx, "acid blast");
    }
    
    private static boolean handleBlindness(Integer casterId, String args, SpellContext ctx) {
        // Blindness: DEBUFF effect "1002" (Blindness)
        return applySpellEffects(ctx, "blindness");
    }
    
    private static boolean handleBurningHands(Integer casterId, String args, SpellContext ctx) {
        // Burning Hands: Fire DOT effect "1003" with 25% spread chance per tick
        // - 2nd level single target fire DOT
        // - Scales to 5d4 damage
        // - 15 seconds duration
        // - Refreshes on recast
        // - Can "dance" between enemies in combat
        return applySpellEffects(ctx, "burning hands");
    }
    
    private static boolean handleCallLightning(Integer casterId, String args, SpellContext ctx) {
        // Call Lightning: Level 5 ARCANE spell, 10d10 electricity damage
        // - Can only be used outdoors
        // - Clear/Partly Cloudy: 1/2 damage
        // - Stormy/Hurricane (hasLightning): 2x damage
        // - Other weather: normal damage
        
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[call lightning] No context available");
            return false;
        }
        
        CommandContext cc = ctx.getCommandContext();
        
        // Check if caster is outdoors
        Integer roomId = cc.currentRoomId;
        if (roomId != null) {
            java.util.Set<com.example.tassmud.model.RoomFlag> flags = DaoProvider.rooms().getRoomFlags(roomId);
            if (flags != null && flags.contains(com.example.tassmud.model.RoomFlag.INDOORS)) {
                cc.send("You cannot call lightning from indoors!");
                return false;
            }
        }
        
        // Get current weather and determine damage modifier
        com.example.tassmud.model.Weather weather = com.example.tassmud.util.WeatherService.getInstance().getCurrentWeather();
        double damageMultiplier = 1.0;
        String weatherMessage = "";
        
        if (weather == com.example.tassmud.model.Weather.CLEAR || 
            weather == com.example.tassmud.model.Weather.PARTLY_CLOUDY) {
            // Clear skies - harder to call lightning
            damageMultiplier = 0.5;
            weatherMessage = "The clear sky reluctantly yields a weak bolt!";
        } else if (weather.hasLightning()) {
            // Stormy/Hurricane - lightning is plentiful
            damageMultiplier = 2.0;
            weatherMessage = "The storm answers your call with devastating fury!";
        } else {
            // Other weather - normal damage
            weatherMessage = "Lightning streaks down from the clouds!";
        }
        
        // Apply the spell effects with damage modifier
        Spell spell = ctx.getSpell();
        if (spell == null) {
            logger.warn("[call lightning] No spell definition in context");
            return false;
        }
        
        List<String> effectIds = spell.getEffectIds();
        if (effectIds == null || effectIds.isEmpty()) {
            logger.warn("[call lightning] No effects defined for spell");
            return false;
        }
        
        // Get targets
        List<Integer> targetIds = ctx.getTargetIds();
        if (targetIds.isEmpty()) {
            logger.warn("[call lightning] No targets resolved");
            cc.send("Call lightning on whom?");
            return false;
        }
        
        int proficiency = ctx.getProficiency();
        boolean anyApplied = false;
        
        for (Integer targetId : targetIds) {
            for (String effectId : effectIds) {
                EffectDefinition def = EffectRegistry.getDefinition(effectId);
                if (def == null) {
                    logger.warn("[call lightning] Effect '{}' not found", effectId);
                    continue;
                }
                
                // Create params with damage multiplier 
                java.util.Map<String, String> params = new java.util.HashMap<>();
                params.put("damageMultiplier", String.valueOf(damageMultiplier));
                params.put("proficiency", String.valueOf(proficiency));
                
                // Apply using the registry (which will dispatch to CallLightningEffect handler)
                EffectInstance inst = EffectRegistry.apply(effectId, casterId, targetId, params);
                if (inst != null) {
                    anyApplied = true;
                }
            }
        }
        
        if (anyApplied) {
            // Send weather-flavored message
            cc.send(weatherMessage);
        }
        
        return anyApplied;
    }
    private static boolean handleCharmPerson(Integer casterId, String args, SpellContext ctx) { return notImplemented("charm person", casterId, args, ctx); }
    
    private static boolean handleChillTouch(Integer casterId, String args, SpellContext ctx) {
        // Chill Touch: Level 4 ARCANE spell
        // - Deals 5d8 cold damage (effect 1008)
        // - Applies SLOW effect (effect 1009) with duration scaling by proficiency
        return applySpellEffects(ctx, "chill touch");
    }
    
    /** Effect IDs used by Colour Spray - must match effects.yaml */
    private static final String EFFECT_BLIND = "1002";
    private static final String EFFECT_SLOW = "1009";
    private static final String EFFECT_CONFUSED = "1010";
    private static final String EFFECT_PARALYZED = "1011";
    private static final String[] COLOUR_SPRAY_EFFECTS = { EFFECT_BLIND, EFFECT_SLOW, EFFECT_CONFUSED, EFFECT_PARALYZED };
    private static final double COLOUR_SPRAY_CHANCE = 0.25; // 25% per effect
    
    private static boolean handleColourSpray(Integer casterId, String args, SpellContext ctx) {
        // Colour Spray: Level 8 ARCANE spell
        // - Deals no damage
        // - 25% chance each to apply: BLIND, SLOW, CONFUSED, PARALYZED
        // - Recasting clears existing colour spray effects then applies fresh
        // - Duration scales with proficiency up to 60 seconds
        
        if (ctx == null) {
            logger.warn("[colour spray] No spell context provided");
            return false;
        }
        
        List<Integer> targets = ctx.getTargetIds();
        if (targets.isEmpty()) {
            logger.debug("[colour spray] No targets resolved");
            if (ctx.getCommandContext() != null) {
                ctx.getCommandContext().send("No valid targets for Colour Spray.");
            }
            return false;
        }
        
        Map<String, String> extraParams = ctx.getExtraParams();
        CommandContext cmdCtx = ctx.getCommandContext();
        CharacterDAO dao = cmdCtx != null ? cmdCtx.dao : DaoProvider.characters();
        
        // Get caster info for messages
        String casterName = "Someone";
        Integer casterRoom = null;
        CharacterDAO.CharacterRecord casterRec = dao.findById(casterId);
        if (casterRec != null) {
            casterName = casterRec.name;
            casterRoom = casterRec.currentRoom;
        }
        
        // Room message for casting
        ClientHandler.broadcastRoomMessage(casterRoom, 
            casterName + " gestures dramatically as a dazzling spray of colors erupts from their hands!");
        
        boolean anyApplied = false;
        java.util.Random rng = new java.util.Random();
        
        for (Integer targetId : targets) {
            // Clear any existing colour spray effects on this target (refresh mechanic)
            for (String effId : COLOUR_SPRAY_EFFECTS) {
                EffectRegistry.removeAllEffectsOfType(targetId, effId);
            }
            
            String targetName = "someone";
            CharacterDAO.CharacterRecord trec = dao.findById(targetId);
            if (trec != null) {
                targetName = trec.name;
            }
            
            // Track which effects landed for summary message
            java.util.List<String> appliedNames = new java.util.ArrayList<>();
            
            // Roll 25% chance for each effect
            for (String effId : COLOUR_SPRAY_EFFECTS) {
                if (rng.nextDouble() < COLOUR_SPRAY_CHANCE) {
                    EffectInstance inst = EffectRegistry.apply(effId, casterId, targetId, extraParams);
                    if (inst != null) {
                        anyApplied = true;
                        EffectDefinition def = EffectRegistry.getDefinition(effId);
                        if (def != null) {
                            appliedNames.add(def.getName());
                        }
                        logger.debug("[colour spray] Applied {} to {}", effId, targetName);
                    }
                }
            }
            
            // Feedback message
            if (appliedNames.isEmpty()) {
                ClientHandler.broadcastRoomMessage(casterRoom, 
                    targetName + " shrugs off the brilliant colors!");
            } else {
                ClientHandler.broadcastRoomMessage(casterRoom, 
                    targetName + " is affected by: " + String.join(", ", appliedNames) + "!");
            }
        }
        
        return anyApplied;
    }
    
    private static boolean handleCurse(Integer casterId, String args, SpellContext ctx) {
        // Curse: Level 6 ARCANE spell
        // - Single target debuff
        // - Causes skills and spells to potentially fail
        // - Duration and fail chance scale with proficiency
        return applySpellEffects(ctx, "curse");
    }
    
    private static boolean handleDispelEvil(Integer casterId, String args, SpellContext ctx) { return notImplemented("dispel evil", casterId, args, ctx); }
    private static boolean handleDispelMagic(Integer casterId, String args, SpellContext ctx) { return notImplemented("dispel magic", casterId, args, ctx); }
    
    private static boolean handleEarthquake(Integer casterId, String args, SpellContext ctx) {
        // Earthquake: Level 9 ARCANE spell
        // - AoE spell affecting ALL enemies in combat
        // - Damage scales from 1d20 to 20d20 based on proficiency
        // - Also attempts to trip each target (same logic as trip skill)
        // - Flying creatures are immune to both damage and trip
        
        if (ctx == null) {
            logger.warn("[earthquake] No spell context provided");
            return false;
        }
        
        com.example.tassmud.combat.Combat combat = ctx.getCombat();
        if (combat == null) {
            if (ctx.getCommandContext() != null) {
                ctx.getCommandContext().send("You can only cast Earthquake during combat.");
            }
            return false;
        }
        
        CommandContext cmdCtx = ctx.getCommandContext();
        CharacterDAO dao = cmdCtx != null ? cmdCtx.dao : DaoProvider.characters();
        
        // Get caster info
        String casterName = "Someone";
        Integer casterRoom = null;
        int casterLevel = 1;
        CharacterDAO.CharacterRecord casterRec = dao.findById(casterId);
        if (casterRec != null) {
            casterName = casterRec.name;
            casterRoom = casterRec.currentRoom;
            if (casterRec.currentClassId != null) {
                com.example.tassmud.persistence.CharacterClassDAO classDao = new com.example.tassmud.persistence.CharacterClassDAO();
                casterLevel = classDao.getCharacterClassLevel(casterId, casterRec.currentClassId);
            }
        }
        
        // Get proficiency (1-100) for damage and trip scaling
        int proficiency = ctx.getProficiency();
        proficiency = Math.max(1, Math.min(100, proficiency));
        
        // Calculate number of d20s for damage: 1 at 1%, 20 at 100%
        int numDice = 1 + (int)((proficiency - 1) * 19.0 / 99.0); // Linear scale 1-20
        
        // Room message for casting
        ClientHandler.broadcastRoomMessage(casterRoom, 
            casterName + " slams their hands to the ground as the earth begins to shake violently!");
        
        // Find caster's combatant to determine alliance
        com.example.tassmud.combat.Combatant casterCombatant = combat.findByCharacterId(casterId);
        if (casterCombatant == null) {
            logger.warn("[earthquake] Could not find caster combatant");
            return false;
        }
        int casterAlliance = casterCombatant.getAlliance();
        
        java.util.Random rng = new java.util.Random();
        boolean anyAffected = false;
        
        // Iterate over all enemy combatants
        for (com.example.tassmud.combat.Combatant target : combat.getCombatants()) {
            // Skip allies and self
            if (target.getAlliance() == casterAlliance) continue;
            if (!target.isActive() || !target.isAlive()) continue;
            
            String targetName = target.getName();
            Integer targetCharId = target.getCharacterId();
            
            // Check if target is flying - completely immune
            if (targetCharId != null && com.example.tassmud.effect.FlyingEffect.isFlying(targetCharId)) {
                ClientHandler.broadcastRoomMessage(casterRoom, 
                    targetName + " hovers safely above the shaking earth!");
                continue;
            }
            
            anyAffected = true;
            
            // Roll damage: numDice d20
            int damage = 0;
            for (int i = 0; i < numDice; i++) {
                damage += rng.nextInt(20) + 1;
            }
            
            // Apply damage
            int oldHp = target.getHpCurrent();
            target.damage(damage);
            int newHp = target.getHpCurrent();
            
            // Damage message
            ClientHandler.broadcastRoomMessage(casterRoom,
                "The heaving earth strikes %s for %d damage!".formatted(targetName, damage));
            
            // Notify target if player
            if (target.isPlayer() && targetCharId != null) {
                ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                if (targetHandler != null) {
                    targetHandler.out.println("\u001B[31mThe earthquake deals %d damage to you! [%d -> %d HP]\u001B[0m".formatted(
                        damage, oldHp, newHp));
                }
            }
            
            // Check if target died from damage
            if (!target.isAlive()) {
                ClientHandler.broadcastRoomMessage(casterRoom, 
                    targetName + " is crushed by the earthquake!");
                continue;
            }
            
            // Attempt to trip (if not already prone)
            if (!target.isProne()) {
                // Get target level for opposed check
                int targetLevel;
                if (target.isPlayer()) {
                    CharacterDAO.CharacterRecord targetRec = dao.getCharacterById(targetCharId);
                    if (targetRec != null && targetRec.currentClassId != null) {
                        com.example.tassmud.persistence.CharacterClassDAO classDao = new com.example.tassmud.persistence.CharacterClassDAO();
                        targetLevel = classDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId);
                    } else {
                        targetLevel = 1;
                    }
                } else {
                    // For mobiles, use their level
                    com.example.tassmud.model.Mobile mob = target.getMobile();
                    targetLevel = mob != null ? mob.getLevel() : Math.max(1, target.getHpMax() / 10);
                }
                
                // Perform opposed check with spell proficiency
                int roll = rng.nextInt(100) + 1;
                int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(
                    casterLevel, targetLevel, proficiency);
                
                if (roll <= successChance) {
                    // Trip succeeded
                    target.setProne();
                    ClientHandler.broadcastRoomMessage(casterRoom, 
                        targetName + " is knocked off their feet by the tremors!");
                    
                    // Notify target if player
                    if (target.isPlayer() && targetCharId != null) {
                        ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetCharId);
                        if (targetHandler != null) {
                            targetHandler.out.println("\u001B[33mThe earthquake knocks you prone!\u001B[0m");
                        }
                    }
                }
                // No message on failed trip - the shaking ground didn't knock them down
            }
        }
        
        if (!anyAffected) {
            ClientHandler.broadcastRoomMessage(casterRoom, 
                "The earthquake rumbles harmlessly - all enemies are airborne!");
        }
        
        return anyAffected;
    }
    
    private static boolean handleEnergyDrain(Integer casterId, String args, SpellContext ctx) { return notImplemented("energy drain", casterId, args, ctx); }
    private static boolean handleFaerieFire(Integer casterId, String args, SpellContext ctx) { return notImplemented("faerie fire", casterId, args, ctx); }
    private static boolean handleFireball(Integer casterId, String args, SpellContext ctx) { return notImplemented("fireball", casterId, args, ctx); }
    private static boolean handleFlamestrike(Integer casterId, String args, SpellContext ctx) { return notImplemented("flamestrike", casterId, args, ctx); }
    private static boolean handleHarm(Integer casterId, String args, SpellContext ctx) { return notImplemented("harm", casterId, args, ctx); }
    private static boolean handleKnowAlignment(Integer casterId, String args, SpellContext ctx) { return notImplemented("know alignment", casterId, args, ctx); }
    private static boolean handleLightningBolt(Integer casterId, String args, SpellContext ctx) { return notImplemented("lightning bolt", casterId, args, ctx); }
    
    private static boolean handleMagicMissile(Integer casterId, String args, SpellContext ctx) {
        // Magic Missile: Level 1 ARCANE spell
        // - Single target damage spell (CURRENT_ENEMY)
        // - Damage scales from 1d4+1 to 10d4+10 based on proficiency
        // - Simple, reliable damage - the bread-and-butter spell for casters
        
        if (ctx == null) {
            logger.warn("[magic missile] No spell context provided");
            return false;
        }
        
        List<Integer> targets = ctx.getTargetIds();
        if (targets.isEmpty()) {
            if (ctx.getCommandContext() != null) {
                ctx.getCommandContext().send("No valid target for Magic Missile.");
            }
            return false;
        }
        
        CommandContext cmdCtx = ctx.getCommandContext();
        CharacterDAO dao = cmdCtx != null ? cmdCtx.dao : DaoProvider.characters();
        
        // Get caster info
        String casterName = "Someone";
        Integer casterRoom = null;
        CharacterDAO.CharacterRecord casterRec = dao.findById(casterId);
        if (casterRec != null) {
            casterName = casterRec.name;
            casterRoom = casterRec.currentRoom;
        }
        
        // Get proficiency (1-100) for damage scaling
        int proficiency = ctx.getProficiency();
        proficiency = Math.max(1, Math.min(100, proficiency));
        
        // Calculate number of missiles: 1 at 1%, 10 at 100%
        int numMissiles = 1 + (int)((proficiency - 1) * 9.0 / 99.0); // Linear scale 1-10
        
        java.util.Random rng = new java.util.Random();
        boolean anyHit = false;
        
        // Get combat for combatant lookup
        com.example.tassmud.combat.Combat combat = ctx.getCombat();
        
        for (Integer targetId : targets) {
            String targetName = "someone";
            CharacterDAO.CharacterRecord trec = dao.findById(targetId);
            if (trec != null) {
                targetName = trec.name;
            }
            
            // Roll damage: numMissiles x (1d4+1)
            int totalDamage = 0;
            for (int i = 0; i < numMissiles; i++) {
                totalDamage += rng.nextInt(4) + 1 + 1; // 1d4+1 per missile
            }
            
            // Room message for casting
            String missileWord = numMissiles == 1 ? "missile" : "missiles";
            ClientHandler.broadcastRoomMessage(casterRoom,
                "%s hurls %d glowing %s of magical energy at %s!".formatted(
                    casterName, numMissiles, missileWord, targetName));
            
            // Apply damage through combat system if in combat
            if (combat != null) {
                com.example.tassmud.combat.Combatant targetCombatant = combat.findByCharacterId(targetId);
                if (targetCombatant != null) {
                    int oldHp = targetCombatant.getHpCurrent();
                    targetCombatant.damage(totalDamage);
                    int newHp = targetCombatant.getHpCurrent();
                    
                    ClientHandler.broadcastRoomMessage(casterRoom,
                        "The missiles strike %s for %d damage!".formatted(targetName, totalDamage));
                    
                    // Notify target if player
                    if (targetCombatant.isPlayer()) {
                        ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetId);
                        if (targetHandler != null) {
                            targetHandler.out.println("\u001B[31mMagic missiles deal %d damage to you! [%d -> %d HP]\u001B[0m".formatted(
                                totalDamage, oldHp, newHp));
                        }
                    }
                    
                    if (!targetCombatant.isAlive()) {
                        ClientHandler.broadcastRoomMessage(casterRoom, 
                            targetName + " is slain by the magical barrage!");
                    }
                    
                    // Track aggro for damage spell (10x spell level + damage)
                    ctx.addDamageSpellAggro(totalDamage);
                    
                    anyHit = true;
                }
            } else {
                // Outside combat - just show message (damage not applied without combat system)
                ClientHandler.broadcastRoomMessage(casterRoom,
                    "The missiles strike %s for %d damage!".formatted(targetName, totalDamage));
                anyHit = true;
            }
        }
        
        return anyHit;
    }
    
    private static boolean handlePoison(Integer casterId, String args, SpellContext ctx) { return notImplemented("poison", casterId, args, ctx); }
    private static boolean handleShockingGrasp(Integer casterId, String args, SpellContext ctx) { return notImplemented("shocking grasp", casterId, args, ctx); }
    private static boolean handleSleep(Integer casterId, String args, SpellContext ctx) { return notImplemented("sleep", casterId, args, ctx); }
    private static boolean handleWeaken(Integer casterId, String args, SpellContext ctx) { return notImplemented("weaken", casterId, args, ctx); }

    private static boolean handleMeteorSwarm(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[meteor swarm] No context available");
            return false;
        }

        // Requires active enemies in combat to start the swarm field.
        if (ctx.getTargetIds() == null || ctx.getTargetIds().isEmpty()) {
            ctx.getCommandContext().send("There are no enemies for your meteors to strike.");
            return false;
        }

        Spell spell = ctx.getSpell();
        if (spell == null || spell.getEffectIds() == null || spell.getEffectIds().isEmpty()) {
            logger.warn("[meteor swarm] No effects defined for spell");
            return false;
        }

        String effectId = spell.getEffectIds().get(0);
        EffectInstance inst = EffectRegistry.apply(effectId, casterId, casterId, ctx.getExtraParams());
        return inst != null;
    }

    private static boolean handleTeleport(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) return false;
        CommandContext cc = ctx.getCommandContext();

        if (args == null || args.trim().isEmpty()) {
            cc.send("Teleport where? Usage: cast teleport <room_id>");
            return false;
        }

        int roomId;
        try {
            roomId = Integer.parseInt(args.trim());
        } catch (NumberFormatException e) {
            cc.send("Invalid room ID '" + args.trim() + "'. Usage: cast teleport <room_id>");
            return false;
        }

        Room destRoom = DaoProvider.rooms().getRoomById(roomId);
        if (destRoom == null) {
            cc.send("No room with ID " + roomId + " exists.");
            return false;
        }

        String casterName = cc.playerName;
        Integer oldRoomId = cc.currentRoomId;

        ClientHandler.roomAnnounce(oldRoomId,
            casterName + " vanishes in a flash of arcane light!", cc.characterId, true);

        DaoProvider.characters().updateCharacterRoom(casterName, roomId);
        cc.handler.currentRoomId = roomId;

        cc.send("\nYou tear a hole through space and emerge in " + destRoom.getName() + ".\n");
        ClientHandler.roomAnnounce(roomId,
            casterName + " materializes from a swirling portal of arcane energy!", cc.characterId, true);

        MovementCommandHandler.showRoom(destRoom, roomId, cc);
        return true;
    }

    private static boolean handleMassTeleport(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) return false;
        CommandContext cc = ctx.getCommandContext();

        if (args == null || args.trim().isEmpty()) {
            cc.send("Mass Teleport where? Usage: cast mass teleport <room_id>");
            return false;
        }

        int roomId;
        try {
            roomId = Integer.parseInt(args.trim());
        } catch (NumberFormatException e) {
            cc.send("Invalid room ID '" + args.trim() + "'. Usage: cast mass teleport <room_id>");
            return false;
        }

        Room destRoom = DaoProvider.rooms().getRoomById(roomId);
        if (destRoom == null) {
            cc.send("No room with ID " + roomId + " exists.");
            return false;
        }

        // Build member set: caster first, then any party members
        Set<Integer> memberIds = new LinkedHashSet<>();
        memberIds.add(casterId);
        GroupManager.getInstance().getGroupForCharacter(casterId)
            .ifPresent(group -> memberIds.addAll(group.getMemberIds()));

        int teleported = 0;
        for (Integer memberId : memberIds) {
            ClientHandler memberHandler = ClientHandler.getHandlerByCharacterId(memberId);
            if (memberHandler == null || memberHandler.playerName == null || memberHandler.out == null) continue;

            String memberName = memberHandler.playerName;
            Integer oldRoomId = memberHandler.currentRoomId;

            ClientHandler.roomAnnounce(oldRoomId,
                memberName + " vanishes in a flash of arcane light!", memberId, true);

            DaoProvider.characters().updateCharacterRoom(memberName, roomId);
            memberHandler.currentRoomId = roomId;

            if (memberId.equals(casterId)) {
                cc.send("\nYou rip open a portal through space, pulling your party to " + destRoom.getName() + ".\n");
            } else {
                memberHandler.out.println("\nYou are swept through a shimmering portal and emerge in " + destRoom.getName() + ".\n");
            }

            ClientHandler.roomAnnounce(roomId,
                memberName + " materializes from a swirling portal of arcane energy!", memberId, true);

            // Build a synthetic CommandContext so showRoom works for each member
            CommandContext memberCtx = new CommandContext(
                null, memberName, memberId, roomId,
                null, DaoProvider.characters(), memberHandler.out,
                false, false, memberHandler
            );
            MovementCommandHandler.showRoom(destRoom, roomId, memberCtx);
            teleported++;
        }

        if (teleported <= 1) {
            // Teleported only self — no online party members
            cc.send("(No party members were online to bring along.)");
        }

        return true;
    }

    private static boolean handleDisintegrate(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) return false;
        CommandContext cc = ctx.getCommandContext();

        com.example.tassmud.combat.Combat combat = ctx.getCombat();
        if (combat == null) {
            cc.send("You must be in combat to channel your life force into destruction.");
            return false;
        }

        // Resolve the primary target from context
        List<Integer> targets = ctx.getTargetIds();
        if (targets == null || targets.isEmpty()) {
            cc.send("There is no target to disintegrate.");
            return false;
        }
        Integer targetId = targets.get(0);

        com.example.tassmud.combat.Combatant targetCombatant = combat.findByCharacterId(targetId);
        com.example.tassmud.combat.Combatant casterCombatant = combat.findByCharacterId(casterId);
        if (targetCombatant == null || casterCombatant == null) {
            cc.send("The spell fizzles — you cannot locate your target.");
            return false;
        }

        // Damage = caster's current HP
        int casterCurrentHp = casterCombatant.getHpCurrent();
        if (casterCurrentHp <= 0) {
            cc.send("You have no life force left to channel.");
            return false;
        }

        // Proficiency reduces caster's backlash: 0% prof = 100% damage, 100% prof = 50% damage
        int proficiency = Math.max(0, Math.min(100, ctx.getProficiency()));
        double casterMultiplier = 1.0 - (proficiency * 0.005);  // 1.0 → 0.5

        int targetDamage = casterCurrentHp;
        int casterDamage = (int) Math.max(1, Math.round(casterCurrentHp * casterMultiplier));

        String targetName = targetCombatant.getName();
        String casterName = cc.playerName != null ? cc.playerName : "The wizard";
        int casterRoom = cc.currentRoomId != null ? cc.currentRoomId : -1;

        // Dramatic cast announcement
        ClientHandler.broadcastRoomMessage(casterRoom,
            casterName + " tears open a vein of raw arcane force, channeling their very life into a beam of white destruction!");

        // --- Apply damage to target ---
        int targetOldHp = targetCombatant.getHpCurrent();
        targetCombatant.damage(targetDamage);
        int targetNewHp = targetCombatant.getHpCurrent();

        ClientHandler.broadcastRoomMessage(casterRoom,
            "The beam strikes %s for %d damage!".formatted(targetName, targetDamage));

        if (targetCombatant.isPlayer()) {
            ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetId);
            if (targetHandler != null) {
                targetHandler.out.println(
                    "\u001B[31mDisintegrate deals %d damage to you! [%d -> %d HP]\u001B[0m"
                        .formatted(targetDamage, targetOldHp, targetNewHp));
            }
        }

        if (!targetCombatant.isAlive()) {
            ClientHandler.broadcastRoomMessage(casterRoom,
                targetName + " is utterly disintegrated!");
        }

        // --- Apply backlash to caster ---
        int casterOldHp = casterCombatant.getHpCurrent(); // may have changed if target hit back, but typically unchanged
        casterCombatant.damage(casterDamage);
        int casterNewHp = casterCombatant.getHpCurrent();

        ClientHandler.broadcastRoomMessage(casterRoom,
            casterName + " reels from the backlash, taking %d damage!".formatted(casterDamage));

        cc.send("\u001B[31mThe backlash tears through you for %d damage! [%d -> %d HP]\u001B[0m"
            .formatted(casterDamage, casterOldHp, casterNewHp));

        if (!casterCombatant.isAlive()) {
            ClientHandler.broadcastRoomMessage(casterRoom,
                casterName + " is consumed by the backlash of their own spell!");
        }

        ctx.addDamageSpellAggro(targetDamage);
        return true;
    }

    // Dragon breaths
    private static boolean handleAcidBreath(Integer casterId, String args, SpellContext ctx) { return notImplemented("acid breath", casterId, args, ctx); }
    private static boolean handleFireBreath(Integer casterId, String args, SpellContext ctx) { return notImplemented("fire breath", casterId, args, ctx); }
    private static boolean handleFrostBreath(Integer casterId, String args, SpellContext ctx) { return notImplemented("frost breath", casterId, args, ctx); }
    private static boolean handleGasBreath(Integer casterId, String args, SpellContext ctx) { return notImplemented("gas breath", casterId, args, ctx); }
    private static boolean handleLightningBreath(Integer casterId, String args, SpellContext ctx) { return notImplemented("lightning breath", casterId, args, ctx); }

    private static boolean notImplemented(String spellName, Integer casterId, String args, SpellContext ctx) {
        if (ctx != null && ctx.getCommandContext() != null) {
            CommandContext cc = ctx.getCommandContext();
            cc.send("The spell '" + spellName + "' is not implemented yet.");
        } else {
            logger.debug("[spell] '{}' invoked but not implemented (casterId={})", spellName, casterId);
        }
        return false;
    }
    
    // === Helper methods ===
    
    /**
     * Apply all effects from the spell's effectIds to all resolved targets.
     * This is the standard implementation for effect-based spells.
     * 
     * @param ctx The spell context containing targets, caster, and params
     * @param spellName The spell name (for logging)
     * @return true if at least one effect was applied successfully
     */
    private static boolean applySpellEffects(SpellContext ctx, String spellName) {
        if (ctx == null) {
            logger.warn("[{}] No spell context provided", spellName);
            return false;
        }
        
        Spell spell = ctx.getSpell();
        if (spell == null) {
            logger.warn("[{}] No spell definition in context", spellName);
            return false;
        }
        
        List<String> effectIds = spell.getEffectIds();
        if (effectIds == null || effectIds.isEmpty()) {
            logger.warn("[{}] Spell has no effectIds defined", spellName);
            return false;
        }
        
        List<Integer> targets = ctx.getTargetIds();
        if (targets.isEmpty()) {
            logger.debug("[{}] No targets resolved", spellName);
            if (ctx.getCommandContext() != null) {
                ctx.getCommandContext().send("No valid targets for " + spell.getName() + ".");
            }
            return false;
        }
        
        Integer casterId = ctx.getCasterId();
        Map<String, String> extraParams = ctx.getExtraParams();
        boolean anyApplied = false;
        
        for (String effId : effectIds) {
            EffectDefinition def = EffectRegistry.getDefinition(effId);
            if (def == null) {
                logger.warn("[{}] Effect definition not found: {}", spellName, effId);
                continue;
            }
            
            for (Integer targetId : targets) {
                EffectInstance inst = EffectRegistry.apply(effId, casterId, targetId, extraParams);
                if (inst != null) {
                    anyApplied = true;
                    logger.debug("[{}] Applied effect {} to target {}", spellName, def.getName(), targetId);

                }
            }
        }
        
        return anyApplied;
    }
}
