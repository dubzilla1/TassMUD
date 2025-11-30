package com.example.tassmud;

import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
import com.example.tassmud.util.ProficiencyCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProficiencyCheck utility.
 */
@DisplayName("ProficiencyCheck Tests")
public class ProficiencyCheckTest {
    
    @Test
    @DisplayName("Result correctly reports no improvement")
    void resultNoImprovement() {
        ProficiencyCheck.Result result = new ProficiencyCheck.Result(false, "Kick", 50, 50);
        
        assertFalse(result.hasImproved());
        assertEquals("Kick", result.getSkillName());
        assertEquals(50, result.getOldProficiency());
        assertEquals(50, result.getNewProficiency());
        assertNull(result.getImprovementMessage());
    }
    
    @Test
    @DisplayName("Result correctly reports improvement")
    void resultWithImprovement() {
        ProficiencyCheck.Result result = new ProficiencyCheck.Result(true, "Kick", 50, 51);
        
        assertTrue(result.hasImproved());
        assertEquals("Kick", result.getSkillName());
        assertEquals(50, result.getOldProficiency());
        assertEquals(51, result.getNewProficiency());
        assertEquals("Your Kick has improved! (50% -> 51%)", result.getImprovementMessage());
    }
    
    @Test
    @DisplayName("Improvement message format is correct")
    void improvementMessageFormat() {
        ProficiencyCheck.Result result = new ProficiencyCheck.Result(true, "Arcane Magic", 1, 2);
        assertEquals("Your Arcane Magic has improved! (1% -> 2%)", result.getImprovementMessage());
        
        result = new ProficiencyCheck.Result(true, "Kick", 99, 100);
        assertEquals("Your Kick has improved! (99% -> 100%)", result.getImprovementMessage());
    }
    
    @Test
    @DisplayName("INSTANT progression skills never improve")
    void instantSkillsNeverImprove() {
        // INSTANT skills start at 100% and don't progress
        Skill instantSkill = new Skill(1, "Sword Proficiency", "Sword use", Skill.SkillProgression.INSTANT);
        CharacterSkill charSkill = new CharacterSkill(1, 1, 100);
        
        // Even with success, instant skills don't grow
        // We can't easily test without a real DAO, but we can verify the logic
        assertEquals(Skill.SkillProgression.INSTANT, instantSkill.getProgression());
        assertTrue(instantSkill.getProgression().isInstant());
        assertEquals(0, instantSkill.getProgression().getGainChance(100));
    }
    
    @Test
    @DisplayName("Mastered skills (100%) don't improve further")
    void masteredSkillsDontImprove() {
        Skill skill = new Skill(1, "Kick", "A kick", Skill.SkillProgression.NORMAL);
        
        // At 100%, gain chance should be 0
        assertEquals(0, skill.getProgression().getGainChance(100));
    }
    
    @Test
    @DisplayName("Gain chance decreases as proficiency increases")
    void gainChanceDecreasesWithProficiency() {
        Skill.SkillProgression normal = Skill.SkillProgression.NORMAL;
        
        int chanceAt1 = normal.getGainChance(1);
        int chanceAt25 = normal.getGainChance(25);
        int chanceAt50 = normal.getGainChance(50);
        
        // Chance should decrease as proficiency increases
        assertTrue(chanceAt1 > chanceAt25, "Chance at 1% should be higher than at 25%");
        assertTrue(chanceAt25 > chanceAt50, "Chance at 25% should be higher than at 50%");
        
        // All chances should be at least 1%
        int chanceAt99 = normal.getGainChance(99);
        assertTrue(chanceAt99 >= 1, "Chance should always be at least 1%");
    }
    
    @Test
    @DisplayName("Different progressions have different base chances")
    void progressionsHaveDifferentBaseChances() {
        assertEquals(50, Skill.SkillProgression.TRIVIAL.baseGainChance);
        assertEquals(35, Skill.SkillProgression.EASY.baseGainChance);
        assertEquals(25, Skill.SkillProgression.NORMAL.baseGainChance);
        assertEquals(15, Skill.SkillProgression.HARD.baseGainChance);
        assertEquals(10, Skill.SkillProgression.VERY_HARD.baseGainChance);
        assertEquals(5, Skill.SkillProgression.LEGENDARY.baseGainChance);
    }
}
