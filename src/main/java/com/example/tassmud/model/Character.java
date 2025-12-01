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
    
    // Current stance (out-of-combat position)
    private Stance stance;

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
        this(name, age, description, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur, currentRoom,
             str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, Stance.STANDING);
    }
    
    public Character(String name, int age, String description,
                     int hpMax, int hpCur,
                     int mpMax, int mpCur,
                     int mvMax, int mvCur,
                     Integer currentRoom,
                     int str, int dex, int con, int intel, int wis, int cha,
                     int armor, int fortitude, int reflex, int will,
                     Stance stance) {
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
        this.stance = stance != null ? stance : Stance.STANDING;
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
    
    public Stance getStance() { return stance; }
    public void setStance(Stance stance) { this.stance = stance != null ? stance : Stance.STANDING; }

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
    
    /**
     * Apply regeneration based on current stance.
     * Returns the amounts regenerated as [hp, mp, mv].
     */
    public int[] regenerate() {
        int percent = stance.getRegenPercent();
        int hpRegen = Math.max(1, (hpMax * percent) / 100);
        int mpRegen = Math.max(1, (mpMax * percent) / 100);
        int mvRegen = Math.max(1, (mvMax * percent) / 100);
        
        int oldHp = hpCur;
        int oldMp = mpCur;
        int oldMv = mvCur;
        
        setHpCur(hpCur + hpRegen);
        setMpCur(mpCur + mpRegen);
        setMvCur(mvCur + mvRegen);
        
        return new int[] { hpCur - oldHp, mpCur - oldMp, mvCur - oldMv };
    }
    
    /**
     * Check if this character needs regeneration (any stat below max).
     */
    public boolean needsRegen() {
        return hpCur < hpMax || mpCur < mpMax || mvCur < mvMax;
    }
}
