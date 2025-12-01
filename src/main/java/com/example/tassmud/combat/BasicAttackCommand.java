package com.example.tassmud.combat;

import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.util.OpposedCheck;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The basic melee attack command available to all combatants.
 * Uses equipped weapon damage or unarmed combat.
 * 
 * Attack roll bonus: (attacker_level - defender_level) * 2, capped at attacker's level
 * Damage multiplier: (attacker_family/defender_family) * (attacker_category/defender_category)
 * - Applied to bonus damage only, not base die roll
 * - Mob skills: category = level * 0.02, family = min(level * 0.1, 1.0)
 */
public class BasicAttackCommand implements CombatCommand {
    
    /** Cooldowns per combatant (combatant ID -> timestamp when cooldown ends) */
    private final Map<Long, Long> cooldowns = new ConcurrentHashMap<>();
    
    /** Combat calculator for level/skill bonuses */
    private final CombatCalculator calculator = new CombatCalculator();
    
    /** Base cooldown between attacks (2 seconds) */
    private static final long BASE_COOLDOWN_MS = 2000;
    
    /** Unarmed base damage (1d4) */
    private static final int UNARMED_DIE = 4;
    
    /** Critical hit threshold (roll this or higher on d20 = crit) */
    private static final int CRIT_THRESHOLD = 20;
    
    /** Critical hit damage multiplier */
    private static final double CRIT_MULTIPLIER = 2.0;
    
    /** Parry skill ID (from skills.yaml) */
    private static final int PARRY_SKILL_ID = 13;
    
    /** Parry cooldown in milliseconds (15 seconds, matching skills.yaml) */
    private static final long PARRY_COOLDOWN_MS = 15_000;
    
    @Override
    public String getName() {
        return "attack";
    }
    
    @Override
    public String getDisplayName() {
        return "Attack";
    }
    
    @Override
    public long getCooldownMs() {
        return BASE_COOLDOWN_MS;
    }
    
    @Override
    public boolean canUse(Combatant user, Combat combat) {
        // Check cooldown
        Long cooldownEnd = cooldowns.get(user.getCombatantId());
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            return false;
        }
        
        // Check if user is alive and active
        if (!user.isAlive() || !user.isActive()) {
            return false;
        }
        
