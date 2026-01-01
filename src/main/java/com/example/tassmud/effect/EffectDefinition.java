package com.example.tassmud.effect;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class EffectDefinition {
    public enum ProficiencyImpact { DURATION, COOLDOWN, DICE_MULTIPLIER }
    public enum Type { MODIFIER, HEAL, DOT, CUSTOM, INSTANT_HEAL, INSTANT_DAMAGE, WEAPON_INFUSION, DEBUFF, BURNING_HANDS, CALL_LIGHTNING, CAUSE_WOUNDS, UNDEAD, SLOW, CONFUSED, PARALYZED, CURSED, FLYING }
    public enum StackPolicy { STACK, REFRESH, REPLACE_HIGHER_PRIORITY, UNIQUE }

    private final String id;
    private final String name;
    private final Type type;
    private final Map<String, String> params;
    private final double durationSeconds;
    private final double cooldownSeconds;
    private final String diceMultiplierRaw;
    private final int levelMultiplier;
    private final Set<ProficiencyImpact> proficiencyImpact;
    private final StackPolicy stackPolicy;
    private final boolean persistent;
    private final int priority;

    public EffectDefinition(String id, String name, Type type, Map<String,String> params,
                            double durationSeconds, double cooldownSeconds, String diceMultiplierRaw,
                            int levelMultiplier,
                            Set<ProficiencyImpact> proficiencyImpact, StackPolicy stackPolicy, boolean persistent, int priority) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.params = params;
        this.durationSeconds = durationSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.diceMultiplierRaw = diceMultiplierRaw == null ? "" : diceMultiplierRaw;
        this.levelMultiplier = levelMultiplier;
        this.proficiencyImpact = proficiencyImpact == null ? new HashSet<>() : proficiencyImpact;
        this.stackPolicy = stackPolicy;
        this.persistent = persistent;
        this.priority = priority;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Type getType() { return type; }
    public Map<String,String> getParams() { return params; }
    public double getDurationSeconds() { return durationSeconds; }
    public double getCooldownSeconds() { return cooldownSeconds; }
    public String getDiceMultiplierRaw() { return diceMultiplierRaw; }
    public int getLevelMultiplier() { return levelMultiplier; }
    public Set<ProficiencyImpact> getProficiencyImpact() { return proficiencyImpact; }
    public StackPolicy getStackPolicy() { return stackPolicy; }
    public boolean isPersistent() { return persistent; }
    public int getPriority() { return priority; }
}
