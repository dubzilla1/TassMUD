package com.example.tassmud.effect;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central service for checking whether a target can resist a negative effect.
 * <p>
 * Sources register {@link ResistanceCheck} callbacks. Before any negative effect
 * is applied (via {@link EffectRegistry#apply} or {@link com.example.tassmud.combat.Combatant#addStatusFlag}),
 * callers invoke {@link #checkResistance}. If any registered check returns a
 * non-null message, the effect is blocked and the message is sent to the target.
 * <p>
 * Examples of registered sources:
 * <ul>
 *   <li>Wholeness of Body (monk skill) — resists debuffs based on proficiency</li>
 *   <li>NPC immunities (undead immune to poison/disease/sleep)</li>
 *   <li>GM immunity — blanket resistance to all negative effects</li>
 * </ul>
 */
public class EffectResistanceService {

    private static final List<ResistanceCheck> checks = new CopyOnWriteArrayList<>();

    /**
     * Callback interface for resistance sources.  Each source decides independently
     * whether the target resists the given effect category.
     */
    @FunctionalInterface
    public interface ResistanceCheck {
        /**
         * Evaluate whether the target resists an effect.
         *
         * @param targetId   character/entity ID of the target
         * @param categories set of tags describing the effect (e.g. "debuff", "mind", "magical", "physical")
         * @return a flavour message if the target resists (e.g. "Your inner calm repels the darkness!"),
         *         or {@code null} if this source does not block the effect
         */
        String check(Integer targetId, Set<String> categories);
    }

    /**
     * Register a new resistance check.  Order of registration determines evaluation order;
     * the first non-null result wins.
     */
    public static void register(ResistanceCheck check) {
        if (check != null) {
            checks.add(check);
        }
    }

    /**
     * Remove all registered checks.  Useful for tests or server restarts.
     */
    public static void clearAll() {
        checks.clear();
    }

    /**
     * Evaluate all registered resistance checks for the given target and effect categories.
     *
     * @param targetId   the target entity ID (may be null for mobs identified differently)
     * @param categories set of category tags for the effect being applied
     * @return a resistance message if any check blocks the effect, or {@code null} if the effect proceeds
     */
    public static String checkResistance(Integer targetId, Set<String> categories) {
        if (targetId == null || categories == null || categories.isEmpty()) {
            return null;
        }
        for (ResistanceCheck check : checks) {
            String msg = check.check(targetId, categories);
            if (msg != null) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Convenience overload for a single category tag.
     */
    public static String checkResistance(Integer targetId, String category) {
        if (category == null) return null;
        return checkResistance(targetId, Set.of(category));
    }

    /**
     * @return the number of registered resistance checks (useful for tests)
     */
    public static int registeredCheckCount() {
        return checks.size();
    }
}
