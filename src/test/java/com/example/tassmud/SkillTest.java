package com.example.tassmud;

import com.example.tassmud.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Skill and SkillProgression Tests")
class SkillTest {

    @Test
    @DisplayName("Skill constructor sets all fields correctly")
    void skillConstructorSetsFields() {
        Skill skill = new Skill(1, "Sword Fighting", "Basic sword combat");
        
        assertEquals(1, skill.getId());
        assertEquals("Sword Fighting", skill.getName());
        assertEquals("Basic sword combat", skill.getDescription());
        assertEquals(Skill.SkillProgression.NORMAL, skill.getProgression());
    }
    
    @Test
    @DisplayName("Skill constructor with progression sets progression correctly")
    void skillConstructorWithProgression() {
        Skill skill = new Skill(2, "Arcane Magic", "Magic proficiency", Skill.SkillProgression.INSTANT);
        
        assertEquals(Skill.SkillProgression.INSTANT, skill.getProgression());
    }
    
    @Test
    @DisplayName("Skill constructor defaults null progression to NORMAL")
    void skillConstructorNullProgressionDefaultsToNormal() {
        Skill skill = new Skill(3, "Test", "Test desc", null);
        
        assertEquals(Skill.SkillProgression.NORMAL, skill.getProgression());
    }
    
    // --- SkillProgression Tests ---
    
    @Test
    @DisplayName("INSTANT progression is instant")
    void instantProgressionIsInstant() {
        assertTrue(Skill.SkillProgression.INSTANT.isInstant());
    }
    
    @ParameterizedTest
    @EnumSource(value = Skill.SkillProgression.class, names = {"TRIVIAL", "EASY", "NORMAL", "HARD", "VERY_HARD", "LEGENDARY"})
    @DisplayName("Non-INSTANT progressions are not instant")
    void nonInstantProgressionsAreNotInstant(Skill.SkillProgression progression) {
        assertFalse(progression.isInstant());
    }
    
    @Test
    @DisplayName("INSTANT progression starting proficiency is 100")
    void instantProgressionStartsAt100() {
        assertEquals(100, Skill.SkillProgression.INSTANT.getStartingProficiency());
    }
    
    @ParameterizedTest
    @EnumSource(value = Skill.SkillProgression.class, names = {"TRIVIAL", "EASY", "NORMAL", "HARD", "VERY_HARD", "LEGENDARY"})
    @DisplayName("Non-INSTANT progressions start at proficiency 1")
    void nonInstantProgressionsStartAt1(Skill.SkillProgression progression) {
        assertEquals(1, progression.getStartingProficiency());
    }
    
    @Test
    @DisplayName("INSTANT progression has 0% gain chance")
    void instantProgressionHasZeroGainChance() {
        assertEquals(0, Skill.SkillProgression.INSTANT.getGainChance(50));
    }
    
    @Test
    @DisplayName("Mastered skill has 0% gain chance")
    void masteredSkillHasZeroGainChance() {
        assertEquals(0, Skill.SkillProgression.NORMAL.getGainChance(100));
    }
    
    @Test
    @DisplayName("Gain chance decreases as proficiency increases")
    void gainChanceDecreasesWithProficiency() {
        Skill.SkillProgression prog = Skill.SkillProgression.NORMAL;
        int chanceAt1 = prog.getGainChance(1);
        int chanceAt50 = prog.getGainChance(50);
        int chanceAt99 = prog.getGainChance(99);
        
        assertTrue(chanceAt1 >= chanceAt50, "Chance at 1 should be >= chance at 50");
        assertTrue(chanceAt50 >= chanceAt99, "Chance at 50 should be >= chance at 99");
        // At minimum, chance at start should be at least as high as at end
        assertTrue(chanceAt1 >= chanceAt99, "Chance at 1 should be >= chance at 99");
    }
    
    @Test
    @DisplayName("Gain chance is always at least 1% for non-INSTANT progressions")
    void gainChanceMinimumIs1Percent() {
        for (Skill.SkillProgression prog : Skill.SkillProgression.values()) {
            if (!prog.isInstant()) {
                int chance = prog.getGainChance(99);
                assertTrue(chance >= 1, prog + " should have at least 1% chance at 99 proficiency");
            }
        }
    }
    
    @ParameterizedTest
    @CsvSource({
        "INSTANT, instant",
        "TRIVIAL, trivial",
        "EASY, easy",
        "NORMAL, normal",
        "HARD, hard",
        "VERY_HARD, very_hard",
        "LEGENDARY, legendary"
    })
    @DisplayName("fromString parses progression strings correctly")
    void fromStringParsesCorrectly(Skill.SkillProgression expected, String input) {
        assertEquals(expected, Skill.SkillProgression.fromString(input));
    }
    
    @Test
    @DisplayName("fromString is case-insensitive")
    void fromStringIsCaseInsensitive() {
        assertEquals(Skill.SkillProgression.NORMAL, Skill.SkillProgression.fromString("NORMAL"));
        assertEquals(Skill.SkillProgression.NORMAL, Skill.SkillProgression.fromString("normal"));
        assertEquals(Skill.SkillProgression.NORMAL, Skill.SkillProgression.fromString("Normal"));
    }
    
    @Test
    @DisplayName("fromString handles hyphenated input")
    void fromStringHandlesHyphens() {
        assertEquals(Skill.SkillProgression.VERY_HARD, Skill.SkillProgression.fromString("very-hard"));
    }
    
    @Test
    @DisplayName("fromString returns NORMAL for invalid input")
    void fromStringDefaultsToNormal() {
        assertEquals(Skill.SkillProgression.NORMAL, Skill.SkillProgression.fromString("invalid"));
        assertEquals(Skill.SkillProgression.NORMAL, Skill.SkillProgression.fromString(""));
        assertEquals(Skill.SkillProgression.NORMAL, Skill.SkillProgression.fromString(null));
    }
}
