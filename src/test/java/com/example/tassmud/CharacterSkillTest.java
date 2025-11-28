package com.example.tassmud;

import com.example.tassmud.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CharacterSkill Tests")
class CharacterSkillTest {

    @Test
    @DisplayName("Constructor sets all fields correctly")
    void constructorSetsFields() {
        CharacterSkill cs = new CharacterSkill(1, 2, 50);
        
        assertEquals(1, cs.getCharacterId());
        assertEquals(2, cs.getSkillId());
        assertEquals(50, cs.getProficiency());
    }
    
    @Test
    @DisplayName("Proficiency is clamped to minimum of 1")
    void proficiencyClampedToMinimum() {
        CharacterSkill cs = new CharacterSkill(1, 1, 0);
        assertEquals(CharacterSkill.MIN_PROFICIENCY, cs.getProficiency());
        
        CharacterSkill cs2 = new CharacterSkill(1, 1, -10);
        assertEquals(CharacterSkill.MIN_PROFICIENCY, cs2.getProficiency());
    }
    
    @Test
    @DisplayName("Proficiency is clamped to maximum of 100")
    void proficiencyClampedToMaximum() {
        CharacterSkill cs = new CharacterSkill(1, 1, 150);
        assertEquals(CharacterSkill.MAX_PROFICIENCY, cs.getProficiency());
    }
    
    @Test
    @DisplayName("setProficiency clamps values correctly")
    void setProficiencyClampsValues() {
        CharacterSkill cs = new CharacterSkill(1, 1, 50);
        
        cs.setProficiency(0);
        assertEquals(CharacterSkill.MIN_PROFICIENCY, cs.getProficiency());
        
        cs.setProficiency(150);
        assertEquals(CharacterSkill.MAX_PROFICIENCY, cs.getProficiency());
        
        cs.setProficiency(75);
        assertEquals(75, cs.getProficiency());
    }
    
    @Test
    @DisplayName("isMastered returns true only at 100%")
    void isMasteredOnlyAtMax() {
        CharacterSkill cs = new CharacterSkill(1, 1, 99);
        assertFalse(cs.isMastered());
        
        cs.setProficiency(100);
        assertTrue(cs.isMastered());
    }
    
    @ParameterizedTest
    @CsvSource({
        "100, Mastered",
        "95, 'Expert (95%)'",
        "90, 'Expert (90%)'",
        "80, 'Adept (80%)'",
        "75, 'Adept (75%)'",
        "60, 'Skilled (60%)'",
        "50, 'Skilled (50%)'",
        "35, 'Familiar (35%)'",
        "25, 'Familiar (25%)'",
        "15, 'Novice (15%)'",
        "1, 'Novice (1%)'"
    })
    @DisplayName("getProficiencyDisplay returns correct label")
    void proficiencyDisplayReturnsCorrectLabel(int proficiency, String expected) {
        CharacterSkill cs = new CharacterSkill(1, 1, proficiency);
        assertEquals(expected, cs.getProficiencyDisplay());
    }
    
    @Test
    @DisplayName("Legacy getLevel/setLevel map to proficiency")
    @SuppressWarnings("deprecation")
    void legacyMethodsMapToProficiency() {
        CharacterSkill cs = new CharacterSkill(1, 1, 50);
        
        assertEquals(50, cs.getLevel());
        
        cs.setLevel(75);
        assertEquals(75, cs.getProficiency());
        assertEquals(75, cs.getLevel());
    }
    
    @Test
    @DisplayName("MIN_PROFICIENCY constant is 1")
    void minProficiencyConstant() {
        assertEquals(1, CharacterSkill.MIN_PROFICIENCY);
    }
    
    @Test
    @DisplayName("MAX_PROFICIENCY constant is 100")
    void maxProficiencyConstant() {
        assertEquals(100, CharacterSkill.MAX_PROFICIENCY);
    }
}
