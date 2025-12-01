package com.example.tassmud.model;

/**
 * Armor training categories.
 * Characters must have the category skill to effectively use armor in that category.
 * 
 * - CLOTH: Light robes and garments favored by mages (minimal protection, no penalty)
 * - LEATHER: Light armor for rogues and rangers (good mobility, modest protection)
 * - MAIL: Medium armor for fighters and clerics (balanced protection and mobility)
 * - PLATE: Heavy armor for warriors and paladins (maximum protection, reduced mobility)
 * - OTHER: Special armors that don't fit other categories
 */
public enum ArmorCategory {
    CLOTH("Cloth", "skill_cloth_armor"),
    LEATHER("Leather", "skill_leather_armor"),
    MAIL("Mail", "skill_mail_armor"),
    PLATE("Plate", "skill_plate_armor"),
    OTHER("Other", "skill_other_armor");
    
    private final String displayName;
    private final String skillKey;
    
    ArmorCategory(String displayName, String skillKey) {
        this.displayName = displayName;
        this.skillKey = skillKey;
    }
    
    public String getDisplayName() { return displayName; }
    
    /**
     * Get the skill key for this armor category (e.g., "cloth_armor").
     */
    public String getSkillKey() { return skillKey; }
    
    /**
     * Parse a category from a string (case-insensitive).
     */
    public static ArmorCategory fromString(String s) {
        if (s == null || s.isEmpty()) return null;
        String upper = s.toUpperCase().trim();
        try {
            return ArmorCategory.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
