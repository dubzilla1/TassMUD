package com.example.tassmud.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class Room {
    private final int id;
    private final int areaId;
    private final String name;
    private final String shortDesc;
    private final String longDesc;
    private final Integer moveCost;  // Optional room-specific move cost override

    /** Immutable map of exits — only directions with non-null destination room IDs are present. */
    private final Map<Direction, Integer> exits;

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
        this.moveCost = moveCost;
        EnumMap<Direction, Integer> map = new EnumMap<>(Direction.class);
        if (exitN != null) map.put(Direction.NORTH, exitN);
        if (exitE != null) map.put(Direction.EAST,  exitE);
        if (exitS != null) map.put(Direction.SOUTH, exitS);
        if (exitW != null) map.put(Direction.WEST,  exitW);
        if (exitU != null) map.put(Direction.UP,    exitU);
        if (exitD != null) map.put(Direction.DOWN,  exitD);
        this.exits = Collections.unmodifiableMap(map);
    }

    public int getId() { return id; }
    public int getAreaId() { return areaId; }
    public String getName() { return name; }
    public String getShortDesc() { return shortDesc; }
    public String getLongDesc() { return longDesc; }

    /** Get the exit map (Direction → destination room ID). Only present directions are in the map. */
    public Map<Direction, Integer> getExits() { return exits; }

    /** Get destination room ID for a direction, or null if no exit in that direction. */
    public Integer getExit(Direction dir) { return exits.get(dir); }

    // Legacy convenience getters — delegate to the map
    public Integer getExitN() { return exits.get(Direction.NORTH); }
    public Integer getExitE() { return exits.get(Direction.EAST); }
    public Integer getExitS() { return exits.get(Direction.SOUTH); }
    public Integer getExitW() { return exits.get(Direction.WEST); }
    public Integer getExitU() { return exits.get(Direction.UP); }
    public Integer getExitD() { return exits.get(Direction.DOWN); }
    
    /**
     * Get the room-specific movement cost override, or null if using area default.
     */
    public Integer getMoveCost() { return moveCost; }
    
    /**
     * Check if this room has a custom movement cost.
     */
    public boolean hasCustomMoveCost() { return moveCost != null; }
}
