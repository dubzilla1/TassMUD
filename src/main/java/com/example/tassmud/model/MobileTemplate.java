package com.example.tassmud.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Template defining a type of mobile (NPC/monster).
 * Similar to ItemTemplate - this defines the blueprint, instances are spawned from it.
 */
public class MobileTemplate {
    
    private final int id;
    private final String key;              // Unique string identifier (e.g., "goblin_warrior")
    private final String name;             // Display name (e.g., "Goblin Warrior")
    private final String shortDesc;        // Short description for room listings
    private final String longDesc;         // Full description when examined
    private final List<String> keywords;   // Keywords for targeting (e.g., "goblin", "warrior")
    
    // Base stats
    private final int level;
    private final int hpMax;
    private final int mpMax;
    private final int mvMax;
    
    // Ability scores
    private final int str;
    private final int dex;
    private final int con;
    private final int intel;
    private final int wis;
    private final int cha;
    
    // Defenses
    private final int armor;
    private final int fortitude;
    private final int reflex;
    private final int will;
    
    // Combat
    private final int baseDamage;          // Base damage dice (e.g., 6 for 1d6)
    private final int damageBonus;         // Flat damage bonus
    private final int attackBonus;         // To-hit bonus
    
    // Behaviors - a mob can have multiple behaviors (e.g., AGGRESSIVE + SHOPKEEPER)
    private final List<MobileBehavior> behaviors;
    private final int aggroRange;          // How far away mob will detect players (0 = same room only)
    
    // Rewards
    private final int experienceValue;     // XP awarded on kill
    private final int goldMin;             // Minimum gold dropped
    private final int goldMax;             // Maximum gold dropped
    
    // Spawning
    private final int respawnSeconds;      // Time to respawn after death (0 = no respawn)
    
    // Auto-combat behavior
    private final int autoflee;            // Auto-flee threshold (0-100), defaults to 0
    
    // Optional JSON for extended data (loot tables, dialogue, etc.)
    private final String templateJson;
    
    public MobileTemplate(
            int id, String key, String name, String shortDesc, String longDesc, List<String> keywords,
            int level, int hpMax, int mpMax, int mvMax,
            int str, int dex, int con, int intel, int wis, int cha,
            int armor, int fortitude, int reflex, int will,
            int baseDamage, int damageBonus, int attackBonus,
            List<MobileBehavior> behaviors, int aggroRange,
            int experienceValue, int goldMin, int goldMax,
            int respawnSeconds, int autoflee, String templateJson) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.shortDesc = shortDesc;
        this.longDesc = longDesc;
        this.keywords = keywords == null ? Collections.emptyList() : Collections.unmodifiableList(keywords);
        this.level = level;
        this.hpMax = hpMax;
        this.mpMax = mpMax;
        this.mvMax = mvMax;
        this.str = str;
        this.dex = dex;
        this.con = con;
        this.intel = intel;
        this.wis = wis;
        this.cha = cha;
        this.armor = armor;
        this.fortitude = fortitude;
        this.reflex = reflex;
        this.will = will;
        this.baseDamage = baseDamage;
        this.damageBonus = damageBonus;
        this.attackBonus = attackBonus;
        this.behaviors = behaviors == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(behaviors));
        this.aggroRange = aggroRange;
        this.experienceValue = experienceValue;
        this.goldMin = goldMin;
        this.goldMax = goldMax;
        this.respawnSeconds = respawnSeconds;
        this.autoflee = autoflee;
        this.templateJson = templateJson;
    }
    
    // Getters
    public int getId() { return id; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getShortDesc() { return shortDesc; }
    public String getLongDesc() { return longDesc; }
    public List<String> getKeywords() { return keywords; }
    public int getLevel() { return level; }
    public int getHpMax() { return hpMax; }
    public int getMpMax() { return mpMax; }
    public int getMvMax() { return mvMax; }
    public int getStr() { return str; }
    public int getDex() { return dex; }
    public int getCon() { return con; }
    public int getIntel() { return intel; }
    public int getWis() { return wis; }
    public int getCha() { return cha; }
    public int getArmor() { return armor; }
    public int getFortitude() { return fortitude; }
    public int getReflex() { return reflex; }
    public int getWill() { return will; }
    public int getBaseDamage() { return baseDamage; }
    public int getDamageBonus() { return damageBonus; }
    public int getAttackBonus() { return attackBonus; }
    public List<MobileBehavior> getBehaviors() { return behaviors; }
    public int getAggroRange() { return aggroRange; }
    public int getAutoflee() { return autoflee; }
    
    /**
     * Check if this mobile has a specific behavior.
     */
    public boolean hasBehavior(MobileBehavior behavior) {
        return behaviors.contains(behavior);
    }
    
    /**
     * Convenience method: check if mobile is aggressive.
     */
    public boolean isAggressive() {
        return hasBehavior(MobileBehavior.AGGRESSIVE);
    }
    
    /**
     * Convenience method: check if mobile is immortal.
     */
    public boolean isImmortal() {
        return hasBehavior(MobileBehavior.IMMORTAL);
    }
    
    /**
     * Convenience method: check if mobile is a shopkeeper.
     */
    public boolean isShopkeeper() {
        return hasBehavior(MobileBehavior.SHOPKEEPER);
    }
    public int getExperienceValue() { return experienceValue; }
    public int getGoldMin() { return goldMin; }
    public int getGoldMax() { return goldMax; }
    public int getRespawnSeconds() { return respawnSeconds; }
    public String getTemplateJson() { return templateJson; }
    
    /**
     * Check if a keyword matches this mobile (case-insensitive, prefix match).
     */
    public boolean matchesKeyword(String input) {
        if (input == null || input.isEmpty()) return false;
        String lower = input.toLowerCase();
        
        // Check name
        if (name.toLowerCase().startsWith(lower)) return true;
        
        // Check keywords
        for (String kw : keywords) {
            if (kw.toLowerCase().startsWith(lower)) return true;
        }
        return false;
    }
}
