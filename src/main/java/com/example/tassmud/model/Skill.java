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
    private final double cooldown;          // cooldown in seconds, 0 means no cooldown
    private final double duration;          // duration in seconds, 0 means instant
    private final List<String> effectIds;   // effect IDs to apply when skill is used

    public Skill(int id, String name, String description) {
        this(id, name, description, SkillProgression.NORMAL, null, 0, 0, null);
    }
    
    public Skill(int id, String name, String description, SkillProgression progression) {
        this(id, name, description, progression, null, 0, 0, null);
    }
    
    public Skill(int id, String name, String description, SkillProgression progression,
                 List<SkillTrait> traits, double cooldown) {
        this(id, name, description, progression, traits, cooldown, 0, null);
    }
    
    public Skill(int id, String name, String description, SkillProgression progression,
                 List<SkillTrait> traits, double cooldown, double duration, List<String> effectIds) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.progression = progression != null ? progression : SkillProgression.NORMAL;
        this.traits = traits != null ? new ArrayList<>(traits) : new ArrayList<>();
        this.cooldown = Math.max(0, cooldown);
        this.duration = Math.max(0, duration);
        this.effectIds = effectIds != null ? new ArrayList<>(effectIds) : new ArrayList<>();
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public SkillProgression getProgression() { return progression; }
    public List<SkillTrait> getTraits() { return Collections.unmodifiableList(traits); }
    public double getCooldown() { return cooldown; }
    public double getDuration() { return duration; }
    public List<String> getEffectIds() { return Collections.unmodifiableList(effectIds); }
    
    /**
     * Check if this skill has associated effects.
     */
    public boolean hasEffects() {
        return !effectIds.isEmpty();
    }
    
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
    
}
