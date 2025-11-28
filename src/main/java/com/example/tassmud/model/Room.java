package com.example.tassmud.model;

public class Room {
    private final int id;
    private final int areaId;
    private final String name;
    private final String shortDesc;
    private final String longDesc;

    // exits reference other room IDs (nullable)
    private final Integer exitN;
    private final Integer exitE;
    private final Integer exitS;
    private final Integer exitW;
    private final Integer exitU;
    private final Integer exitD;

    public Room(int id, int areaId, String name, String shortDesc, String longDesc,
                Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
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
}
