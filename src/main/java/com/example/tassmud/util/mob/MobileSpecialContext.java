package com.example.tassmud.util.mob;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.model.Mobile;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Context bundle passed to {@link MobileSpecialHandler} when a special fires.
 * <p>
 * {@code combat} is {@code null} when the special is triggered out of combat
 * (e.g. from {@link MobileSpecialService}).  Handlers should check
 * {@code combat != null} before attempting to use combat-only helpers.
 */
public class MobileSpecialContext {

    /** The active combat instance — may be {@code null} for out-of-combat specials. */
    public final Combat combat;

    /** The room the mobile currently occupies. */
    public final int roomId;

    /** Send a message to a specific player character by ID. */
    public final BiConsumer<Integer, String> sendToPlayer;

    /** Broadcast a message to all players in the mobile's room. */
    public final BiConsumer<Integer, String> sendToRoom;

    /** Access to the combat manager for initiating / ending combat. */
    public final CombatManager combatManager;

    /** Shared random source — use for probability checks inside handlers. */
    public final ThreadLocalRandom rng;

    public MobileSpecialContext(Combat combat,
                                int roomId,
                                BiConsumer<Integer, String> sendToPlayer,
                                BiConsumer<Integer, String> sendToRoom,
                                CombatManager combatManager) {
        this.combat = combat;
        this.roomId = roomId;
        this.sendToPlayer = sendToPlayer;
        this.sendToRoom = sendToRoom;
        this.combatManager = combatManager;
        this.rng = ThreadLocalRandom.current();
    }

    /**
     * Returns {@code true} with the given probability (0–100 integer percent).
     * Convenience for in-handler rate checks: {@code if (!ctx.chance(25)) return false;}
     */
    public boolean chance(int percent) {
        return rng.nextInt(100) < percent;
    }
}
