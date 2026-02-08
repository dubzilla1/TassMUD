package com.example.tassmud.combat;

import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Handles all combat reward logic: XP awards, weapon skill proficiency,
 * and armor proficiency tracking/improvement.
 *
 * Extracted from CombatManager to keep combat orchestration separate
 * from reward business logic.
 */
public class CombatRewardService {

    /** Callback for sending messages to players. */
    private final BiConsumer<Integer, String> playerMessageCallback;

    public CombatRewardService(BiConsumer<Integer, String> playerMessageCallback) {
        this.playerMessageCallback = playerMessageCallback;
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void sendToPlayer(Integer characterId, String message) {
        if (playerMessageCallback != null && characterId != null) {
            playerMessageCallback.accept(characterId, message);
        }
    }

    // ── XP ─────────────────────────────────────────────────────────────

    /**
     * Calculate and award experience points when a player kills a mob.
     *
     * Formula: 100 / 2^(effective_level - target_level)
     * Where effective_level = char_class_level + floor(char_class_level / 10)
     *
     * This means:
     * - Levels 1-9: 100 XP for same-level kills
     * - Levels 10-19: 50 XP for same-level kills
     * - Levels 20-29: 25 XP for same-level kills
     * - Each level the foe is weaker: half XP
     * - Each level the foe is stronger: double XP
     */
    public void awardExperienceOnKill(Combatant killer, Combatant victim) {
        if (!killer.isPlayer() || killer.getCharacterId() == null) {
            return; // Only players gain XP
        }
        if (!victim.isMobile() || victim.getMobile() == null) {
            return; // Only mob kills award XP
        }

        int characterId = killer.getCharacterId();
        int targetLevel = victim.getMobile().getLevel();

        com.example.tassmud.util.ExperienceService.awardCombatXp(
                characterId, targetLevel, msg -> sendToPlayer(characterId, msg));
    }

    // ── Weapon skill ───────────────────────────────────────────────────

    /**
     * Award weapon family skill proficiency to a player when they get a kill.
     * Awards 1 point of proficiency (1%) for the weapon family of the equipped main-hand weapon.
     * Skill gain is capped at (class level * 10)% proficiency.
     */
    public void awardWeaponSkillOnKill(Combatant killer) {
        if (!killer.isPlayer() || killer.getCharacterId() == null) {
            return; // Only players gain skills
        }

        GameCharacter killerChar = killer.getAsCharacter();
        if (killerChar == null) {
            return;
        }

        int characterId = killer.getCharacterId();

        CharacterClassDAO classDAO = DaoProvider.classes();
        ItemDAO itemDAO = DaoProvider.items();

        // Get character's current class level for skill cap calculation
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        int classLevel = 1; // Default to level 1 if no class
        if (currentClassId != null) {
            classLevel = Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }
        int maxProficiencyForLevel = classLevel * 10; // Cap: 10% per class level

        // Get equipped main-hand weapon
        Long mainHandInstanceId = DaoProvider.equipment().getCharacterEquipment(characterId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            return; // No weapon equipped
        }

        // Get the item template to find weapon family
        ItemInstance weaponInstance = itemDAO.getInstance(mainHandInstanceId);
        if (weaponInstance == null) {
            return;
        }

        ItemTemplate weaponTemplate = itemDAO.getTemplateById(weaponInstance.templateId);
        if (weaponTemplate == null) {
            return;
        }

        WeaponFamily weaponFamily = weaponTemplate.getWeaponFamily();
        if (weaponFamily == null) {
            return; // Not a weapon or no family assigned
        }

        // Look up the skill for this weapon family
        String skillKey = weaponFamily.getSkillKey();
        Skill familySkill = DaoProvider.skills().getSkillByKey(skillKey);
        if (familySkill == null) {
            return; // Skill not found in database
        }

        // Check if character has this skill
        CharacterSkill charSkill = DaoProvider.skills().getCharacterSkill(characterId, familySkill.getId());
        if (charSkill == null) {
            // Character doesn't have this weapon skill - teach it at base proficiency
            DaoProvider.skills().learnSkill(characterId, familySkill.getId());
            charSkill = DaoProvider.skills().getCharacterSkill(characterId, familySkill.getId());
            if (charSkill != null) {
                sendToPlayer(characterId, "You have learned " + familySkill.getName() + "!");
            }
        }

        if (charSkill == null) {
            return; // Failed to learn/get skill
        }

        // Check if already at cap for current class level (or max proficiency)
        int currentProficiency = charSkill.getProficiency();
        int effectiveCap = Math.min(maxProficiencyForLevel, CharacterSkill.MAX_PROFICIENCY);
        if (currentProficiency >= effectiveCap) {
            return; // At cap for current level or already mastered
        }

        // Award 1 point of proficiency (but don't exceed level cap)
        int gainAmount = Math.min(1, effectiveCap - currentProficiency);
        if (gainAmount > 0) {
            int result = DaoProvider.skills().increaseSkillProficiency(characterId, familySkill.getId(), gainAmount);
            if (result > 0) {
                sendToPlayer(characterId, "Your " + familySkill.getName() + " skill improves! (" + result + "%)");
            }
        }
    }

    // ── Armor proficiency ──────────────────────────────────────────────

    /**
     * Track damage taken for armor proficiency training.
     * Records damage against each equipped armor category.
     */
    public void trackArmorDamage(Combatant target, int damage) {
        if (!target.isPlayer() || target.getCharacterId() == null) {
            return;
        }

        ItemDAO itemDAO = DaoProvider.items();
        Integer characterId = target.getCharacterId();

        // Get all equipped items and find armor categories
        Map<Integer, Long> equipped = DaoProvider.equipment().getEquipmentMapByCharacterId(characterId);
        Set<ArmorCategory> wornCategories = new java.util.HashSet<>();

        for (Long instanceId : equipped.values()) {
            if (instanceId == null) continue;
            ItemInstance inst = itemDAO.getInstance(instanceId);
            if (inst == null) continue;
            ItemTemplate tmpl = itemDAO.getTemplateById(inst.templateId);
            if (tmpl == null || !tmpl.isArmor()) continue;

            ArmorCategory category = tmpl.getArmorCategory();
            if (category != null) {
                wornCategories.add(category);
            }
        }

        // Record damage for each worn armor category
        for (ArmorCategory category : wornCategories) {
            target.recordArmorDamage(category, damage);
        }
    }

    /**
     * Check if a player's armor proficiency should improve at end of combat.
     *
     * For each armor category where damage taken >= max HP:
     *   Roll 1d100 and compare to success threshold.
     *   Success threshold = 1 / 2^max(0, skill% - level*2)
     *
     * This creates diminishing returns: first 2% per level are guaranteed,
     * then 50% chance, 25%, 12.5%, etc.
     */
    public void checkArmorProficiencyGain(Integer characterId, Combatant combatant) {
        if (characterId == null) return;

        // Get character's max HP and level
        GameCharacter character = combatant.getAsCharacter();
        if (character == null) return;

        int maxHp = character.getHpMax();
        if (maxHp <= 0) return;

        // Get character's class level for the proficiency cap
        CharacterClassDAO classDAO = DaoProvider.classes();
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        int classLevel = 1;
        if (currentClassId != null) {
            classLevel = Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }

        // Check each armor category with accumulated damage
        Map<ArmorCategory, Integer> damageCounters = combatant.getArmorDamageCounters();

        for (Map.Entry<ArmorCategory, Integer> entry : damageCounters.entrySet()) {
            ArmorCategory category = entry.getKey();
            int damageTaken = entry.getValue();

            // Must have taken at least max HP worth of damage
            if (damageTaken < maxHp) {
                continue;
            }

            // Look up the skill for this armor category
            String skillKey = category.getSkillKey();
            Skill armorSkill = DaoProvider.skills().getSkillByKey(skillKey);
            if (armorSkill == null) {
                continue;
            }

            // Check if character has this skill
            CharacterSkill charSkill = DaoProvider.skills().getCharacterSkill(characterId, armorSkill.getId());
            if (charSkill == null) {
                continue; // Must have the skill to improve it
            }

            int currentProficiency = charSkill.getProficiency();
            if (currentProficiency >= CharacterSkill.MAX_PROFICIENCY) {
                continue; // Already mastered
            }

            // Calculate success chance: 1 / 2^max(0, skill% - level*2)
            int exponent = Math.max(0, currentProficiency - (classLevel * 2));
            double successChance = 1.0 / Math.pow(2, exponent);
            int successThreshold = (int) Math.round(successChance * 100);

            // Roll 1d100
            int roll = ThreadLocalRandom.current().nextInt(1, 101);

            if (roll <= successThreshold) {
                // Success! Increase proficiency by 1%
                int newProficiency = DaoProvider.skills().increaseSkillProficiency(characterId, armorSkill.getId(), 1);
                if (newProficiency > 0) {
                    sendToPlayer(characterId, "Your " + armorSkill.getName() + " skill improves! (" + newProficiency + "%)");
                }
            }
        }

        // Reset counters after check
        combatant.resetArmorDamageCounters();
    }
}
