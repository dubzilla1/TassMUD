package com.example.tassmud.combat;

import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.WeaponCategory;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;

/**
 * Combat calculator for attack bonuses and damage multipliers.
 * 
 * Attack roll bonus: (attacker_level - defender_level) * 2, capped at attacker's level.
 * 
 * Damage multiplier: (attacker_weapon_family_skill / defender_weapon_family_skill) 
 *                    * (attacker_weapon_category_skill / defender_weapon_category_skill)
 * 
 * For mobs:
 *   - weapon_category_skill = mob_level * 0.02 (up to 1.0)
 *   - weapon_family_skill = min(mob_level * 0.1, 1.0)
 */
public class CombatCalculator {
    
    /** Default proficiency for characters that lack a skill (minimum to avoid divide by zero) */
    private static final double MIN_SKILL_PROFICIENCY = 0.01;
    
    /** Maximum proficiency (100%) */
    private static final double MAX_SKILL_PROFICIENCY = 1.0;
    
    // Lazy-loaded DAOs (created once per calculator instance)
    private CharacterDAO characterDAO;
    private CharacterClassDAO classDAO;
    private ItemDAO itemDAO;
    
    /**
     * Create a new CombatCalculator.
     */
    public CombatCalculator() {
        // DAOs created lazily
    }
    
    /**
     * Calculate the attack roll bonus based on level difference.
     * 
     * Formula: (attacker_level - defender_level) * 2
     * Cap: min(calculated_bonus, attacker_level)
     * 
     * @param attackerLevel Attacker's level
     * @param defenderLevel Defender's level
     * @return The attack roll bonus (can be negative)
     */
    public int calculateLevelAttackBonus(int attackerLevel, int defenderLevel) {
        int rawBonus = (attackerLevel - defenderLevel) * 2;
        
        // Cap at attacker's level (only applies to positive bonuses)
        if (rawBonus > 0) {
            return Math.min(rawBonus, attackerLevel);
        }
        
        // Negative bonuses (defender is higher level) are not capped
        return rawBonus;
    }
    
    /**
     * Calculate the damage multiplier based on weapon skills.
     * 
     * Formula: (attacker_family / defender_family) * (attacker_category / defender_category)
     * 
     * Multiplier is applied to bonus damage only, not the base die roll.
     * 
     * @param attackerFamilySkill Attacker's weapon family proficiency (0.0-1.0)
     * @param defenderFamilySkill Defender's weapon family proficiency (0.0-1.0)
     * @param attackerCategorySkill Attacker's weapon category proficiency (0.0-1.0)
     * @param defenderCategorySkill Defender's weapon category proficiency (0.0-1.0)
     * @return The damage multiplier
     */
    public double calculateDamageMultiplier(double attackerFamilySkill, double defenderFamilySkill,
                                            double attackerCategorySkill, double defenderCategorySkill) {
        // Ensure minimum proficiency to avoid division by zero
        defenderFamilySkill = Math.max(defenderFamilySkill, MIN_SKILL_PROFICIENCY);
        defenderCategorySkill = Math.max(defenderCategorySkill, MIN_SKILL_PROFICIENCY);
        
        double familyRatio = attackerFamilySkill / defenderFamilySkill;
        double categoryRatio = attackerCategorySkill / defenderCategorySkill;
        
        return familyRatio * categoryRatio;
    }
    
    /**
     * Calculate a mob's effective weapon category skill based on level.
     * Formula: mob_level * 0.02, capped at 1.0
     * 
     * @param mobLevel The mob's level
     * @return The effective category skill (0.0-1.0)
     */
    public double calculateMobCategorySkill(int mobLevel) {
        return Math.min(mobLevel * 0.02, MAX_SKILL_PROFICIENCY);
    }
    
    /**
     * Calculate a mob's effective weapon family skill based on level.
     * Formula: min(mob_level * 0.1, 1.0)
     * 
     * @param mobLevel The mob's level
     * @return The effective family skill (0.0-1.0)
     */
    public double calculateMobFamilySkill(int mobLevel) {
        return Math.min(mobLevel * 0.1, MAX_SKILL_PROFICIENCY);
    }
    
