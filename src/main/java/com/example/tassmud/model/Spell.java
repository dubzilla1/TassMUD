package com.example.tassmud.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a spell definition.
 * Spells belong to a school (Arcane, Divine, Primal, Occult) and have a level 1-10.
 */
public class Spell {
    private final int id;
    private final String name;
    private final String description;
    private final SpellSchool school;
    private final int level;                    // 1-10
    private final double baseCastingTime;       // in seconds
    private final SpellTarget target;
    private final List<String> effectIds;       // spell effect IDs
    private final Skill.SkillProgression progression;
    private final List<SpellTrait> traits;
    private final double cooldown;              // cooldown in seconds, 0 means no cooldown

    public Spell(int id, String name, String description) {
        this(id, name, description, SpellSchool.ARCANE, 1, 1.0, SpellTarget.SELF, 
             null, Skill.SkillProgression.NORMAL, null, 0);
    }
    
    public Spell(int id, String name, String description, SpellSchool school, int level,
                 double baseCastingTime, SpellTarget target, List<String> effectIds,
                 Skill.SkillProgression progression) {
        this(id, name, description, school, level, baseCastingTime, target, effectIds,
             progression, null, 0);
    }
    
    public Spell(int id, String name, String description, SpellSchool school, int level,
                 double baseCastingTime, SpellTarget target, List<String> effectIds,
                 Skill.SkillProgression progression, List<SpellTrait> traits, double cooldown) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.school = school != null ? school : SpellSchool.ARCANE;
        this.level = Math.max(1, Math.min(10, level));
        this.baseCastingTime = baseCastingTime;
        this.target = target != null ? target : SpellTarget.SELF;
        this.effectIds = effectIds != null ? new ArrayList<>(effectIds) : new ArrayList<>();
        this.progression = progression != null ? progression : Skill.SkillProgression.NORMAL;
        this.traits = traits != null ? new ArrayList<>(traits) : new ArrayList<>();
        this.cooldown = Math.max(0, cooldown);
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public SpellSchool getSchool() { return school; }
    public int getLevel() { return level; }
    public double getBaseCastingTime() { return baseCastingTime; }
    public SpellTarget getTarget() { return target; }
    public List<String> getEffectIds() { return Collections.unmodifiableList(effectIds); }
    public Skill.SkillProgression getProgression() { return progression; }
    public List<SpellTrait> getTraits() { return Collections.unmodifiableList(traits); }
    public double getCooldown() { return cooldown; }
    
    /**
     * Check if this spell has a specific trait.
     */
    public boolean hasTrait(SpellTrait trait) {
        return traits.contains(trait);
    }
    
    /**
     * Check if this spell has a cooldown.
     */
    public boolean hasCooldown() {
        return cooldown > 0;
    }
    
    /**
     * Check if this spell requires combat.
     */
    public boolean requiresCombat() {
        return hasTrait(SpellTrait.COMBAT);
    }
    
    /**
     * Spell schools - each has different acquisition methods.
     */
    public enum SpellSchool {
        /** Arcane magic - learned from spellbooks */
        ARCANE("Arcane", "arcane_magic"),
        
        /** Divine magic - learned through prayer and offerings */
        DIVINE("Divine", "divine_magic"),
        
        /** Primal magic - learned through exploration of nature */
        PRIMAL("Primal", "primal_magic"),
        
        /** Occult magic - learned via rituals using corpses of deceased casters */
        OCCULT("Occult", "occult_magic");
        
        public final String displayName;
        public final String requiredSkillKey;  // The skill key required to learn spells of this school
        
        SpellSchool(String displayName, String requiredSkillKey) {
            this.displayName = displayName;
            this.requiredSkillKey = requiredSkillKey;
        }
        
        /**
         * Parse school from string (case-insensitive).
         */
        public static SpellSchool fromString(String s) {
            if (s == null || s.isEmpty()) return ARCANE;
            try {
                return valueOf(s.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                return ARCANE;
            }
        }
    }
    
    /**
     * Spell targeting types.
     */
    public enum SpellTarget {
        /** Targets the caster */
        SELF("self"),
        
        /** Targets the current enemy in combat */
        CURRENT_ENEMY("current_enemy"),
        
        /** Requires explicit mob target selection */
        EXPLICIT_MOB_TARGET("explicit_mob_target"),
        
        /** Targets an item */
        ITEM("item"),
        
        /** Targets all enemies in combat/room */
        ALL_ENEMIES("all_enemies"),
        
        /** Targets all allies in room/group */
        ALL_ALLIES("all_allies"),
        
        /** Targets everyone in room */
        EVERYONE("everyone");
        
        public final String key;
        
        SpellTarget(String key) {
            this.key = key;
        }
        
        /**
         * Parse target from string (case-insensitive).
         */
        public static SpellTarget fromString(String s) {
            if (s == null || s.isEmpty()) return SELF;
            String normalized = s.toLowerCase().trim().replace("-", "_").replace(" ", "_");
            for (SpellTarget t : values()) {
                if (t.key.equals(normalized) || t.name().toLowerCase().equals(normalized)) {
                    return t;
                }
            }
            return SELF;
        }
    }
}
