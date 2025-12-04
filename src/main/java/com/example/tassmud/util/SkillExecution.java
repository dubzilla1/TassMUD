package com.example.tassmud.util;

import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
import com.example.tassmud.persistence.CharacterDAO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Unified skill execution handler that manages the full lifecycle of using a skill:
 * 1. Pre-check (cooldowns, combat state, etc.)
 * 2. Execute the skill logic
 * 3. Apply cooldown
 * 4. Check for proficiency growth
 * 
 * This ensures consistent behavior for all skill uses and automatic proficiency tracking.
 */
public class SkillExecution {
    
    /**
     * Result of a skill execution attempt.
     */
    public static class Result {
        private final boolean executed;           // Did the skill execute (passed pre-checks)?
        private final boolean succeeded;          // Did the skill effect succeed?
        private final String preCheckFailure;     // If executed=false, why?
        private final ProficiencyCheck.Result proficiencyResult;  // Proficiency growth result
        
        private Result(boolean executed, boolean succeeded, String preCheckFailure, 
                       ProficiencyCheck.Result proficiencyResult) {
            this.executed = executed;
            this.succeeded = succeeded;
            this.preCheckFailure = preCheckFailure;
            this.proficiencyResult = proficiencyResult;
        }
        
        /** Did the skill pass pre-checks and execute? */
        public boolean wasExecuted() { return executed; }
        
        /** Did the skill effect succeed (only meaningful if wasExecuted() is true)? */
        public boolean wasSuccessful() { return succeeded; }
        
        /** Get the pre-check failure message (only meaningful if wasExecuted() is false) */
        public String getPreCheckFailure() { return preCheckFailure; }
        
        /** Did proficiency improve? */
        public boolean didProficiencyImprove() { 
            return proficiencyResult != null && proficiencyResult.hasImproved(); 
        }
        
        /** Get the proficiency improvement message, or null if no improvement */
        public String getProficiencyMessage() {
            return proficiencyResult != null ? proficiencyResult.getImprovementMessage() : null;
        }
        
        /** Get the full proficiency result */
        public ProficiencyCheck.Result getProficiencyResult() { return proficiencyResult; }
        
        // Static factory methods
        static Result preCheckFailed(String message) {
            return new Result(false, false, message, null);
        }
        
        static Result executed(boolean succeeded, ProficiencyCheck.Result profResult) {
            return new Result(true, succeeded, null, profResult);
        }
    }
    
    /**
     * Execute a skill for a player character with full lifecycle management.
     * 
     * This method:
     * 1. Checks if the skill can be used (cooldowns, combat state)
     * 2. Executes the provided skill logic
     * 3. Applies cooldown (regardless of skill success)
     * 4. Checks for proficiency growth (1 roll on success, 2 rolls on failure)
     * 
     * @param characterName player's name
     * @param characterId player's DB id
     * @param skill the skill definition
     * @param charSkill the character's proficiency in this skill
     * @param dao CharacterDAO for persistence
     * @param skillLogic supplier that executes the skill and returns true if it succeeded
     * @return Result containing execution status, success, and proficiency growth
     */
    public static Result executePlayerSkill(String characterName, Integer characterId, 
                                            Skill skill, CharacterSkill charSkill,
                                            CharacterDAO dao, Supplier<Boolean> skillLogic) {
        // 1. Pre-check
        AbilityCheck.CheckResult preCheck = AbilityCheck.canPlayerUseSkill(characterName, characterId, skill);
        if (preCheck.isFailure()) {
            return Result.preCheckFailed(preCheck.getFailureMessage());
        }
        
        // 2. Execute skill logic
        boolean succeeded = skillLogic.get();
        
        // 3. Apply cooldown (always, regardless of success)
        AbilityCheck.applyPlayerSkillCooldown(characterName, skill);
        
        // 4. Check proficiency growth
        ProficiencyCheck.Result profResult = null;
        if (charSkill != null) {
            profResult = ProficiencyCheck.checkProficiencyGrowth(
                characterId, skill, charSkill, succeeded, dao);
        }
        
        return Result.executed(succeeded, profResult);
    }
    
