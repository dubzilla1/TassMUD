package com.example.tassmud.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a skill definition.
 * Skills have a progression curve that determines how quickly proficiency increases.
 */
public class Skill {
    private final int id;
    private final String name;
    private final String description;
    private final SkillProgression progression;
    private final List<SkillTrait> traits;
    private final double cooldown; // cooldown in seconds, 0 means no cooldown

    public Skill(int id, String name, String description) {
        this(id, name, description, SkillProgression.NORMAL, null, 0);
    }
    
    public Skill(int id, String name, String description, SkillProgression progression) {
        this(id, name, description, progression, null, 0);
    }
    
    public Skill(int id, String name, String description, SkillProgression progression,
                 List<SkillTrait> traits, double cooldown) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.progression = progression != null ? progression : SkillProgression.NORMAL;
        this.traits = traits != null ? new ArrayList<>(traits) : new ArrayList<>();
        this.cooldown = Math.max(0, cooldown);
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public SkillProgression getProgression() { return progression; }
    public List<SkillTrait> getTraits() { return Collections.unmodifiableList(traits); }
    public double getCooldown() { return cooldown; }
    
    /**
     * Check if this skill has a specific trait.
     */
    public boolean hasTrait(SkillTrait trait) {
        return traits.contains(trait);
    }
    
    /**
     * Check if this skill has a cooldown.
     */
    public boolean hasCooldown() {
        return cooldown > 0;
    }
    
    /**
     * Check if this skill requires combat.
     */
    public boolean requiresCombat() {
        return hasTrait(SkillTrait.COMBAT);
    }
    
    /**
     * Check if this skill is innate (automatically known at 100% by all).
     * Innate skills are basic combat maneuvers that don't require training.
     */
    public boolean isInnate() {
        return hasTrait(SkillTrait.INNATE);
    }
    
    /**
     * Skill progression curves determine how quickly proficiency increases.
     * Each curve defines:
     * - baseGainChance: base % chance to gain 1% proficiency on successful use
     * - difficultyScaling: how much the chance decreases as proficiency increases
     */
    public enum SkillProgression {
        /** Instant mastery - proficiencies, spell schools, armor/weapon types */
        INSTANT(0, 0),
        
        /** Very easy to learn - combat basics, movement skills */
        TRIVIAL(50, 0.3),
        
        /** Easy to learn - common combat skills */
        EASY(35, 0.25),
        
        /** Standard learning curve - most skills */
        NORMAL(25, 0.2),
        
        /** Harder to learn - specialized skills */
        HARD(15, 0.15),
        
        /** Very hard to learn - advanced techniques */
        VERY_HARD(10, 0.1),
        
        /** Extremely difficult - master-level abilities */
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
         * Formula: baseChance * (1 - (proficiency/100) * scaling)
         * 
         * At proficiency 0%: full base chance
         * At proficiency 100%: base chance * (1 - scaling)
         * 
         * Example for EASY (base=35%, scaling=0.25):
         *   At 0%:   35% chance
         *   At 50%:  35% * (1 - 0.5 * 0.25) = 35% * 0.875 = ~31%
         *   At 100%: 35% * (1 - 1.0 * 0.25) = 35% * 0.75 = ~26%
         * 
         * @param currentProficiency Current proficiency level (1-100)
         * @return Percentage chance (0-100) to gain proficiency on skill use
         */
        public int getGainChance(int currentProficiency) {
            if (this == INSTANT) return 0; // Instant skills don't progress
            if (currentProficiency >= 100) return 0; // Already mastered
            // Chance decreases as proficiency increases
            double modifier = 1.0 - (currentProficiency / 100.0) * difficultyScaling;
            int chance = (int) Math.round(baseGainChance * Math.max(0.1, modifier));
            return Math.max(1, chance); // Always at least 1% chance
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
}
