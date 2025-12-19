package com.example.tassmud.combat;

import com.example.tassmud.effect.WeaponInfusionEffect;
import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.util.OpposedCheck;

import java.util.ArrayList;
import java.util.List;
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
        
        // Check for active weapon infusion on the equipped weapon
        WeaponFamily weaponFamily = calculator.getEquippedWeaponFamily(user);
        WeaponInfusionEffect.InfusionData infusion = null;
        if (user.isPlayer() && user.getCharacterId() != null && weaponFamily != null) {
            infusion = WeaponInfusionEffect.getActiveInfusion(user.getCharacterId(), weaponFamily);
        }
        
        // Determine if this attack should be treated as ranged
        // Either from a ranged weapon OR from a weapon infusion that makes it ranged
        boolean isRangedAttack = calculator.isUsingRangedWeapon(user) || (infusion != null && infusion.isRanged);
        
        // Calculate stat modifiers
        int strMod = (attacker.getStr() - 10) / 2;
        int dexMod = (attacker.getDex() - 10) / 2;
        int intMod = (attacker.getIntel() - 10) / 2;
        
        // Determine which stat to use for attack/damage
        // Default: STR for melee, DEX for ranged
        // Weapon infusions can override this (e.g., INT for arcane infusion)
        int statBonus;
        int damageStatMod;
        if (infusion != null) {
            // Use the stat specified by the infusion
            damageStatMod = getStatMod(attacker, infusion.attackStat);
            statBonus = damageStatMod; // Use same stat for attack roll
        } else if (isRangedAttack) {
            statBonus = dexMod;
            damageStatMod = dexMod;
        } else {
            statBonus = strMod;
            damageStatMod = strMod;
        }
        
        // Determine which defense to target
        // Default: Armor
        // Weapon infusions can override this (e.g., Reflex for arcane infusion)
        int targetDefense;
        String defenseType;
        if (infusion != null && "REFLEX".equals(infusion.defenseStat)) {
            targetDefense = target.getReflex();
            defenseType = "reflex";
        } else {
            targetDefense = target.getArmor();
            defenseType = "armor";
        }
        
        // Check if target is prone - affects advantage/disadvantage based on weapon type
        int proneModifier = 0;
        if (target.isProne()) {
            if (isRangedAttack) {
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
        
        // Roll d20 + attack bonus vs target defense
        int attackRoll = rollD20();
        int totalAttack = attackRoll + totalAttackBonus;
        
        // Check for critical hit (natural 20, or lower with CRITICAL_THRESHOLD_BONUS)
        // The bonus reduces the threshold (e.g., -1 means crit on 19+, -18 means crit on 2+)
        int critThreshold = CRIT_THRESHOLD + user.getCriticalThresholdBonus();
        critThreshold = Math.max(2, critThreshold); // Can't crit on natural 1
        boolean isCrit = (attackRoll >= critThreshold);
        
        // Check for miss (natural 1 always misses, or roll < target defense)
        if (attackRoll == 1 || (!isCrit && totalAttack < targetDefense)) {
            // Miss - build debug info
            String debugInfo = buildAttackDebugInfo(
                attacker.getName(), attackRoll, totalAttackBonus,
                defender.getName(), defenseType.toUpperCase(), targetDefense,
                false, null, 0, 0
            );
            
            CombatResult result = CombatResult.miss(user, target);
            result.setAttackRoll(attackRoll);
            result.setDebugInfo(debugInfo);
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
        
        // Calculate damage bonus from ability score
        // For weapons: use weapon's abilityScore and abilityMultiplier (from instance or template)
        // For infusions: use the infusion's specified stat
        // For unarmed/mobs: use default STR
        int damageBonus;
        if (infusion != null) {
            // Infusion overrides weapon ability - use infusion's stat with 1x multiplier
            damageBonus = damageStatMod;
        } else if (user.isPlayer() && user.getCharacterId() != null) {
            // Player with weapon - use weapon's ability score and multiplier
            damageBonus = getWeaponAbilityDamageBonus(user, attacker);
            // If unarmed (returns 0), fall back to STR mod
            CharacterDAO tempDao = new CharacterDAO();
            Long mainHandId = tempDao.getCharacterEquipment(user.getCharacterId(), 
                com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
            if (mainHandId == null) {
                damageBonus = strMod; // Unarmed uses STR
            }
        } else {
            // Mob or fallback - use default stat
            damageBonus = isRangedAttack ? dexMod : strMod;
        }
        
        // Two-handed melee weapons get 1.5x STR bonus to damage (only for unarmed/default, not weapon ability)
        // Note: For weapons with custom ability multipliers, the multiplier is already applied
        if (!isRangedAttack && infusion == null && isTwoHandedWeapon(user)) {
            // Only apply 1.5x bonus if using default STR calculation (not custom weapon ability)
            // Check if weapon has custom ability multiplier > 1.0
            CharacterDAO th2Dao = new CharacterDAO();
            com.example.tassmud.persistence.ItemDAO th2ItemDAO = new com.example.tassmud.persistence.ItemDAO();
            Long mainHandId = th2Dao.getCharacterEquipment(user.getCharacterId(), 
                com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
            if (mainHandId != null) {
                com.example.tassmud.model.ItemInstance weaponInst = th2ItemDAO.getInstance(mainHandId);
                com.example.tassmud.model.ItemTemplate weaponTmpl = weaponInst != null 
                    ? th2ItemDAO.getTemplateById(weaponInst.templateId) : null;
                double abilMult = weaponInst != null ? weaponInst.getEffectiveAbilityMultiplier(weaponTmpl) : 1.0;
                // Only apply 1.5x if weapon uses default 1.0 multiplier
                if (abilMult <= 1.0) {
                    damageBonus = (int) Math.floor(damageBonus * 1.5);
                }
            }
        }
        damageBonus += attacker.getAttackDamageBonus();
        
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
        
        // Build debug info for hit
        String damageDice = getDamageDiceNotation(user);
        String debugInfo = buildAttackDebugInfo(
            attacker.getName(), attackRoll, totalAttackBonus,
            defender.getName(), defenseType.toUpperCase(), targetDefense,
            true, damageDice, baseDamage, multipliedBonus
        );
        
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
        result.setDebugInfo(debugInfo);
        
        return result;
    }
    
    /**
     * Execute an AoE (Area of Effect) attack against all valid targets.
     * Used when the attacker has an AoE weapon infusion active.
     * 
     * @param user The attacking combatant
     * @param combat The combat instance
     * @param levelPenalty Level penalty to apply
     * @return List of combat results for each target hit
     */
    public List<CombatResult> executeAoE(Combatant user, Combat combat, int levelPenalty) {
        List<CombatResult> results = new ArrayList<>();
        
        // Get all valid targets (all enemies)
        List<Combatant> targets = combat.getValidTargets(user);
        if (targets.isEmpty()) {
            return results;
        }
        
        // Execute attack against each target
        for (Combatant target : targets) {
            if (target.isAlive() && target.isActive()) {
                CombatResult result = executeWithPenalty(user, target, combat, levelPenalty);
                results.add(result);
            }
        }
        
        return results;
    }
    
    /**
     * Check if the attacker has an active AoE weapon infusion.
     * 
     * @param user The attacking combatant
     * @return true if the attacker has an AoE infusion active on their equipped weapon
     */
    public boolean hasAoEInfusion(Combatant user) {
        if (!user.isPlayer() || user.getCharacterId() == null) {
            return false;
        }
        
        WeaponFamily weaponFamily = calculator.getEquippedWeaponFamily(user);
        if (weaponFamily == null) {
            return false;
        }
        
        WeaponInfusionEffect.InfusionData infusion = 
            WeaponInfusionEffect.getActiveInfusion(user.getCharacterId(), weaponFamily);
        
        return infusion != null && infusion.isAoE;
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
     * Get the damage dice notation for the attacker (e.g., "1d8", "2d6", "1d4").
     * Used for debug output.
     */
    private String getDamageDiceNotation(Combatant attacker) {
        // For mobs: use mob's base damage as 1dN
        if (attacker.isMobile() && attacker.getMobile() != null) {
            int baseDie = attacker.getMobile().getBaseDamage();
            if (baseDie > 0) {
                return "1d" + baseDie;
            }
        }
        
        // For players: check equipped main-hand weapon
        if (attacker.isPlayer() && attacker.getCharacterId() != null) {
            CharacterDAO dao = new CharacterDAO();
            com.example.tassmud.persistence.ItemDAO itemDAO = new com.example.tassmud.persistence.ItemDAO();
            
            Long mainHandId = dao.getCharacterEquipment(attacker.getCharacterId(), 
                com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
            
            if (mainHandId != null) {
                com.example.tassmud.model.ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
                if (weaponInst != null) {
                    com.example.tassmud.model.ItemTemplate weaponTmpl = itemDAO.getTemplateById(weaponInst.templateId);
                    int effectiveBaseDie = weaponInst.getEffectiveBaseDie(weaponTmpl);
                    int effectiveMultiplier = weaponInst.getEffectiveMultiplier(weaponTmpl);
                    if (effectiveBaseDie > 0) {
                        int mult = effectiveMultiplier > 0 ? effectiveMultiplier : 1;
                        return mult + "d" + effectiveBaseDie;
                    }
                }
            }
        }
        
        // Unarmed: 1d4
        return "1d" + UNARMED_DIE;
    }
    
    /**
     * Build a debug info string for an attack roll.
     * Format: "Attacker hit roll: 1d20 (roll) + bonus = total, vs Target AC (defense) hits/misses. Damage: dice (roll) + bonus = total"
     */
    private String buildAttackDebugInfo(String attackerName, int attackRoll, int attackBonus,
                                        String defenderName, String defenseType, int defenseValue,
                                        boolean isHit, String damageDice, int damageRoll, int damageBonus) {
        StringBuilder sb = new StringBuilder();
        
        // Attack roll portion
        int totalAttack = attackRoll + attackBonus;
        sb.append(attackerName).append(" hit roll: 1d20 (").append(attackRoll).append(")");
        if (attackBonus >= 0) {
            sb.append(" +").append(attackBonus);
        } else {
            sb.append(" ").append(attackBonus); // Already has minus sign
        }
        sb.append(" = ").append(totalAttack);
        sb.append(", vs ").append(defenderName).append(" ").append(defenseType).append(" (").append(defenseValue).append(")");
        
        if (isHit) {
            sb.append(" hits.");
            // Add damage info
            int totalDamage = damageRoll + damageBonus;
            sb.append(" Damage: ").append(damageDice).append(" (").append(damageRoll).append(")");
            if (damageBonus >= 0) {
                sb.append(" +").append(damageBonus);
            } else {
                sb.append(" ").append(damageBonus);
            }
            sb.append(" = ").append(totalDamage);
        } else {
            sb.append(" misses.");
        }
        
        return sb.toString();
    }
    
    /**
     * Roll damage for this attacker based on equipped weapon.
     * Uses weapon's baseDie and multiplier, or mob's base damage, or 1d4 unarmed.
     * Now correctly uses instance overrides for generated loot.
     */
    private int rollDamage(Combatant attacker) {
        // For mobs: use mob's base damage
        if (attacker.isMobile() && attacker.getMobile() != null) {
            int baseDie = attacker.getMobile().getBaseDamage();
            if (baseDie > 0) {
                return (int)(Math.random() * baseDie) + 1 + attacker.getMobile().getDamageBonus();
            }
        }
        
        // For players: check equipped main-hand weapon
        if (attacker.isPlayer() && attacker.getCharacterId() != null) {
            CharacterDAO dao = new CharacterDAO();
            com.example.tassmud.persistence.ItemDAO itemDAO = new com.example.tassmud.persistence.ItemDAO();
            
            Long mainHandId = dao.getCharacterEquipment(attacker.getCharacterId(), 
                com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
            
            if (mainHandId != null) {
                com.example.tassmud.model.ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
                if (weaponInst != null) {
                    com.example.tassmud.model.ItemTemplate weaponTmpl = itemDAO.getTemplateById(weaponInst.templateId);
                    // Use effective stats (instance overrides if present, otherwise template)
                    int effectiveBaseDie = weaponInst.getEffectiveBaseDie(weaponTmpl);
                    int effectiveMultiplier = weaponInst.getEffectiveMultiplier(weaponTmpl);
                    if (effectiveBaseDie > 0) {
                        // Roll multiplier * d(baseDie)
                        int mult = effectiveMultiplier > 0 ? effectiveMultiplier : 1;
                        int total = 0;
                        for (int i = 0; i < mult; i++) {
                            total += (int)(Math.random() * effectiveBaseDie) + 1;
                        }
                        return total;
                    }
                }
            }
        }
        
        // Unarmed: 1d4
        return (int)(Math.random() * UNARMED_DIE) + 1;
    }
    
    /**
     * Calculate the ability-based damage bonus from the equipped weapon.
     * Uses the weapon's abilityScore and abilityMultiplier fields (from instance overrides or template).
     * 
     * @param attacker The attacking combatant
     * @param character The attacker's character for stat lookup
     * @return The ability damage bonus, or 0 if no weapon/unarmed
     */
    private int getWeaponAbilityDamageBonus(Combatant attacker, Character character) {
        if (!attacker.isPlayer() || attacker.getCharacterId() == null) {
            return 0; // Mobs don't use this system
        }
        
        CharacterDAO dao = new CharacterDAO();
        com.example.tassmud.persistence.ItemDAO itemDAO = new com.example.tassmud.persistence.ItemDAO();
        
        Long mainHandId = dao.getCharacterEquipment(attacker.getCharacterId(), 
            com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
        
        if (mainHandId == null) {
            return 0; // Unarmed - no ability multiplier bonus
        }
        
        com.example.tassmud.model.ItemInstance weaponInst = itemDAO.getInstance(mainHandId);
        if (weaponInst == null) {
            return 0;
        }
        
        com.example.tassmud.model.ItemTemplate weaponTmpl = itemDAO.getTemplateById(weaponInst.templateId);
        
        // Get ability score name from template (default to STR)
        String abilityScore = (weaponTmpl != null && weaponTmpl.abilityScore != null) 
            ? weaponTmpl.abilityScore : "strength";
        
        // Get ability multiplier from instance (uses override if present, otherwise template)
        double abilityMultiplier = weaponInst.getEffectiveAbilityMultiplier(weaponTmpl);
        
        // Calculate the stat modifier
        int statMod = getStatMod(character, abilityScore);
        
        // Apply multiplier and return
        return (int) Math.round(statMod * abilityMultiplier);
    }

    /**
     * Check if the attacker is using a two-handed weapon.
     * A weapon is two-handed if main hand and off hand both hold the same item instance.
     */
    private boolean isTwoHandedWeapon(Combatant attacker) {
        if (!attacker.isPlayer() || attacker.getCharacterId() == null) {
            return false;
        }
        
        CharacterDAO dao = new CharacterDAO();
        Long mainHandId = dao.getCharacterEquipment(attacker.getCharacterId(), 
            com.example.tassmud.model.EquipmentSlot.MAIN_HAND.getId());
        Long offHandId = dao.getCharacterEquipment(attacker.getCharacterId(), 
            com.example.tassmud.model.EquipmentSlot.OFF_HAND.getId());
        
        // Two-handed if both slots have the same non-null item
        return mainHandId != null && mainHandId.equals(offHandId);
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
    
    /**
     * Get the stat modifier for a character based on the stat name.
     * 
     * @param character The character to get the stat from
     * @param statName The name of the stat (STRENGTH, DEXTERITY, INTELLIGENCE, WISDOM, CHARISMA, CONSTITUTION)
     * @return The stat modifier (stat - 10) / 2
     */
    private int getStatMod(Character character, String statName) {
        if (character == null || statName == null) return 0;
        
        int statValue;
        switch (statName.toUpperCase()) {
            case "STRENGTH":
            case "STR":
                statValue = character.getStr();
                break;
            case "DEXTERITY":
            case "DEX":
                statValue = character.getDex();
                break;
            case "INTELLIGENCE":
            case "INT":
                statValue = character.getIntel();
                break;
            case "WISDOM":
            case "WIS":
                statValue = character.getWis();
                break;
            case "CHARISMA":
            case "CHA":
                statValue = character.getCha();
                break;
            case "CONSTITUTION":
            case "CON":
                statValue = character.getCon();
                break;
            default:
                statValue = 10; // Default to no modifier
        }
        
        return (statValue - 10) / 2;
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
