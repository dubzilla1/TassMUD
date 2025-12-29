package com.example.tassmud.model;

/**
 * Weapon training categories.
 * Characters must have the category skill to effectively use weapons in that category.
 * 
 * - SIMPLE: Basic weapons anyone can learn (daggers, clubs, staves, slings, crossbows)
 * - MARTIAL: Combat weapons requiring martial training (swords, axes, gauntlets, bows)
 * - EXOTIC: Specialized weapons requiring dedicated training (glaives, flails, other)
 */
public enum WeaponCategory {
    SIMPLE("Simple", "simple_weapons"),
    MARTIAL("Martial", "martial_weapons"),
    EXOTIC("Exotic", "exotic_weapons"),
    MAGICAL("Magical", "magical_weapons");
    
    private final String displayName;
    private final String skillKey;
    
    WeaponCategory(String displayName, String skillKey) {
        this.displayName = displayName;
        this.skillKey = skillKey;
    }
    
    public String getDisplayName() { return displayName; }
    
    /**
     * Get the skill key for this weapon category (e.g., "simple_weapons").
     */
    public String getSkillKey() { return skillKey; }
    
    /**
     * Parse a category from a string (case-insensitive).
     */
    public static WeaponCategory fromString(String s) {
        if (s == null || s.isEmpty()) return null;
        String upper = s.toUpperCase().trim();
        try {
            return WeaponCategory.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
