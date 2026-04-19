package com.example.tassmud.spell;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Defines the six tiers of undead that can be raised by Animate Dead.
 * Each tier unlocks at a minimum caster level and carries stat multipliers
 * and an optional spec_fun for combat AI.
 */
public enum UndeadType {

    SKELETON(1, -3, 0.8, 1.0, 1.0, null,
            new String[]{"a shambling skeleton", "a dusty skeleton", "a rattling skeleton"},
            "A skeleton held together by dark magic shambles about."),

    GHOUL(10, -2, 0.9, 0.9, 1.0, "spec_poison",
            new String[]{"a rotting ghoul", "a slavering ghoul", "a hunched ghoul"},
            "A ghoul crouches here, its eyes burning with unholy hunger."),

    MUMMY(20, -1, 1.3, 1.2, 0.85, "spec_undead_mummy",
            new String[]{"a shambling mummy", "a bandaged mummy", "an ancient mummy"},
            "A mummy wrapped in tattered bandages lurches forward."),

    VAMPIRE(30, 0, 1.0, 1.0, 1.1, "spec_undead_drain",
            new String[]{"a pale vampire", "a gaunt vampire", "a shadowy vampire"},
            "A vampire stands here, its eyes gleaming with malice."),

    DEATH_KNIGHT(40, 0, 1.4, 1.5, 1.2, "spec_undead_taunt",
            new String[]{"a death knight", "a fallen knight", "an armored death knight"},
            "A death knight clad in blackened armor stands here, radiating dread."),

    LICH(50, 0, 0.6, 0.7, 0.7, "spec_cast_undead",
            new String[]{"a skeletal lich", "a robed lich", "an ancient lich"},
            "A lich floats here, crackling with necrotic energy.");

    private final int minCasterLevel;
    private final int levelOffset;
    private final double hpMult;
    private final double armorMult;
    private final double dmgMult;
    private final String specFun;
    private final String[] nameVariants;
    private final String longDesc;

    UndeadType(int minCasterLevel, int levelOffset, double hpMult, double armorMult,
               double dmgMult, String specFun, String[] nameVariants, String longDesc) {
        this.minCasterLevel = minCasterLevel;
        this.levelOffset = levelOffset;
        this.hpMult = hpMult;
        this.armorMult = armorMult;
        this.dmgMult = dmgMult;
        this.specFun = specFun;
        this.nameVariants = nameVariants;
        this.longDesc = longDesc;
    }

    public int getMinCasterLevel() { return minCasterLevel; }
    public int getLevelOffset() { return levelOffset; }
    public double getHpMult() { return hpMult; }
    public double getArmorMult() { return armorMult; }
    public double getDmgMult() { return dmgMult; }
    public String getSpecFun() { return specFun; }
    public String getLongDesc() { return longDesc; }

    /** Pick a random display name from this type's variants. */
    public String randomName() {
        return nameVariants[ThreadLocalRandom.current().nextInt(nameVariants.length)];
    }

    /** Return all undead types the caster qualifies for based on level. */
    public static List<UndeadType> getAvailableTypes(int casterLevel) {
        List<UndeadType> available = new ArrayList<>();
        for (UndeadType type : values()) {
            if (casterLevel >= type.minCasterLevel) {
                available.add(type);
            }
        }
        return available;
    }

    /** Roll a random undead type from those available at the given caster level. */
    public static UndeadType rollRandomType(int casterLevel) {
        List<UndeadType> available = getAvailableTypes(casterLevel);
        if (available.isEmpty()) {
            return SKELETON; // fallback
        }
        return available.get(ThreadLocalRandom.current().nextInt(available.size()));
    }
}
