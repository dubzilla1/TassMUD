package com.example.tassmud.model;

/**
 * Weapon families within each category.
 * Using a weapon builds proficiency in that specific family.
 * 
 * Simple weapons:
 * - DAGGERS: Small bladed weapons (dagger, throwing knives)
 * - CLUBS: Blunt one-handed weapons (club, mace)
 * - STAVES: Two-handed wooden weapons (quarterstaff, wizard staff)
 * - SLINGS: Simple ranged weapons (sling)
 * - CROSSBOWS: Mechanical ranged weapons (crossbow)
 * 
 * Martial weapons:
 * - SWORDS: Bladed weapons (shortsword, longsword, greatsword, rapier, scimitar, bastard sword)
 * - AXES: Chopping weapons (hand axe, battle axe, greataxe)
 * - GAUNTLETS: Fist weapons (spiked gauntlet, etc.)
 * - BOWS: Draw-string ranged weapons (shortbow, longbow)
 * 
 * Exotic weapons:
 * - GLAIVES: Polearm weapons (glaive, halberd, pike, spear, trident)
 * - FLAILS: Chain weapons (flail, morningstar, whip)
 * - OTHER: Unique weapons that don't fit elsewhere (warhammer, maul, sickle, scythe)
 */
public enum WeaponFamily {
    // Simple weapon families
    DAGGERS(WeaponCategory.SIMPLE, "Daggers", "skill_daggers"),
    CLUBS(WeaponCategory.SIMPLE, "Clubs", "skill_clubs"),
    STAVES(WeaponCategory.SIMPLE, "Staves", "skill_staves"),
    SLINGS(WeaponCategory.SIMPLE, "Slings", "skill_slings"),
    CROSSBOWS(WeaponCategory.SIMPLE, "Crossbows", "skill_crossbows"),
    
    // Martial weapon families
    SWORDS(WeaponCategory.MARTIAL, "Swords", "skill_swords"),
    AXES(WeaponCategory.MARTIAL, "Axes", "skill_axes"),
    GAUNTLETS(WeaponCategory.MARTIAL, "Gauntlets", "skill_gauntlets"),
    BOWS(WeaponCategory.MARTIAL, "Bows", "skill_bows"),
    
    // Exotic weapon families
    GLAIVES(WeaponCategory.EXOTIC, "Glaives", "skill_glaives"),
    FLAILS(WeaponCategory.EXOTIC, "Flails", "skill_flails"),
    OTHER(WeaponCategory.EXOTIC, "Other", "skill_exotic_other");
    
    private final WeaponCategory category;
    private final String displayName;
    private final String skillKey;
    
    WeaponFamily(WeaponCategory category, String displayName, String skillKey) {
        this.category = category;
        this.displayName = displayName;
        this.skillKey = skillKey;
    }
    
    public WeaponCategory getCategory() { return category; }
    public String getDisplayName() { return displayName; }
    
    /**
     * Check if this weapon family is ranged.
     * Ranged weapons are: SLINGS, CROSSBOWS, BOWS
     */
    public boolean isRanged() {
        return this == SLINGS || this == CROSSBOWS || this == BOWS;
    }
    
    /**
     * Check if this weapon family is melee (not ranged).
     */
    public boolean isMelee() {
        return !isRanged();
    }
    
    /**
     * Get the skill key for this weapon family (e.g., "skill_swords").
     */
    public String getSkillKey() { return skillKey; }
    
    /**
     * Parse a family from a string (case-insensitive).
     */
    public static WeaponFamily fromString(String s) {
        if (s == null || s.isEmpty()) return null;
        String upper = s.toUpperCase().trim();
        try {
            return WeaponFamily.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * Get all families in a specific category.
     */
    public static WeaponFamily[] getFamiliesInCategory(WeaponCategory category) {
        return java.util.Arrays.stream(values())
            .filter(f -> f.category == category)
            .toArray(WeaponFamily[]::new);
    }
}
