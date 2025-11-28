package com.example.tassmud;

import com.example.tassmud.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CharacterClass Tests")
class CharacterClassTest {

    @Test
    @DisplayName("Constructor sets all fields correctly")
    void constructorSetsFields() {
        CharacterClass cc = new CharacterClass(1, "Fighter", "A martial warrior",
                                               10, 2, 5, null);
        
        assertEquals(1, cc.id);
        assertEquals("Fighter", cc.name);
        assertEquals("A martial warrior", cc.description);
        assertEquals(10, cc.hpPerLevel);
        assertEquals(2, cc.mpPerLevel);
        assertEquals(5, cc.mvPerLevel);
        assertTrue(cc.getSkillGrants().isEmpty());
    }
    
    @Test
    @DisplayName("Constructor with skill grants stores them correctly")
    void constructorWithSkillGrants() {
        List<CharacterClass.ClassSkillGrant> grants = Arrays.asList(
            new CharacterClass.ClassSkillGrant(1, 1, 100),
            new CharacterClass.ClassSkillGrant(1, 5, 101),
            new CharacterClass.ClassSkillGrant(1, 10, 102)
        );
        
        CharacterClass cc = new CharacterClass(1, "Fighter", "Desc", 10, 2, 5, grants);
        
        assertEquals(3, cc.getSkillGrants().size());
    }
    
    @Test
    @DisplayName("getSkillGrants returns immutable list")
    void skillGrantsImmutable() {
        CharacterClass cc = new CharacterClass(1, "Fighter", "Desc", 10, 2, 5, null);
        
        assertThrows(UnsupportedOperationException.class, () -> {
            cc.getSkillGrants().add(new CharacterClass.ClassSkillGrant(1, 1, 100));
        });
    }
    
    @Test
    @DisplayName("getSkillsAtLevel returns skills at or before level")
    void getSkillsAtLevelFiltersCorrectly() {
        List<CharacterClass.ClassSkillGrant> grants = Arrays.asList(
            new CharacterClass.ClassSkillGrant(1, 1, 100),   // Level 1
            new CharacterClass.ClassSkillGrant(1, 5, 101),   // Level 5
            new CharacterClass.ClassSkillGrant(1, 10, 102),  // Level 10
            new CharacterClass.ClassSkillGrant(1, 20, 103)   // Level 20
        );
        
        CharacterClass cc = new CharacterClass(1, "Fighter", "Desc", 10, 2, 5, grants);
        
        // At level 1, should have 1 skill
        assertEquals(1, cc.getSkillsAtLevel(1).size());
        
        // At level 5, should have 2 skills
        assertEquals(2, cc.getSkillsAtLevel(5).size());
        
        // At level 10, should have 3 skills
        assertEquals(3, cc.getSkillsAtLevel(10).size());
        
        // At level 50, should have all 4 skills
        assertEquals(4, cc.getSkillsAtLevel(50).size());
    }
    
    @Test
    @DisplayName("getTotalHpBonus calculates correctly")
    void getTotalHpBonusCalculates() {
        CharacterClass cc = new CharacterClass(1, "Fighter", "Desc", 10, 2, 5, null);
        
        assertEquals(0, cc.getTotalHpBonus(0));
        assertEquals(10, cc.getTotalHpBonus(1));
        assertEquals(50, cc.getTotalHpBonus(5));
        assertEquals(500, cc.getTotalHpBonus(50));
    }
    
    @Test
    @DisplayName("getTotalMpBonus calculates correctly")
    void getTotalMpBonusCalculates() {
        CharacterClass cc = new CharacterClass(1, "Wizard", "Desc", 4, 10, 3, null);
        
        assertEquals(0, cc.getTotalMpBonus(0));
        assertEquals(10, cc.getTotalMpBonus(1));
        assertEquals(100, cc.getTotalMpBonus(10));
    }
    
