package com.example.tassmud.spell;

import com.example.tassmud.effect.BestialWrathEffect;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.AllyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Central dispatcher for PRIMAL spells. Registers handlers for all primal
 * spells and provides dedicated handle<SpellName>() methods for each.
 *
 * Currently each handler delegates to a common "not implemented" stub,
 * except for Primal Volley which is fully implemented.
 */
public class PrimalSpellHandler {

    private static final Logger logger = LoggerFactory.getLogger(PrimalSpellHandler.class);

    static {
        // Register PRIMAL spells with the SpellRegistry.
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
        registerPrimal("primal volley");
        registerPrimal("bestial wrath");
        registerPrimal("bw");
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
            case "primal volley": return handlePrimalVolley(casterId, args, ctx);
            case "bestial wrath", "bw": return handleBestialWrath(casterId, args, ctx);
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

    // --- Primal Volley ---
    private static boolean handlePrimalVolley(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) return false;
        CommandContext cc = ctx.getCommandContext();

        if (casterId == null) {
            cc.send("You must be logged in to cast Primal Volley.");
            return false;
        }

        // Require a ranged weapon in main hand
        Long mainHandInstanceId = DaoProvider.equipment().getCharacterEquipment(casterId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            cc.send("You need a ranged weapon equipped to use Primal Volley.");
            return false;
        }

        ItemDAO itemDao = DaoProvider.items();
        ItemInstance weaponInst = itemDao.getInstance(mainHandInstanceId);
        if (weaponInst == null) {
            cc.send("You need a ranged weapon equipped to use Primal Volley.");
            return false;
        }

        ItemTemplate weaponTmpl = itemDao.getTemplateById(weaponInst.templateId);
        if (weaponTmpl == null) {
            cc.send("You need a ranged weapon equipped to use Primal Volley.");
            return false;
        }

        WeaponFamily weaponFamily = weaponTmpl.getWeaponFamily();
        if (weaponFamily == null || !weaponFamily.isRanged()) {
            cc.send("Primal Volley requires a ranged weapon (bow, crossbow, or sling).");
            return false;
        }

        // Proficiency from SpellContext (set by SpellCastHandler)
        int proficiency = ctx.getProficiency();

        EffectDefinition def = EffectRegistry.getDefinition("550");
        if (def == null) {
            cc.send("Primal Volley effect definition not found. Please report this bug.");
            logger.error("[primal volley] Effect definition '550' not found in registry");
            return false;
        }

        // Override weapon_family to match the equipped ranged weapon so
        // BasicAttackCommand.getActiveInfusion() finds it by exact family lookup
        Map<String, String> extraParams = new HashMap<>();
        extraParams.put("weapon_family", weaponFamily.name());
        extraParams.put("proficiency", String.valueOf(proficiency));

        EffectRegistry.apply("550", casterId, casterId, extraParams);

        // Duration displayed to the player (mirrors WeaponInfusionEffect scaling)
        double durationSecs = def.getDurationSeconds() * Math.max(1, proficiency) / 100.0;
        int displaySecs = Math.max(1, (int) Math.round(durationSecs));

        cc.send("\u001B[32mPrimal energy floods your " + weaponFamily.name().toLowerCase()
                + " — your shots will scatter to all enemies for " + displaySecs + " seconds!\u001B[0m");

        if (cc.playerName != null && cc.currentRoomId != null) {
            ClientHandler.roomAnnounceFromActor(cc.currentRoomId,
                    cc.playerName + "'s arrows crackle with primal energy!",
                    casterId);
        }

        // Aggro management for buff spell
        ctx.addUtilitySpellAggro();

        return true;
    }

    // --- Bestial Wrath ---
    private static boolean handleBestialWrath(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) return false;
        CommandContext cc = ctx.getCommandContext();

        if (casterId == null) {
            cc.send("You must be logged in to cast Bestial Wrath.");
            return false;
        }

        if (!AllyManager.getInstance().hasCompanion(casterId)) {
            cc.send("You must have an animal companion to use Bestial Wrath.");
            return false;
        }

        EffectDefinition def = EffectRegistry.getDefinition("551");
        if (def == null) {
            cc.send("Bestial Wrath effect definition not found. Please report this bug.");
            logger.error("[bestial wrath] Effect definition '551' not found in registry");
            return false;
        }

        EffectRegistry.apply("551", casterId, casterId, new HashMap<>());

        if (cc.playerName != null && cc.currentRoomId != null) {
            ClientHandler.roomAnnounceFromActor(cc.currentRoomId,
                    cc.playerName + " lets out a savage war cry as bestial fury takes hold!",
                    casterId);
        }

        ctx.addUtilitySpellAggro();
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
}
