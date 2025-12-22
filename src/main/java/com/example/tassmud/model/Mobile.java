package com.example.tassmud.model;

import java.util.Collections;
import java.util.List;

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
    private final List<MobileBehavior> behaviors;
    private final int experienceValue;
    private final int baseDamage;
    private final int damageBonus;
    private final int attackBonus;
    private final int autoflee;            // Auto-flee threshold (0-100)
    
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
            template.getStr(),
            template.getDex(),
            template.getCon(),
            template.getIntel(),
            template.getWis(),
            template.getCha(),
            template.getArmor(),
            template.getFortitude(),
            template.getReflex(),
            template.getWill()
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
        this.behaviors = template.getBehaviors();
        this.experienceValue = template.getExperienceValue();
        this.baseDamage = template.getBaseDamage();
        this.damageBonus = template.getDamageBonus();
        this.attackBonus = template.getAttackBonus();
        this.autoflee = template.getAutoflee();
    }
    
    /**
     * Create a Mobile instance with explicit values (for loading from DB).
     */
    public Mobile(long instanceId, int templateId, int level, String name, String description,
                  int hpMax, int hpCur, int mpMax, int mpCur, int mvMax, int mvCur,
                  Integer currentRoom, Integer spawnRoomId,
                  int str, int dex, int con, int intel, int wis, int cha,
                  int armor, int fortitude, int reflex, int will,
                  String shortDesc, List<MobileBehavior> behaviors,
                  int experienceValue, int baseDamage, int damageBonus, int attackBonus,
                  int autoflee,
                  String originUuid,
                  long spawnedAt, boolean isDead, long diedAt) {
        super(name, 0, description, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur, currentRoom,
              str, dex, con, intel, wis, cha, armor, fortitude, reflex, will);
        
        this.instanceId = instanceId;
        this.templateId = templateId;
        this.level = level;
        this.spawnRoomId = spawnRoomId;
          this.spawnedAt = spawnedAt;
          this.originUuid = originUuid;
          this.isDead = isDead;
        this.diedAt = diedAt;
        this.shortDesc = shortDesc;
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
        this.isDead = true;
        this.diedAt = System.currentTimeMillis();
        setHpCur(0);
        clearTarget();
    }
    
    public void respawn() {
        this.isDead = false;
        this.diedAt = 0;
        setHpCur(getHpMax());
        setMpCur(getMpMax());
        setMvCur(getMvMax());
        setCurrentRoom(spawnRoomId);
        clearTarget();
    }
    
    // Cached template data
    public String getShortDesc() { return shortDesc; }
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
    public boolean shouldFlee() {
        if (!isCowardly()) return false;
        return getHpCur() < (getHpMax() / 4); // Flee at 25% HP
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
    public int rollDamage() {
        // Roll 1d(baseDamage) + damageBonus + STR modifier
        int roll = (int) (Math.random() * baseDamage) + 1;
        int strMod = (getStr() - 10) / 2;
        return Math.max(1, roll + damageBonus + strMod);
    }
    
    /**
     * Get the room description line for this mobile.
     */
    public String getRoomLine() {
        if (isDead) return null;
        return shortDesc != null ? shortDesc : getName() + " is here.";
    }
}
