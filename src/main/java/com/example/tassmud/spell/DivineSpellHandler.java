package com.example.tassmud.spell;

import com.example.tassmud.net.commands.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static boolean handleFly(Integer casterId, String args, SpellContext ctx)
    { return notImplemented("fly", casterId, args, ctx); }
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
}
