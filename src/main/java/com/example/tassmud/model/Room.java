package com.example.tassmud.model;

public class Room {
    private final int id;
    private final int areaId;
    private final String name;
    private final String shortDesc;
    private final String longDesc;
    private final Integer moveCost;  // Optional room-specific move cost override

    // exits reference other room IDs (nullable)
    private final Integer exitN;
    private final Integer exitE;
    private final Integer exitS;
    private final Integer exitW;
    private final Integer exitU;
    private final Integer exitD;

    public Room(int id, int areaId, String name, String shortDesc, String longDesc,
                Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        this(id, areaId, name, shortDesc, longDesc, exitN, exitE, exitS, exitW, exitU, exitD, null);
    }
    
    public Room(int id, int areaId, String name, String shortDesc, String longDesc,
                Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD,
                Integer moveCost) {
        this.id = id;
        this.areaId = areaId;
        this.name = name;
        this.shortDesc = shortDesc;
        this.longDesc = longDesc;
        this.exitN = exitN;
        this.exitE = exitE;
        this.exitS = exitS;
        this.exitW = exitW;
        this.exitU = exitU;
        this.exitD = exitD;
        this.moveCost = moveCost;
    }

    public int getId() { return id; }
    public int getAreaId() { return areaId; }
    public String getName() { return name; }
    public String getShortDesc() { return shortDesc; }
    public String getLongDesc() { return longDesc; }
    public Integer getExitN() { return exitN; }
    public Integer getExitE() { return exitE; }
    public Integer getExitS() { return exitS; }
    public Integer getExitW() { return exitW; }
    public Integer getExitU() { return exitU; }
    public Integer getExitD() { return exitD; }
    
    /**
     * Get the room-specific movement cost override, or null if using area default.
     */
    public Integer getMoveCost() { return moveCost; }
    
    /**
     * Check if this room has a custom movement cost.
     */
    public boolean hasCustomMoveCost() { return moveCost != null; }
}
