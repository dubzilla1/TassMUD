package com.example.tassmud.model;

/**
 * Controls how long an ally relationship survives and what happens on death.
 *
 * <p>This is a property of the <em>binding</em>, not the mob template. The same
 * mob could be a TEMPORARY city guard or a PERMANENT undead thrall depending on
 * how the relationship was formed.
 */
public enum AllyPersistence {

    /**
     * The binding ends when the ally dies.
     *
     * <p>Used for: temporary protection (city guard), summoned creatures with
     * limited duration, tamed animals before a pet bond is established.
     */
    TEMPORARY("Ally relationship ends when the ally dies"),

    /**
     * The ally respawns and re-bonds to the owner after death (via the normal
     * respawn cycle).  The binding is kept in {@code AllyManager} across the
     * ally's death so it can be re-attached when the mob respawns.
     *
     * <p>Used for: Ranger animal companions, Warlock demons.
     * Requires the mob template to have a non-zero respawn interval.
     */
    PERMANENT("Ally respawns and re-bonds to owner after death");

    private final String description;

    AllyPersistence(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static AllyPersistence fromString(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
