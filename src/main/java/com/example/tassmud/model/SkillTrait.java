package com.example.tassmud.model;

/**
 * Traits that can be assigned to skills.
 * A skill can have multiple traits that define when and how it can be used.
 */
public enum SkillTrait {
    
    /** Skill can only be used while in combat */
    COMBAT("Can only be used in combat"),
    
    /** Skill can only be used while NOT in combat */
    NOCOMBAT("Can only be used outside of combat"),
    
    /** Skill requires a shield to be equipped in off-hand */
    SHIELD("Requires a shield equipped"),
    
    /** 
     * Skill is innate - automatically known by all characters and mobs at 100% proficiency.
     * These are basic combat maneuvers like Stand (from prone) that don't require training.
     * Innate skills typically don't show in the skills list and don't progress.
     */
    INNATE("Innate ability - automatically known at 100%");
    
    private final String description;
    
    SkillTrait(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Parse a trait from a string, case-insensitive.
     */
    public static SkillTrait fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            return SkillTrait.valueOf(str.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
