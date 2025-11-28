package com.example.tassmud.model;

public class Character {
    private final String name;
    private final int age;
    private final String description;

    private final int hpMax;
    private int hpCur;

    private final int mpMax;
    private int mpCur;

    private final int mvMax;
    private int mvCur;
    // Current room (nullable)
    private Integer currentRoom;

    // Ability scores
    private final int str;
    private final int dex;
    private final int con;
    private final int intel;
    private final int wis;
    private final int cha;

    // Saves
    private final int armor;
    private final int fortitude;
    private final int reflex;
    private final int will;

    public Character(String name, int age, String description,
                     int hpMax, int hpCur,
                     int mpMax, int mpCur,
                     int mvMax, int mvCur,
                     Integer currentRoom,
                     int str, int dex, int con, int intel, int wis, int cha,
                     int armor, int fortitude, int reflex, int will) {
        this.name = name;
        this.age = age;
        this.description = description;
        this.hpMax = hpMax;
        this.hpCur = hpCur;
        this.mpMax = mpMax;
        this.mpCur = mpCur;
        this.mvMax = mvMax;
        this.mvCur = mvCur;
        this.currentRoom = currentRoom;
        this.str = str;
        this.dex = dex;
        this.con = con;
        this.intel = intel;
        this.wis = wis;
        this.cha = cha;
        this.armor = armor;
        this.fortitude = fortitude;
        this.reflex = reflex;
        this.will = will;
    }

    public String getName() { return name; }
    public int getAge() { return age; }
    public String getDescription() { return description; }

    public int getHpMax() { return hpMax; }
    public int getHpCur() { return hpCur; }
    public void setHpCur(int hpCur) { this.hpCur = Math.max(0, Math.min(hpCur, hpMax)); }

    public int getMpMax() { return mpMax; }
    public int getMpCur() { return mpCur; }
    public void setMpCur(int mpCur) { this.mpCur = Math.max(0, Math.min(mpCur, mpMax)); }

    public int getMvMax() { return mvMax; }
    public int getMvCur() { return mvCur; }
    public void setMvCur(int mvCur) { this.mvCur = Math.max(0, Math.min(mvCur, mvMax)); }

    public Integer getCurrentRoom() { return currentRoom; }
    public void setCurrentRoom(Integer currentRoom) { this.currentRoom = currentRoom; }

    // Ability score getters
    public int getStr() { return str; }
    public int getDex() { return dex; }
    public int getCon() { return con; }
    public int getIntel() { return intel; }
    public int getWis() { return wis; }
    public int getCha() { return cha; }
    // Save getters
    public int getArmor() { return armor; }
    public int getFortitude() { return fortitude; }
    public int getReflex() { return reflex; }
    public int getWill() { return will; }
}
