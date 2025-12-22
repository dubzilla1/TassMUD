package com.example.tassmud.model;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameCharacter {
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

    public GameCharacter(String name, int age, String description,
                     int hpMax, int hpCur,
                     int mpMax, int mpCur,
                     int mvMax, int mvCur,
                     Integer currentRoom,
                     int str, int dex, int con, int intel, int wis, int cha,
                     int armor, int fortitude, int reflex, int will) {
        this(name, age, description, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur, currentRoom,
             str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, Stance.STANDING);
    }
    
    public GameCharacter(String name, int age, String description,
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
        // initialize modifier base values and caches
        initBaseStats();
    }

    public String getName() { return name; }
    public int getAge() { return age; }
    public String getDescription() { return description; }

    public int getHpMax() { return hpMax; }
    public int getHpCur() { return hpCur; }
    public void setHpCur(int hpCur) { this.hpCur = Math.max(0, Math.min(hpCur, hpMax)); }
    
    /**
     * Heal the character by the given amount (capped at hpMax).
     * @param amount Amount to heal
     */
    public void heal(int amount) {
        if (amount > 0) {
            setHpCur(hpCur + amount);
        }
    }

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

    // -- Modifier system backing fields --
    private final EnumMap<Stat, Double> baseStats = new EnumMap<>(Stat.class);
    private final EnumMap<Stat, CopyOnWriteArrayList<Modifier>> modifiers = new EnumMap<>(Stat.class);
    private final EnumMap<Stat, Double> cachedStats = new EnumMap<>(Stat.class);
    private final EnumSet<Stat> dirtyStats = EnumSet.noneOf(Stat.class);

    private void initBaseStats() {
        baseStats.put(Stat.STRENGTH, (double) str);
        baseStats.put(Stat.DEXTERITY, (double) dex);
        baseStats.put(Stat.CONSTITUTION, (double) con);
        baseStats.put(Stat.INTELLIGENCE, (double) intel);
        baseStats.put(Stat.WISDOM, (double) wis);
        baseStats.put(Stat.CHARISMA, (double) cha);

        baseStats.put(Stat.HP_MAX, (double) hpMax);
        baseStats.put(Stat.HP_CURRENT, (double) hpCur);
        baseStats.put(Stat.MP_MAX, (double) mpMax);
        baseStats.put(Stat.MP_CURRENT, (double) mpCur);
        baseStats.put(Stat.MV_MAX, (double) mvMax);
        baseStats.put(Stat.MV_CURRENT, (double) mvCur);

        baseStats.put(Stat.ARMOR, (double) armor);
        baseStats.put(Stat.FORTITUDE, (double) fortitude);
        baseStats.put(Stat.REFLEX, (double) reflex);
        baseStats.put(Stat.WILL, (double) will);

        // New combat/spell stats (default 0)
        baseStats.put(Stat.ATTACK_HIT_BONUS, 0.0);
        baseStats.put(Stat.ATTACK_DAMAGE_BONUS, 0.0);
        baseStats.put(Stat.SPELL_HIT_BONUS, 0.0);
        baseStats.put(Stat.SPELL_DAMAGE_BONUS, 0.0);
        baseStats.put(Stat.ATTACK_DAMAGE_REDUCTION, 0.0);
        baseStats.put(Stat.SPELL_DAMAGE_REDUCTION, 0.0);
        baseStats.put(Stat.CRITICAL_THRESHOLD_BONUS, 0.0);

        // initially mark all stats dirty so cache builds on demand
        dirtyStats.addAll(EnumSet.allOf(Stat.class));
    }

    /**
     * Add a modifier onto this character. Returns the modifier id.
     */
    public UUID addModifier(Modifier m) {
        modifiers.computeIfAbsent(m.stat(), k -> new CopyOnWriteArrayList<>()).add(m);
        markDirty(m.stat());
        return m.id();
    }

    /**
     * Remove a modifier by id. Returns true if removed.
     */
    public boolean removeModifier(UUID modifierId) {
        boolean removedAny = false;
        for (var entry : modifiers.entrySet()) {
            List<Modifier> list = entry.getValue();
            boolean removed = list.removeIf(mod -> mod.id().equals(modifierId));
            if (removed) {
                removedAny = true;
                markDirty(entry.getKey());
            }
        }
        return removedAny;
    }

    private void markDirty(Stat s) {
        cachedStats.remove(s);
        dirtyStats.add(s);
    }

    /**
     * Compute effective stat value, applying modifiers.
     */
    public double getStat(Stat s) {
        // lazy compute: if not dirty and cached, return
        if (!dirtyStats.contains(s) && cachedStats.containsKey(s)) {
            return cachedStats.get(s);
        }

        double base = baseStats.getOrDefault(s, 0.0);
        var list = modifiers.getOrDefault(s, new CopyOnWriteArrayList<>());

        // remove expired modifiers
        boolean removedExpired = list.removeIf(Modifier::isExpired);
        if (removedExpired) markDirty(s);

        // OVERRIDE by highest priority
        Modifier override = list.stream()
            .filter(m -> m.op() == Modifier.Op.OVERRIDE)
            .max(Comparator.comparingInt(Modifier::priority))
            .orElse(null);
        if (override != null) {
            double res = override.value();
            cachedStats.put(s, res);
            dirtyStats.remove(s);
            return res;
        }

        double addSum = list.stream()
            .filter(m -> m.op() == Modifier.Op.ADD)
            .mapToDouble(Modifier::value)
            .sum();

        double mulProduct = list.stream()
            .filter(m -> m.op() == Modifier.Op.MULTIPLY)
            .mapToDouble(Modifier::value)
            .reduce(1.0, (a, b) -> a * b);

        double result = (base + addSum) * mulProduct;
        cachedStats.put(s, result);
        dirtyStats.remove(s);
        return result;
    }

    // Convenience integer getters for the new combat/spell stats (rounded)
    public int getAttackHitBonus() { return (int)Math.round(getStat(Stat.ATTACK_HIT_BONUS)); }
    public int getAttackDamageBonus() { return (int)Math.round(getStat(Stat.ATTACK_DAMAGE_BONUS)); }
    public int getSpellHitBonus() { return (int)Math.round(getStat(Stat.SPELL_HIT_BONUS)); }
    public int getSpellDamageBonus() { return (int)Math.round(getStat(Stat.SPELL_DAMAGE_BONUS)); }
    public int getAttackDamageReduction() { return (int)Math.round(getStat(Stat.ATTACK_DAMAGE_REDUCTION)); }
    public int getSpellDamageReduction() { return (int)Math.round(getStat(Stat.SPELL_DAMAGE_REDUCTION)); }
    
    /**
     * Get the critical threshold bonus (reduces the roll needed for a crit).
     * Default is 0 (crit on natural 20). A value of -1 means crit on 19+, -18 means crit on 2+.
     */
    public int getCriticalThresholdBonus() { return (int)Math.round(getStat(Stat.CRITICAL_THRESHOLD_BONUS)); }

    /**
     * Return a snapshot list of all active modifiers on this character.
     */
    public java.util.List<Modifier> getAllModifiers() {
        java.util.List<Modifier> out = new java.util.ArrayList<>();
        for (var entry : modifiers.entrySet()) {
            out.addAll(entry.getValue());
        }
        return out;
    }

    
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
