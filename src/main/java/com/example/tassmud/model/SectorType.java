package com.example.tassmud.model;

/**
 * Sector types determine the base movement cost for traveling through an area.
 * Inspired by DikuMUD's sector system.
 * 
 * Each area has a default sector type that applies to all rooms in that area.
 * Individual rooms can override the movement cost if needed.
 */
public enum SectorType {
    // Indoor/urban areas - easy travel
    INSIDE(1, "Inside"),
    CITY(1, "City"),
    
    // Natural terrain - progressively harder
    FIELD(2, "Field"),
    FOREST(3, "Forest"),
    HILLS(4, "Hills"),
    
    // Water
    WATER_SWIM(4, "Shallow Water"),
    WATER_NOSWIM(50, "Deep Water"),  // Requires boat or magic
    UNDERWATER(8, "Underwater"),
    FLYING(0, "Flying"),  // can't enter without flight
    
    // Difficult terrain
    SLUSH(6, "Slush"),
    MOUNTAIN(8, "Mountain"),
    DESERT(8, "Desert"),
    SWAMP(8, "Swamp"),
    SNOW(8, "Snow"),
    ICE(10, "Ice");
    
    private final int moveCost;
    private final String displayName;
    
    SectorType(int moveCost, String displayName) {
        this.moveCost = moveCost;
        this.displayName = displayName;
    }
    
    /**
     * Get the movement point cost for traveling through this sector type.
     */
    public int getMoveCost() {
        return moveCost;
    }
    
    /**
     * Get the human-readable name for this sector type.
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Parse a sector type from a string (case-insensitive).
     * Returns FIELD as a sensible default if not found.
     */
    public static SectorType fromString(String s) {
        if (s == null || s.isEmpty()) return FIELD;
        String upper = s.toUpperCase().trim();
        for (SectorType st : values()) {
            if (st.name().equals(upper)) return st;
        }
        // Try display name match
        for (SectorType st : values()) {
            if (st.displayName.equalsIgnoreCase(s.trim())) return st;
        }
        return FIELD;
    }
}
