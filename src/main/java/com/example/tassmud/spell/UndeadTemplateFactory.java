package com.example.tassmud.spell;

import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates {@link MobileTemplate} instances at runtime for summoned undead.
 * Templates use reserved IDs 90001-90006 (never persisted in YAML).
 * Stats are calibrated from MERC area mob data with type-specific multipliers
 * and slight random variance.
 */
public final class UndeadTemplateFactory {

    /** Reserved template ID base — each UndeadType ordinal is added to this. */
    private static final int TEMPLATE_ID_BASE = 90001;

    private UndeadTemplateFactory() {}

    /**
     * Create a MobileTemplate for a summoned undead.
     *
     * @param type        the undead tier
     * @param minionLevel the effective level of the summoned creature
     * @return a fully populated MobileTemplate ready for MobileDAO.spawnMobile()
     */
    public static MobileTemplate createTemplate(UndeadType type, int minionLevel) {
        int level = Math.max(1, minionLevel);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Base stats from calibration formulas (MERC mob data)
        int baseHP = (int) (level * level * 0.6 + level * 10);
        int baseArmor = 10 + level / 2;
        int baseDamage = 4 + level / 5;
        int baseDamageBonus = 1 + level / 3;
        int baseStat = 10 + level / 5;

        // Apply type multipliers
        int hp = (int) (baseHP * type.getHpMult());
        int armor = (int) (baseArmor * type.getArmorMult());
        int damage = (int) (baseDamage * type.getDmgMult());
        int damageBonus = (int) (baseDamageBonus * type.getDmgMult());

        // Random variance: ±10% HP, ±1-2 damage, ±1 armor
        hp = hp + rng.nextInt(-Math.max(1, hp / 10), Math.max(2, hp / 10) + 1);
        damage = damage + rng.nextInt(-1, 2);
        damageBonus = damageBonus + rng.nextInt(-1, 2);
        armor = armor + rng.nextInt(-1, 2);

        // Clamp minimums
        hp = Math.max(10, hp);
        damage = Math.max(1, damage);
        damageBonus = Math.max(0, damageBonus);
        armor = Math.max(1, armor);

        // Saves scale with level
        int fortitude = 5 + level / 3 + rng.nextInt(0, 3);
        int reflex = 3 + level / 4 + rng.nextInt(0, 3);
        int will = 4 + level / 3 + rng.nextInt(0, 3);

        String name = type.randomName();
        List<String> keywords = buildKeywords(type, name);

        List<MobileBehavior> behaviors = new ArrayList<>();
        // Summoned undead don't wander or aggro independently
        behaviors.add(MobileBehavior.SENTINEL);

        int templateId = TEMPLATE_ID_BASE + type.ordinal();

        return MobileTemplate.builder()
                .id(templateId)
                .key("undead_" + type.name().toLowerCase() + "_" + level)
                .name(name)
                .shortDesc(name)
                .longDesc(type.getLongDesc())
                .keywords(keywords)
                .level(level)
                .hpMax(hp)
                .mpMax(type == UndeadType.LICH ? 50 + level * 2 : 0)
                .mvMax(100)
                .str(baseStat)
                .dex(baseStat - 2)
                .con(baseStat + 1)
                .intel(type == UndeadType.LICH ? baseStat + 5 : baseStat - 3)
                .wis(type == UndeadType.LICH ? baseStat + 3 : baseStat - 4)
                .cha(baseStat - 5)
                .armor(armor)
                .fortitude(fortitude)
                .reflex(reflex)
                .will(will)
                .baseDamage(damage)
                .damageBonus(damageBonus)
                .attackBonus(level / 3)
                .behaviors(behaviors)
                .experienceValue(0) // No XP from summoned undead
                .goldMin(0)
                .goldMax(0)
                .respawnSeconds(0) // Never respawn — temporary summon
                .autoflee(0)
                .specFun(type.getSpecFun())
                .build();
    }

    private static List<String> buildKeywords(UndeadType type, String name) {
        List<String> kw = new ArrayList<>();
        // Add the type name as a keyword
        String typeName = type.name().toLowerCase().replace('_', ' ');
        kw.add(typeName);
        // Add individual words from the display name (skip articles)
        for (String word : name.split("\\s+")) {
            String lower = word.toLowerCase();
            if (!lower.equals("a") && !lower.equals("an") && !lower.equals("the")) {
                if (!kw.contains(lower)) {
                    kw.add(lower);
                }
            }
        }
        kw.add("undead");
        kw.add("summoned");
        return kw;
    }
}
