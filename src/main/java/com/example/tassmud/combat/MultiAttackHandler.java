package com.example.tassmud.combat;

import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Skill;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.util.ProficiencyCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Handles multiple attack rolls for combatants with second_attack, third_attack, 
 * and fourth_attack skills.
 * 
 * Each successive attack:
 * - Requires the appropriate skill (second_attack for 2nd, etc.)
 * - Has a chance to trigger equal to skill proficiency (1-100%)
 * - Applies a cumulative level penalty (-1 for 2nd, -2 for 3rd, -3 for 4th)
 * - Can improve through use (even on failure, with 2 checks required)
 * 
 * For mobs: chance is derived from level (level * 2%, capped at 100%)
 */
public class MultiAttackHandler {
    
    /** Skill IDs for multiple attacks (from skills.yaml) */
    public static final int SECOND_ATTACK_SKILL_ID = 15;
    public static final int THIRD_ATTACK_SKILL_ID = 16;
    public static final int FOURTH_ATTACK_SKILL_ID = 17;
    
    /** Level penalties for each successive attack */
    public static final int SECOND_ATTACK_PENALTY = 2;
    public static final int THIRD_ATTACK_PENALTY = 4;
    public static final int FOURTH_ATTACK_PENALTY = 6;
    
    /** DAO for player skill lookups (lazy initialized) */
    private CharacterDAO dao;
    
    /** Callback for sending messages to players (characterId, message) */
    private BiConsumer<Integer, String> playerMessageCallback;
    
    public MultiAttackHandler() {
        // DAO created lazily to avoid DB connection in constructor
    }
    
    /**
     * Get the DAO instance, creating it if necessary.
     */
    private CharacterDAO getDao() {
        if (dao == null) {
            dao = new CharacterDAO();
        }
        return dao;
    }
    
    /**
     * Set the callback for sending messages to players.
     */
    public void setPlayerMessageCallback(BiConsumer<Integer, String> callback) {
        this.playerMessageCallback = callback;
    }
    
    /**
     * Represents the result of checking for additional attacks.
     */
    public static class AttackOpportunity {
        private final int attackNumber;    // 2, 3, or 4
        private final int levelPenalty;    // -1, -2, or -3
        private final int skillId;         // skill ID that granted this attack
        private final boolean triggered;   // whether the attack actually happens
        
        public AttackOpportunity(int attackNumber, int levelPenalty, int skillId, boolean triggered) {
            this.attackNumber = attackNumber;
            this.levelPenalty = levelPenalty;
            this.skillId = skillId;
            this.triggered = triggered;
        }
        
        public int getAttackNumber() { return attackNumber; }
        public int getLevelPenalty() { return levelPenalty; }
        public int getSkillId() { return skillId; }
        public boolean isTriggered() { return triggered; }
    }
    
    /**
     * Get the list of additional attacks a combatant may make.
     * This checks skills and rolls for each potential extra attack.
     * 
     * If the combatant is affected by SLOW, they get no additional attacks.
     * 
     * @param combatant the attacking combatant
     * @return list of attack opportunities (some may not trigger)
     */
    public List<AttackOpportunity> getAdditionalAttacks(Combatant combatant) {
        List<AttackOpportunity> opportunities = new ArrayList<>();
        
        // Check if combatant is slowed - if so, no additional attacks allowed
        Integer combatantId = combatant.getCharacterId();
        if (combatantId != null && com.example.tassmud.effect.SlowEffect.isSlowed(combatantId)) {
            // Slowed combatants are limited to single basic attack
            return opportunities; // Empty list = no additional attacks
        }
        
        if (combatant.isPlayer()) {
            opportunities.addAll(getPlayerAdditionalAttacks(combatant));
        } else if (combatant.isMobile()) {
            opportunities.addAll(getMobileAdditionalAttacks(combatant));
        }
        
        return opportunities;
    }
    