    /**
     * Execute a skill for a player character without a Supplier (for simpler cases).
     * Use this when you need to handle the skill logic separately but still want
     * cooldown and proficiency management.
     * 
     * @param characterName player's name
     * @param characterId player's DB id
     * @param skill the skill definition
     * @param charSkill the character's proficiency in this skill
     * @param dao CharacterDAO for persistence
     * @param succeeded whether the skill effect succeeded
     * @return Result containing execution status and proficiency growth
     */
    public static Result recordPlayerSkillUse(String characterName, Integer characterId,
                                              Skill skill, CharacterSkill charSkill,
                                              CharacterDAO dao, boolean succeeded) {
        // Apply cooldown
        AbilityCheck.applyPlayerSkillCooldown(characterName, skill);
        
        // Check proficiency growth
        ProficiencyCheck.Result profResult = null;
        if (charSkill != null) {
            profResult = ProficiencyCheck.checkProficiencyGrowth(
                characterId, skill, charSkill, succeeded, dao);
        }
        
        return Result.executed(succeeded, profResult);
    }
    
    /**
     * Convenience method that only checks prerequisites without executing.
     * Useful when skill logic is complex and handled elsewhere.
     */
    public static AbilityCheck.CheckResult checkPlayerCanUseSkill(String characterName, 
                                                                   Integer characterId, 
                                                                   Skill skill) {
        return AbilityCheck.canPlayerUseSkill(characterName, characterId, skill);
    }
    
    /**
     * Execute a skill for a mobile/NPC.
     * Mobs don't have proficiency growth, so this only handles cooldowns.
     * 
     * @param mobileInstanceId mob's instance ID
     * @param skill the skill definition
     * @param inCombat whether the mob is in combat
     * @param skillLogic supplier that executes the skill and returns true if it succeeded
     * @return true if the skill was executed (passed pre-checks)
     */
    public static boolean executeMobileSkill(long mobileInstanceId, Skill skill, 
                                             boolean inCombat, Supplier<Boolean> skillLogic) {
        // Pre-check
        AbilityCheck.CheckResult preCheck = AbilityCheck.canMobileUseSkill(mobileInstanceId, skill, inCombat);
        if (preCheck.isFailure()) {
            return false;
        }
        
        // Execute skill logic (we don't care about success for mobs)
        skillLogic.get();
        
        // Apply cooldown
        AbilityCheck.applyMobileSkillCooldown(mobileInstanceId, skill);
        
        return true;
    }
    
    /**
     * Result of applying skill effects.
     */
    public static class EffectResult {
        private final List<String> appliedEffects;
        private final List<String> failedEffects;
        
        public EffectResult() {
            this.appliedEffects = new ArrayList<>();
            this.failedEffects = new ArrayList<>();
        }
        
        public void addApplied(String effectName) { appliedEffects.add(effectName); }
        public void addFailed(String effectId) { failedEffects.add(effectId); }
        
        public List<String> getAppliedEffects() { return appliedEffects; }
        public List<String> getFailedEffects() { return failedEffects; }
        public boolean hasAppliedEffects() { return !appliedEffects.isEmpty(); }
        public boolean hasFailures() { return !failedEffects.isEmpty(); }
        
        /**
         * Get a summary message of applied effects.
         */
        public String getSummary() {
            if (appliedEffects.isEmpty()) return null;
            return String.join(", ", appliedEffects);
        }
    }
    
    /**
     * Apply all effects associated with a skill.
     * This is used for skills that trigger spell-like effects (e.g., Heroic Strike applies Heroism).
     * 
     * @param skill the skill with effectIds
     * @param casterId the character ID of the skill user (caster)
     * @param targetId the character ID of the target (often same as caster for self-buffs)
     * @param proficiency the user's proficiency in this skill (1-100)
     * @return EffectResult containing applied and failed effects
     */
    public static EffectResult applySkillEffects(Skill skill, Integer casterId, Integer targetId, int proficiency) {
        EffectResult result = new EffectResult();
        
        if (skill == null || !skill.hasEffects()) {
            return result;
        }
        
        Map<String, String> extraParams = new HashMap<>();
        extraParams.put("proficiency", String.valueOf(proficiency));
        
        for (String effId : skill.getEffectIds()) {
            EffectDefinition def = EffectRegistry.getDefinition(effId);
            if (def == null) {
                result.addFailed(effId);
                continue;
            }
            
            EffectInstance inst = EffectRegistry.apply(effId, casterId, targetId, extraParams);
            if (inst != null) {
                result.addApplied(def.getName());
            } else {
                result.addFailed(effId);
            }
        }
        
        return result;
    }
    
    /**
     * Apply skill effects to self (convenience method where caster = target).
     */
    public static EffectResult applySkillEffectsToSelf(Skill skill, Integer characterId, int proficiency) {
        return applySkillEffects(skill, characterId, characterId, proficiency);
    }
}
