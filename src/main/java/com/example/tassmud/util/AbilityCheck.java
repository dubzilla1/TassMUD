package com.example.tassmud.util;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.model.CooldownType;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.SkillTrait;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.SpellTrait;

/**
 * Utility class for checking ability usage conditions (cooldowns, combat state, etc).
 * Returns failure messages or null if the ability can be used.
 * 
 * These checks apply to both players and mobs, but messages are only relevant for players.
 */
public class AbilityCheck {
    
    /**
     * Result of an ability check - either success (null message) or failure with a reason.
     */
    public static class CheckResult {
        private final boolean success;
        private final String failureMessage;
        
        private CheckResult(boolean success, String failureMessage) {
            this.success = success;
            this.failureMessage = failureMessage;
        }
        
        public static CheckResult success() {
            return new CheckResult(true, null);
        }
        
        public static CheckResult failure(String message) {
            return new CheckResult(false, message);
        }
        
        public boolean isSuccess() { return success; }
        public boolean isFailure() { return !success; }
        public String getFailureMessage() { return failureMessage; }
    }
    
    // ========== Skill Checks ==========
    
    /**
     * Check if a player can use a skill.
     * @param characterName player's name (for cooldown lookup)
     * @param characterId player's DB id (for combat check)
     * @param skill the skill to check
     * @return CheckResult - success or failure with message
     */
    public static CheckResult canPlayerUseSkill(String characterName, Integer characterId, Skill skill) {
        if (skill == null) {
            return CheckResult.failure("Invalid skill.");
        }
        
        // Check cooldown first
        CooldownManager cooldownMgr = CooldownManager.getInstance();
        if (cooldownMgr.isPlayerOnCooldown(characterName, CooldownType.SKILL, skill.getId())) {
            double remaining = cooldownMgr.getPlayerCooldownRemaining(characterName, CooldownType.SKILL, skill.getId());
            int seconds = (int) Math.ceil(remaining);
            return CheckResult.failure(skill.getName() + " is on cooldown for another " + seconds + " second" + (seconds != 1 ? "s" : "") + ".");
        }
        
        // Check combat traits and status effects
        CombatManager combatMgr = CombatManager.getInstance();
        boolean inCombat = characterId != null && combatMgr.isInCombat(characterId);
        
        if (skill.hasTrait(SkillTrait.COMBAT) && !inCombat) {
            return CheckResult.failure(skill.getName() + " can only be used in combat.");
        }
        
        if (skill.hasTrait(SkillTrait.NOCOMBAT) && inCombat) {
            return CheckResult.failure(skill.getName() + " cannot be used in combat.");
        }
        
        // Check SHIELD trait - requires shield equipped in off-hand
        if (skill.hasTrait(SkillTrait.SHIELD) && characterId != null) {
            com.example.tassmud.persistence.CharacterDAO charDao = new com.example.tassmud.persistence.CharacterDAO();
            com.example.tassmud.persistence.ItemDAO itemDao = new com.example.tassmud.persistence.ItemDAO();
            if (!charDao.hasShield(characterId, itemDao)) {
                return CheckResult.failure(skill.getName() + " requires a shield equipped.");
            }
        }
        
        // Check STUNNED status - prevents using skills with cooldowns
        if (skill.hasCooldown() && characterId != null) {
            com.example.tassmud.combat.Combatant combatant = combatMgr.getCombatantForCharacter(characterId);
            if (combatant != null && combatant.isStunned()) {
                // Consume the stun (it wears off after blocking one skill attempt)
                combatant.consumeStunned();
                return CheckResult.failure("You are stunned and cannot use " + skill.getName() + "!");
            }
        }
        
        return CheckResult.success();
    }
    
    /**
     * Check if a mob can use a skill.
     * @param mobileInstanceId mob's instance ID (for cooldown lookup)
     * @param skill the skill to check
     * @param inCombat whether the mob is currently in combat
     * @return CheckResult - success or failure (message not shown to players)
     */
    public static CheckResult canMobileUseSkill(long mobileInstanceId, Skill skill, boolean inCombat) {
        if (skill == null) {
            return CheckResult.failure("Invalid skill.");
        }
        
        // Check cooldown
        CooldownManager cooldownMgr = CooldownManager.getInstance();
        if (cooldownMgr.isMobileOnCooldown(mobileInstanceId, CooldownType.SKILL, skill.getId())) {
            return CheckResult.failure("On cooldown.");
        }
        
        // Check combat traits
        if (skill.hasTrait(SkillTrait.COMBAT) && !inCombat) {
            return CheckResult.failure("Combat only.");
        }
        
        if (skill.hasTrait(SkillTrait.NOCOMBAT) && inCombat) {
            return CheckResult.failure("No combat.");
        }
        
        return CheckResult.success();
    }
    
