package com.example.tassmud.combat;

import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
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
    
    /** Riposte skill ID (from skills.yaml) */
    private static final int RIPOSTE_SKILL_ID = 14;
    
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
        return executeWithPenalty(user, target, combat, 0);
    }
    
    /**
     * Execute an attack with a level penalty (for multi-attack).
     * 
     * @param user The attacking combatant
     * @param target The target combatant
     * @param combat The combat instance
     * @param levelPenalty Level penalty to apply (e.g., 1 for second attack)
     * @return The combat result
     */
    public CombatResult executeWithPenalty(Combatant user, Combatant target, Combat combat, int levelPenalty) {
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
        @SuppressWarnings("unused") // TODO: check weapon type for ranged using DEX
        int dexMod = (attacker.getDex() - 10) / 2;
        
        // Use STR for melee (TODO: check weapon type for ranged using DEX)
        int statBonus = strMod;
        
        // Check if target is prone - affects advantage/disadvantage based on weapon type
        int proneModifier = 0;
        if (target.isProne()) {
            if (calculator.isUsingRangedWeapon(user)) {
                // Ranged attacks have disadvantage against prone targets
                proneModifier = -1;
            } else {
                // Melee attacks have advantage against prone targets
                proneModifier = 1;
            }
        }
        
        // Calculate effective level penalty:
        // - Start with multi-attack penalty (0, 1, 2, or 3)
        // - Subtract advantage/disadvantage modifier (+1 for advantage, -1 for disadvantage)
        // - Subtract prone modifier (+1 for melee vs prone, -1 for ranged vs prone)
        // - So advantage/melee-vs-prone reduces penalty, disadvantage/ranged-vs-prone increases it
        int advantageModifier = user.getAttackLevelModifier();
        int effectivePenalty = levelPenalty - advantageModifier - proneModifier;
        
        // Calculate level-based attack bonus (with effective penalty)
        int levelBonus = calculator.calculateFullAttackBonus(user, target, effectivePenalty);
        // Include any modifier-based attack hit bonuses
        int attackHitBonus = attacker.getAttackHitBonus();
        int totalAttackBonus = statBonus + levelBonus + attackHitBonus;
        
        // Roll d20 + attack bonus vs armor
        int attackRoll = rollD20();
        int totalAttack = attackRoll + totalAttackBonus;
        int targetArmor = target.getArmor();
        
        // Check for critical hit (natural 20, or lower with CRITICAL_THRESHOLD_BONUS)
        // The bonus reduces the threshold (e.g., -1 means crit on 19+, -18 means crit on 2+)
        int critThreshold = CRIT_THRESHOLD + user.getCriticalThresholdBonus();
        critThreshold = Math.max(2, critThreshold); // Can't crit on natural 1
        boolean isCrit = (attackRoll >= critThreshold);
        
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
        // STR to damage for melee + modifier-based attack damage bonus
        int damageBonus = strMod + attacker.getAttackDamageBonus();
        
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

        // Apply defender's attack damage reduction (flat)
        int reduction = defender.getAttackDamageReduction();
        if (reduction > 0) {
            totalDamage = Math.max(0, totalDamage - reduction);
        }

        if (totalDamage < 1) totalDamage = 1;
        
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
        if (!defender.isPlayer() || defender.getCharacterId() == null) {
            return null;
        }
        
        // Check if defender knows the parry skill
        CharacterDAO dao = new CharacterDAO();
        CharacterSkill parrySkill = dao.getCharacterSkill(defender.getCharacterId(), PARRY_SKILL_ID);
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
        int defenderLevel = calculator.getCombatantLevel(defender);
        int attackerLevel = calculator.getCombatantLevel(attacker);
        
        boolean parrySuccess = OpposedCheck.checkWithProficiency(defenderLevel, attackerLevel, proficiency);
        
        if (parrySuccess) {
            // Set parry on cooldown
            long cooldownEnd = System.currentTimeMillis() + PARRY_COOLDOWN_MS;
            defender.setParryCooldownUntil(cooldownEnd);
            
            // Check for riposte opportunity
            checkRiposte(defender, attacker);
            
            // Return parried result
            CombatResult result = CombatResult.parried(attacker, defender);
            result.setAttackRoll(attackRoll);
            return result;
        }
        
        // Parry attempt failed - attack proceeds normally
        return null;
    }
    
    /**
     * Check if the defender can riposte after a successful parry.
     * If they know Riposte and pass an opposed check, they get a bonus attack next round.
     * 
     * @param defender the combatant who parried
     * @param attacker the combatant whose attack was parried
     */
    private void checkRiposte(Combatant defender, Combatant attacker) {
        // Only player characters can riposte for now
        if (!defender.isPlayer() || defender.getCharacterId() == null) {
            return;
        }
        
        // Check if defender knows Riposte skill
        CharacterDAO dao = new CharacterDAO();
        CharacterSkill riposteSkill = dao.getCharacterSkill(defender.getCharacterId(), RIPOSTE_SKILL_ID);
        if (riposteSkill == null) {
            return; // Doesn't know Riposte
        }
        
        int proficiency = riposteSkill.getProficiency();
        int defenderLevel = calculator.getCombatantLevel(defender);
        int attackerLevel = calculator.getCombatantLevel(attacker);
        
        // Opposed check for riposte opportunity
        boolean riposteSuccess = OpposedCheck.checkWithProficiency(defenderLevel, attackerLevel, proficiency);
        
        if (riposteSuccess) {
            defender.addRiposteAttack();
            
            // Try to improve proficiency
            Skill riposteDef = dao.getSkillById(RIPOSTE_SKILL_ID);
            if (riposteDef != null) {
                dao.tryImproveSkill(defender.getCharacterId(), RIPOSTE_SKILL_ID, riposteDef);
            }
            
            // TODO: Send message about riposte opportunity via ClientHandler
        }
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
