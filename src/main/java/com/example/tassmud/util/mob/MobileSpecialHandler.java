package com.example.tassmud.util.mob;

import com.example.tassmud.model.Mobile;

/**
 * Functional interface for a mobile special-function handler.
 * <p>
 * A handler is registered in {@link MobileSpecialRegistry} under a key that
 * matches the {@code spec_fun} field on a {@link com.example.tassmud.model.MobileTemplate}.
 * <p>
 * The handler is invoked each combat tick (by
 * {@code CombatManager.selectMobileCommand}) or each out-of-combat tick (by
 * {@link MobileSpecialService}).  The handler returns {@code true} if it
 * consumed the mobile's action for this tick, or {@code false} to allow
 * normal processing (e.g. a basic attack) to continue.
 * <p>
 * <b>Rate control</b> — each handler owns its own trigger probability and
 * should call {@link MobileSpecialContext#chance(int)} at the top of its
 * implementation before doing any work:
 * <pre>{@code
 *   if (!ctx.chance(25)) return false; // fire ~25% of ticks
 * }</pre>
 */
@FunctionalInterface
public interface MobileSpecialHandler {

    /**
     * Attempt to execute the special behaviour for the given mobile.
     *
     * @param mob the mobile whose special is firing
     * @param ctx contextual information (room, combat, messaging callbacks, rng)
     * @return {@code true} if the special consumed the mobile's action this
     *         tick; {@code false} to fall through to default behaviour
     */
    boolean trigger(Mobile mob, MobileSpecialContext ctx);
}
