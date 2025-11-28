package com.example.tassmud;

import com.example.tassmud.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CharacterSpell Tests")
class CharacterSpellTest {

    @Test
    @DisplayName("Constructor sets all fields correctly")
    void constructorSetsFields() {
        CharacterSpell cs = new CharacterSpell(1, 2, 50);
        
        assertEquals(1, cs.getCharacterId());
        assertEquals(2, cs.getSpellId());
        assertEquals(50, cs.getProficiency());
    }
    
    @Test
    @DisplayName("Proficiency is clamped to minimum of 1")
    void proficiencyClampedToMinimum() {
        CharacterSpell cs = new CharacterSpell(1, 1, 0);
        assertEquals(CharacterSpell.MIN_PROFICIENCY, cs.getProficiency());
        
        CharacterSpell cs2 = new CharacterSpell(1, 1, -10);
        assertEquals(CharacterSpell.MIN_PROFICIENCY, cs2.getProficiency());
    }
    
    @Test
    @DisplayName("Proficiency is clamped to maximum of 100")
    void proficiencyClampedToMaximum() {
        CharacterSpell cs = new CharacterSpell(1, 1, 150);
        assertEquals(CharacterSpell.MAX_PROFICIENCY, cs.getProficiency());
    }
    
    @Test
    @DisplayName("setProficiency clamps values correctly")
    void setProficiencyClampsValues() {
        CharacterSpell cs = new CharacterSpell(1, 1, 50);
        
        cs.setProficiency(0);
        assertEquals(CharacterSpell.MIN_PROFICIENCY, cs.getProficiency());
        
        cs.setProficiency(150);
        assertEquals(CharacterSpell.MAX_PROFICIENCY, cs.getProficiency());
        
        cs.setProficiency(75);
        assertEquals(75, cs.getProficiency());
    }
    
    @Test
    @DisplayName("isMastered returns true only at 100%")
    void isMasteredOnlyAtMax() {
        CharacterSpell cs = new CharacterSpell(1, 1, 99);
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
        CharacterSpell cs = new CharacterSpell(1, 1, proficiency);
        assertEquals(expected, cs.getProficiencyDisplay());
    }
    
    @Test
    @DisplayName("getEffectiveCastingTime reduces at higher proficiency")
    void effectiveCastingTimeReducesWithProficiency() {
        double baseCastTime = 2.0;
        
        CharacterSpell novice = new CharacterSpell(1, 1, 1);
        double noviceTime = novice.getEffectiveCastingTime(baseCastTime);
        
        CharacterSpell master = new CharacterSpell(1, 1, 100);
        double masterTime = master.getEffectiveCastingTime(baseCastTime);
        
        // Novice should be at full cast time (2.0)
        assertEquals(2.0, noviceTime, 0.01);
        
        // Master should be at 50% cast time (1.0)
        assertEquals(1.0, masterTime, 0.01);
    }
    
    @Test
    @DisplayName("getEffectiveCastingTime scales linearly")
    void effectiveCastingTimeScalesLinearly() {
        double baseCastTime = 2.0;
        
        CharacterSpell midPoint = new CharacterSpell(1, 1, 50);
        double midTime = midPoint.getEffectiveCastingTime(baseCastTime);
        
        // At 50% proficiency, should be roughly 75% of base time (1.5)
        // Formula: baseCastTime * (1 - (proficiency-1)/198)
        // At 50: 2.0 * (1 - 49/198) = 2.0 * 0.753 = 1.505
        assertTrue(midTime > 1.4 && midTime < 1.6, 
                   "Mid proficiency cast time should be around 1.5, was " + midTime);
    }
    
    @Test
    @DisplayName("Legacy getLevel/setLevel map to proficiency")
    @SuppressWarnings("deprecation")
    void legacyMethodsMapToProficiency() {
        CharacterSpell cs = new CharacterSpell(1, 1, 50);
        
        assertEquals(50, cs.getLevel());
        
        cs.setLevel(75);
        assertEquals(75, cs.getProficiency());
        assertEquals(75, cs.getLevel());
    }
    
    @Test
    @DisplayName("MIN_PROFICIENCY constant is 1")
    void minProficiencyConstant() {
        assertEquals(1, CharacterSpell.MIN_PROFICIENCY);
    }
    
    @Test
    @DisplayName("MAX_PROFICIENCY constant is 100")
    void maxProficiencyConstant() {
        assertEquals(100, CharacterSpell.MAX_PROFICIENCY);
    }
}