    @Test
    @DisplayName("getTotalMvBonus calculates correctly")
    void getTotalMvBonusCalculates() {
        CharacterClass cc = new CharacterClass(1, "Fighter", "Desc", 10, 2, 5, null);
        
        assertEquals(0, cc.getTotalMvBonus(0));
        assertEquals(5, cc.getTotalMvBonus(1));
        assertEquals(50, cc.getTotalMvBonus(10));
    }
    
    // --- Static method tests ---
    
    @Test
    @DisplayName("MAX_NORMAL_LEVEL is 50")
    void maxNormalLevelIs50() {
        assertEquals(50, CharacterClass.MAX_NORMAL_LEVEL);
    }
    
    @Test
    @DisplayName("MAX_HERO_LEVEL is 55")
    void maxHeroLevelIs55() {
        assertEquals(55, CharacterClass.MAX_HERO_LEVEL);
    }
    
    @Test
    @DisplayName("XP_PER_LEVEL is 1000")
    void xpPerLevelIs1000() {
        assertEquals(1000, CharacterClass.XP_PER_LEVEL);
    }
    
    @ParameterizedTest
    @ValueSource(ints = {51, 52, 53, 54, 55})
    @DisplayName("isHeroLevel returns true for levels 51-55")
    void isHeroLevelTrueForHeroLevels(int level) {
        assertTrue(CharacterClass.isHeroLevel(level));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {1, 25, 50, 56, 100})
    @DisplayName("isHeroLevel returns false for non-hero levels")
    void isHeroLevelFalseForNonHeroLevels(int level) {
        assertFalse(CharacterClass.isHeroLevel(level));
    }
    
    @ParameterizedTest
    @CsvSource({
        "1, 0",
        "2, 1000",
        "3, 2000",
        "10, 9000",
        "50, 49000",
        "55, 54000"
    })
    @DisplayName("xpRequiredForLevel calculates correctly")
    void xpRequiredForLevelCalculates(int level, int expectedXp) {
        assertEquals(expectedXp, CharacterClass.xpRequiredForLevel(level));
    }
    
    @Test
    @DisplayName("xpRequiredForLevel returns 0 for level 1 or below")
    void xpRequiredForLevelZeroAtStart() {
        assertEquals(0, CharacterClass.xpRequiredForLevel(1));
        assertEquals(0, CharacterClass.xpRequiredForLevel(0));
        assertEquals(0, CharacterClass.xpRequiredForLevel(-5));
    }
    
    @ParameterizedTest
    @CsvSource({
        "0, 1",
        "999, 1",
        "1000, 2",
        "1500, 2",
        "9000, 10",
        "49000, 50",
        "54000, 55",
        "100000, 55"  // Capped at MAX_HERO_LEVEL
    })
    @DisplayName("levelFromXp calculates correctly")
    void levelFromXpCalculates(int xp, int expectedLevel) {
        assertEquals(expectedLevel, CharacterClass.levelFromXp(xp));
    }
    
    @Test
    @DisplayName("levelFromXp caps at MAX_HERO_LEVEL")
    void levelFromXpCapsAtMax() {
        assertEquals(CharacterClass.MAX_HERO_LEVEL, CharacterClass.levelFromXp(1000000));
    }
    
    @Test
    @DisplayName("toString includes name and ID")
    void toStringIncludesNameAndId() {
        CharacterClass cc = new CharacterClass(5, "Paladin", "Desc", 8, 4, 4, null);
        String str = cc.toString();
        
        assertTrue(str.contains("Paladin"));
        assertTrue(str.contains("5"));
    }
    
    // --- ClassSkillGrant Tests ---
    
    @Test
    @DisplayName("ClassSkillGrant constructor sets fields correctly")
    void classSkillGrantConstructor() {
        CharacterClass.ClassSkillGrant grant = new CharacterClass.ClassSkillGrant(1, 5, 100);
        
        assertEquals(1, grant.classId);
        assertEquals(5, grant.classLevel);
        assertEquals(100, grant.skillId);
    }
}
