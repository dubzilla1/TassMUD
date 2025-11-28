package com.example.tassmud.combat;

/**
 * Represents the current state of a combat encounter.
 */
public enum CombatState {
    
    /** Combat is initializing (gathering combatants, setting up) */
    INITIALIZING("Initializing"),
    
    /** Combat is actively running rounds */
    ACTIVE("Active"),
    
    /** Combat is paused (for future use - e.g., dialogue, cutscene) */
    PAUSED("Paused"),
    
    /** Combat has ended (all hostiles dead, fled, or otherwise resolved) */
    ENDED("Ended");
    
    private final String displayName;
    
    CombatState(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
