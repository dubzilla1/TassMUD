package com.example.tassmud.spell;

import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Central dispatcher for ARCANE spells. Registers handlers for all arcane
 * spells and provides dedicated handle<SpellName>() methods for each.
 *
 * Currently each handler delegates to a common "not implemented" stub.
 */
public class DivineSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(DivineSpellHandler.class);

    static {
        // Register ARCANE spells with the SpellRegistry. Each registration
        // captures the spell name and dispatches to our shared dispatcher.
        registerDivine("armor");
        registerDivine("bless");
        registerDivine("continual light");
        registerDivine("cure blindness");
        registerDivine("cure critical");
        registerDivine("cure light");
        registerDivine("cure poison");
        registerDivine("cure serious");
        registerDivine("detect evil");
        registerDivine("detect hidden");
        registerDivine("detect invis");
        registerDivine("detect magic");
        registerDivine("fade");
        registerDivine("fly");
        registerDivine("giant strength");
        registerDivine("heal");
        registerDivine("infravision");
        registerDivine("invis");
        registerDivine("protection");
        registerDivine("refresh");
        registerDivine("remove curse");
        registerDivine("sanctuary");
        registerDivine("shield");
        registerDivine("stone skin");
        
        
        
    }

    private static void registerDivine(String spellName) {
        SpellRegistry.register(spellName, (casterId, args, ctx) -> dispatch(spellName, casterId, args, ctx), SpellSchool.DIVINE);
    }

    private static boolean dispatch(String spellName, Integer casterId, String args, SpellContext ctx) {
        switch (spellName) {
            case "armor": return handleArmor(casterId, args, ctx);
            case "bless": return handleBless(casterId, args, ctx);
            case "continual light": return handleContinualLight(casterId, args, ctx);
            case "cure blindness": return handleCureBlindness(casterId, args, ctx);
            case "cure critical": return handleCureCritical(casterId, args, ctx);
            case "cure light": return handleCureLight(casterId, args, ctx);
            case "cure poison": return handleCurePoison(casterId, args, ctx);
            case "cure serious": return handleCureSerious(casterId, args,ctx);
            case "detect evil": return handleDetectEvil(casterId, args, ctx);
            case "detect hidden": return handleDetectHidden(casterId, args, ctx);
            case "detect invis": return handleDetectInvis(casterId, args, ctx);
            case "detect magic": return handleDetectMagic(casterId, args, ctx);
            case "fade": return handleFade(casterId, args, ctx);
            case "fly": return handleFly(casterId, args, ctx);
            case "giant strength": return handleGiantStrength(casterId, args, ctx);
            case "heal": return handleHeal(casterId, args, ctx);
            case "infravision": return handleInfravision(casterId, args, ctx);
            case "invis": return handleInvis(casterId, args, ctx);
            case "protection": return handleProtection(casterId, args, ctx);
            case "refresh": return handleRefresh(casterId, args, ctx);
            case "remove curse": return handleRemoveCurse(casterId, args, ctx);
            case "sanctuary": return handleSanctuary(casterId, args, ctx);
            case "shield": return handleShield(casterId, args, ctx);
            case "stone skin": return handleStoneSkin(casterId, args, ctx);
            default:
                return notImplemented(spellName, casterId, args, ctx);
        }
    }

    // --- Per-spell handler stubs ---
    
    private static boolean handleArmor(Integer casterId, String args, SpellContext ctx) { return notImplemented("armor", casterId, args, ctx); }
    private static boolean handleBless(Integer casterId, String args, SpellContext ctx) { return notImplemented("bless", casterId, args, ctx); }
    private static boolean handleContinualLight(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("continual light", casterId, args, ctx); }
    private static boolean handleCureBlindness(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("cure blindness", casterId, args, ctx); }
    private static boolean handleCureCritical(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("cure critical", casterId, args, ctx); }
    private static boolean handleCureLight(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("cure light", casterId, args, ctx); }
    private static boolean handleCurePoison(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("cure poison", casterId, args, ctx); }
    private static boolean handleCureSerious(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("cure serious", casterId, args, ctx); }
    private static boolean handleDetectEvil(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("detect evil", casterId, args, ctx); }
    private static boolean handleDetectHidden(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("detect hidden", casterId, args, ctx); }
    private static boolean handleDetectInvis(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("detect invis", casterId, args, ctx); }
    private static boolean handleDetectMagic(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("detect magic", casterId, args, ctx); }
    
    private static boolean handleFade(Integer casterId, String args, SpellContext ctx) {
        // Fade: Level 2 DIVINE spell
        // - Single target aggro reduction (self or ally)
        // - Reduces aggro by 25% to 75% based on proficiency
        // - Defaults to self if no target specified
        // - Generates utility spell aggro for the caster
        
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[fade] No spell context provided");
            return false;
        }
        
        CommandContext cmdCtx = ctx.getCommandContext();
        com.example.tassmud.combat.Combat combat = ctx.getCombat();
        
        if (combat == null) {
            cmdCtx.send("Fade can only be cast during combat.");
            return false;
        }
        
        // Resolve target - defaults to caster if no target specified
        List<Integer> targets = ctx.getTargetIds();
        Integer targetId;
        if (targets.isEmpty()) {
            targetId = casterId;
        } else {
            targetId = targets.getFirst();
        }
        
        // Verify target is a player in the same combat
        com.example.tassmud.combat.Combatant targetCombatant = combat.findByCharacterId(targetId);
        if (targetCombatant == null || !targetCombatant.isPlayer()) {
            cmdCtx.send("Fade can only be cast on players.");
            return false;
        }
        
        // Calculate reduction percentage based on proficiency (25% at 1, 75% at 100)
        int proficiency = ctx.getProficiency();
        // Linear scale: 25 + (proficiency - 1) * 50 / 99 = 25 to 75
        double reductionPercent = 25.0 + (proficiency - 1) * 50.0 / 99.0;
        
        // Get current aggro and calculate new value
        long currentAggro = combat.getAggro(targetId);
        long reduction = (long)(currentAggro * reductionPercent / 100.0);
        long newAggro = currentAggro - reduction;
        
        combat.setAggro(targetId, newAggro);
        
        // Get target name for messages
        CharacterDAO dao = cmdCtx.dao;
        CharacterDAO.CharacterRecord targetRec = dao.getCharacterById(targetId);
        String targetName = targetRec != null ? targetRec.name : "the target";
        
        // Messages
        if (targetId.equals(casterId)) {
            cmdCtx.send("\u001B[36mDivine magic shrouds you, causing enemies to lose interest.\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId, 
                cmdCtx.playerName + " fades from attention as divine magic shrouds them.", casterId);
        } else {
            cmdCtx.send("\u001B[36mYou cast Fade on " + targetName + ", reducing their aggro.\u001B[0m");
            ClientHandler targetHandler = ClientHandler.charIdToSession.get(targetId);
            if (targetHandler != null) {
                targetHandler.out.println("\u001B[36m" + cmdCtx.playerName + "'s divine magic causes enemies to lose interest in you.\u001B[0m");
            }
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId, 
                targetName + " fades from attention as " + cmdCtx.playerName + "'s divine magic takes effect.", casterId);
        }
        
        // Add utility spell aggro for the caster (100x level = 200 for level 2)
        ctx.addUtilitySpellAggro();
        
        logger.debug("[fade] {} cast fade on {} - reduced aggro from {} to {} ({}% reduction)", 
            casterId, targetId, currentAggro, newAggro, (int)reductionPercent);
        
        return true;
    }
    
    private static boolean handleFly(Integer casterId, String args, SpellContext ctx) {
        // Fly: Level 3 DIVINE spell
        // - Single target buff (self or ally)
        // - Grants FLYING effect allowing movement through air/water sectors
        // - No movement point cost while flying
        // - Immunity to trip attacks
        // - Duration scales with proficiency (60s-600s)
        return applySpellEffects(ctx, "fly");
    }
    private static boolean handleGiantStrength(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("giant strength", casterId, args, ctx); }
    private static boolean handleHeal(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("heal", casterId, args, ctx); }
    private static boolean handleInfravision(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("infravision", casterId, args, ctx); }
    private static boolean handleInvis(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("invis", casterId, args, ctx); }
    private static boolean handleProtection(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("protection", casterId, args, ctx); }
    private static boolean handleRefresh(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("refresh", casterId, args, ctx); }
    private static boolean handleRemoveCurse(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("remove curse", casterId, args, ctx); }
    private static boolean handleSanctuary(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("sanctuary", casterId, args, ctx); }
    private static boolean handleShield(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("shield", casterId, args, ctx); }
    private static boolean handleStoneSkin(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("stone skin", casterId, args, ctx); }
    
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
        CommandContext cmdCtx = ctx.getCommandContext();
        CharacterDAO dao = cmdCtx != null ? cmdCtx.dao : new CharacterDAO();
        
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
