package com.example.tassmud.model;

import java.util.Map;

/**
 * Cardinal and vertical movement directions in the MUD world.
 * Each direction has a full name (e.g., "north"), a short alias (e.g., "n"),
 * and a DB column suffix (e.g., "n" for exit_n).
 */
public enum Direction {
    NORTH("north", "n"),
    EAST("east",   "e"),
    SOUTH("south", "s"),
    WEST("west",   "w"),
    UP("up",       "u"),
    DOWN("down",   "d");

    private final String fullName;
    private final String shortName;

    Direction(String fullName, String shortName) {
        this.fullName = fullName;
        this.shortName = shortName;
    }

    /** Full lowercase name: "north", "east", etc. */
    public String fullName()  { return fullName; }

    /** Single-char alias: "n", "e", etc. */
    public String shortName() { return shortName; }

    /** DB column suffix: same as shortName. Used to build "exit_n", "exit_e", etc. */
    public String columnSuffix() { return shortName; }

    /** Returns the opposite direction (NORTH↔SOUTH, EAST↔WEST, UP↔DOWN). */
    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST  -> WEST;
            case WEST  -> EAST;
            case UP    -> DOWN;
            case DOWN  -> UP;
        };
    }

    // Pre-built lookup table for fromString()
    private static final Map<String, Direction> LOOKUP = buildLookup();

    private static Map<String, Direction> buildLookup() {
        java.util.Map<String, Direction> m = new java.util.HashMap<>();
        for (Direction d : values()) {
            m.put(d.fullName, d);
            m.put(d.shortName, d);
            m.put(d.name(), d); // e.g. "NORTH"
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Parse a direction string (case-insensitive).
     * Accepts full names ("north"), short names ("n"), or enum names ("NORTH").
     * @return the Direction, or null if unrecognized
     */
    public static Direction fromString(String s) {
        if (s == null) return null;
        return LOOKUP.get(s.toLowerCase());
    }
}
