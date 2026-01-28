package com.example.tassmud.spell;

import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.Spell;
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
            CharacterClassDAO classDao = new CharacterClassDAO();
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
}
