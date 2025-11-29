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
        // Attacker level 5 vs defender level 3 = (5-3)*2 = +4, cap is 5, so +4
        assertEquals(4, calculator.calculateLevelAttackBonus(5, 3));
        
        // Attacker level 10 vs defender level 5 = (10-5)*2 = +10, cap is 10, so +10
        assertEquals(10, calculator.calculateLevelAttackBonus(10, 5));
        
        // Attacker level 5 vs defender level 1 = (5-1)*2 = +8, but cap is 5, so +5
        assertEquals(5, calculator.calculateLevelAttackBonus(5, 1));
        
        // Attacker level 3 vs defender level 1 = (3-1)*2 = +4, but cap is 3, so +3
        assertEquals(3, calculator.calculateLevelAttackBonus(3, 1));
    }
    
    @Test
    void testLevelAttackBonus_defenderHigher() {
        // Defender higher = negative bonus, no cap applies
        // Attacker level 3 vs defender level 5 = (3-5)*2 = -4
        assertEquals(-4, calculator.calculateLevelAttackBonus(3, 5));
        
        // Attacker level 1 vs defender level 10 = (1-10)*2 = -18
        assertEquals(-18, calculator.calculateLevelAttackBonus(1, 10));
    }
    
    // === Damage Multiplier Tests ===
    
    @Test
    void testDamageMultiplier_equalSkills() {
        // Equal skills = 1.0 multiplier
        double mult = calculator.calculateDamageMultiplier(0.5, 0.5, 0.5, 0.5);
        assertEquals(1.0, mult, 0.001);
        
        mult = calculator.calculateDamageMultiplier(1.0, 1.0, 1.0, 1.0);
        assertEquals(1.0, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_attackerHigherSkills() {
        // Attacker has 100% family, defender has 50% family
        // Attacker has 100% category, defender has 50% category
        // (1.0/0.5) * (1.0/0.5) = 2 * 2 = 4.0
        double mult = calculator.calculateDamageMultiplier(1.0, 0.5, 1.0, 0.5);
        assertEquals(4.0, mult, 0.001);
        
        // Attacker has 80% skills, defender has 40% skills
        // (0.8/0.4) * (0.8/0.4) = 2 * 2 = 4.0
        mult = calculator.calculateDamageMultiplier(0.8, 0.4, 0.8, 0.4);
        assertEquals(4.0, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_defenderHigherSkills() {
        // Attacker has 50% skills, defender has 100% skills
        // (0.5/1.0) * (0.5/1.0) = 0.5 * 0.5 = 0.25
        double mult = calculator.calculateDamageMultiplier(0.5, 1.0, 0.5, 1.0);
        assertEquals(0.25, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_mixedSkills() {
        // Attacker family 80%, defender family 40% = 2.0
        // Attacker category 40%, defender category 80% = 0.5
        // Total: 2.0 * 0.5 = 1.0
        double mult = calculator.calculateDamageMultiplier(0.8, 0.4, 0.4, 0.8);
        assertEquals(1.0, mult, 0.001);
    }
    
    @Test
    void testDamageMultiplier_handlesZeroSkills() {
        // Defender with zero skills should use minimum (0.01)
        // This prevents divide by zero
        double mult = calculator.calculateDamageMultiplier(0.5, 0.0, 0.5, 0.0);
        // (0.5/0.01) * (0.5/0.01) = 50 * 50 = 2500
        assertEquals(2500.0, mult, 0.001);
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
        assertEquals(4, attackBonus); // (5-3)*2=4, cap=5, so 4
        
        // Player has 50% sword skill, mob has level-based skills
        double mobCategory = calculator.calculateMobCategorySkill(3); // 3*0.02=0.06
        double mobFamily = calculator.calculateMobFamilySkill(3); // min(3*0.1,1)=0.3
        
        // Player 50% family, 50% category vs mob
        double mult = calculator.calculateDamageMultiplier(0.5, mobFamily, 0.5, mobCategory);
        // (0.5/0.3) * (0.5/0.06) = 1.667 * 8.333 = ~13.89
        assertTrue(mult > 10, "Expected significant damage bonus against low-skill mob");
    }
    
    @Test
    void testScenario_level1PlayerVsLevel10Mob() {
        // Newbie player level 1 attacking high level mob
        int attackBonus = calculator.calculateLevelAttackBonus(1, 10);
        assertEquals(-18, attackBonus); // (1-10)*2=-18, big penalty
        
        // Mob level 10 skills
        double mobCategory = calculator.calculateMobCategorySkill(10); // 10*0.02=0.2
        double mobFamily = calculator.calculateMobFamilySkill(10); // min(10*0.1,1)=1.0
        
        // Player with 10% skills vs mob
        double mult = calculator.calculateDamageMultiplier(0.1, mobFamily, 0.1, mobCategory);
        // (0.1/1.0) * (0.1/0.2) = 0.1 * 0.5 = 0.05
        assertEquals(0.05, mult, 0.001);
    }
}
