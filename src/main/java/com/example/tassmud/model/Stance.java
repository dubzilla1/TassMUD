package com.example.tassmud.model;

/**
 * Character stance determines their current physical position/state.
 * This affects regeneration rates and available actions.
 */
public enum Stance {
    STANDING(1, "standing"),    // Normal stance - 1% regen per tick
    SITTING(5, "sitting"),      // Resting stance - 5% regen per tick
    SLEEPING(10, "sleeping"),   // Deep rest stance - 10% regen per tick
    SWIMMING(1, "swimming"),    // In water - normal regen but movement actions differ
    FLYING(1, "flying");        // Airborne - normal regen but movement actions differ
    
    private final int regenPercent;
    private final String displayName;
    
    Stance(int regenPercent, String displayName) {
        this.regenPercent = regenPercent;
        this.displayName = displayName;
    }
    
    /**
     * Get the regeneration percentage per tick for this stance.
     * When out of combat, characters regenerate this percentage of their max HP/MP/MV.
     */
    public int getRegenPercent() {
        return regenPercent;
    }
    
    /**
     * Get the display name for this stance.
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Check if this stance allows normal movement commands.
     */
    public boolean canMove() {
        return this == STANDING || this == FLYING;
    }
    
    /**
     * Check if this stance allows combat initiation.
     */
    public boolean canInitiateCombat() {
        return this == STANDING || this == FLYING;
    }
    
    /**
     * Check if the character is resting (sitting or sleeping).
     */
    public boolean isResting() {
        return this == SITTING || this == SLEEPING;
    }
    
    /**
     * Check if the character is asleep (cannot see or respond to most stimuli).
     */
    public boolean isAsleep() {
        return this == SLEEPING;
    }
    
    /**
     * Parse a stance from a string (case-insensitive).
     * Returns STANDING as default if not found.
     */
    public static Stance fromString(String s) {
        if (s == null || s.isEmpty()) return STANDING;
        String upper = s.toUpperCase().trim();
        for (Stance stance : values()) {
            if (stance.name().equals(upper)) return stance;
        }
        // Try display name match
        for (Stance stance : values()) {
            if (stance.displayName.equalsIgnoreCase(s.trim())) return stance;
        }
        return STANDING;
    }
}
