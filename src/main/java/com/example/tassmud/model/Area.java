package com.example.tassmud.model;

public class Area {
    private final int id;
    private final String name;
    private final String description;
    private final SectorType sectorType;
    private final String levelRange;

    public Area(int id, String name, String description) {
        this(id, name, description, SectorType.FIELD, null);
    }
    
    public Area(int id, String name, String description, SectorType sectorType) {
        this(id, name, description, sectorType, null);
    }

    public Area(int id, String name, String description, SectorType sectorType, String levelRange) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.sectorType = sectorType != null ? sectorType : SectorType.FIELD;
        this.levelRange = levelRange;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public SectorType getSectorType() { return sectorType; }
    public String getLevelRange() { return levelRange; }
    
    /**
     * Get the base movement cost for this area.
     */
    public int getMoveCost() { return sectorType.getMoveCost(); }
}
