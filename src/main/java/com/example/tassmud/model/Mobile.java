package com.example.tassmud.model;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A mobile (NPC/monster) that extends Character with mob-specific attributes.
 * This represents a spawned instance of a MobileTemplate in the game world.
 */
public class Mobile extends GameCharacter {
    
    private final long instanceId;         // Unique instance ID
    private final int templateId;          // Reference to MobileTemplate
    private final int level;               // Mob's level (from template)
    
    // Combat state
    private Integer targetCharacterId;     // Current combat target (player character ID)
    private Long targetMobileId;           // Current combat target (another mobile's instance ID)
    
    // Spawn info
    private final Integer spawnRoomId;     // Room where this mob was spawned
    private final long spawnedAt;          // Timestamp when spawned
    private final String originUuid;       // Optional UUID tying this instance to an original spawn mapping
    
    // State
    private boolean isDead;
    private long diedAt;                   // Timestamp when died (for respawn calculation)
    
    // Cached template data for quick access
    private final String shortDesc;
    private final java.util.List<String> keywords;
    private final List<MobileBehavior> behaviors;
    private final int experienceValue;
    private final int baseDamage;
    private final int damageBonus;
    private final int attackBonus;
    private final int autoflee;            // Auto-flee threshold (0-100)
    // Runtime tracking of applied equipment modifiers (so they can be removed on death/respawn)
    private final java.util.List<java.util.UUID> equipModifierIds = new java.util.ArrayList<>();
    
