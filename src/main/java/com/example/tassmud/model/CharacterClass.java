package com.example.tassmud.model;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Represents a character class (e.g., Fighter, Wizard, Cleric).
 * Characters gain levels in classes and earn bonuses per level.
 */
public class CharacterClass {
    public final int id;
    public final String name;
    public final String description;
    public final int hpPerLevel;   // bonus HP gained per level
    public final int mpPerLevel;   // bonus MP gained per level
    public final int mvPerLevel;   // bonus MV gained per level
    
    // Skills available at each level (list of ClassSkillGrant)
    private final List<ClassSkillGrant> skillGrants;
    
    public static final int MAX_NORMAL_LEVEL = 50;
    public static final int MAX_HERO_LEVEL = 55;  // 5 hero levels (51-55)
    public static final int XP_PER_LEVEL = 1000;
    
    public CharacterClass(int id, String name, String description, 
                          int hpPerLevel, int mpPerLevel, int mvPerLevel,
                          List<ClassSkillGrant> skillGrants) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.hpPerLevel = hpPerLevel;
        this.mpPerLevel = mpPerLevel;
        this.mvPerLevel = mvPerLevel;
        this.skillGrants = skillGrants != null ? new ArrayList<>(skillGrants) : new ArrayList<>();
    }
    
    /**
     * Get all skill grants for this class.
     */
    public List<ClassSkillGrant> getSkillGrants() {
        return Collections.unmodifiableList(skillGrants);
    }
    
    /**
     * Get skills available at or before a given level.
     */
    public List<ClassSkillGrant> getSkillsAtLevel(int level) {
        List<ClassSkillGrant> result = new ArrayList<>();
        for (ClassSkillGrant grant : skillGrants) {
            if (grant.classLevel <= level) {
                result.add(grant);
            }
        }
        return result;
    }
    
    /**
     * Get skills that unlock exactly at a given level.
     */
    public List<ClassSkillGrant> getSkillsUnlockedAtLevel(int level) {
        List<ClassSkillGrant> result = new ArrayList<>();
        for (ClassSkillGrant grant : skillGrants) {
            if (grant.classLevel == level) {
                result.add(grant);
            }
        }
        return result;
    }
    
    /**
     * Calculate total HP bonus for a given class level.
     */
    public int getTotalHpBonus(int level) {
        return hpPerLevel * level;
    }
    
    /**
     * Calculate total MP bonus for a given class level.
     */
    public int getTotalMpBonus(int level) {
        return mpPerLevel * level;
    }
    
    /**
     * Calculate total MV bonus for a given class level.
     */
    public int getTotalMvBonus(int level) {
        return mvPerLevel * level;
    }
    
    /**
     * Check if a level is a hero level (51-55).
     */
    public static boolean isHeroLevel(int level) {
        return level > MAX_NORMAL_LEVEL && level <= MAX_HERO_LEVEL;
    }
    
    /**
     * Calculate XP required to reach a given level from level 1.
     */
    public static int xpRequiredForLevel(int level) {
        if (level <= 1) return 0;
        return (level - 1) * XP_PER_LEVEL;
    }
    
    /**
     * Calculate level from total XP earned.
     */
    public static int levelFromXp(int totalXp) {
        int level = (totalXp / XP_PER_LEVEL) + 1;
        return Math.min(level, MAX_HERO_LEVEL);
    }
    
    @Override
    public String toString() {
        return name + " (ID: " + id + ")";
    }
    
    /**
     * Represents a skill or spell granted by a class at a specific level.
     * Either skillId or spellId will be set, but not both.
     */
    public static class ClassSkillGrant {
        public final int classId;
        public final int classLevel;
        public final int skillId;  // 0 if this is a spell grant
        public final int spellId;  // 0 if this is a skill grant
        
        /** Constructor for skill grants */
        public ClassSkillGrant(int classId, int classLevel, int skillId) {
            this.classId = classId;
            this.classLevel = classLevel;
            this.skillId = skillId;
            this.spellId = 0;
        }
        
        /** Constructor for spell grants */
        public ClassSkillGrant(int classId, int classLevel, int skillId, int spellId) {
            this.classId = classId;
            this.classLevel = classLevel;
            this.skillId = skillId;
            this.spellId = spellId;
        }
        
        public boolean isSpellGrant() {
            return spellId > 0;
        }
        
        public boolean isSkillGrant() {
            return skillId > 0;
        }
    }
}
