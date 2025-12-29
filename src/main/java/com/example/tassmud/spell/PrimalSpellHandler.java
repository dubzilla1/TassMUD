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
public class PrimalSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(PrimalSpellHandler.class);

    static {
        // Register ARCANE spells with the SpellRegistry. Each registration
        // captures the spell name and dispatches to our shared dispatcher.
        registerPrimal("change sex");
        registerPrimal("control weather");
        registerPrimal("create food");
        registerPrimal("create spring");
        registerPrimal("create water");
        registerPrimal("detect poison");
        registerPrimal("enchant weapon");
        registerPrimal("faerie fog");
        registerPrimal("gate");
        registerPrimal("identify");
        registerPrimal("locate object");
        registerPrimal("mass invis");
        registerPrimal("pass door");
        registerPrimal("summon");
        registerPrimal("teleport");
        registerPrimal("ventriloquate");
        registerPrimal("word of recall");
    }

    private static void registerPrimal(String spellName) {
        SpellRegistry.register(spellName, (casterId, args, ctx) -> dispatch(spellName, casterId, args, ctx), SpellSchool.PRIMAL);
    }

    private static boolean dispatch(String spellName, Integer casterId, String args, SpellContext ctx) {
        switch (spellName) {
            case "change sex": return handleChangeSex(casterId, args, ctx);
            case "control weather": return handleControlWeather(casterId, args, ctx);
            case "create food": return handleCreateFood(casterId, args, ctx);
            case "create spring": return handleCreateSpring(casterId, args, ctx);
            case "create water": return handleCreateWater(casterId, args, ctx);
            case "detect poison": return handleDetectPoison(casterId, args, ctx);
            case "enchant weapon": return handleEnchantWeapon(casterId, args, ctx);
            case "faerie fog": return handleFaerieFog(casterId, args, ctx);
            case "gate": return handleGate(casterId, args, ctx);
            case "identify": return handleIdentify(casterId, args, ctx);
            case "locate object": return handleLocateObject(casterId, args, ctx);
            case "mass invis": return handleMassInvis(casterId, args, ctx);
            case "pass door": return handlePassDoor(casterId, args, ctx);
            case "summon": return handleSummon(casterId, args, ctx);
            case "teleport": return handleTeleport(casterId, args, ctx);
            case "ventriloquate": return handleVentriloquate(casterId, args, ctx);
            case "word of recall": return handleWordOfRecall(casterId, args, ctx);
            default:
                return notImplemented(spellName, casterId, args, ctx);
        }
    }

    // --- Per-spell handler stubs ---
    private static boolean handleChangeSex(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("change sex", casterId, args, ctx);
    }
    private static boolean handleControlWeather(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("control weather", casterId, args, ctx);
    }
    private static boolean handleCreateFood(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("create food", casterId, args, ctx);
    }
    private static boolean handleCreateSpring(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("create spring", casterId, args, ctx);
    }
    private static boolean handleCreateWater(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("create water", casterId, args, ctx);
    }
    private static boolean handleDetectPoison(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("detect poison", casterId, args, ctx);
    }
    private static boolean handleEnchantWeapon(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("enchant weapon", casterId, args, ctx);
    }
    private static boolean handleFaerieFog(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("faerie fog", casterId, args, ctx);
    }
    private static boolean handleGate(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("gate", casterId, args, ctx);
    }
    private static boolean handleIdentify(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("identify", casterId, args, ctx);
    }
    private static boolean handleLocateObject(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("locate object", casterId, args, ctx);
    }
    private static boolean handleMassInvis(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("mass invis", casterId, args, ctx);
    }
    private static boolean handlePassDoor(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("pass door", casterId, args, ctx);
    }
    private static boolean handleSummon(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("summon", casterId, args, ctx);
    }
    private static boolean handleTeleport(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("teleport", casterId, args, ctx);
    }
    private static boolean handleVentriloquate(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("ventriloquate", casterId, args, ctx);
    }
    private static boolean handleWordOfRecall(Integer casterId, String args, SpellContext ctx) {
        return notImplemented("word of recall", casterId, args, ctx);
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
}
