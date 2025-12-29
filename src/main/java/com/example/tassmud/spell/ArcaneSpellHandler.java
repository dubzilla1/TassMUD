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
public class ArcaneSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(ArcaneSpellHandler.class);

    static {
        // Register ARCANE spells with the SpellRegistry. Each registration
        // captures the spell name and dispatches to our shared dispatcher.
        registerArcane("acid blast");
        registerArcane("blindness");
        registerArcane("burning hands");
        registerArcane("call lightning");
        registerArcane("cause critical");
        registerArcane("cause light");
        registerArcane("cause serious");
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
            case "cause critical": return handleCauseCritical(casterId, args, ctx);
            case "cause light": return handleCauseLight(casterId, args, ctx);
            case "cause serious": return handleCauseSerious(casterId, args, ctx);
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
            case "acid breath": return handleAcidBreath(casterId, args, ctx);
            case "fire breath": return handleFireBreath(casterId, args, ctx);
            case "frost breath": return handleFrostBreath(casterId, args, ctx);
            case "gas breath": return handleGasBreath(casterId, args, ctx);
            case "lightning breath": return handleLightningBreath(casterId, args, ctx);
            default:
                return notImplemented(spellName, casterId, args, ctx);
        }
    }

    // --- Per-spell handler stubs ---
    private static boolean handleAcidBlast(Integer casterId, String args, SpellContext ctx) { return notImplemented("acid blast", casterId, args, ctx); }
    private static boolean handleBlindness(Integer casterId, String args, SpellContext ctx) { return notImplemented("blindness", casterId, args, ctx); }
    private static boolean handleBurningHands(Integer casterId, String args, SpellContext ctx) { return notImplemented("burning hands", casterId, args, ctx); }
    private static boolean handleCallLightning(Integer casterId, String args, SpellContext ctx) { return notImplemented("call lightning", casterId, args, ctx); }
    private static boolean handleCauseCritical(Integer casterId, String args, SpellContext ctx) { return notImplemented("cause critical", casterId, args, ctx); }
    private static boolean handleCauseLight(Integer casterId, String args, SpellContext ctx) { return notImplemented("cause light", casterId, args, ctx); }
    private static boolean handleCauseSerious(Integer casterId, String args, SpellContext ctx) { return notImplemented("cause serious", casterId, args, ctx); }
    private static boolean handleCharmPerson(Integer casterId, String args, SpellContext ctx) { return notImplemented("charm person", casterId, args, ctx); }
    private static boolean handleChillTouch(Integer casterId, String args, SpellContext ctx) { return notImplemented("chill touch", casterId, args, ctx); }
    private static boolean handleColourSpray(Integer casterId, String args, SpellContext ctx) { return notImplemented("colour spray", casterId, args, ctx); }
    private static boolean handleCurse(Integer casterId, String args, SpellContext ctx) { return notImplemented("curse", casterId, args, ctx); }
    private static boolean handleDispelEvil(Integer casterId, String args, SpellContext ctx) { return notImplemented("dispel evil", casterId, args, ctx); }
    private static boolean handleDispelMagic(Integer casterId, String args, SpellContext ctx) { return notImplemented("dispel magic", casterId, args, ctx); }
    private static boolean handleEarthquake(Integer casterId, String args, SpellContext ctx) { return notImplemented("earthquake", casterId, args, ctx); }
    private static boolean handleEnergyDrain(Integer casterId, String args, SpellContext ctx) { return notImplemented("energy drain", casterId, args, ctx); }
    private static boolean handleFaerieFire(Integer casterId, String args, SpellContext ctx) { return notImplemented("faerie fire", casterId, args, ctx); }
    private static boolean handleFireball(Integer casterId, String args, SpellContext ctx) { return notImplemented("fireball", casterId, args, ctx); }
    private static boolean handleFlamestrike(Integer casterId, String args, SpellContext ctx) { return notImplemented("flamestrike", casterId, args, ctx); }
    private static boolean handleHarm(Integer casterId, String args, SpellContext ctx) { return notImplemented("harm", casterId, args, ctx); }
    private static boolean handleKnowAlignment(Integer casterId, String args, SpellContext ctx) { return notImplemented("know alignment", casterId, args, ctx); }
    private static boolean handleLightningBolt(Integer casterId, String args, SpellContext ctx) { return notImplemented("lightning bolt", casterId, args, ctx); }
    private static boolean handleMagicMissile(Integer casterId, String args, SpellContext ctx) { return notImplemented("magic missile", casterId, args, ctx); }
    private static boolean handlePoison(Integer casterId, String args, SpellContext ctx) { return notImplemented("poison", casterId, args, ctx); }
    private static boolean handleShockingGrasp(Integer casterId, String args, SpellContext ctx) { return notImplemented("shocking grasp", casterId, args, ctx); }
    private static boolean handleSleep(Integer casterId, String args, SpellContext ctx) { return notImplemented("sleep", casterId, args, ctx); }
    private static boolean handleWeaken(Integer casterId, String args, SpellContext ctx) { return notImplemented("weaken", casterId, args, ctx); }

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
}
