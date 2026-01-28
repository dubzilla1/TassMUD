package com.example.tassmud.util;

import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
import com.example.tassmud.persistence.CharacterDAO;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility class for handling skill proficiency growth checks.
 * 
 * When a skill is used:
 * - On SUCCESS: Roll one proficiency check. If it passes, gain 1% proficiency.
 * - On FAILURE: Roll two proficiency checks. If BOTH pass, gain 1% proficiency.
 *   (Learning from failure is harder, but still possible!)
 */
public class ProficiencyCheck {
    
    /**
     * Result of a proficiency check attempt.
     */
    public static class Result {
        private final boolean improved;
        private final String skillName;
        private final int oldProficiency;
        private final int newProficiency;
        
        public Result(boolean improved, String skillName, int oldProficiency, int newProficiency) {
            this.improved = improved;
            this.skillName = skillName;
            this.oldProficiency = oldProficiency;
            this.newProficiency = newProficiency;
        }
        
        public boolean hasImproved() { return improved; }
        public String getSkillName() { return skillName; }
        public int getOldProficiency() { return oldProficiency; }
        public int getNewProficiency() { return newProficiency; }
        
        /**
         * Get the improvement message to display to the player.
         * Returns null if no improvement occurred.
         */
        public String getImprovementMessage() {
            if (!improved) return null;
            return "Your " + skillName + " has improved! (" + oldProficiency + "% -> " + newProficiency + "%)";
        }
    }
    
    /**
     * Perform a proficiency growth check for a skill use.
     * 
     * @param characterId the character who used the skill
     * @param skill the skill definition
     * @param charSkill the character's current skill proficiency
     * @param skillSucceeded whether the skill use was successful
     * @param dao the CharacterDAO for persistence
     * @return Result indicating if proficiency improved
     */
    public static Result checkProficiencyGrowth(int characterId, Skill skill, CharacterSkill charSkill, 
                                                 boolean skillSucceeded, CharacterDAO dao) {
        // Instant progression skills don't grow
        if (skill.getProgression().isInstant()) {
            return new Result(false, skill.getName(), charSkill.getProficiency(), charSkill.getProficiency());
        }
        
        // Already mastered
        int currentProficiency = charSkill.getProficiency();
        if (currentProficiency >= CharacterSkill.MAX_PROFICIENCY) {
            return new Result(false, skill.getName(), currentProficiency, currentProficiency);
        }
        
        // Get the gain chance based on progression and current proficiency
        int gainChance = skill.getProgression().getGainChance(currentProficiency);
        
        boolean gained;
        if (skillSucceeded) {
            // On success: single roll
            gained = rollProficiencyCheck(gainChance);
        } else {
            // On failure: must pass TWO rolls (learning from failure is harder)
            boolean roll1 = rollProficiencyCheck(gainChance);
            boolean roll2 = rollProficiencyCheck(gainChance);
            gained = roll1 && roll2;
        }
        
        if (gained) {
            int newProficiency = currentProficiency + 1;
            // Update in database
            dao.setCharacterSkillLevel(characterId, skill.getId(), newProficiency);
            // Update the in-memory object too
            charSkill.setProficiency(newProficiency);
            return new Result(true, skill.getName(), currentProficiency, newProficiency);
        }
        
        return new Result(false, skill.getName(), currentProficiency, currentProficiency);
    }
    
    /**
     * Perform a single proficiency roll.
     * 
     * @param gainChance percentage chance (0-100) to succeed
     * @return true if the roll passed
     */
    private static boolean rollProficiencyCheck(int gainChance) {
        int roll = (int)(ThreadLocalRandom.current().nextDouble() * 100) + 1; // 1-100
        return roll <= gainChance;
    }
    
    /**
     * Convenience method for skill success case.
     */
    public static Result checkOnSuccess(int characterId, Skill skill, CharacterSkill charSkill, CharacterDAO dao) {
        return checkProficiencyGrowth(characterId, skill, charSkill, true, dao);
    }
    
    /**
     * Convenience method for skill failure case.
     */
    public static Result checkOnFailure(int characterId, Skill skill, CharacterSkill charSkill, CharacterDAO dao) {
        return checkProficiencyGrowth(characterId, skill, charSkill, false, dao);
    }
}