    /**
     * Apply skill cooldown after successful use.
     * Call this ONLY after the skill has been successfully executed.
     */
    public static void applyPlayerSkillCooldown(String characterName, Skill skill) {
        if (skill != null && skill.hasCooldown()) {
            CooldownManager.getInstance().setPlayerCooldown(
                characterName, CooldownType.SKILL, skill.getId(), skill.getCooldown());
        }
    }
    
    /**
     * Apply skill cooldown for a mob after successful use.
     */
    public static void applyMobileSkillCooldown(long mobileInstanceId, Skill skill) {
        if (skill != null && skill.hasCooldown()) {
            CooldownManager.getInstance().setMobileCooldown(
                mobileInstanceId, CooldownType.SKILL, skill.getId(), skill.getCooldown());
        }
    }
    
    // ========== Spell Checks ==========
    
    /**
     * Check if a player can cast a spell.
     * @param characterName player's name (for cooldown lookup)
     * @param characterId player's DB id (for combat check)
     * @param spell the spell to check
     * @return CheckResult - success or failure with message
     */
    public static CheckResult canPlayerCastSpell(String characterName, Integer characterId, Spell spell) {
        if (spell == null) {
            return CheckResult.failure("Invalid spell.");
        }
        
        // Check cooldown first
        CooldownManager cooldownMgr = CooldownManager.getInstance();
        if (cooldownMgr.isPlayerOnCooldown(characterName, CooldownType.SPELL, spell.getId())) {
            double remaining = cooldownMgr.getPlayerCooldownRemaining(characterName, CooldownType.SPELL, spell.getId());
            int seconds = (int) Math.ceil(remaining);
            return CheckResult.failure(spell.getName() + " is on cooldown for another " + seconds + " second" + (seconds != 1 ? "s" : "") + ".");
        }
        
        // Check combat traits and status effects
        CombatManager combatMgr = CombatManager.getInstance();
        boolean inCombat = characterId != null && combatMgr.isInCombat(characterId);
        
        if (spell.hasTrait(SpellTrait.COMBAT) && !inCombat) {
            return CheckResult.failure(spell.getName() + " can only be used in combat.");
        }
        
        if (spell.hasTrait(SpellTrait.NOCOMBAT) && inCombat) {
            return CheckResult.failure(spell.getName() + " cannot be used in combat.");
        }
        
        // Check status effects that block spellcasting
        if (characterId != null) {
            com.example.tassmud.combat.Combatant combatant = combatMgr.getCombatantForCharacter(characterId);
            if (combatant != null) {
                // SILENCED blocks all spells
                if (combatant.hasStatusFlag(com.example.tassmud.combat.Combatant.StatusFlag.SILENCED)) {
                    return CheckResult.failure("You are silenced and cannot cast spells!");
                }
                
                // STUNNED blocks spells with cooldowns
                if (spell.hasCooldown() && combatant.isStunned()) {
                    combatant.consumeStunned();
                    return CheckResult.failure("You are stunned and cannot cast " + spell.getName() + "!");
                }
            }
        }
        
        return CheckResult.success();
    }
    
    /**
     * Check if a mob can cast a spell.
     * @param mobileInstanceId mob's instance ID (for cooldown lookup)
     * @param spell the spell to check
     * @param inCombat whether the mob is currently in combat
     * @return CheckResult - success or failure (message not shown to players)
     */
    public static CheckResult canMobileCastSpell(long mobileInstanceId, Spell spell, boolean inCombat) {
        if (spell == null) {
            return CheckResult.failure("Invalid spell.");
        }
        
        // Check cooldown
        CooldownManager cooldownMgr = CooldownManager.getInstance();
        if (cooldownMgr.isMobileOnCooldown(mobileInstanceId, CooldownType.SPELL, spell.getId())) {
            return CheckResult.failure("On cooldown.");
        }
        
        // Check combat traits
        if (spell.hasTrait(SpellTrait.COMBAT) && !inCombat) {
            return CheckResult.failure("Combat only.");
        }
        
        if (spell.hasTrait(SpellTrait.NOCOMBAT) && inCombat) {
            return CheckResult.failure("No combat.");
        }
        
        return CheckResult.success();
    }
    
    /**
     * Apply spell cooldown after successful cast.
     * Call this ONLY after the spell has been successfully cast.
     */
    public static void applyPlayerSpellCooldown(String characterName, Spell spell) {
        if (spell != null && spell.hasCooldown()) {
            CooldownManager.getInstance().setPlayerCooldown(
                characterName, CooldownType.SPELL, spell.getId(), spell.getCooldown());
        }
    }
    
    /**
     * Apply spell cooldown for a mob after successful cast.
     */
    public static void applyMobileSpellCooldown(long mobileInstanceId, Spell spell) {
        if (spell != null && spell.hasCooldown()) {
            CooldownManager.getInstance().setMobileCooldown(
                mobileInstanceId, CooldownType.SPELL, spell.getId(), spell.getCooldown());
        }
    }
}
