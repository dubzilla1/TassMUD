package com.example.tassmud.model;

public enum EquipmentSlot {
    HEAD(1, "head", "Head"),
    NECK(2, "neck", "Neck"),
    SHOULDERS(3, "shoulders", "Shoulders"),
    ARMS(4, "arms", "Arms"),
    HANDS(5, "hands", "Hands"),
    CHEST(6, "chest", "Chest"),
    WAIST(7, "waist", "Waist"),
    LEGS(8, "legs", "Legs"),
    BOOTS(9, "boots", "Boots"),
    BACK(10, "back", "Back"),
    MAIN_HAND(11, "main_hand", "Main Hand"),
    OFF_HAND(12, "off_hand", "Off Hand");

    public final int id;
    public final String key;
    public final String displayName;

    EquipmentSlot(int id, String key, String displayName) {
        this.id = id;
        this.key = key;
        this.displayName = displayName;
    }

    public int getId() { return id; }
    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }

    public static EquipmentSlot fromId(int id) {
        for (EquipmentSlot s : values()) if (s.id == id) return s;
        return null;
    }

    public static EquipmentSlot fromKey(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase();
        // Handle common aliases
        if (k.equals("main") || k.equals("mainhand")) return MAIN_HAND;
        if (k.equals("off") || k.equals("offhand")) return OFF_HAND;
        for (EquipmentSlot s : values()) if (s.key.equals(k) || s.name().toLowerCase().equals(k)) return s;
        return null;
    }
}
