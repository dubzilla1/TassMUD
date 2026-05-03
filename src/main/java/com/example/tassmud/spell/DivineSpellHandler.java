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
 * Central dispatcher for DIVINE spells. Registers handlers for all divine
 * spells and provides dedicated handle<SpellName>() methods for each.
 *
 * Currently each handler delegates to a common "not implemented" stub.
 */
public class DivineSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(DivineSpellHandler.class);

    static {
        // Register DIVINE spells with the SpellRegistry. Each registration
        // captures the spell name and dispatches to our shared dispatcher.
        registerDivine("armor");
        registerDivine("bless");
        registerDivine("continual light");
        registerDivine("cure blindness");
        registerDivine("cure critical");
        registerDivine("cure light");
        registerDivine("cure moderate wounds");
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
        registerDivine("aura of protection");
        registerDivine("holy avenger");
        registerDivine("divine fury");
        registerDivine("flame strike");
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
            case "cure moderate wounds": return handleCureModerate(casterId, args, ctx);
            case "cure poison": return handleCurePoison(casterId, args, ctx);
            case "cure serious": return handleCureSerious(casterId, args, ctx);
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
            case "aura of protection": return handleAuraOfProtection(casterId, args, ctx);
            case "holy avenger": return handleHolyAvenger(casterId, args, ctx);
            case "divine fury": return handleDivineFury(casterId, args, ctx);
            case "flame strike": return applySpellEffects(ctx, "flame strike");
            default:
                return notImplemented(spellName, casterId, args, ctx);
        }
    }

    // --- Per-spell handler stubs ---
    
    private static boolean handleArmor(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[armor] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();
        List<Integer> targets = ctx.getTargetIds();

        Integer targetId = targets.isEmpty() ? casterId : targets.get(0);
        if (targetId == null) {
            cmdCtx.send("No valid target for Armor.");
            return false;
        }

        // AC bonus = ceil(target level / 10), minimum 1
        int level = DaoProvider.characters().getPlayerLevel(targetId);
        int acBonus = Math.max(1, (int) Math.ceil(level / 10.0));

        Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
        extraParams.put("value", String.valueOf(acBonus));
        extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));

        com.example.tassmud.effect.EffectInstance inst =
                com.example.tassmud.effect.EffectRegistry.apply("1033", casterId, targetId, extraParams);

        if (inst == null) {
            cmdCtx.send("The divine armor fails to take hold.");
            return false;
        }

        CharacterDAO.CharacterRecord targetRec = DaoProvider.characters().findById(targetId);
        String targetName = targetRec != null ? targetRec.name : "the target";

        if (targetId.equals(casterId)) {
            cmdCtx.send("\u001B[36mDivine magic hardens around you, bolstering your defenses! (AC +" + acBonus + ")\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    cmdCtx.playerName + " is surrounded by a faint divine glow.", casterId);
        } else {
            cmdCtx.send("\u001B[36mYou reinforce " + targetName + " with divine armor! (AC +" + acBonus + ")\u001B[0m");
            ClientHandler.sendToCharacter(targetId,
                    "\u001B[36m" + cmdCtx.playerName + "'s divine magic hardens around you! (AC +" + acBonus + ")\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    targetName + " is surrounded by a faint divine glow.", casterId);
        }

        logger.debug("[armor] {} applied AC +{} armor to {} (level={}, proficiency={})",
                casterId, acBonus, targetId, level, ctx.getProficiency());
        return true;
    }
    private static boolean handleBless(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[bless] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();
        List<Integer> targets = ctx.getTargetIds();

        if (targets.isEmpty()) {
            cmdCtx.send("No valid targets for Bless.");
            return false;
        }

        boolean anyApplied = false;
        for (Integer targetId : targets) {
            int level = DaoProvider.characters().getPlayerLevel(targetId);
            int bonus = Math.max(1, (int) Math.ceil(level / 10.0));

            Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
            extraParams.put("value", String.valueOf(bonus));
            extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));

            com.example.tassmud.effect.EffectInstance inst =
                    com.example.tassmud.effect.EffectRegistry.apply("202", casterId, targetId, extraParams);

            if (inst != null) {
                anyApplied = true;
                CharacterDAO.CharacterRecord targetRec = DaoProvider.characters().findById(targetId);
                String targetName = targetRec != null ? targetRec.name : "the target";

                if (targetId.equals(casterId)) {
                    cmdCtx.send("\u001B[93mDivine favor fills you with battle-readiness! (To-hit +" + bonus + ")\u001B[0m");
                } else {
                    ClientHandler.sendToCharacter(targetId,
                            "\u001B[93m" + cmdCtx.playerName + "'s blessing fills you with divine purpose! (To-hit +" + bonus + ")\u001B[0m");
                    cmdCtx.send("\u001B[93mYou bless " + targetName + ". (To-hit +" + bonus + ")\u001B[0m");
                }
                logger.debug("[bless] {} applied +{} to-hit to {} (level={})", casterId, bonus, targetId, level);
            }
        }

        if (anyApplied) {
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    cmdCtx.playerName + "'s prayer calls down divine favor upon your allies.", casterId);
        }

        return anyApplied;
    }
    private static boolean handleContinualLight(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("continual light", casterId, args, ctx); }
    private static boolean handleCureBlindness(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("cure blindness", casterId, args, ctx); }
    private static boolean handleCureCritical(Integer casterId, String args, SpellContext ctx)
    { return applySpellEffects(ctx, "cure critical"); }
    private static boolean handleCureLight(Integer casterId, String args, SpellContext ctx)
    { return applySpellEffects(ctx, "cure light"); }
    private static boolean handleCureModerate(Integer casterId, String args, SpellContext ctx)
    { return applySpellEffects(ctx, "cure moderate wounds"); }
    private static boolean handleCurePoison(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[cure poison] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();
        List<Integer> targets = ctx.getTargetIds();
        Integer targetId = targets.isEmpty() ? casterId : targets.get(0);
        if (targetId == null) {
            cmdCtx.send("No valid target for Cure Poison.");
            return false;
        }

        // Remove all effects tagged both "poison" and "debuff" — captures any
        // poison DOT or poison debuff added in the future, not weapon coatings (tagged "buff").
        int removed = com.example.tassmud.effect.EffectRegistry.removeEffectsByTag(targetId, "poison", "debuff");

        CharacterDAO.CharacterRecord targetRec = DaoProvider.characters().findById(targetId);
        String targetName = targetRec != null ? targetRec.name : "the target";

        if (removed == 0) {
            if (targetId.equals(casterId)) {
                cmdCtx.send("You are not poisoned.");
            } else {
                cmdCtx.send(targetName + " is not poisoned.");
            }
            return false;
        }

        if (targetId.equals(casterId)) {
            cmdCtx.send("\u001B[32mDivine light purges the poison from your veins!\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    cmdCtx.playerName + "'s wounds glow briefly as divine power purges the poison.", casterId);
        } else {
            cmdCtx.send("\u001B[32mYou purge the poison from " + targetName + "!\u001B[0m");
            ClientHandler.sendToCharacter(targetId,
                    "\u001B[32m" + cmdCtx.playerName + "'s divine magic purges the poison from your veins!\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    targetName + "'s wounds glow briefly as " + cmdCtx.playerName + " purges the poison.", casterId);
        }

        logger.debug("[cure poison] {} removed {} poison effect(s) from {}", casterId, removed, targetId);
        return true;
    }
    private static boolean handleCureSerious(Integer casterId, String args, SpellContext ctx)
    { return applySpellEffects(ctx, "cure serious"); }
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
    private static boolean handleRefresh(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[refresh] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();
        List<Integer> targets = ctx.getTargetIds();
        Integer targetId = targets.isEmpty() ? casterId : targets.get(0);
        if (targetId == null) {
            cmdCtx.send("No valid target for Refresh.");
            return false;
        }

        // MV to restore: casterLevel * 3 + proficiency / 5, minimum 5
        int casterLevel = casterId != null ? DaoProvider.characters().getPlayerLevel(casterId) : 1;
        int proficiency = ctx.getProficiency();
        int mvAmount = Math.max(5, casterLevel * 3 + proficiency / 5);

        CharacterDAO.CharacterRecord targetRec = DaoProvider.characters().findById(targetId);
        String targetName = targetRec != null ? targetRec.name : "the target";

        // Check if target is in active combat (update live GameCharacter)
        com.example.tassmud.combat.CombatManager cm = com.example.tassmud.combat.CombatManager.getInstance();
        com.example.tassmud.combat.Combatant targetCombatant = cm.getCombatantForCharacter(targetId);

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            com.example.tassmud.model.GameCharacter ch = targetCombatant.getAsCharacter();
            int oldMv = ch.getMvCur();
            ch.setMvCur(Math.min(ch.getMvMax(), ch.getMvCur() + mvAmount));
            int actual = ch.getMvCur() - oldMv;
            // Persist
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                DaoProvider.characters().saveCharacterStateByName(
                    ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
            sendRefreshMessages(cmdCtx, casterId, targetId, targetName, actual);
        } else if (targetRec != null) {
            // Not in combat: update persisted record
            int newMv = Math.min(targetRec.mvMax, targetRec.mvCur + mvAmount);
            int actual = newMv - targetRec.mvCur;
            DaoProvider.characters().saveCharacterStateByName(
                targetRec.name, targetRec.hpCur, targetRec.mpCur, newMv, targetRec.currentRoom);
            sendRefreshMessages(cmdCtx, casterId, targetId, targetName, actual);
        } else {
            cmdCtx.send("No valid target for Refresh.");
            return false;
        }

        logger.debug("[refresh] {} restored {} MV to {} (casterLevel={}, proficiency={})",
                casterId, mvAmount, targetId, casterLevel, proficiency);
        return true;
    }

    private static void sendRefreshMessages(CommandContext cmdCtx, Integer casterId, Integer targetId,
                                            String targetName, int actual) {
        if (targetId.equals(casterId)) {
            cmdCtx.send("\u001B[36mDivine energy surges through your legs, refreshing your movement! (MV +" + actual + ")\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    cmdCtx.playerName + "'s feet glow briefly with divine light.", casterId);
        } else {
            cmdCtx.send("\u001B[36mYou refresh " + targetName + "'s movement! (MV +" + actual + ")\u001B[0m");
            ClientHandler.sendToCharacter(targetId,
                    "\u001B[36m" + cmdCtx.playerName + "'s divine magic refreshes your movement! (MV +" + actual + ")\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    targetName + "'s feet glow briefly with divine light.", casterId);
        }
    }
    private static boolean handleRemoveCurse(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("remove curse", casterId, args, ctx); }
    private static boolean handleSanctuary(Integer casterId, String args, SpellContext ctx)
    { return applySpellEffects(ctx, "sanctuary"); }
    private static boolean handleShield(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("shield", casterId, args, ctx); }
    private static boolean handleStoneSkin(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[stone skin] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();
        List<Integer> targets = ctx.getTargetIds();

        Integer targetId = targets.isEmpty() ? casterId : targets.get(0);
        if (targetId == null) {
            cmdCtx.send("No valid target for Stone Skin.");
            return false;
        }

        // DR bonus = ceil(target level / 10), minimum 1
        int level = DaoProvider.characters().getPlayerLevel(targetId);
        int drBonus = Math.max(1, (int) Math.ceil(level / 10.0));

        Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
        extraParams.put("value", String.valueOf(drBonus));
        extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));

        com.example.tassmud.effect.EffectInstance inst =
                com.example.tassmud.effect.EffectRegistry.apply("1034", casterId, targetId, extraParams);

        if (inst == null) {
            cmdCtx.send("The stone skin fails to take hold.");
            return false;
        }

        CharacterDAO.CharacterRecord targetRec = DaoProvider.characters().findById(targetId);
        String targetName = targetRec != null ? targetRec.name : "the target";

        if (targetId.equals(casterId)) {
            cmdCtx.send("\u001B[36mYour flesh hardens like stone, shrugging off physical blows! (DR +" + drBonus + ")\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    cmdCtx.playerName + "'s skin takes on a stony, hardened appearance.", casterId);
        } else {
            cmdCtx.send("\u001B[36mYou harden " + targetName + "'s flesh to the texture of stone! (DR +" + drBonus + ")\u001B[0m");
            ClientHandler.sendToCharacter(targetId,
                    "\u001B[36m" + cmdCtx.playerName + "'s divine magic hardens your flesh like stone! (DR +" + drBonus + ")\u001B[0m");
            ClientHandler.roomAnnounceFromActor(cmdCtx.currentRoomId,
                    targetName + "'s skin takes on a stony, hardened appearance.", casterId);
        }

        logger.debug("[stone skin] {} applied melee DR +{} to {} (level={}, proficiency={})",
                casterId, drBonus, targetId, level, ctx.getProficiency());
        return true;
    }

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

    private static boolean handleAuraOfProtection(Integer casterId, String args, SpellContext ctx) {
        // Aura of Protection: Level 6 DIVINE paladin spell
        // Radiates a holy aura that boosts the ARMOR stat of all PCs in the caster's room.
        // AC bonus = max(1, paladin level / 5). Duration scales with proficiency.
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[aura of protection] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();

        if (casterId == null) {
            cmdCtx.send("No caster for Aura of Protection.");
            return false;
        }

        int level = DaoProvider.characters().getPlayerLevel(casterId);
        int acBonus = Math.max(1, level / 5);

        Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
        extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));
        extraParams.put("value", String.valueOf(acBonus));

        com.example.tassmud.effect.EffectInstance inst =
                com.example.tassmud.effect.EffectRegistry.apply("1026", casterId, casterId, extraParams);

        if (inst == null) {
            cmdCtx.send("The protective aura fails to form.");
            return false;
        }

        // Messages are sent by ProtectionAuraEffect.apply().
        logger.debug("[aura of protection] {} applied AC +{} aura (level={}, proficiency={})",
                casterId, acBonus, level, ctx.getProficiency());
        return true;
    }

    private static boolean handleHolyAvenger(Integer casterId, String args, SpellContext ctx) {
        // Holy Avenger: Level 8 DIVINE paladin spell
        // Infuses the paladin with divine vengeance. Each hit while active deals
        // escalating bonus damage: 1d10 on hit #1, 2d10 on hit #2, etc.
        // Hit counter resets when the effect expires or a new cast replaces it.
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[holy avenger] No spell context provided");
            return false;
        }

        CommandContext cmdCtx = ctx.getCommandContext();

        if (casterId == null) {
            cmdCtx.send("No caster for Holy Avenger.");
            return false;
        }

        Map<String, String> extraParams = new java.util.HashMap<>(ctx.getExtraParams());
        extraParams.put("proficiency", String.valueOf(ctx.getProficiency()));

        com.example.tassmud.effect.EffectInstance inst =
                com.example.tassmud.effect.EffectRegistry.apply("1028", casterId, casterId, extraParams);

        if (inst == null) {
            cmdCtx.send("The holy light of vengeance fails to ignite within you.");
            return false;
        }

        // Messages are sent by HolyAvengerEffect.apply().
        logger.debug("[holy avenger] {} activated (proficiency={})", casterId, ctx.getProficiency());
        return true;
    }

    private static boolean handleDivineFury(Integer casterId, String args, SpellContext ctx) {
        return DivineFuryHandler.handle(casterId, args, ctx);
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
