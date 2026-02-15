package com.example.tassmud.util;

/**
 * Centralized ANSI color constants and helper methods for MUD output.
 *
 * All colors are chosen for readability on a black terminal background.
 * Avoids dark blues and purples.
 *
 * Usage:
 *   Colors.room("Temple of Midgaard")   → bright-white room name
 *   Colors.exit("[Exits: north south]")  → bright-green exit line
 *   Colors.mob("A city guard")           → bright-yellow mob name
 *   Colors.prompt("<100/100hp ...>")      → cyan prompt
 *   Colors.dmgVerb("DEVASTATES", 30)     → color-scaled damage verb
 *   Colors.header("[ VITALS ]")          → bright-cyan section header
 *   Colors.border("====...")             → dark-cyan border/divider
 */
public final class Colors {

    private Colors() {} // utility class

    // ── ANSI escape sequences ──────────────────────────────────────────

    public static final String RESET       = "\u001B[0m";

    // Regular colors (readable on black bg)
    public static final String RED         = "\u001B[31m";
    public static final String GREEN       = "\u001B[32m";
    public static final String YELLOW      = "\u001B[33m";
    public static final String CYAN        = "\u001B[36m";
    public static final String WHITE       = "\u001B[37m";

    // Bright / bold colors
    public static final String BRIGHT_RED     = "\u001B[91m";
    public static final String BRIGHT_GREEN   = "\u001B[92m";
    public static final String BRIGHT_YELLOW  = "\u001B[93m";
    public static final String BRIGHT_CYAN    = "\u001B[96m";
    public static final String BRIGHT_WHITE   = "\u001B[97m";
    public static final String BOLD           = "\u001B[1m";
    public static final String BOLD_WHITE     = "\u001B[1;37m";

    // ── Semantic wrappers ──────────────────────────────────────────────

    /** Wrap a room name in bright-white bold. */
    public static String room(String text) {
        return BOLD_WHITE + text + RESET;
    }

    /** Wrap an exit line in bright-green. */
    public static String exit(String text) {
        return BRIGHT_GREEN + text + RESET;
    }

    /** Wrap a mob short-desc or name in bright-yellow. */
    public static String mob(String text) {
        return BRIGHT_YELLOW + text + RESET;
    }

    /** Wrap the player prompt in cyan. */
    public static String prompt(String text) {
        return CYAN + text + RESET;
    }

    /** Wrap a section header (e.g. "[ VITALS ]") in bright-cyan. */
    public static String header(String text) {
        return BRIGHT_CYAN + text + RESET;
    }

    /** Wrap a border/divider line in cyan. */
    public static String border(String text) {
        return CYAN + text + RESET;
    }

    // ── Damage verb coloring (scaled by damage amount) ─────────────────

    /**
     * Color a damage verb based on the damage amount.
     * Low damage  → green  (scratches, grazes)
     * Mid damage  → yellow (wounds, mauls)
     * High damage → bright-red (DEVASTATES, DECIMATES...)
     *
     * @param verb   the damage verb text
     * @param damage the damage amount (used to pick color)
     * @return ANSI-colored verb string
     */
    public static String dmgVerb(String verb, int damage) {
        if (damage <= 0) {
            return WHITE + verb + RESET;            // miss — neutral
        } else if (damage <= 3) {
            return GREEN + verb + RESET;             // scratch, graze, hit
        } else if (damage <= 13) {
            return YELLOW + verb + RESET;            // injure, wound, maul
        } else if (damage <= 30) {
            return BRIGHT_YELLOW + verb + RESET;     // maim, DEVASTATE
        } else if (damage <= 65) {
            return RED + verb + RESET;               // DECIMATE .. *DESTROY*
        } else {
            return BRIGHT_RED + verb + RESET;        // **EVISCERATE** and above
        }
    }

    // ── Plain coloring helpers ─────────────────────────────────────────

    /** Wrap text in a specific color code and reset. */
    public static String wrap(String color, String text) {
        return color + text + RESET;
    }
}
