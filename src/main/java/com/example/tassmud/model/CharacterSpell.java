package com.example.tassmud.model;

/**
 * Represents a character's learned spell and their proficiency level.
 * Proficiency starts at 1% when learned and can progress to 100% (Mastered).
 * Similar to CharacterSkill but for spells.
 */
public class CharacterSpell {
    private final int characterId;
    private final int spellId;
    private int proficiency;  // 1-100 percentage

    public static final int MIN_PROFICIENCY = 1;
    public static final int MAX_PROFICIENCY = 100;

    public CharacterSpell(int characterId, int spellId, int proficiency) {
        this.characterId = characterId;
        this.spellId = spellId;
        this.proficiency = Math.max(MIN_PROFICIENCY, Math.min(MAX_PROFICIENCY, proficiency));
    }

    public int getCharacterId() { return characterId; }
    public int getSpellId() { return spellId; }
    
    /**
     * Get proficiency level (1-100%).
     */
    public int getProficiency() { return proficiency; }
    
    /**
     * Set proficiency level (clamped to 1-100).
     */
    public void setProficiency(int proficiency) { 
        this.proficiency = Math.max(MIN_PROFICIENCY, Math.min(MAX_PROFICIENCY, proficiency));
    }
    
    /**
     * Check if spell is mastered (100%).
     */
    public boolean isMastered() {
        return proficiency >= MAX_PROFICIENCY;
    }
    
    /**
     * Get a display string for the proficiency level.
     */
    public String getProficiencyDisplay() {
        if (proficiency >= 100) return "Mastered";
        if (proficiency >= 90) return "Expert (" + proficiency + "%)";
        if (proficiency >= 75) return "Adept (" + proficiency + "%)";
        if (proficiency >= 50) return "Skilled (" + proficiency + "%)";
        if (proficiency >= 25) return "Familiar (" + proficiency + "%)";
        return "Novice (" + proficiency + "%)";
    }
    
    /**
     * Calculate effective casting time based on proficiency.
     * Higher proficiency = faster casting (up to 50% reduction at mastery).
     */
    public double getEffectiveCastingTime(double baseCastingTime) {
        // At 1% proficiency: 100% of base time
        // At 100% proficiency: 50% of base time
        double reduction = (proficiency - 1) / 198.0; // 0 at 1%, 0.5 at 100%
        return baseCastingTime * (1.0 - reduction);
    }
    
    // Legacy compatibility - getLevel/setLevel map to proficiency
    @Deprecated
    public int getLevel() { return proficiency; }
    @Deprecated
    public void setLevel(int level) { setProficiency(level); }
}
