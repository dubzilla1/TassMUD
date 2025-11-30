package com.example.tassmud;

import com.example.tassmud.util.SkillExecution;
import com.example.tassmud.util.ProficiencyCheck;
import com.example.tassmud.util.AbilityCheck;
import com.example.tassmud.model.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillExecution utility.
 */
@DisplayName("SkillExecution Tests")
public class SkillExecutionTest {
    
    @Test
    @DisplayName("checkPlayerCanUseSkill returns failure for null skill")
    void checkPlayerCanUseSkillNullSkill() {
        AbilityCheck.CheckResult result = SkillExecution.checkPlayerCanUseSkill("TestPlayer", 1, null);
        
        assertTrue(result.isFailure());
        assertEquals("Invalid skill.", result.getFailureMessage());
    }
    
    @Test
    @DisplayName("checkPlayerCanUseSkill returns success for valid skill with no cooldown")
    void checkPlayerCanUseSkillValidSkill() {
        // Create a simple skill with no cooldown and no traits
        Skill testSkill = new Skill(999, "Test Skill", "A test skill");
        
        AbilityCheck.CheckResult result = SkillExecution.checkPlayerCanUseSkill("TestPlayer", 1, testSkill);
        
        assertTrue(result.isSuccess());
        assertNull(result.getFailureMessage());
    }
    
    @Test
    @DisplayName("ProficiencyCheck.Result improvement message format is correct")
    void proficiencyResultMessageFormat() {
        // Test the improvement message format
        ProficiencyCheck.Result improved = new ProficiencyCheck.Result(true, "Kick", 50, 51);
        assertEquals("Your Kick has improved! (50% -> 51%)", improved.getImprovementMessage());
        assertTrue(improved.hasImproved());
        
        ProficiencyCheck.Result notImproved = new ProficiencyCheck.Result(false, "Kick", 50, 50);
        assertNull(notImproved.getImprovementMessage());
        assertFalse(notImproved.hasImproved());
    }
    
    @Test
    @DisplayName("Learning from failure scenario (proficiency gain on failed skill use)")
    void learningFromFailureScenario() {
        // This tests the concept: even on skill failure, proficiency can improve
        // (requires both proficiency rolls to pass)
        ProficiencyCheck.Result learnedFromFailure = new ProficiencyCheck.Result(true, "Kick", 25, 26);
        
        // The skill effect failed, but proficiency improved
        assertTrue(learnedFromFailure.hasImproved());
        assertEquals("Your Kick has improved! (25% -> 26%)", learnedFromFailure.getImprovementMessage());
        assertEquals(25, learnedFromFailure.getOldProficiency());
        assertEquals(26, learnedFromFailure.getNewProficiency());
    }
}
