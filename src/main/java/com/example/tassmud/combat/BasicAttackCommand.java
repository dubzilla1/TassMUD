package com.example.tassmud.combat;

import com.example.tassmud.model.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The basic melee attack command available to all combatants.
 * Uses equipped weapon damage or unarmed combat.
 */
public class BasicAttackCommand implements CombatCommand {
    
    /** Cooldowns per combatant (combatant ID -> timestamp when cooldown ends) */
    private final Map<Long, Long> cooldowns = new ConcurrentHashMap<>();
    
    /** Base cooldown between attacks (2 seconds) */
    private static final long BASE_COOLDOWN_MS = 2000;
    
    /** Base to-hit bonus (d20 + bonus vs armor) */
    private static final int BASE_HIT_BONUS = 0;
    
    /** Unarmed base damage (1d4) */
    private static final int UNARMED_DIE = 4;
    
    /** Critical hit threshold (roll this or higher on d20 = crit) */
    private static final int CRIT_THRESHOLD = 20;
    
    /** Critical hit damage multiplier */
    private static final double CRIT_MULTIPLIER = 2.0;
    
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
        int attackBonus = strMod + BASE_HIT_BONUS;
        
        // Roll d20 + attack bonus vs armor
        int attackRoll = rollD20();
        int totalAttack = attackRoll + attackBonus;
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
        
        // Hit! Calculate damage
        int baseDamage = rollDamage(user);
        int damageBonus = strMod; // STR to damage for melee
        
        int totalDamage = baseDamage + damageBonus;
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
