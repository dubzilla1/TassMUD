package com.example.tassmud.model;

/**
 * Traits that can be assigned to spells.
 * A spell can have multiple traits that define when and how it can be used.
 */
public enum SpellTrait {
    
    /** Spell can only be used while in combat */
    COMBAT("Can only be used in combat"),
    
    /** Spell can only be used while NOT in combat */
    NOCOMBAT("Can only be used outside of combat");
    
    private final String description;
    
    SpellTrait(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Parse a trait from a string, case-insensitive.
     */
    public static SpellTrait fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            return SpellTrait.valueOf(str.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
