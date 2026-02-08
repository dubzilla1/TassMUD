package com.example.tassmud.model;

/**
 * Skill/spell progression curves determine how quickly proficiency increases.
 * Each curve defines:
 * <ul>
 *   <li>{@code baseGainChance} — base % chance to gain 1% proficiency on successful use</li>
 *   <li>{@code difficultyScaling} — how much the chance decreases as proficiency increases</li>
 * </ul>
 *
 * <p>Promoted from {@code Skill.SkillProgression} to a top-level enum because both
 * {@link Skill} and {@link Spell} depend on it.</p>
 */
public enum SkillProgression {
    /** Instant mastery — proficiencies, spell schools, armor/weapon types */
    INSTANT(0, 0),

    /** Very easy to learn — combat basics, movement skills */
    TRIVIAL(50, 0.3),

    /** Easy to learn — common combat skills */
    EASY(35, 0.25),

    /** Standard learning curve — most skills */
    NORMAL(25, 0.2),

    /** Harder to learn — specialized skills */
    HARD(15, 0.15),

    /** Very hard to learn — advanced techniques */
    VERY_HARD(10, 0.1),

    /** Extremely difficult — master-level abilities */
    LEGENDARY(5, 0.05);

    public final int baseGainChance;      // Base % chance to gain proficiency
    public final double difficultyScaling; // Multiplier reduction per proficiency point

    SkillProgression(int baseGainChance, double difficultyScaling) {
        this.baseGainChance = baseGainChance;
        this.difficultyScaling = difficultyScaling;
    }

    /**
     * Check if this progression grants instant mastery.
     */
    public boolean isInstant() {
        return this == INSTANT;
    }

    /**
     * Calculate the chance to gain 1% proficiency at a given proficiency level.
     * The chance decreases as proficiency increases, making it harder to master.
     *
     * <p>Formula: {@code baseChance * (1 - (proficiency/100) * scaling)}</p>
     *
     * @param currentProficiency Current proficiency level (1-100)
     * @return Percentage chance (0-100) to gain proficiency on skill use
     */
    public int getGainChance(int currentProficiency) {
        if (this == INSTANT) return 0;
        if (currentProficiency >= 100) return 0;
        double modifier = 1.0 - (currentProficiency / 100.0) * difficultyScaling;
        int chance = (int) Math.round(baseGainChance * Math.max(0.1, modifier));
        return Math.max(1, chance);
    }

    /**
     * Get the starting proficiency for this progression type.
     */
    public int getStartingProficiency() {
        return this == INSTANT ? 100 : 1;
    }

    /**
     * Parse progression from string (case-insensitive).
     */
    public static SkillProgression fromString(String s) {
        if (s == null || s.isEmpty()) return NORMAL;
        try {
            return valueOf(s.toUpperCase().replace("-", "_").replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