        // Check if there are valid targets
        return !combat.getValidTargets(user).isEmpty();
    }
    
    @Override
    public CombatResult execute(Combatant user, Combatant target, Combat combat) {
        // Check for interrupted status - if present, skip the attack
        if (user.consumeInterrupted()) {
            CombatResult result = CombatResult.interrupted(user);
            setCooldown(user);
            return result;
        }
        
        // Verify target is valid
        if (target == null || !target.isAlive() || !target.isActive()) {
            return CombatResult.error("Invalid target");
        }
        
        if (!user.isHostileTo(target)) {
            return CombatResult.error("Cannot attack an ally");
        }
        
        // Get attacker stats
        Character attacker = user.getAsCharacter();
        Character defender = target.getAsCharacter();
        
        if (attacker == null || defender == null) {
            return CombatResult.error("Invalid combatants");
        }
        
        // Calculate attack bonus
        int strMod = (attacker.getStr() - 10) / 2;
        int dexMod = (attacker.getDex() - 10) / 2;
        
        // Use STR for melee (TODO: check weapon type for ranged using DEX)
        int statBonus = strMod;
        
        // Calculate level-based attack bonus
        int levelBonus = calculator.calculateFullAttackBonus(user, target);
        int totalAttackBonus = statBonus + levelBonus;
        
        // Roll d20 + attack bonus vs armor
        int attackRoll = rollD20();
        int totalAttack = attackRoll + totalAttackBonus;
        int targetArmor = target.getArmor();
        
        // Check for critical hit (natural 20)
        boolean isCrit = (attackRoll >= CRIT_THRESHOLD);
        
        // Check for miss (natural 1 always misses, or roll < armor)
        if (attackRoll == 1 || (!isCrit && totalAttack < targetArmor)) {
            // Miss
            CombatResult result = CombatResult.miss(user, target);
            result.setAttackRoll(attackRoll);
            setCooldown(user);
            return result;
        }
        
        // Hit confirmed - check if defender can parry
        CombatResult parryResult = tryParry(target, user, attackRoll);
        if (parryResult != null) {
            setCooldown(user);
            return parryResult;
        }
        
        // Hit! Calculate damage
        int baseDamage = rollDamage(user);
        int damageBonus = strMod; // STR to damage for melee
        
        // Calculate skill-based damage multiplier
        // Applied to bonus damage only, not base die roll
        double damageMultiplier = calculator.calculateFullDamageMultiplier(user, target);
        
        // Apply multiplier to bonus damage only
        int multipliedBonus = (int) Math.round(damageBonus * damageMultiplier);
        int totalDamage = baseDamage + multipliedBonus;
        if (totalDamage < 1) totalDamage = 1; // Minimum 1 damage on hit
        
        // Apply critical multiplier
        if (isCrit) {
            totalDamage = (int)(totalDamage * CRIT_MULTIPLIER);
        }
        
        // Apply damage
        target.damage(totalDamage);
        
        // Set cooldown
        setCooldown(user);
        
        // Create result
        CombatResult result;
        if (!target.isAlive()) {
            result = CombatResult.death(user, target, totalDamage);
        } else if (isCrit) {
            result = CombatResult.criticalHit(user, target, totalDamage);
        } else {
            result = CombatResult.hit(user, target, totalDamage);
        }
        
        result.setAttackRoll(attackRoll);
        result.setDamageRoll(baseDamage);
        
        return result;
    }
    
    /**
     * Attempts a parry by the defender against the attacker.
     * Returns a PARRIED CombatResult if successful, null otherwise.
     *
     * @param defender the combatant being attacked (potential parrier)
     * @param attacker the combatant attacking
     * @param attackRoll the attack roll for logging
     * @return CombatResult.parried if parry succeeds, null if parry doesn't apply or fails
     */
    private CombatResult tryParry(Combatant defender, Combatant attacker, int attackRoll) {
        // Only player characters can parry (mobs may get their own version later)
        Character defenderChar = defender.getAsCharacter();
        if (defenderChar == null) {
            return null;
        }
        
        // Check if defender knows the parry skill
        CharacterSkill parrySkill = defenderChar.getSkill(PARRY_SKILL_ID);
        if (parrySkill == null) {
            return null;
        }
        
        // Check if parry is on cooldown
        if (defender.isParryOnCooldown()) {
            return null;
        }
        
        // Get proficiency percentage (0-100)
        int proficiency = parrySkill.getProficiency();
        
        // Run opposed check: defender's level vs attacker's level, with proficiency
        int defenderLevel = defenderChar.getLevel();
        int attackerLevel = attacker.getLevel();
        
        boolean parrySuccess = OpposedCheck.checkWithProficiency(defenderLevel, attackerLevel, proficiency);
        
        if (parrySuccess) {
            // Set parry on cooldown
            long cooldownEnd = System.currentTimeMillis() + PARRY_COOLDOWN_MS;
            defender.setParryCooldownUntil(cooldownEnd);
            
            // Return parried result
            CombatResult result = CombatResult.parried(attacker, defender);
            result.setAttackRoll(attackRoll);
            return result;
        }
        
        // Parry attempt failed - attack proceeds normally
        return null;
    }
    
    /**
     * Roll a d20.
     */
    private int rollD20() {
        return (int)(Math.random() * 20) + 1;
    }
    
    /**
     * Roll damage for this attacker.
     * TODO: Check equipped weapon for damage dice.
     */
    private int rollDamage(Combatant attacker) {
        // For now: 1d4 unarmed, or mob's base damage if it's a mobile
        if (attacker.isMobile() && attacker.getMobile() != null) {
            int baseDie = attacker.getMobile().getBaseDamage();
            if (baseDie > 0) {
                return (int)(Math.random() * baseDie) + 1 + attacker.getMobile().getDamageBonus();
            }
        }
        
        // Unarmed: 1d4
        return (int)(Math.random() * UNARMED_DIE) + 1;
    }
    
    /**
     * Set cooldown for a combatant.
     */
    private void setCooldown(Combatant user) {
        // TODO: Calculate cooldown based on weapon speed, DEX, haste effects, etc.
        long cooldownEnd = System.currentTimeMillis() + BASE_COOLDOWN_MS;
        cooldowns.put(user.getCombatantId(), cooldownEnd);
        user.setGlobalCooldownUntil(cooldownEnd);
    }
    
    @Override
    public long getCooldownEndTime(Combatant user) {
        Long end = cooldowns.get(user.getCombatantId());
        return end != null ? end : 0;
    }
    
    @Override
    public boolean requiresTarget() {
        return true;
    }
    
    @Override
    public boolean canTargetEnemy() {
        return true;
    }
    
    @Override
    public int getAiPriority() {
        return 0; // Basic attack is lowest priority
    }
}