    /**
     * Get combat skills for a player combatant.
     * Returns the weapon category and family skills for their equipped main-hand weapon.
     * 
     * @param characterId The character ID
     * @return CombatSkills containing category and family proficiency (0.0-1.0)
     */
    public CombatSkills getPlayerCombatSkills(Integer characterId) {
        if (characterId == null) {
            return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
        }
        
        ensureDAOs();
        
        // Get equipped main-hand weapon
        Long mainHandInstanceId = characterDAO.getCharacterEquipment(characterId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            // Unarmed - use minimum skill (could add unarmed skill later)
            return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
        }
        
        // Get weapon template to find category and family
        ItemInstance weaponInstance = itemDAO.getInstance(mainHandInstanceId);
        if (weaponInstance == null) {
            return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
        }
        
        ItemTemplate weaponTemplate = itemDAO.getTemplateById(weaponInstance.templateId);
        if (weaponTemplate == null) {
            return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
        }
        
        // Get weapon category and family
        WeaponFamily weaponFamily = weaponTemplate.getWeaponFamily();
        WeaponCategory weaponCategory = weaponFamily != null ? weaponFamily.getCategory() : null;
        
        // Look up character's skills
        double categorySkill = MIN_SKILL_PROFICIENCY;
        double familySkill = MIN_SKILL_PROFICIENCY;
        
        // Get category skill
        if (weaponCategory != null) {
            Skill catSkill = characterDAO.getSkillByKey(weaponCategory.getSkillKey());
            if (catSkill != null) {
                CharacterSkill charCatSkill = characterDAO.getCharacterSkill(characterId, catSkill.getId());
                if (charCatSkill != null) {
                    categorySkill = charCatSkill.getProficiency() / 100.0; // Convert 0-100 to 0.0-1.0
                }
            }
        }
        
        // Get family skill
        if (weaponFamily != null) {
            Skill famSkill = characterDAO.getSkillByKey(weaponFamily.getSkillKey());
            if (famSkill != null) {
                CharacterSkill charFamSkill = characterDAO.getCharacterSkill(characterId, famSkill.getId());
                if (charFamSkill != null) {
                    familySkill = charFamSkill.getProficiency() / 100.0;
                }
            }
        }
        
        return new CombatSkills(categorySkill, familySkill);
    }
    
    /**
     * Get combat skills for a mobile combatant.
     * Calculated based on mob level.
     * 
     * @param mobile The mobile
     * @return CombatSkills containing category and family proficiency (0.0-1.0)
     */
    public CombatSkills getMobileCombatSkills(Mobile mobile) {
        if (mobile == null) {
            return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
        }
        
        int level = mobile.getLevel();
        double categorySkill = calculateMobCategorySkill(level);
        double familySkill = calculateMobFamilySkill(level);
        
        return new CombatSkills(categorySkill, familySkill);
    }
    
    /**
     * Get combat skills for any combatant.
     * 
     * @param combatant The combatant
     * @return CombatSkills containing category and family proficiency (0.0-1.0)
     */
    public CombatSkills getCombatSkills(Combatant combatant) {
        if (combatant == null) {
            return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
        }
        
        if (combatant.isPlayer()) {
            return getPlayerCombatSkills(combatant.getCharacterId());
        } else if (combatant.isMobile()) {
            return getMobileCombatSkills(combatant.getMobile());
        }
        
        return new CombatSkills(MIN_SKILL_PROFICIENCY, MIN_SKILL_PROFICIENCY);
    }
    
    /**
     * Get the effective level for a player character (class level or character level).
     * 
     * @param characterId The character ID
     * @return The character's effective combat level
     */
    public int getPlayerLevel(Integer characterId) {
        if (characterId == null) return 1;
        
        ensureDAOs();
        
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        if (currentClassId != null) {
            return Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }
        
        // Fallback to character base level (if any)
        return 1;
    }
    
    /**
     * Get the effective level for any combatant.
     * 
     * @param combatant The combatant
     * @return The combatant's effective combat level
     */
    public int getCombatantLevel(Combatant combatant) {
        if (combatant == null) return 1;
        
        if (combatant.isPlayer()) {
            return getPlayerLevel(combatant.getCharacterId());
        } else if (combatant.isMobile() && combatant.getMobile() != null) {
            return combatant.getMobile().getLevel();
        }
        
        return 1;
    }
    
    /**
     * Calculate the full attack bonus for a combatant attacking another.
     * Includes level-based bonus.
     * 
     * @param attacker The attacking combatant
     * @param defender The defending combatant
     * @return The total attack roll bonus
     */
    public int calculateFullAttackBonus(Combatant attacker, Combatant defender) {
        return calculateFullAttackBonus(attacker, defender, 0);
    }
    
    /**
     * Calculate the full attack bonus for a combatant attacking another,
     * with an optional level penalty (for multi-attack skills).
     * 
     * @param attacker The attacking combatant
     * @param defender The defending combatant
     * @param levelPenalty Penalty to attacker's effective level (e.g., 1 for second attack)
     * @return The total attack roll bonus
     */
    public int calculateFullAttackBonus(Combatant attacker, Combatant defender, int levelPenalty) {
        int attackerLevel = Math.max(1, getCombatantLevel(attacker) - levelPenalty);
        int defenderLevel = getCombatantLevel(defender);
        
        return calculateLevelAttackBonus(attackerLevel, defenderLevel);
    }
    