    /**
     * Check for additional attacks for a player character.
     */
    private List<AttackOpportunity> getPlayerAdditionalAttacks(Combatant combatant) {
        List<AttackOpportunity> opportunities = new ArrayList<>();
        Integer characterId = combatant.getCharacterId();
        if (characterId == null) return opportunities;
        
        // Check second attack
        CharacterSkill secondAttack = getDao().getCharacterSkill(characterId, SECOND_ATTACK_SKILL_ID);
        if (secondAttack != null) {
            boolean triggered = rollForAttack(secondAttack.getProficiency());
            opportunities.add(new AttackOpportunity(2, SECOND_ATTACK_PENALTY, SECOND_ATTACK_SKILL_ID, triggered));
            
            // Check skill progression (whether triggered or not)
            checkSkillProgression(characterId, SECOND_ATTACK_SKILL_ID, secondAttack, triggered);
            
            // Only check for third attack if we have second attack skill
            CharacterSkill thirdAttack = getDao().getCharacterSkill(characterId, THIRD_ATTACK_SKILL_ID);
            if (thirdAttack != null) {
                boolean thirdTriggered = triggered && rollForAttack(thirdAttack.getProficiency());
                opportunities.add(new AttackOpportunity(3, THIRD_ATTACK_PENALTY, THIRD_ATTACK_SKILL_ID, thirdTriggered));
                
                // Check skill progression
                if (triggered) { // Only check if we got a chance to use it
                    checkSkillProgression(characterId, THIRD_ATTACK_SKILL_ID, thirdAttack, thirdTriggered);
                }
                
                // Only check for fourth attack if we have third attack skill
                CharacterSkill fourthAttack = getDao().getCharacterSkill(characterId, FOURTH_ATTACK_SKILL_ID);
                if (fourthAttack != null) {
                    boolean fourthTriggered = thirdTriggered && rollForAttack(fourthAttack.getProficiency());
                    opportunities.add(new AttackOpportunity(4, FOURTH_ATTACK_PENALTY, FOURTH_ATTACK_SKILL_ID, fourthTriggered));
                    
                    // Check skill progression
                    if (thirdTriggered) { // Only check if we got a chance to use it
                        checkSkillProgression(characterId, FOURTH_ATTACK_SKILL_ID, fourthAttack, fourthTriggered);
                    }
                }
            }
        }
        
        return opportunities;
    }
    
    /**
     * Check for additional attacks for a mobile.
     * Mobs derive their multi-attack chance from level: level * 2%, capped at 100%.
     */
    private List<AttackOpportunity> getMobileAdditionalAttacks(Combatant combatant) {
        List<AttackOpportunity> opportunities = new ArrayList<>();
        Mobile mobile = combatant.getMobile();
        if (mobile == null) return opportunities;
        
        int level = mobile.getLevel();
        
        // Calculate base chance from level (level * 2%, capped at 100%)
        int baseChance = Math.min(level * 2, 100);
        
        // Second attack - all mobs have potential for this based on level
        if (baseChance > 0) {
            boolean triggered = rollForAttack(baseChance);
            opportunities.add(new AttackOpportunity(2, SECOND_ATTACK_PENALTY, SECOND_ATTACK_SKILL_ID, triggered));
            
            // Third attack - requires level 10+ (20% base at level 10)
            if (level >= 10 && triggered) {
                // Reduce chance slightly for each tier (level * 2 - 20 for third, capped at 80%)
                int thirdChance = Math.min(Math.max(0, baseChance - 20), 80);
                boolean thirdTriggered = rollForAttack(thirdChance);
                opportunities.add(new AttackOpportunity(3, THIRD_ATTACK_PENALTY, THIRD_ATTACK_SKILL_ID, thirdTriggered));
                
                // Fourth attack - requires level 25+ (30% base at level 25)
                if (level >= 25 && thirdTriggered) {
                    // Further reduced chance (level * 2 - 50, capped at 50%)
                    int fourthChance = Math.min(Math.max(0, baseChance - 50), 50);
                    boolean fourthTriggered = rollForAttack(fourthChance);
                    opportunities.add(new AttackOpportunity(4, FOURTH_ATTACK_PENALTY, FOURTH_ATTACK_SKILL_ID, fourthTriggered));
                }
            }
        }
        
        return opportunities;
    }
    
    /**
     * Roll to see if an attack triggers.
     * @param proficiency the skill proficiency (1-100%)
     * @return true if the attack triggers
     */
    private boolean rollForAttack(int proficiency) {
        if (proficiency <= 0) return false;
        if (proficiency >= 100) return true;
        
        int roll = (int)(Math.random() * 100) + 1; // 1-100
        return roll <= proficiency;
    }
    
    /**
     * Check skill progression for a player's multi-attack skill.
     * Uses the standard progression system: 1 check on success, 2 checks on failure.
     */
    private void checkSkillProgression(int characterId, int skillId, CharacterSkill charSkill, boolean succeeded) {
        Skill skill = getDao().getSkillById(skillId);
        if (skill == null) return;
        
        ProficiencyCheck.Result result = ProficiencyCheck.checkProficiencyGrowth(
            characterId, skill, charSkill, succeeded, getDao());
        
        if (result.hasImproved() && playerMessageCallback != null) {
            playerMessageCallback.accept(characterId, result.getImprovementMessage());
        }
    }
    
    /**
     * Get only the triggered attacks from the opportunities list.
     */
    public List<AttackOpportunity> getTriggeredAttacks(List<AttackOpportunity> opportunities) {
        List<AttackOpportunity> triggered = new ArrayList<>();
        for (AttackOpportunity opp : opportunities) {
            if (opp.isTriggered()) {
                triggered.add(opp);
            }
        }
        return triggered;
    }
    
    /**
     * Count how many total attacks a combatant will make (1 base + triggered extras).
     */
    public int countTotalAttacks(Combatant combatant) {
        List<AttackOpportunity> opportunities = getAdditionalAttacks(combatant);
        int count = 1; // Base attack
        for (AttackOpportunity opp : opportunities) {
            if (opp.isTriggered()) {
                count++;
            }
        }
        return count;
    }
}
