package com.example.tassmud.model;

/**
 * Flags that can be applied to rooms to modify their behavior.
 * Based on MERC room flags with TassMUD-specific additions.
 * 
 * Multiple flags can be applied to a single room.
 */
public enum RoomFlag {
    /**
     * Room requires a light source (natural or magical) to see.
     * Players without a light source will see limited information.
     * Placeholder until light source system is implemented.
     */
    DARK("dark", "Room is dark and requires a light source"),
    
    /**
     * Mobs cannot enter this room via normal movement (roaming, fleeing).
     * Mobs can still be summoned or teleported into the room by other means.
     * Used for safe havens, sanctuaries, etc.
     */
    NO_MOB("no_mob", "Mobs cannot enter this room by normal movement"),
    
    /**
     * Room is indoors, protected from weather effects.
     * Placeholder until weather system is implemented.
     */
    INDOORS("indoors", "Room is indoors, protected from weather"),
    
    /**
     * Room can only have one non-GM player at a time.
     * Used for private areas, confession booths, etc.
     * GMs bypass this restriction.
     */
    PRIVATE("private", "Only one non-GM player can occupy this room"),
    
    /**
     * No combat is permitted in this room.
     * Aggressive mobs will not attack.
     * Players cannot initiate combat.
     * Used for temples, inns, safe zones, etc.
     */
    SAFE("safe", "No combat is permitted in this room"),
    
    /**
     * Players cannot leave this room by any means except GM teleport.
     * Used for GM-enforced time-outs.
     * Blocks: movement, recall, teleport spells.
     * Only GMs can move players in or out.
     */
    PRISON("prison", "Cannot leave except by GM teleport"),
    
    /**
     * Room cannot be recalled out of.
     * Used for dungeons, special encounters, etc.
     */
    NO_RECALL("no_recall", "Cannot use recall from this room"),
    
    /**
     * Room is a pet shop (MERC compatibility).
     * Placeholder for future pet system.
     */
    PET_SHOP("pet_shop", "Room is a pet shop"),
    
    /**
     * Room is solitary - only one character can occupy it at a time.
     * Different from PRIVATE which allows GMs.
     */
    SOLITARY("solitary", "Only one character can occupy this room");
    
    private final String key;
    private final String description;
    
    RoomFlag(String key, String description) {
        this.key = key;
        this.description = description;
    }
    
    /**
     * Get the database key for this flag.
     */
    public String getKey() {
        return key;
    }
    
    /**
     * Get a human-readable description of this flag.
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Find a RoomFlag by its key (case-insensitive).
     * @param key the key to look up
     * @return the RoomFlag or null if not found
     */
    public static RoomFlag fromKey(String key) {
        if (key == null) return null;
        String lowerKey = key.toLowerCase().trim();
        for (RoomFlag flag : values()) {
            if (flag.key.equals(lowerKey)) {
                return flag;
            }
        }
        // Also try matching enum name
        try {
            return valueOf(key.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * Convert a MERC room_flags bitfield to a set of RoomFlags.
     * MERC bit values:
     *   1 = DARK, 4 = NO_MOB, 8 = INDOORS, 512 = PRIVATE
     *   1024 = SAFE, 2048 = SOLITARY, 4096 = PET_SHOP, 8192 = NO_RECALL
     * 
     * @param mercBits the MERC bitfield value
     * @return set of matching RoomFlags
     */
    public static java.util.Set<RoomFlag> fromMercBits(int mercBits) {
        java.util.Set<RoomFlag> flags = java.util.EnumSet.noneOf(RoomFlag.class);
        if ((mercBits & 1) != 0) flags.add(DARK);
        if ((mercBits & 4) != 0) flags.add(NO_MOB);
        if ((mercBits & 8) != 0) flags.add(INDOORS);
        if ((mercBits & 512) != 0) flags.add(PRIVATE);
        if ((mercBits & 1024) != 0) flags.add(SAFE);
        if ((mercBits & 2048) != 0) flags.add(SOLITARY);
        if ((mercBits & 4096) != 0) flags.add(PET_SHOP);
        if ((mercBits & 8192) != 0) flags.add(NO_RECALL);
        return flags;
    }
}
