package com.example.tassmud.util;

import com.example.tassmud.model.Cooldown;
import com.example.tassmud.model.CooldownType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages cooldowns for all entities (players and mobs).
 * 
 * Players are identified by character name (String).
 * Mobs are identified by instance ID (Long).
 * 
 * The manager ticks all cooldowns at a regular interval and automatically
 * removes expired cooldowns.
 */
public class CooldownManager {
    
    private static final CooldownManager INSTANCE = new CooldownManager();
    
    // Cooldowns keyed by entity identifier -> cooldown key -> cooldown
    // Entity identifiers: String for players (name), "mob:" + instanceId for mobs
    private final Map<String, Map<String, Cooldown>> entityCooldowns = new ConcurrentHashMap<>();
    
    // Tick interval in milliseconds (matches combat tick rate for consistency)
    private static final long TICK_INTERVAL_MS = 500;
    
    private TickService tickService;
    
    private CooldownManager() {}
    
    public static CooldownManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize the cooldown manager with the tick service.
     * Should be called once during server startup.
     */
    public void initialize(TickService tickService) {
        this.tickService = tickService;
        tickService.scheduleAtFixedRate("cooldown-tick", this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS);
    }
    
    /**
     * Process one tick: decrement all cooldowns and remove expired ones.
     */
    private void tick() {
        double deltaSeconds = TICK_INTERVAL_MS / 1000.0;
        
        for (Map.Entry<String, Map<String, Cooldown>> entityEntry : entityCooldowns.entrySet()) {
            Map<String, Cooldown> cooldowns = entityEntry.getValue();
            
            // Remove expired cooldowns
            cooldowns.entrySet().removeIf(entry -> entry.getValue().tick(deltaSeconds));
            
            // Clean up empty entity maps
            if (cooldowns.isEmpty()) {
                entityCooldowns.remove(entityEntry.getKey());
            }
        }
    }
    
    // ========== Player cooldown methods ==========
    
    /**
     * Set a cooldown for a player character.
     * @param characterName player's character name
     * @param type cooldown type (SKILL or SPELL)
     * @param abilityId the skill or spell ID
     * @param durationSeconds cooldown duration in seconds
     */
    public void setPlayerCooldown(String characterName, CooldownType type, int abilityId, double durationSeconds) {
        if (characterName == null || durationSeconds <= 0) return;
        
        String entityKey = characterName.toLowerCase();
        Map<String, Cooldown> cooldowns = entityCooldowns.computeIfAbsent(entityKey, k -> new ConcurrentHashMap<>());
        
        Cooldown cd = new Cooldown(type, abilityId, durationSeconds);
        cooldowns.put(cd.getKey(), cd);
    }
    
    /**
     * Check if a player ability is on cooldown.
     * @return true if the ability is on cooldown, false if ready
     */
    public boolean isPlayerOnCooldown(String characterName, CooldownType type, int abilityId) {
        if (characterName == null) return false;
        
        String entityKey = characterName.toLowerCase();
        Map<String, Cooldown> cooldowns = entityCooldowns.get(entityKey);
        if (cooldowns == null) return false;
        
        Cooldown cd = cooldowns.get(Cooldown.makeKey(type, abilityId));
        return cd != null && !cd.isExpired();
    }
    
    /**
     * Get remaining cooldown time for a player ability.
     * @return remaining seconds, or 0 if not on cooldown
     */
    public double getPlayerCooldownRemaining(String characterName, CooldownType type, int abilityId) {
        if (characterName == null) return 0;
        
        String entityKey = characterName.toLowerCase();
        Map<String, Cooldown> cooldowns = entityCooldowns.get(entityKey);
        if (cooldowns == null) return 0;
        
        Cooldown cd = cooldowns.get(Cooldown.makeKey(type, abilityId));
        return cd != null ? cd.getRemainingSeconds() : 0;
    }
    
    /**
     * Clear a specific cooldown for a player (e.g., GM command).
     */
    public void clearPlayerCooldown(String characterName, CooldownType type, int abilityId) {
        if (characterName == null) return;
        
        String entityKey = characterName.toLowerCase();
        Map<String, Cooldown> cooldowns = entityCooldowns.get(entityKey);
        if (cooldowns != null) {
            cooldowns.remove(Cooldown.makeKey(type, abilityId));
        }
    }
    
    /**
     * Clear all cooldowns for a player.
     */
    public void clearAllPlayerCooldowns(String characterName) {
        if (characterName == null) return;
        entityCooldowns.remove(characterName.toLowerCase());
    }
    
    // ========== Mobile cooldown methods ==========
    
    /**
     * Set a cooldown for a mobile (NPC/mob).
     * @param mobileInstanceId the mobile's unique instance ID
     * @param type cooldown type (SKILL or SPELL)
     * @param abilityId the skill or spell ID
     * @param durationSeconds cooldown duration in seconds
     */
    public void setMobileCooldown(long mobileInstanceId, CooldownType type, int abilityId, double durationSeconds) {
        if (durationSeconds <= 0) return;
        
        String entityKey = "mob:" + mobileInstanceId;
        Map<String, Cooldown> cooldowns = entityCooldowns.computeIfAbsent(entityKey, k -> new ConcurrentHashMap<>());
        
        Cooldown cd = new Cooldown(type, abilityId, durationSeconds);
        cooldowns.put(cd.getKey(), cd);
    }
    
    /**
     * Check if a mobile ability is on cooldown.
     * @return true if the ability is on cooldown, false if ready
     */
    public boolean isMobileOnCooldown(long mobileInstanceId, CooldownType type, int abilityId) {
        String entityKey = "mob:" + mobileInstanceId;
        Map<String, Cooldown> cooldowns = entityCooldowns.get(entityKey);
        if (cooldowns == null) return false;
        
        Cooldown cd = cooldowns.get(Cooldown.makeKey(type, abilityId));
        return cd != null && !cd.isExpired();
    }
    
    /**
     * Get remaining cooldown time for a mobile ability.
     * @return remaining seconds, or 0 if not on cooldown
     */
    public double getMobileCooldownRemaining(long mobileInstanceId, CooldownType type, int abilityId) {
        String entityKey = "mob:" + mobileInstanceId;
        Map<String, Cooldown> cooldowns = entityCooldowns.get(entityKey);
        if (cooldowns == null) return 0;
        
        Cooldown cd = cooldowns.get(Cooldown.makeKey(type, abilityId));
        return cd != null ? cd.getRemainingSeconds() : 0;
    }
    
    /**
     * Clear all cooldowns for a mobile (e.g., on death/despawn).
     */
    public void clearAllMobileCooldowns(long mobileInstanceId) {
        entityCooldowns.remove("mob:" + mobileInstanceId);
    }
    
    // ========== Utility methods ==========
    
    /**
     * Get the number of entities currently tracked.
     */
    public int getTrackedEntityCount() {
        return entityCooldowns.size();
    }
    
    /**
     * Get the total number of active cooldowns across all entities.
     */
    public int getTotalActiveCooldowns() {
        return entityCooldowns.values().stream()
                .mapToInt(Map::size)
                .sum();
    }
}
