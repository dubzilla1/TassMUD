package com.example.tassmud.model;

/**
 * Defines the combat posture of an NPC ally while bound to a player.
 *
 * <p>Behavior is set per-binding (on {@code AllyBinding}), not per-template, so the same
 * mob type can behave differently depending on how it was recruited.  A city guard
 * temporarily joining a player is PASSIVE; a summoned demon is a DEFENDER by default.
 *
 * <p>Behaviors can be extended later with command sets: e.g. HUNTER allies might
 * respond to {@code order <ally> attack <target>}, while PASSIVE allies only respond
 * to {@code order <ally> follow} and {@code order <ally> stay}.
 */
public enum AllyBehavior {

    /**
     * Non-combatant.  The ally follows the owner but will not enter combat
     * unless directly attacked.  Responds to: follow, stay, release.
     *
     * <p>Examples: a lost NPC being escorted home, a tamed beast not yet trained.
     */
    PASSIVE("Follows owner; does not join combat unless attacked"),

    /**
     * Joins combat automatically when the owner enters combat (same room).
     * Fights on the owner's alliance.  Responds to: follow, stay, release, (future) guard.
     *
     * <p>Examples: animal companion, summoned demon, animated dead, city guard.
     */
    DEFENDER("Joins combat when owner is attacked; fights on owner's side"),

    /**
     * Attacks targets on the owner's explicit command.  Also joins combat as a DEFENDER
     * when the owner is attacked.  Responds to: follow, stay, release, order attack, order kill.
     *
     * <p>Examples: a well-trained hunter's pet, a warlock's thrall.
     */
    HUNTER("Attacks on command; also defends owner in combat");

    private final String description;

    AllyBehavior(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** Parse case-insensitively, returns null on no match. */
    public static AllyBehavior fromString(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
