package com.example.tassmud;

import com.example.tassmud.util.OpposedCheck;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpposedCheck utility, including proficiency-based success calculations.
 */
public class OpposedCheckTest {
    
    private static final double DELTA = 0.001; // Tolerance for floating point comparisons
    
    // ========== Base chance tests ==========
    
    @Test
    void testEqualLevel_BaseChance50Percent() {
        assertEquals(0.5, OpposedCheck.getSuccessChance(10, 10), DELTA);
        assertEquals(50, OpposedCheck.getSuccessPercent(10, 10));
    }
    
    @Test
    void testHigherAttacker_IncreasedChance() {
        // +1 level = 75%
        assertEquals(0.75, OpposedCheck.getSuccessChance(11, 10), DELTA);
        // +2 levels = 87.5%
        assertEquals(0.875, OpposedCheck.getSuccessChance(12, 10), DELTA);
        // +5 levels = 100%
        assertEquals(1.0, OpposedCheck.getSuccessChance(15, 10), DELTA);
    }
    
    @Test
    void testLowerAttacker_DecreasedChance() {
        // -1 level = 25%
        assertEquals(0.25, OpposedCheck.getSuccessChance(9, 10), DELTA);
        // -2 levels = 12.5%
        assertEquals(0.125, OpposedCheck.getSuccessChance(8, 10), DELTA);
        // -5 levels = 0%
        assertEquals(0.0, OpposedCheck.getSuccessChance(5, 10), DELTA);
    }
    
    @Test
    void testLevelDifferenceClamped() {
        // Beyond +5 should still be 100%
        assertEquals(1.0, OpposedCheck.getSuccessChance(20, 10), DELTA);
        // Beyond -5 should still be 0%
        assertEquals(0.0, OpposedCheck.getSuccessChance(1, 10), DELTA);
    }
    
    // ========== Proficiency multiplier tests ==========
    
    @Test
    void testProficiencyMultiplier_Range() {
        // 0% proficiency = 0.5 multiplier
        assertEquals(0.5, OpposedCheck.getProficiencyMultiplier(0), DELTA);
        // 50% proficiency = 1.0 multiplier
        assertEquals(1.0, OpposedCheck.getProficiencyMultiplier(50), DELTA);
        // 100% proficiency = 1.5 multiplier
        assertEquals(1.5, OpposedCheck.getProficiencyMultiplier(100), DELTA);
        // 1% proficiency = 0.51 multiplier
        assertEquals(0.51, OpposedCheck.getProficiencyMultiplier(1), DELTA);
    }
    
    @Test
    void testProficiencyMultiplierDecimal_Range() {
        // 0.0 proficiency = 0.5 multiplier
        assertEquals(0.5, OpposedCheck.getProficiencyMultiplierDecimal(0.0), DELTA);
        // 0.5 proficiency = 1.0 multiplier
        assertEquals(1.0, OpposedCheck.getProficiencyMultiplierDecimal(0.5), DELTA);
        // 1.0 proficiency = 1.5 multiplier
        assertEquals(1.5, OpposedCheck.getProficiencyMultiplierDecimal(1.0), DELTA);
        // 0.01 proficiency = 0.51 multiplier
        assertEquals(0.51, OpposedCheck.getProficiencyMultiplierDecimal(0.01), DELTA);
    }
    
    // ========== User-provided test cases ==========
    // These are the exact examples from the requirements
    
    @Test
    void testLevel10vs10_WithProficiency() {
        // char level 10 vs mob level 10 with 1% kick = 25.5%
        // 50% * (50% + 1%) = 50% * 51% = 25.5%
        assertEquals(0.255, OpposedCheck.getSuccessChanceWithProficiency(10, 10, 1), DELTA);
        assertEquals(26, OpposedCheck.getSuccessPercentWithProficiency(10, 10, 1)); // Rounds to 26
        
        // 50% trained = 50% * (50% + 50%) = 50% * 100% = 50%
        assertEquals(0.5, OpposedCheck.getSuccessChanceWithProficiency(10, 10, 50), DELTA);
        assertEquals(50, OpposedCheck.getSuccessPercentWithProficiency(10, 10, 50));
        
        // 100% trained = 50% * (50% + 100%) = 50% * 150% = 75%
        assertEquals(0.75, OpposedCheck.getSuccessChanceWithProficiency(10, 10, 100), DELTA);
        assertEquals(75, OpposedCheck.getSuccessPercentWithProficiency(10, 10, 100));
    }
    
