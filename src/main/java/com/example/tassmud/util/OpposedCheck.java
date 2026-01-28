package com.example.tassmud.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility class for opposed checks between two entities (characters, mobs, etc).
 * 
 * Opposed checks compare levels and return a success chance multiplier.
 * Each level difference provides diminishing returns:
 * - Equal level: 50% chance
 * - Higher attacker level: increased chance (up to 100% at +5)
 * - Lower attacker level: decreased chance (down to 0% at -5)
 */
public class OpposedCheck {
    
    // Lookup table for level difference -> success chance
    // Index 0 = diff of -5, index 10 = diff of +5
    private static final double[] CHANCE_TABLE = {
        0.0,      // -5: 0%
        0.03125,  // -4: 3.125%
        0.0625,   // -3: 6.25%
        0.125,    // -2: 12.5%
        0.25,     // -1: 25%
        0.5,      // 0: 50%
        0.75,     // +1: 75%
        0.875,    // +2: 87.5%
        0.9375,   // +3: 93.75%
        0.96875,  // +4: 96.875%
        1.0       // +5: 100%
    };
    
    /**
     * Calculate the success chance for an opposed check.
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @return success chance as a decimal (0.0 to 1.0)
     */
    public static double getSuccessChance(int attackerLevel, int defenderLevel) {
        int diff = attackerLevel - defenderLevel;
        // Clamp to -5 to +5 range
        diff = Math.max(-5, Math.min(5, diff));
        // Convert to array index (diff -5 = index 0, diff +5 = index 10)
        int index = diff + 5;
        return CHANCE_TABLE[index];
    }
    
    /**
     * Perform an opposed check and return whether it succeeded.
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @return true if the check succeeded, false otherwise
     */
    public static boolean check(int attackerLevel, int defenderLevel) {
        double chance = getSuccessChance(attackerLevel, defenderLevel);
        return ThreadLocalRandom.current().nextDouble() < chance;
    }
    
    /**
     * Perform an opposed check with a modifier to the base chance.
     * The modifier is added to the chance (e.g., +0.1 adds 10% to the base chance).
     * Result is clamped between 0.0 and 1.0.
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param modifier bonus or penalty to apply to the chance (-1.0 to +1.0 typical)
     * @return true if the check succeeded, false otherwise
     */
    public static boolean checkWithModifier(int attackerLevel, int defenderLevel, double modifier) {
        double chance = getSuccessChance(attackerLevel, defenderLevel) + modifier;
        chance = Math.max(0.0, Math.min(1.0, chance));
        return ThreadLocalRandom.current().nextDouble() < chance;
    }
    
    /**
     * Get the success chance with a modifier applied.
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param modifier bonus or penalty to apply to the chance
     * @return success chance as a decimal (0.0 to 1.0), clamped
     */
    public static double getSuccessChanceWithModifier(int attackerLevel, int defenderLevel, double modifier) {
        double chance = getSuccessChance(attackerLevel, defenderLevel) + modifier;
        return Math.max(0.0, Math.min(1.0, chance));
    }
    
    /**
     * Calculate the success chance as a percentage (0-100).
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @return success chance as a percentage (0 to 100)
     */
    public static int getSuccessPercent(int attackerLevel, int defenderLevel) {
        return (int) Math.round(getSuccessChance(attackerLevel, defenderLevel) * 100);
    }
    
    // ========== Proficiency-based methods ==========
    // Proficiency scales the success chance by a multiplier from 0.5 (at 0% prof) to 1.5 (at 100% prof).
    // Formula: finalChance = baseChance * (0.5 + proficiency)
    // This means:
    //   - 0% proficiency = 50% of base chance (halved)
    //   - 50% proficiency = 100% of base chance (unchanged)
    //   - 100% proficiency = 150% of base chance (boosted)
    
    /**
     * Calculate proficiency multiplier from proficiency percentage (0-100).
     * 
     * @param proficiencyPercent proficiency as an integer percentage (0-100)
     * @return multiplier (0.5 to 1.5)
     */
    public static double getProficiencyMultiplier(int proficiencyPercent) {
        double proficiency = Math.max(0, Math.min(100, proficiencyPercent)) / 100.0;
        return 0.5 + proficiency;
    }
    
    /**
     * Calculate proficiency multiplier from proficiency as a decimal (0.0-1.0).
     * 
     * @param proficiency proficiency as a decimal (0.0-1.0)
     * @return multiplier (0.5 to 1.5)
     */
    public static double getProficiencyMultiplierDecimal(double proficiency) {
        proficiency = Math.max(0.0, Math.min(1.0, proficiency));
        return 0.5 + proficiency;
    }
    
    /**
     * Calculate the success chance factoring in skill proficiency.
     * 
     * Formula: baseChance * (0.5 + proficiency)
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param proficiencyPercent skill proficiency as an integer percentage (0-100)
     * @return success chance as a decimal (0.0 to 1.0), clamped
     */
    public static double getSuccessChanceWithProficiency(int attackerLevel, int defenderLevel, int proficiencyPercent) {
        double baseChance = getSuccessChance(attackerLevel, defenderLevel);
        double multiplier = getProficiencyMultiplier(proficiencyPercent);
        double finalChance = baseChance * multiplier;
        return Math.max(0.0, Math.min(1.0, finalChance));
    }
    
    /**
     * Calculate the success chance factoring in skill proficiency (decimal version).
     * 
     * Formula: baseChance * (0.5 + proficiency)
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param proficiency skill proficiency as a decimal (0.0-1.0)
     * @return success chance as a decimal (0.0 to 1.0), clamped
     */
    public static double getSuccessChanceWithProficiencyDecimal(int attackerLevel, int defenderLevel, double proficiency) {
        double baseChance = getSuccessChance(attackerLevel, defenderLevel);
        double multiplier = getProficiencyMultiplierDecimal(proficiency);
        double finalChance = baseChance * multiplier;
        return Math.max(0.0, Math.min(1.0, finalChance));
    }
    
    /**
     * Calculate the success chance as a percentage (0-100) factoring in skill proficiency.
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param proficiencyPercent skill proficiency as an integer percentage (0-100)
     * @return success chance as a percentage (0 to 100)
     */
    public static int getSuccessPercentWithProficiency(int attackerLevel, int defenderLevel, int proficiencyPercent) {
        return (int) Math.round(getSuccessChanceWithProficiency(attackerLevel, defenderLevel, proficiencyPercent) * 100);
    }
    
    /**
     * Perform an opposed check factoring in skill proficiency.
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param proficiencyPercent skill proficiency as an integer percentage (0-100)
     * @return true if the check succeeded, false otherwise
     */
    public static boolean checkWithProficiency(int attackerLevel, int defenderLevel, int proficiencyPercent) {
        double chance = getSuccessChanceWithProficiency(attackerLevel, defenderLevel, proficiencyPercent);
        return ThreadLocalRandom.current().nextDouble() < chance;
    }
    
    /**
     * Perform an opposed check factoring in skill proficiency (decimal version).
     * 
     * @param attackerLevel the level of the attacker/initiator
     * @param defenderLevel the level of the defender/target
     * @param proficiency skill proficiency as a decimal (0.0-1.0)
     * @return true if the check succeeded, false otherwise
     */
    public static boolean checkWithProficiencyDecimal(int attackerLevel, int defenderLevel, double proficiency) {
        double chance = getSuccessChanceWithProficiencyDecimal(attackerLevel, defenderLevel, proficiency);
        return ThreadLocalRandom.current().nextDouble() < chance;
    }
}
