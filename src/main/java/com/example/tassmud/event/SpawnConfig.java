package com.example.tassmud.event;

/**
 * Configuration for a spawn point in a room.
 * Defines what to spawn, how many, how often, and optionally into which container.
 */
public class SpawnConfig {
    
    public enum SpawnType {
        ITEM,
        MOB
    }
    
    /** Type of spawn (ITEM or MOB) */
    public final SpawnType type;
    
    /** Template ID for the item or mob */
    public final int templateId;
    
    /** Number to spawn each time */
    public final int quantity;
    
    /** Frequency in game hours (1 game hour = 1 real minute). Max 24. */
    public final int frequencyHours;
    
    /** Room ID where this spawn occurs */
    public final int roomId;
    
    /** Optional: Container template ID to spawn items into. -1 if not in container. */
    public final int containerTemplateId;
    
    public SpawnConfig(SpawnType type, int templateId, int quantity, int frequencyHours, int roomId, int containerTemplateId) {
        this.type = type;
        this.templateId = templateId;
        this.quantity = Math.max(1, quantity);
        this.frequencyHours = Math.min(Math.max(1, frequencyHours), EventScheduler.MAX_FREQUENCY_HOURS);
        this.roomId = roomId;
        this.containerTemplateId = containerTemplateId;
    }
    
    /**
     * Create a spawn config without a container.
     */
    public SpawnConfig(SpawnType type, int templateId, int quantity, int frequencyHours, int roomId) {
        this(type, templateId, quantity, frequencyHours, roomId, -1);
    }
    
    /**
     * Check if this spawn goes into a container.
     */
    public boolean hasContainer() {
        return containerTemplateId > 0;
    }
    
    /**
     * Get the delay in milliseconds for this spawn frequency.
     */
    public long getDelayMs() {
        return EventScheduler.gameHoursToMs(frequencyHours);
    }
    
    /**
     * Generate a unique ID for this spawn configuration.
     */
    public String getSpawnId() {
        return "spawn-" + type.name().toLowerCase() + "-" + templateId + "-room" + roomId + 
               (hasContainer() ? "-in" + containerTemplateId : "");
    }
    
    @Override
    public String toString() {
        return "SpawnConfig{type=" + type + ", templateId=" + templateId + ", qty=" + quantity + 
               ", freq=" + frequencyHours + "h, room=" + roomId + 
               (hasContainer() ? ", container=" + containerTemplateId : "") + "}";
    }
}