    @Test
    void testLevel8vs10_WithProficiency() {
        // Level 8 vs 10 base = 12.5% (diff -2)
        assertEquals(0.125, OpposedCheck.getSuccessChance(8, 10), DELTA);
        
        // 1% trained: 12.5% * 51% = 6.375%
        assertEquals(0.06375, OpposedCheck.getSuccessChanceWithProficiency(8, 10, 1), DELTA);
        assertEquals(6, OpposedCheck.getSuccessPercentWithProficiency(8, 10, 1)); // Rounds to 6
        
        // 50% trained: 12.5% * 100% = 12.5%
        assertEquals(0.125, OpposedCheck.getSuccessChanceWithProficiency(8, 10, 50), DELTA);
        assertEquals(13, OpposedCheck.getSuccessPercentWithProficiency(8, 10, 50)); // Rounds to 13
        
        // 100% trained: 12.5% * 150% = 18.75%
        assertEquals(0.1875, OpposedCheck.getSuccessChanceWithProficiency(8, 10, 100), DELTA);
        assertEquals(19, OpposedCheck.getSuccessPercentWithProficiency(8, 10, 100)); // Rounds to 19
    }
    
    @Test
    void testLevel11vs10_WithProficiency() {
        // Level 11 vs 10 base = 75% (diff +1)
        assertEquals(0.75, OpposedCheck.getSuccessChance(11, 10), DELTA);
        
        // 1% trained: 75% * 51% = 38.25%
        assertEquals(0.3825, OpposedCheck.getSuccessChanceWithProficiency(11, 10, 1), DELTA);
        assertEquals(38, OpposedCheck.getSuccessPercentWithProficiency(11, 10, 1)); // Rounds to 38
        
        // 50% trained: 75% * 100% = 75%
        assertEquals(0.75, OpposedCheck.getSuccessChanceWithProficiency(11, 10, 50), DELTA);
        assertEquals(75, OpposedCheck.getSuccessPercentWithProficiency(11, 10, 50));
        
        // 100% trained: 75% * 150% = 112.5% -> clamped to 100%
        assertEquals(1.0, OpposedCheck.getSuccessChanceWithProficiency(11, 10, 100), DELTA);
        assertEquals(100, OpposedCheck.getSuccessPercentWithProficiency(11, 10, 100));
    }
    
    // ========== Edge case tests ==========
    
    @Test
    void testProficiency_Clamping() {
        // Proficiency below 0 should be treated as 0
        assertEquals(0.5, OpposedCheck.getProficiencyMultiplier(-10), DELTA);
        // Proficiency above 100 should be treated as 100
        assertEquals(1.5, OpposedCheck.getProficiencyMultiplier(150), DELTA);
    }
    
    @Test
    void testFinalChance_ClampedTo100Percent() {
        // High level advantage + high proficiency could exceed 100%, should be clamped
        // Level 15 vs 10 (diff +5) = 100% base, with 100% prof = 150% -> clamped to 100%
        assertEquals(1.0, OpposedCheck.getSuccessChanceWithProficiency(15, 10, 100), DELTA);
    }
    
    @Test
    void testZeroBaseChance_StaysZero() {
        // If base chance is 0 (level diff >= -5), proficiency doesn't help
        // Level 5 vs 10 (diff -5) = 0%, with 100% prof = 0% * 1.5 = 0%
        assertEquals(0.0, OpposedCheck.getSuccessChanceWithProficiency(5, 10, 100), DELTA);
    }
    
    // ========== Decimal proficiency tests ==========
    
    @Test
    void testDecimalProficiency_MatchesIntegerVersion() {
        // 0.01 decimal should match 1% integer
        assertEquals(
            OpposedCheck.getSuccessChanceWithProficiency(10, 10, 1),
            OpposedCheck.getSuccessChanceWithProficiencyDecimal(10, 10, 0.01),
            DELTA
        );
        
        // 0.50 decimal should match 50% integer
        assertEquals(
            OpposedCheck.getSuccessChanceWithProficiency(10, 10, 50),
            OpposedCheck.getSuccessChanceWithProficiencyDecimal(10, 10, 0.50),
            DELTA
        );
        
        // 1.0 decimal should match 100% integer
        assertEquals(
            OpposedCheck.getSuccessChanceWithProficiency(10, 10, 100),
            OpposedCheck.getSuccessChanceWithProficiencyDecimal(10, 10, 1.0),
            DELTA
        );
    }
}
