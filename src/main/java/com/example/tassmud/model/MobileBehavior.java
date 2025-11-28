package com.example.tassmud.model;

/**
 * Behaviors that can be assigned to mobiles (NPCs/monsters).
 * A mobile can have multiple behaviors that define how it acts in the game world.
 */
public enum MobileBehavior {
    
    // Combat behaviors
    AGGRESSIVE("Attacks players on sight"),
    PASSIVE("Won't attack unless attacked first"),
    DEFENSIVE("Attacks if player attacks nearby mob of same type"),
    COWARDLY("Flees when HP is low"),
    
    // Role behaviors
    GUARD("Protects an area or NPCs, attacks criminals"),
    SHOPKEEPER("Can buy/sell items with players"),
    HEALER("Can heal players for a fee or quest"),
    TRAINER("Can train players in skills/spells"),
    QUESTGIVER("Has quests available for players"),
    BANKER("Provides banking services"),
    
    // Personality behaviors
    BEGGAR("Asks players for gold"),
    THIEF("May attempt to steal from players"),
    WANDERER("Moves between rooms randomly"),
    SENTINEL("Never moves from spawn location"),
    
    // Special behaviors
    IMMORTAL("Cannot be killed"),
    SCAVENGER("Picks up items from the ground"),
    ASSISTS("Helps nearby mobs of same type in combat"),
    NOCTURNAL("Only active at night"),
    DIURNAL("Only active during the day");
    
    private final String description;
    
    MobileBehavior(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Parse a behavior from a string, case-insensitive.
     */
    public static MobileBehavior fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            return MobileBehavior.valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
