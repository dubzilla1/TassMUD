package com.example.tassmud.model;

public class Area {
    private final int id;
    private final String name;
    private final String description;

    public Area(int id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
