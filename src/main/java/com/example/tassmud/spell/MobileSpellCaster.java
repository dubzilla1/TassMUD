package com.example.tassmud.spell;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.CommandParser;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.DaoProvider;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for NPC mobs to cast spells through the same SpellRegistry/handler
 * pipeline that player spells use. Eliminates duplicated spell logic in
 * spec_fun handlers.
 *
 * <p>Messages intended for the "caster" are silently discarded — mobs have no
 * player session. Target-facing messages and room announcements flow through
 * {@link com.example.tassmud.net.ClientHandler} static methods as normal,
 * using the mob's name as the actor.
 */
public final class MobileSpellCaster {

    private MobileSpellCaster() {}

    /**
     * Cast a named spell from a mobile NPC.
     *
     * @param mob         the casting mob (supplies name for room messages)
     * @param spellName   exact spell name (must match spells.yaml and SpellRegistry)
     * @param targetId    character ID of the target, or null for self-targeted spells
     * @param roomId      room where the cast occurs (used for announcements)
     * @param combat      active Combat instance, or null if out of combat
     * @param proficiency effective proficiency (1–100); affects spell scaling
     * @return true if the spell executed successfully
     */
    public static boolean cast(Mobile mob, String spellName, Integer targetId,
                               int roomId, Combat combat, int proficiency) {
        SpellHandler handler = SpellRegistry.get(spellName);
        if (handler == null) return false;

        Spell spell = DaoProvider.spells().getSpellByName(spellName);
        if (spell == null) return false;

        CommandContext mobCtx = buildMobContext(mob.getName(), roomId);

        List<Integer> targets = targetId != null
                ? Collections.singletonList(targetId)
                : Collections.emptyList();

        Map<String, String> extraParams = new HashMap<>();
        extraParams.put("proficiency", String.valueOf(proficiency));
        extraParams.put("caster_name", mob.getName());

        SpellContext ctx = new SpellContext(mobCtx, combat, null, spell, targets, extraParams);
        return handler.cast(null, "", ctx);
    }

    /**
     * Convenience overload with default 50 proficiency.
     */
    public static boolean cast(Mobile mob, String spellName, Integer targetId,
                               int roomId, Combat combat) {
        return cast(mob, spellName, targetId, roomId, combat, 50);
    }

    /**
     * Build a minimal CommandContext for mob casting. The mob's name is used as
     * {@code playerName} so that room announcement messages read naturally.
     * Output to the "caster" is routed to a no-op stream — mobs have no session.
     */
    private static CommandContext buildMobContext(String mobName, int roomId) {
        PrintWriter devNull = new PrintWriter(new OutputStream() {
            @Override public void write(int b) {}
            @Override public void write(byte[] b, int off, int len) {}
        });
        return new CommandContext(
                new CommandParser.Command("cast", ""),
                mobName,                    // playerName — used in room announcements
                null,                       // characterId — mobs have no character ID
                roomId,                     // currentRoomId — for roomAnnounceFromActor
                null,                       // character record — not needed for mob casts
                DaoProvider.characters(),   // dao — used by some handlers (e.g. handleFade)
                devNull,                    // out — discards caster-facing messages
                false,                      // isGm
                false,                      // inCombat
                null                        // handler — no session for mobs
        );
    }
}
