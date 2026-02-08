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
    
    // Ability scores & defenses (shared stat block)
    private final StatBlock stats;
    
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
    
    /** Creates a new builder for constructing MobileTemplate instances. */
    public static Builder builder() { return new Builder(); }

    MobileTemplate(
            int id, String key, String name, String shortDesc, String longDesc, List<String> keywords,
            int level, int hpMax, int mpMax, int mvMax,
            StatBlock stats,
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
        this.stats = stats;
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
    public int getStr() { return stats.str(); }
    public int getDex() { return stats.dex(); }
    public int getCon() { return stats.con(); }
    public int getIntel() { return stats.intel(); }
    public int getWis() { return stats.wis(); }
    public int getCha() { return stats.cha(); }
    public int getArmor() { return stats.armor(); }
    public int getFortitude() { return stats.fortitude(); }
    public int getReflex() { return stats.reflex(); }
    public int getWill() { return stats.will(); }
    /** Returns the immutable stat block for this template. */
    public StatBlock getStats() { return stats; }
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

    /** Fluent builder for {@link MobileTemplate}. */
    public static class Builder {
        private int id;
        private String key;
        private String name;
        private String shortDesc;
        private String longDesc;
        private List<String> keywords;
        private int level;
        private int hpMax;
        private int mpMax;
        private int mvMax;
        private int str, dex, con, intel, wis, cha;
        private int armor, fortitude, reflex, will;
        private int baseDamage, damageBonus, attackBonus;
        private List<MobileBehavior> behaviors;
        private int aggroRange;
        private int experienceValue;
        private int goldMin, goldMax;
        private int respawnSeconds;
        private int autoflee;
        private String templateJson;

        private Builder() {}

        public Builder id(int v) { this.id = v; return this; }
        public Builder key(String v) { this.key = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder shortDesc(String v) { this.shortDesc = v; return this; }
        public Builder longDesc(String v) { this.longDesc = v; return this; }
        public Builder keywords(List<String> v) { this.keywords = v; return this; }
        public Builder level(int v) { this.level = v; return this; }
        public Builder hpMax(int v) { this.hpMax = v; return this; }
        public Builder mpMax(int v) { this.mpMax = v; return this; }
        public Builder mvMax(int v) { this.mvMax = v; return this; }
        public Builder str(int v) { this.str = v; return this; }
        public Builder dex(int v) { this.dex = v; return this; }
        public Builder con(int v) { this.con = v; return this; }
        public Builder intel(int v) { this.intel = v; return this; }
        public Builder wis(int v) { this.wis = v; return this; }
        public Builder cha(int v) { this.cha = v; return this; }
        public Builder armor(int v) { this.armor = v; return this; }
        public Builder fortitude(int v) { this.fortitude = v; return this; }
        public Builder reflex(int v) { this.reflex = v; return this; }
        public Builder will(int v) { this.will = v; return this; }
        public Builder baseDamage(int v) { this.baseDamage = v; return this; }
        public Builder damageBonus(int v) { this.damageBonus = v; return this; }
        public Builder attackBonus(int v) { this.attackBonus = v; return this; }
        public Builder behaviors(List<MobileBehavior> v) { this.behaviors = v; return this; }
        public Builder aggroRange(int v) { this.aggroRange = v; return this; }
        public Builder experienceValue(int v) { this.experienceValue = v; return this; }
        public Builder goldMin(int v) { this.goldMin = v; return this; }
        public Builder goldMax(int v) { this.goldMax = v; return this; }
        public Builder respawnSeconds(int v) { this.respawnSeconds = v; return this; }
        public Builder autoflee(int v) { this.autoflee = v; return this; }
        public Builder templateJson(String v) { this.templateJson = v; return this; }

        public MobileTemplate build() {
            StatBlock stats = new StatBlock(str, dex, con, intel, wis, cha,
                armor, fortitude, reflex, will);
            return new MobileTemplate(id, key, name, shortDesc, longDesc, keywords,
                level, hpMax, mpMax, mvMax, stats, baseDamage, damageBonus, attackBonus,
                behaviors, aggroRange, experienceValue, goldMin, goldMax,
                respawnSeconds, autoflee, templateJson);
        }
    }
}