    /**
     * Create a Mobile instance from a template.
     */
    public Mobile(long instanceId, MobileTemplate template, Integer spawnRoomId) {
        super(
            template.getName(),
            0, // age not relevant for mobs
            template.getLongDesc(),
            template.getHpMax(),
            template.getHpMax(), // Start at full HP
            template.getMpMax(),
            template.getMpMax(), // Start at full MP
            template.getMvMax(),
            template.getMvMax(), // Start at full MV
            spawnRoomId,
            template.getStats()
        );
        
        this.instanceId = instanceId;
        this.templateId = template.getId();
        this.level = template.getLevel();
        this.spawnRoomId = spawnRoomId;
        this.spawnedAt = System.currentTimeMillis();
        this.originUuid = null;
        this.isDead = false;
        this.diedAt = 0;
        
        // Cache template data
        this.shortDesc = template.getShortDesc();
        this.keywords = template.getKeywords() == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(template.getKeywords()));
        this.behaviors = template.getBehaviors();
        this.experienceValue = template.getExperienceValue();
        this.baseDamage = template.getBaseDamage();
        this.damageBonus = template.getDamageBonus();
        this.attackBonus = template.getAttackBonus();
        this.autoflee = template.getAutoflee();
    }
    
    /** Creates a new builder for DB-loading Mobile instances. */
    public static DbBuilder dbBuilder() { return new DbBuilder(); }

    /**
     * Create a Mobile instance with explicit values (for loading from DB).
     */
    Mobile(long instanceId, int templateId, int level, String name, String description,
                  int hpMax, int hpCur, int mpMax, int mpCur, int mvMax, int mvCur,
                  Integer currentRoom, Integer spawnRoomId,
                  StatBlock stats,
                  java.util.List<String> keywords, String shortDesc, List<MobileBehavior> behaviors,
                  int experienceValue, int baseDamage, int damageBonus, int attackBonus,
                  int autoflee,
                  String originUuid,
                  long spawnedAt, boolean isDead, long diedAt) {
        super(name, 0, description, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur, currentRoom, stats);
        
        this.instanceId = instanceId;
        this.templateId = templateId;
        this.level = level;
        this.spawnRoomId = spawnRoomId;
          this.spawnedAt = spawnedAt;
          this.originUuid = originUuid;
          this.isDead = isDead;
        this.diedAt = diedAt;
        this.shortDesc = shortDesc;
        this.keywords = keywords == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(keywords));
        this.behaviors = behaviors == null ? Collections.emptyList() : behaviors;
        this.experienceValue = experienceValue;
        this.baseDamage = baseDamage;
        this.damageBonus = damageBonus;
        this.attackBonus = attackBonus;
        this.autoflee = autoflee;
    }
    
    // Instance-specific getters
    public long getInstanceId() { return instanceId; }
    public int getTemplateId() { return templateId; }
    public int getLevel() { return level; }
    public Integer getSpawnRoomId() { return spawnRoomId; }
    public long getSpawnedAt() { return spawnedAt; }
    public String getOriginUuid() { return originUuid; }
    
    // Combat state
    public Integer getTargetCharacterId() { return targetCharacterId; }
    public void setTargetCharacterId(Integer targetCharacterId) { this.targetCharacterId = targetCharacterId; }
    public Long getTargetMobileId() { return targetMobileId; }
    public void setTargetMobileId(Long targetMobileId) { this.targetMobileId = targetMobileId; }
    
    public boolean hasTarget() {
        return targetCharacterId != null || targetMobileId != null;
    }
    
    public void clearTarget() {
        this.targetCharacterId = null;
        this.targetMobileId = null;
    }
    
    // Death state
    public boolean isDead() { return isDead; }
    public long getDiedAt() { return diedAt; }
    
    public void die() {
        // Clear any equipment modifiers applied at spawn time
        clearEquipModifiers();
        this.isDead = true;
        this.diedAt = System.currentTimeMillis();
        setHpCur(0);
        clearTarget();
    }
    
    public void respawn() {
        // Ensure old equipment modifiers cleared before respawn
        clearEquipModifiers();
        this.isDead = false;
        this.diedAt = 0;
        setHpCur(getHpMax());
        setMpCur(getMpMax());
        setMvCur(getMvMax());
        setCurrentRoom(spawnRoomId);
        clearTarget();
    }

    public void addEquipModifier(java.util.UUID id) {
        if (id == null) return;
        equipModifierIds.add(id);
    }

    public void clearEquipModifiers() {
        for (java.util.UUID id : new java.util.ArrayList<>(equipModifierIds)) {
            removeModifier(id);
        }
        equipModifierIds.clear();
    }
    
    // Cached template data
    public String getShortDesc() { return shortDesc; }
    public java.util.List<String> getKeywords() { return keywords; }
    public List<MobileBehavior> getBehaviors() { return behaviors; }
    public int getExperienceValue() { return experienceValue; }
    public int getBaseDamage() { return baseDamage; }
    public int getDamageBonus() { return damageBonus; }
    public int getAttackBonus() { return attackBonus; }
    public int getAutoflee() { return autoflee; }
    
    /**
     * Check if this mobile has a specific behavior.
     */
    public boolean hasBehavior(MobileBehavior behavior) {
        return behaviors.contains(behavior);
    }
    
    // Behavior checks
    public boolean isAggressive() { return hasBehavior(MobileBehavior.AGGRESSIVE); }
    public boolean isPassive() { return hasBehavior(MobileBehavior.PASSIVE); }
    public boolean isCowardly() { return hasBehavior(MobileBehavior.COWARDLY); }
    public boolean isDefensive() { return hasBehavior(MobileBehavior.DEFENSIVE); }
    public boolean isImmortal() { return hasBehavior(MobileBehavior.IMMORTAL); }
    public boolean isShopkeeper() { return hasBehavior(MobileBehavior.SHOPKEEPER); }
    
    /**
     * Check if this mobile should flee (cowardly behavior at low HP).
     */
    /** @deprecated Use {@link com.example.tassmud.combat.CombatCalculator#shouldMobFlee(Mobile)} */
    @Deprecated
    public boolean shouldFlee() {
        return com.example.tassmud.combat.CombatCalculator.shouldMobFlee(this);
    }
    
    /**
     * Check if this mob can be killed.
     */
    public boolean canBeKilled() {
        return !isImmortal();
    }
    
    /**
     * Calculate damage for an attack.
     */
    /** @deprecated Use {@link com.example.tassmud.combat.CombatCalculator#rollMobileDamage(Mobile)} */
    @Deprecated
    public int rollDamage() {
        return com.example.tassmud.combat.CombatCalculator.rollMobileDamage(this);
    }
    
    /**
     * Get the room description line for this mobile.
     */
    public String getRoomLine() {
        if (isDead) return null;
        return shortDesc != null ? shortDesc : getName() + " is here.";
    }

    /** Fluent builder for DB-loaded {@link Mobile} instances (34-param constructor). */
    public static class DbBuilder {
        private long instanceId;
        private int templateId;
        private int level;
        private String name;
        private String description;
        private int hpMax, hpCur, mpMax, mpCur, mvMax, mvCur;
        private Integer currentRoom;
        private Integer spawnRoomId;
        private int str, dex, con, intel, wis, cha;
        private int armor, fortitude, reflex, will;
        private java.util.List<String> keywords;
        private String shortDesc;
        private java.util.List<MobileBehavior> behaviors;
        private int experienceValue, baseDamage, damageBonus, attackBonus, autoflee;
        private String originUuid;
        private long spawnedAt;
        private boolean isDead;
        private long diedAt;

        private DbBuilder() {}

        public DbBuilder instanceId(long v) { this.instanceId = v; return this; }
        public DbBuilder templateId(int v) { this.templateId = v; return this; }
        public DbBuilder level(int v) { this.level = v; return this; }
        public DbBuilder name(String v) { this.name = v; return this; }
        public DbBuilder description(String v) { this.description = v; return this; }
        public DbBuilder hpMax(int v) { this.hpMax = v; return this; }
        public DbBuilder hpCur(int v) { this.hpCur = v; return this; }
        public DbBuilder mpMax(int v) { this.mpMax = v; return this; }
        public DbBuilder mpCur(int v) { this.mpCur = v; return this; }
        public DbBuilder mvMax(int v) { this.mvMax = v; return this; }
        public DbBuilder mvCur(int v) { this.mvCur = v; return this; }
        public DbBuilder currentRoom(Integer v) { this.currentRoom = v; return this; }
        public DbBuilder spawnRoomId(Integer v) { this.spawnRoomId = v; return this; }
        public DbBuilder str(int v) { this.str = v; return this; }
        public DbBuilder dex(int v) { this.dex = v; return this; }
        public DbBuilder con(int v) { this.con = v; return this; }
        public DbBuilder intel(int v) { this.intel = v; return this; }
        public DbBuilder wis(int v) { this.wis = v; return this; }
        public DbBuilder cha(int v) { this.cha = v; return this; }
        public DbBuilder armor(int v) { this.armor = v; return this; }
        public DbBuilder fortitude(int v) { this.fortitude = v; return this; }
        public DbBuilder reflex(int v) { this.reflex = v; return this; }
        public DbBuilder will(int v) { this.will = v; return this; }
        public DbBuilder keywords(java.util.List<String> v) { this.keywords = v; return this; }
        public DbBuilder shortDesc(String v) { this.shortDesc = v; return this; }
        public DbBuilder behaviors(java.util.List<MobileBehavior> v) { this.behaviors = v; return this; }
        public DbBuilder experienceValue(int v) { this.experienceValue = v; return this; }
        public DbBuilder baseDamage(int v) { this.baseDamage = v; return this; }
        public DbBuilder damageBonus(int v) { this.damageBonus = v; return this; }
        public DbBuilder attackBonus(int v) { this.attackBonus = v; return this; }
        public DbBuilder autoflee(int v) { this.autoflee = v; return this; }
        public DbBuilder originUuid(String v) { this.originUuid = v; return this; }
        public DbBuilder spawnedAt(long v) { this.spawnedAt = v; return this; }
        public DbBuilder isDead(boolean v) { this.isDead = v; return this; }
        public DbBuilder diedAt(long v) { this.diedAt = v; return this; }

        public Mobile build() {
            StatBlock stats = new StatBlock(str, dex, con, intel, wis, cha,
                armor, fortitude, reflex, will);
            return new Mobile(instanceId, templateId, level, name, description,
                hpMax, hpCur, mpMax, mpCur, mvMax, mvCur,
                currentRoom, spawnRoomId,
                stats,
                keywords, shortDesc, behaviors,
                experienceValue, baseDamage, damageBonus, attackBonus, autoflee,
                originUuid, spawnedAt, isDead, diedAt);
        }
    }
}