    /**
     * Calculate the full damage multiplier for a combatant attacking another.
     * Based on weapon skills.
     * 
     * @param attacker The attacking combatant
     * @param defender The defending combatant
     * @return The damage multiplier for bonus damage
     */
    public double calculateFullDamageMultiplier(Combatant attacker, Combatant defender) {
        CombatSkills attackerSkills = getCombatSkills(attacker);
        CombatSkills defenderSkills = getCombatSkills(defender);
        
        return calculateDamageMultiplier(
            attackerSkills.familySkill, defenderSkills.familySkill,
            attackerSkills.categorySkill, defenderSkills.categorySkill
        );
    }
    
    /**
     * Ensure DAOs are initialized.
     */
    private void ensureDAOs() {
        if (characterDAO == null) {
            characterDAO = new CharacterDAO();
        }
        if (classDAO == null) {
            classDAO = new CharacterClassDAO();
        }
        if (itemDAO == null) {
            itemDAO = new ItemDAO();
        }
    }
    
    /**
     * Get the weapon family of the combatant's equipped main-hand weapon.
     * 
     * @param combatant The combatant to check
     * @return The WeaponFamily of the equipped weapon, or null if unarmed/invalid
     */
    public WeaponFamily getEquippedWeaponFamily(Combatant combatant) {
        if (combatant == null) return null;
        
        if (combatant.isPlayer()) {
            return getPlayerWeaponFamily(combatant.getCharacterId());
        } else if (combatant.isMobile()) {
            // For mobs, return null (they don't have equipped items)
            // TODO: Could add weapon family to MobileTemplate for mob weapons
            return null;
        }
        
        return null;
    }
    
    /**
     * Get the weapon family of a player's equipped main-hand weapon.
     */
    private WeaponFamily getPlayerWeaponFamily(Integer characterId) {
        if (characterId == null) return null;
        
        ensureDAOs();
        
        Long mainHandInstanceId = characterDAO.getCharacterEquipment(characterId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            return null; // Unarmed
        }
        
        ItemInstance weaponInstance = itemDAO.getInstance(mainHandInstanceId);
        if (weaponInstance == null) {
            return null;
        }
        
        ItemTemplate weaponTemplate = itemDAO.getTemplateById(weaponInstance.templateId);
        if (weaponTemplate == null) {
            return null;
        }
        
        return weaponTemplate.getWeaponFamily();
    }
    
    /**
     * Check if a combatant is using a ranged weapon.
     * 
     * @param combatant The combatant to check
     * @return true if using a ranged weapon (bow, crossbow, sling), false otherwise
     */
    public boolean isUsingRangedWeapon(Combatant combatant) {
        if (combatant == null) return false;
        
        if (combatant.isPlayer()) {
            return isPlayerUsingRangedWeapon(combatant.getCharacterId());
        } else if (combatant.isMobile()) {
            // For now, mobs are assumed to be melee unless we add ranged mob support later
            // TODO: Add ranged weapon support for mobiles
            return false;
        }
        
        return false;
    }
    
    /**
     * Check if a player character is using a ranged weapon.
     */
    private boolean isPlayerUsingRangedWeapon(Integer characterId) {
        if (characterId == null) return false;
        
        ensureDAOs();
        
        // Get equipped main-hand weapon
        Long mainHandInstanceId = characterDAO.getCharacterEquipment(characterId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            return false; // Unarmed is melee
        }
        
        // Get weapon template to find family
        ItemInstance weaponInstance = itemDAO.getInstance(mainHandInstanceId);
        if (weaponInstance == null) {
            return false;
        }
        
        ItemTemplate weaponTemplate = itemDAO.getTemplateById(weaponInstance.templateId);
        if (weaponTemplate == null) {
            return false;
        }
        
        WeaponFamily weaponFamily = weaponTemplate.getWeaponFamily();
        return weaponFamily != null && weaponFamily.isRanged();
    }
    
    /**
     * Container for weapon category and family skills.
     */
    public static class CombatSkills {
        public final double categorySkill;
        public final double familySkill;
        
        public CombatSkills(double categorySkill, double familySkill) {
            this.categorySkill = categorySkill;
            this.familySkill = familySkill;
        }
        
        @Override
        public String toString() {
            return String.format("CombatSkills[category=%.0f%%, family=%.0f%%]", 
                categorySkill * 100, familySkill * 100);
        }
    }
}
