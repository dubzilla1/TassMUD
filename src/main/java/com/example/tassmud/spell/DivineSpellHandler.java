package com.example.tassmud.spell;

import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
        registerDivine("regen");
        registerDivine("infravision");
        registerDivine("invis");
        registerDivine("protection");
        registerDivine("refresh");
        registerDivine("remove curse");
        registerDivine("sanctuary");
        registerDivine("shield");
        registerDivine("stone skin");
        registerDivine("divine intervention");
        registerDivine("smite");
        registerDivine("divine shield");
        registerDivine("holy weapon");
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
            case "regen": return handleRegen(casterId, args, ctx);
            case "infravision": return handleInfravision(casterId, args, ctx);
            case "invis": return handleInvis(casterId, args, ctx);
            case "protection": return handleProtection(casterId, args, ctx);
            case "refresh": return handleRefresh(casterId, args, ctx);
            case "remove curse": return handleRemoveCurse(casterId, args, ctx);
            case "sanctuary": return handleSanctuary(casterId, args, ctx);
            case "shield": return handleShield(casterId, args, ctx);
            case "stone skin": return handleStoneSkin(casterId, args, ctx);
            case "divine intervention": return handleDivineIntervention(casterId, args, ctx);
            case "smite": return handleSmite(casterId, args, ctx);
            case "divine shield": return handleDivineShield(casterId, args, ctx);
            case "holy weapon": return handleHolyWeapon(casterId, args, ctx);
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
            targetId = targets.get(0);
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
    private static boolean handleRegen(Integer casterId, String args, SpellContext ctx)
    { return applySpellEffects(ctx, "regen"); }
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
    { return applySpellEffects(ctx, "sanctuary"); }
    private static boolean handleShield(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("shield", casterId, args, ctx); }
    private static boolean handleStoneSkin(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("stone skin", casterId, args, ctx); }

    private static boolean handleDivineIntervention(Integer casterId, String args, SpellContext ctx) {
        // Divine Intervention: Level 9 DIVINE capstone, out-of-combat only (NOCOMBAT trait)
        // Places a 24-hour death-save ward on a target player.
        // Only one ward per caster; re-casting on a new target displaces the old one.
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[divine intervention] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();

        // Resolve target: the spell target type is TARGET (player by name), defaults to self
        List<Integer> targetIds = ctx.getTargetIds();
        Integer targetId;
        String targetName;

        if (targetIds.isEmpty()) {
            // Default to self
            targetId = casterId;
        } else {
            targetId = targetIds.get(0);
        }

        if (targetId == null) {
            cmdCtx.send("You must specify a target for Divine Intervention.");
            return false;
        }

        // Look up target record
        CharacterDAO.CharacterRecord targetRec =
            com.example.tassmud.persistence.DaoProvider.characters().findById(targetId);
        if (targetRec == null) {
            cmdCtx.send("No such player found.");
            return false;
        }
        targetName = targetRec.name;

        // Verify target is in the same room (or is self)
        if (!targetId.equals(casterId)) {
            com.example.tassmud.net.ClientHandler targetSession =
                com.example.tassmud.net.ClientHandler.charIdToSession.get(targetId);
            if (targetSession == null || !Integer.valueOf(cmdCtx.currentRoomId).equals(targetSession.currentRoomId)) {
                cmdCtx.send(targetName + " is not in the room with you.");
                return false;
            }
        }

        // Build extra params (proficiency forwarded from context)
        java.util.Map<String, String> extraParams = new java.util.HashMap<>();
        int proficiency = ctx.getProficiency();
        extraParams.put("proficiency", String.valueOf(proficiency));
        if (casterId != null) extraParams.put("casterId", casterId.toString());

        // Apply the effect
        com.example.tassmud.effect.EffectInstance inst =
            com.example.tassmud.effect.EffectRegistry.apply(
                com.example.tassmud.effect.DivineInterventionEffect.EFFECT_DEF_ID,
                casterId, targetId, extraParams);

        if (inst == null) {
            cmdCtx.send("The divine ward failed to take hold.");
            return false;
        }

        // Messages
        if (targetId.equals(casterId)) {
            cmdCtx.send("\u001B[93mYou invoke the power of your deity. A divine ward now shields your life!\u001B[0m");
            com.example.tassmud.net.ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                cmdCtx.playerName + " is surrounded by a faint divine light.", casterId);
        } else {
            cmdCtx.send("\u001B[93mYou invoke the power of your deity, placing a divine ward upon " + targetName + "!\u001B[0m");
            com.example.tassmud.net.ClientHandler.sendToCharacter(targetId,
                "\u001B[93m" + cmdCtx.playerName + " places a divine ward upon you! An Angel stands ready to intercept death.\u001B[0m");
            com.example.tassmud.net.ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                cmdCtx.playerName + " surrounds " + targetName + " with a faint divine light.", casterId);
        }

        logger.debug("[divine intervention] Cast by {} on {} (proficiency={})", casterId, targetId, proficiency);
        return true;
    }
    
    private static boolean handleSmite(Integer casterId, String args, SpellContext ctx) {
        // Smite: Level 3 DIVINE paladin spell
        // Randomly applies one of 4 debuffs to the current combat target:
        //   slow (1009), confused (1010), paralyzed (1011), or cursed (1012)
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[smite] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();
        List<Integer> targets = ctx.getTargetIds();

        if (targets.isEmpty()) {
            cmdCtx.send("You must be in combat to use Smite.");
            return false;
        }

        Integer targetId = targets.get(0);

        // Randomly pick one of 4 divine judgments
        String[] effectOptions  = {"1009", "1010", "1011", "1012"};
        String[] casterMessages = {
            "Divine power arrests your foe's movements!",
            "Holy light fractures your foe's concentration!",
            "Sacred energy locks your foe in place!",
            "A divine curse descends upon your foe!"
        };
        String[] targetMessages = {
            "Divine power arrests your movements!",
            "Holy light fractures your concentration!",
            "Sacred energy locks you in place!",
            "A divine curse descends upon you!"
        };
        String[] roomSuffixes = {
            "slowing their movements.",
            "leaving them confused.",
            "holding them in place.",
            "cursing them with divine wrath."
        };

        int roll = ThreadLocalRandom.current().nextInt(effectOptions.length);
        String chosenEffect = effectOptions[roll];

        Map<String, String> extraParams = ctx.getExtraParams();
        EffectInstance inst = EffectRegistry.apply(chosenEffect, casterId, targetId, extraParams);

        if (inst == null) {
            cmdCtx.send("Your divine smite fails to take hold.");
            return false;
        }

        CharacterDAO.CharacterRecord targetRec = DaoProvider.characters().findById(targetId);
        String targetName = targetRec != null ? targetRec.name : "your foe";

        cmdCtx.send("\u001B[93mYou call down divine judgment upon " + targetName + "! " + casterMessages[roll] + "\u001B[0m");
        ClientHandler.sendToCharacter(targetId,
            "\u001B[93m" + cmdCtx.playerName + " calls down divine judgment upon you! " + targetMessages[roll] + "\u001B[0m");
        ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
            cmdCtx.playerName + " calls down divine judgment upon " + targetName + ", " + roomSuffixes[roll],
            casterId);

        logger.debug("[smite] {} applied effect {} to {}", casterId, chosenEffect, targetId);
        return true;
    }

    private static boolean handleDivineShield(Integer casterId, String args, SpellContext ctx) {
        // Divine Shield: Level 5 DIVINE paladin spell
        // Wraps the caster in a radiant AC buff that scales with paladin class level / 5.
        // Duration scales with proficiency (120s * proficiency/100).
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[divine shield] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();

        if (casterId == null) {
            cmdCtx.send("No caster for Divine Shield.");
            return false;
        }

        // Compute AC bonus from paladin class level
        int level = DaoProvider.characters().getPlayerLevel(casterId);
        int acBonus = Math.max(1, level / 5);

        // Build extraParams: override value with dynamic AC bonus and forward proficiency
        Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
        extraParams.put("value", String.valueOf(acBonus));
        extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));

        EffectInstance inst = EffectRegistry.apply("1024", casterId, casterId, extraParams);

        if (inst == null) {
            cmdCtx.send("The divine shield fails to form around you.");
            return false;
        }

        cmdCtx.send("\u001B[93mRadiant holy energy flows around you, hardening into a divine shield! (AC +" + acBonus + ")\u001B[0m");
        ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
            cmdCtx.playerName + " is surrounded by a shimmering radiant barrier.", casterId);

        logger.debug("[divine shield] {} applied AC +{} (level={}, proficiency={})",
            casterId, acBonus, level, ctx.getProficiency());
        return true;
    }

    private static boolean handleHolyWeapon(Integer casterId, String args, SpellContext ctx) {
        // Holy Weapon: Level 7 DIVINE paladin spell
        // Applies a MODIFIER effect (CRITICAL_THRESHOLD_BONUS -1) to the caster,
        // widening the critical hit range by 1. While active, every critical hit
        // also triggers a free Smite (random debuff: slow/confused/paralyzed/cursed)
        // via CombatManager.tryHolyWeaponSmite().
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[holy weapon] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();

        if (casterId == null) {
            cmdCtx.send("No caster for Holy Weapon.");
            return false;
        }

        Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
        extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));

        EffectInstance inst = EffectRegistry.apply("1025", casterId, casterId, extraParams);

        if (inst == null) {
            cmdCtx.send("The divine blessing fails to take hold.");
            return false;
        }

        cmdCtx.send("\u001B[1;93mYour weapon blazes with divine radiance! Your strikes are honed to deadly precision, and each perfect blow will call down holy judgment.\u001B[0m");
        ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
            cmdCtx.playerName + "'s weapon blazes with divine radiance!", casterId);

        logger.debug("[holy weapon] {} applied holy weapon crit bonus (proficiency={})", casterId, ctx.getProficiency());
        return true;
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
