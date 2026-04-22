package com.example.tassmud.spell;


import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterClassDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Central dispatcher for OCCULT spells. Occult magic deals with negative energy,
 * death, undead, and dark rituals. 
 * 
 * Key spells:
 * - Cause Light/Serious/Critical Wounds: Deal negative energy damage to living,
 *   but HEAL undead targets. Damage scales with caster level.
 * - Chill Touch, Cause Fear, etc.
 */
public class OccultSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(OccultSpellHandler.class);
    
    /** Effect ID for the UNDEAD flag effect */
    public static final String EFFECT_UNDEAD = "2000";

    static {
        // Register OCCULT spells with the SpellRegistry
        registerOccult("cause light wounds");
        registerOccult("cause serious wounds");
        registerOccult("cause critical wounds");
        registerOccult("cause fear");
        registerOccult("detect undead");
        registerOccult("blindness");  // Also in Occult school
        registerOccult("darkness");
        registerOccult("spectral hand");
        registerOccult("vampiric touch");
        registerOccult("enervation");
        registerOccult("fear");
        registerOccult("create undead");
        registerOccult("circle of death");
        registerOccult("animate dead");
        registerOccult("summon skeleton");
        registerOccult("army of the dead");
        registerOccult("drain life");
        registerOccult("death coil");
        registerOccult("corpse explosion");
        registerOccult("bone armor");
        registerOccult("plague");
    }

    private static void registerOccult(String spellName) {
        SpellRegistry.register(spellName, (casterId, args, ctx) -> dispatch(spellName, casterId, args, ctx), SpellSchool.OCCULT);
    }

    private static boolean dispatch(String spellName, Integer casterId, String args, SpellContext ctx) {
        switch (spellName) {
            case "cause light wounds": return handleCauseWounds(casterId, args, ctx, "light");
            case "cause serious wounds": return handleCauseWounds(casterId, args, ctx, "serious");
            case "cause critical wounds": return handleCauseWounds(casterId, args, ctx, "critical");
            case "cause fear": return handleCauseFear(casterId, args, ctx);
            case "detect undead": return handleDetectUndead(casterId, args, ctx);
            case "blindness": return handleBlindness(casterId, args, ctx);
            case "darkness": return handleDarkness(casterId, args, ctx);
            case "spectral hand": return handleSpectralHand(casterId, args, ctx);
            case "vampiric touch": return handleVampiricTouch(casterId, args, ctx);
            case "enervation": return handleEnervation(casterId, args, ctx);
            case "fear": return handleFear(casterId, args, ctx);
            case "create undead": return handleCreateUndead(casterId, args, ctx);
            case "circle of death": return handleCircleOfDeath(casterId, args, ctx);
            case "animate dead": return AnimateDeadHandler.handle(casterId, args, ctx);
            case "summon skeleton": return AnimateDeadHandler.handleSummonSkeleton(casterId, args, ctx);
            case "army of the dead": return AnimateDeadHandler.handleArmyOfTheDead(casterId, args, ctx);
            case "drain life": return handleDrainLife(casterId, args, ctx);
            case "death coil": return handleDeathCoil(casterId, args, ctx);
            case "corpse explosion": return handleCorpseExplosion(casterId, args, ctx);
            case "bone armor": return handleBoneArmor(casterId, args, ctx);
            case "plague": return handlePlague(casterId, args, ctx);
            default:
                return notImplemented(spellName, casterId, args, ctx);
        }
    }

    // --- Cause Wounds handler (handles all three variants) ---
    
    /**
     * Cause Light/Serious/Critical Wounds
     * 
     * Deals negative energy damage to living targets, but HEALS undead.
     * Damage = NdM + caster_level * (0.5 + proficiency%)
     * 
     * Where:
     * - Light: 1d8, level 1
     * - Serious: 5d8, level 4
     * - Critical: 10d8, level 7
     * 
     * If target has UNDEAD effect, the calculated damage is applied as healing instead.
     */
    private static boolean handleCauseWounds(Integer casterId, String args, SpellContext ctx, String severity) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[cause {} wounds] No context available", severity);
            return false;
        }
        
        CommandContext cc = ctx.getCommandContext();
        Spell spell = ctx.getSpell();
        if (spell == null) {
            logger.warn("[cause {} wounds] No spell definition in context", severity);
            return false;
        }
        
        List<String> effectIds = spell.getEffectIds();
        if (effectIds == null || effectIds.isEmpty()) {
            logger.warn("[cause {} wounds] No effects defined for spell", severity);
            return false;
        }
        
        List<Integer> targetIds = ctx.getTargetIds();
        if (targetIds.isEmpty()) {
            cc.send("Cause wounds on whom?");
            return false;
        }
        
        int proficiency = ctx.getProficiency();
        
        // Get caster level from class progress
        CharacterDAO.CharacterRecord casterRec = cc.dao.findById(casterId);
        int casterLevel = 1;
        if (casterRec != null && casterRec.currentClassId != null) {
            CharacterClassDAO classDao = DaoProvider.classes();
            casterLevel = classDao.getCharacterClassLevel(casterId, casterRec.currentClassId);
        }
        
        boolean anyApplied = false;
        
        for (Integer targetId : targetIds) {
            for (String effectId : effectIds) {
                EffectDefinition def = EffectRegistry.getDefinition(effectId);
                if (def == null) {
                    logger.warn("[cause {} wounds] Effect '{}' not found", severity, effectId);
                    continue;
                }
                
                // Pass caster level and proficiency to the effect handler
                java.util.Map<String, String> params = new java.util.HashMap<>();
                params.put("proficiency", String.valueOf(proficiency));
                params.put("casterLevel", String.valueOf(casterLevel));
                params.put("severity", severity);
                
                // Check if target is undead - the effect handler will use this
                boolean isUndead = EffectRegistry.hasEffect(targetId, EFFECT_UNDEAD);
                params.put("targetIsUndead", String.valueOf(isUndead));
                
                EffectInstance inst = EffectRegistry.apply(effectId, casterId, targetId, params);
                if (inst != null) {
                    anyApplied = true;
                }
            }
        }
        
        return anyApplied;
    }

    // --- Other occult spell handlers (stubs for now) ---
    
    private static boolean handleCauseFear(Integer casterId, String args, SpellContext ctx) {
        return applySpellEffects(ctx, "cause fear");
    }
    
    private static boolean handleDetectUndead(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("detect undead", casterId, args, ctx);
    }
    
    private static boolean handleBlindness(Integer casterId, String args, SpellContext ctx) {
        return applySpellEffects(ctx, "blindness");
    }
    
    private static boolean handleDarkness(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("darkness", casterId, args, ctx);
    }
    
    private static boolean handleSpectralHand(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("spectral hand", casterId, args, ctx);
    }
    
    private static boolean handleVampiricTouch(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("vampiric touch", casterId, args, ctx);
    }
    
    private static boolean handleEnervation(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("enervation", casterId, args, ctx);
    }
    
    private static boolean handleFear(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("fear", casterId, args, ctx);
    }
    
    private static boolean handleCreateUndead(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("create undead", casterId, args, ctx);
    }
    
    private static boolean handleCircleOfDeath(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("circle of death", casterId, args, ctx);
    }

    /**
     * Drain Life — deals negative energy damage to the current enemy
     * and heals the caster for the full amount drained.
     */
    private static boolean handleDrainLife(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[drain life] No context available");
            return false;
        }

        CommandContext cc = ctx.getCommandContext();
        Spell spell = ctx.getSpell();
        if (spell == null) {
            logger.warn("[drain life] No spell definition in context");
            return false;
        }

        List<String> effectIds = spell.getEffectIds();
        if (effectIds == null || effectIds.isEmpty()) {
            logger.warn("[drain life] No effects defined for spell");
            return false;
        }

        List<Integer> targetIds = ctx.getTargetIds();
        if (targetIds.isEmpty()) {
            cc.send("Drain life from whom?");
            return false;
        }

        int proficiency = ctx.getProficiency();

        // Get caster level from class progress
        CharacterDAO.CharacterRecord casterRec = cc.dao.findById(casterId);
        int casterLevel = 1;
        if (casterRec != null && casterRec.currentClassId != null) {
            CharacterClassDAO classDao = DaoProvider.classes();
            casterLevel = classDao.getCharacterClassLevel(casterId, casterRec.currentClassId);
        }

        boolean anyApplied = false;

        for (Integer targetId : targetIds) {
            for (String effectId : effectIds) {
                EffectDefinition def = EffectRegistry.getDefinition(effectId);
                if (def == null) {
                    logger.warn("[drain life] Effect '{}' not found", effectId);
                    continue;
                }

                java.util.Map<String, String> params = new java.util.HashMap<>();
                params.put("proficiency", String.valueOf(proficiency));
                params.put("casterLevel", String.valueOf(casterLevel));

                EffectInstance inst = EffectRegistry.apply(effectId, casterId, targetId, params);
                if (inst != null) {
                    anyApplied = true;
                }
            }
        }

        return anyApplied;
    }

    private static boolean handleDeathCoil(Integer casterId, String args, SpellContext ctx) {
        return applySpellEffects(ctx, "death coil");
    }

    private static boolean notImplemented(String spellName, Integer casterId, String args, SpellContext ctx) {
        if (ctx != null && ctx.getCommandContext() != null) {
            CommandContext cc = ctx.getCommandContext();
            cc.send("The spell '" + spellName + "' is not implemented yet.");
        } else {
            logger.debug("[spell] '{}' invoked but not implemented (casterId={})", spellName, casterId);
        }
        return false;
    }
    
    /**
     * Apply all effects from the spell's effectIds to all resolved targets.
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

    /**
     * Corpse Explosion — requires a corpse in the room. Detonates it, dealing AoE
     * negative energy damage to all enemies in combat. Consumes the corpse.
     */
    private static boolean handleCorpseExplosion(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[corpse explosion] No context available");
            return false;
        }

        CommandContext cc = ctx.getCommandContext();
        Spell spell = ctx.getSpell();
        if (spell == null) {
            logger.warn("[corpse explosion] No spell definition in context");
            return false;
        }

        // --- Step 1: Find a corpse in the room ---
        Integer roomId = cc.currentRoomId;
        java.util.List<ItemDAO.RoomItem> roomItems = DaoProvider.items().getItemsInRoom(roomId);
        ItemDAO.RoomItem corpse = null;
        for (ItemDAO.RoomItem ri : roomItems) {
            if (ri.instance.templateId == ItemDAO.CORPSE_TEMPLATE_ID) {
                corpse = ri;
                break;
            }
        }
        if (corpse == null) {
            cc.send("There is no corpse here to detonate.");
            return false;
        }

        // --- Step 2: Get targets (resolved by SpellCastHandler for ALL_ENEMIES) ---
        List<Integer> targetIds = ctx.getTargetIds();
        if (targetIds.isEmpty()) {
            cc.send("There are no enemies to hit with the explosion.");
            return false;
        }

        // --- Step 3: Get caster info ---
        int proficiency = ctx.getProficiency();
        CharacterDAO.CharacterRecord casterRec = cc.dao.findById(casterId);
        int casterLevel = 1;
        if (casterRec != null && casterRec.currentClassId != null) {
            CharacterClassDAO classDao = DaoProvider.classes();
            casterLevel = classDao.getCharacterClassLevel(casterId, casterRec.currentClassId);
        }
        String casterName = casterRec != null ? casterRec.name : "Someone";

        // --- Step 4: Roll AoE damage once (same damage for all targets) ---
        List<String> effectIds = spell.getEffectIds();
        if (effectIds == null || effectIds.isEmpty()) {
            logger.warn("[corpse explosion] No effects defined for spell");
            return false;
        }

        String effectId = effectIds.get(0);
        EffectDefinition def = EffectRegistry.getDefinition(effectId);
        if (def == null) {
            logger.warn("[corpse explosion] Effect '{}' not found", effectId);
            return false;
        }

        // Parse dice from effect definition
        String raw = def.getDiceMultiplierRaw();
        if (raw == null || raw.isEmpty()) {
            logger.warn("[corpse explosion] No dice defined in effect");
            return false;
        }
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return false;
        int baseN, dieM;
        try {
            baseN = Integer.parseInt(raw.substring(0, dIdx));
            dieM = Integer.parseInt(raw.substring(dIdx + 1));
        } catch (Exception e) {
            logger.warn("[corpse explosion] Invalid dice format: {}", raw);
            return false;
        }

        // Scale dice count by proficiency
        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (proficiency / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        int diceTotal = 0;
        for (int i = 0; i < scaledN; i++) {
            diceTotal += java.util.concurrent.ThreadLocalRandom.current().nextInt(1, dieM + 1);
        }
        int levelBonus = def.getLevelMultiplier() * casterLevel;
        int totalDamage = diceTotal + levelBonus;

        // --- Step 5: AoE announcement ---
        String corpseName = corpse.instance.customName != null ? corpse.instance.customName : "a corpse";
        String roomMsg = casterName + " detonates " + corpseName
                + "! Necrotic shrapnel shreds everything nearby for " + totalDamage + " damage!";
        ClientHandler.broadcastRoomMessage(roomId, roomMsg);

        // --- Step 6: Apply damage to each target via effect handler ---
        boolean anyApplied = false;
        for (Integer targetId : targetIds) {
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("proficiency", String.valueOf(proficiency));
            params.put("casterLevel", String.valueOf(casterLevel));
            params.put("prerolledDamage", String.valueOf(totalDamage));

            EffectInstance inst = EffectRegistry.apply(effectId, casterId, targetId, params);
            if (inst != null) {
                anyApplied = true;
            }
        }

        // --- Step 7: Consume the corpse ---
        DaoProvider.items().deleteInstance(corpse.instance.instanceId);
        logger.debug("[corpse explosion] {} detonated corpse #{} for {} AoE damage ({} targets, dice={}, levelBonus={}, prof={}%)",
                casterName, corpse.instance.instanceId, totalDamage, targetIds.size(), diceTotal, levelBonus, proficiency);

        return anyApplied;
    }

    /**
     * Bone Armor — self-buff that grants damage reduction (melee, ranged, spell)
     * equal to half the caster's level for 24 hours.
     */
    private static boolean handleBoneArmor(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[bone armor] No context available");
            return false;
        }

        CommandContext cc = ctx.getCommandContext();
        Spell spell = ctx.getSpell();
        if (spell == null) {
            logger.warn("[bone armor] No spell definition in context");
            return false;
        }

        // Get caster level
        CharacterDAO.CharacterRecord casterRec = cc.dao.findById(casterId);
        int casterLevel = 1;
        if (casterRec != null && casterRec.currentClassId != null) {
            casterLevel = DaoProvider.classes().getCharacterClassLevel(casterId, casterRec.currentClassId);
        }
        String casterName = casterRec != null ? casterRec.name : "Someone";

        int drValue = Math.max(1, casterLevel / 2);

        // Apply effect with casterLevel as extra param
        List<String> effectIds = spell.getEffectIds();
        if (effectIds == null || effectIds.isEmpty()) {
            logger.warn("[bone armor] No effects defined for spell");
            return false;
        }

        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("casterLevel", String.valueOf(casterLevel));

        boolean anyApplied = false;
        for (String effId : effectIds) {
            EffectInstance inst = EffectRegistry.apply(effId, casterId, casterId, params);
            if (inst != null) anyApplied = true;
        }

        if (anyApplied) {
            cc.send("Spectral bones coalesce around you, forming a protective shell! (DR +" + drValue + ")");
            ClientHandler.broadcastRoomMessage(cc.currentRoomId,
                    casterName + " is encased in a shell of spectral bone.", casterId);
        }

        logger.debug("[bone armor] {} cast Bone Armor (level={}, DR={})", casterName, casterLevel, drValue);
        return anyApplied;
    }

    private static boolean handlePlague(Integer casterId, String args, SpellContext ctx) {
        return applySpellEffects(ctx, "plague");
    }
}
