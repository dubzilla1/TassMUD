package com.example.tassmud;

import com.example.tassmud.combat.CombatCalculator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CombatCalculator attack bonus and damage multiplier formulas.
 */
public class CombatCalculatorTest {

    private final CombatCalculator calculator = new CombatCalculator();

    // === Level-based Attack Bonus Tests ===
    
    @Test
    void testLevelAttackBonus_equalLevels() {
        // Same level = no bonus
        assertEquals(0, calculator.calculateLevelAttackBonus(5, 5));
        assertEquals(0, calculator.calculateLevelAttackBonus(10, 10));
        assertEquals(0, calculator.calculateLevelAttackBonus(1, 1));
    }
    
    @Test
    void testLevelAttackBonus_attackerHigher() {
        // Attacker level 5 vs defender level 3 = (5-3) = +2, capped at +5
        assertEquals(2, calculator.calculateLevelAttackBonus(5, 3));
        
        // Attacker level 10 vs defender level 5 = (10-5) = +5, capped at +5
        assertEquals(5, calculator.calculateLevelAttackBonus(10, 5));
        
        // Attacker level 5 vs defender level 1 = (5-1) = +4, capped at +5
        assertEquals(4, calculator.calculateLevelAttackBonus(5, 1));
        
        // Attacker level 3 vs defender level 1 = (3-1) = +2, capped at +5
        assertEquals(2, calculator.calculateLevelAttackBonus(3, 1));
        
        // Attacker level 20 vs defender level 1 = +19 raw, capped at +5
        assertEquals(5, calculator.calculateLevelAttackBonus(20, 1));
    }
    
    @Test
    void testLevelAttackBonus_defenderHigher() {
        // Defender higher = negative bonus, capped at -5
        // Attacker level 3 vs defender level 5 = (3-5) = -2
        assertEquals(-2, calculator.calculateLevelAttackBonus(3, 5));
        
        // Attacker level 1 vs defender level 10 = (1-10) = -9, capped at -5
        assertEquals(-5, calculator.calculateLevelAttackBonus(1, 10));
    }
    
    // === Damage Multiplier Tests ===
    
    @Test
    void testDamageMultiplier_equalSkills() {
        // Formula: 0.5 + avg(family, category). Defender skills are ignored.
        // 0.5 + avg(0.5, 0.5) = 0.5 + 0.5 = 1.0
        double mult = calculator.calculateDamageMultiplier(0.5, 0.5, 0.5, 0.5);
        assertEquals(1.0, mult, 0.001);
        
        // 0.5 + avg(1.0, 1.0) = 0.5 + 1.0 = 1.5 (max)
        mult = calculator.calculateDamageMultiplier(1.0, 1.0, 1.0, 1.0);
        assertEquals(1.5, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_attackerHigherSkills() {
        // Formula: 0.5 + avg(attackerFamily, attackerCategory). Defender ignored.
        // 0.5 + avg(1.0, 1.0) = 1.5 (max)
        double mult = calculator.calculateDamageMultiplier(1.0, 0.5, 1.0, 0.5);
        assertEquals(1.5, mult, 0.001);
        
        // 0.5 + avg(0.8, 0.8) = 0.5 + 0.8 = 1.3
        mult = calculator.calculateDamageMultiplier(0.8, 0.4, 0.8, 0.4);
        assertEquals(1.3, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_defenderHigherSkills() {
        // Formula: 0.5 + avg(attackerFamily, attackerCategory). Defender ignored.
        // 0.5 + avg(0.5, 0.5) = 1.0 (same as neutral — defender skill doesn't matter)
        double mult = calculator.calculateDamageMultiplier(0.5, 1.0, 0.5, 1.0);
        assertEquals(1.0, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_mixedSkills() {
        // 0.5 + avg(0.8, 0.4) = 0.5 + 0.6 = 1.1
        double mult = calculator.calculateDamageMultiplier(0.8, 0.4, 0.4, 0.8);
        assertEquals(1.1, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_handlesZeroSkills() {
        // 0.5 + avg(0.5, 0.5) = 1.0 (defender zeroes are irrelevant)
        double mult = calculator.calculateDamageMultiplier(0.5, 0.0, 0.5, 0.0);
        assertEquals(1.0, mult, 0.001);
        
        // Attacker at zero: 0.5 + avg(0.0, 0.0) = 0.5 (minimum)
        mult = calculator.calculateDamageMultiplier(0.0, 1.0, 0.0, 1.0);
        assertEquals(0.5, mult, 0.001);
    }
    
    // === Mob Skill Calculation Tests ===
    
    @Test
    void testMobCategorySkill() {
        // mob_level * 0.02, capped at 1.0
        assertEquals(0.02, calculator.calculateMobCategorySkill(1), 0.001);
        assertEquals(0.10, calculator.calculateMobCategorySkill(5), 0.001);
        assertEquals(0.20, calculator.calculateMobCategorySkill(10), 0.001);
        assertEquals(0.50, calculator.calculateMobCategorySkill(25), 0.001);
        assertEquals(1.0, calculator.calculateMobCategorySkill(50), 0.001);
        assertEquals(1.0, calculator.calculateMobCategorySkill(100), 0.001); // Capped at 1.0
    }
    
    @Test
    void testMobFamilySkill() {
        // min(mob_level * 0.1, 1.0)
        assertEquals(0.1, calculator.calculateMobFamilySkill(1), 0.001);
        assertEquals(0.5, calculator.calculateMobFamilySkill(5), 0.001);
        assertEquals(1.0, calculator.calculateMobFamilySkill(10), 0.001); // Capped at 1.0
        assertEquals(1.0, calculator.calculateMobFamilySkill(20), 0.001);
    }
    
    // === Integration tests with example scenarios ===
    
    @Test
    void testScenario_level5PlayerVsLevel3Mob() {
        // Player level 5 attacking mob level 3
        int attackBonus = calculator.calculateLevelAttackBonus(5, 3);
        assertEquals(2, attackBonus); // (5-3) = +2, capped at +5
        
        // Player has 50% sword skill, mob has level-based skills
        double mobCategory = calculator.calculateMobCategorySkill(3); // 3*0.02=0.06
        double mobFamily = calculator.calculateMobFamilySkill(3); // min(3*0.1,1)=0.3
        
        // Player 50% family, 50% category vs mob
        // Formula: 0.5 + avg(0.5, 0.5) = 1.0 (defender skills unused)
        double mult = calculator.calculateDamageMultiplier(0.5, mobFamily, 0.5, mobCategory);
        assertEquals(1.0, mult, 0.001);
    }
    
    @Test
    void testScenario_level1PlayerVsLevel10Mob() {
        // Newbie player level 1 attacking high level mob
        int attackBonus = calculator.calculateLevelAttackBonus(1, 10);
        assertEquals(-5, attackBonus); // (1-10) = -9, capped at -5
        
        // Mob level 10 skills
        double mobCategory = calculator.calculateMobCategorySkill(10); // 10*0.02=0.2
        double mobFamily = calculator.calculateMobFamilySkill(10); // min(10*0.1,1)=1.0
        
        // Player with 10% skills vs mob
        // Formula: 0.5 + avg(0.1, 0.1) = 0.5 + 0.1 = 0.6 (defender ignored)
        double mult = calculator.calculateDamageMultiplier(0.1, mobFamily, 0.1, mobCategory);
        assertEquals(0.6, mult, 0.001);
    }
}
