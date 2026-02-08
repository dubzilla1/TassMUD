package com.example.tassmud.util;

import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Centralized XP calculation and level-up processing.
 * Replaces 6+ duplicated XP formula / level-up sequences across
 * CombatRewardService, RogueSkillHandler, ItemCommandHandler, DeathHandler, and GmCharacterHandler.
 *
 * <p>XP formula: {@code 100 / 2^(effectiveLevel - targetLevel)}, clamped to [-10, 10], minimum 1 XP.
 * <p>Effective level: {@code charLevel + floor(charLevel / 10)}.
 */
public final class ExperienceService {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceService.class);

    private ExperienceService() {} // utility class

    /**
     * Calculate combat XP based on level difference.
     *
     * @param charLevel   the character's current class level
     * @param targetLevel the defeated mob's level
     * @return XP to award (minimum 1)
     */
    public static int calculateCombatXp(int charLevel, int targetLevel) {
        int effectiveLevel = charLevel + (charLevel / 10);
        int levelDiff = Math.max(-10, Math.min(10, effectiveLevel - targetLevel));
        double xpDouble = 100.0 / Math.pow(2, levelDiff);
        return Math.max(1, (int) Math.round(xpDouble));
    }

    /**
     * Award combat XP to a character based on a defeated mob's level.
     * Handles the full flow: calculate XP, add to current class, notify player, process level-up.
     *
     * @param characterId the character to award XP to
     * @param targetLevel the defeated mob's level
     * @param messenger   callback to send messages to the player (e.g. "You gain X experience.")
     * @return the amount of XP awarded
     */
    public static int awardCombatXp(int characterId, int targetLevel, Consumer<String> messenger) {
        CharacterClassDAO classDAO = DaoProvider.classes();
        Integer classId = classDAO.getCharacterCurrentClassId(characterId);
        if (classId == null) {
            logger.debug("[xp] No class found for character {}, skipping XP", characterId);
            return 0;
        }
        int charLevel = classDAO.getCharacterClassLevel(characterId, classId);
        int xpAwarded = calculateCombatXp(charLevel, targetLevel);

        return doAward(characterId, classId, xpAwarded, messenger);
    }

    /**
     * Award a flat amount of XP (e.g. sacrifice = 1 XP).
     * Handles the add + level-up check + messaging.
     *
     * @param characterId the character to award XP to
     * @param amount      the flat XP amount
     * @param messenger   callback to send messages to the player
     * @return the amount of XP awarded (same as {@code amount})
     */
    public static int awardFlatXp(int characterId, int amount, Consumer<String> messenger) {
        CharacterClassDAO classDAO = DaoProvider.classes();
        Integer classId = classDAO.getCharacterCurrentClassId(characterId);
        if (classId == null) {
            logger.debug("[xp] No class found for character {}, skipping flat XP", characterId);
            return 0;
        }
        return doAward(characterId, classId, amount, messenger);
    }

    private static int doAward(int characterId, int classId, int xpAwarded, Consumer<String> messenger) {
        CharacterClassDAO classDAO = DaoProvider.classes();

        // Wrap XP grant + level-up processing in a single transaction
        // to prevent partial level-ups (XP committed but skills/vitals missing)
        return TransactionManager.runInTransaction(() -> {
            boolean leveledUp = classDAO.addXpToCurrentClass(characterId, xpAwarded);

            messenger.accept("You gain " + xpAwarded + " experience.");

            if (leveledUp) {
                int newLevel = classDAO.getCharacterClassLevel(characterId, classId);
                messenger.accept("You have reached level " + newLevel + "!");
                classDAO.processLevelUp(characterId, newLevel, messenger);
            }

            return xpAwarded;
        });
    }
}
