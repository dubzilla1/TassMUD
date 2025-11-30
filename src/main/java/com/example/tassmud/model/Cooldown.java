package com.example.tassmud.model;

/**
 * Lightweight cooldown entry for a single ability.
 * Tracks the type (skill/spell), ability ID, and remaining time in seconds.
 * 
 * Remaining time is stored as a double to preserve sub-second precision
 * when decremented by tick intervals.
 */
public class Cooldown {
    private final CooldownType type;
    private final int abilityId;
    private double remainingSeconds;
    
    public Cooldown(CooldownType type, int abilityId, double remainingSeconds) {
        this.type = type;
        this.abilityId = abilityId;
        this.remainingSeconds = Math.max(0, remainingSeconds);
    }
    
    public CooldownType getType() { return type; }
    public int getAbilityId() { return abilityId; }
    public double getRemainingSeconds() { return remainingSeconds; }
    
    /**
     * Decrement the remaining cooldown by the given delta (in seconds).
     * @param deltaSeconds time elapsed since last tick
     * @return true if cooldown has expired (reached 0)
     */
    public boolean tick(double deltaSeconds) {
        remainingSeconds = Math.max(0, remainingSeconds - deltaSeconds);
        return remainingSeconds <= 0;
    }
    
    /**
     * Check if this cooldown has expired.
     */
    public boolean isExpired() {
        return remainingSeconds <= 0;
    }
    
    /**
     * Generate a unique key for this cooldown (type + ability ID).
     */
    public String getKey() {
        return type.name() + ":" + abilityId;
    }
    
    /**
     * Create a key for looking up a cooldown by type and ability ID.
     */
    public static String makeKey(CooldownType type, int abilityId) {
        return type.name() + ":" + abilityId;
    }
}
