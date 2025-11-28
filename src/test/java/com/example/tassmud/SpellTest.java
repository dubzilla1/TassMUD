package com.example.tassmud;

import com.example.tassmud.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Spell and SpellSchool/SpellTarget Tests")
class SpellTest {

    @Test
    @DisplayName("Simple constructor sets defaults correctly")
    void simpleConstructorSetsDefaults() {
        Spell spell = new Spell(1, "Magic Missile", "Fires bolts of magic");
        
        assertEquals(1, spell.getId());
        assertEquals("Magic Missile", spell.getName());
        assertEquals("Fires bolts of magic", spell.getDescription());
        assertEquals(Spell.SpellSchool.ARCANE, spell.getSchool());
        assertEquals(1, spell.getLevel());
        assertEquals(1.0, spell.getBaseCastingTime());
        assertEquals(Spell.SpellTarget.SELF, spell.getTarget());
        assertEquals(Skill.SkillProgression.NORMAL, spell.getProgression());
        assertTrue(spell.getEffectIds().isEmpty());
    }
    
    @Test
    @DisplayName("Full constructor sets all fields correctly")
    void fullConstructorSetsFields() {
        List<String> effects = Arrays.asList("101", "102");
        Spell spell = new Spell(5, "Fireball", "A ball of fire", 
                                Spell.SpellSchool.ARCANE, 3, 2.5,
                                Spell.SpellTarget.ALL_ENEMIES, effects,
                                Skill.SkillProgression.HARD);
        
        assertEquals(5, spell.getId());
        assertEquals("Fireball", spell.getName());
        assertEquals("A ball of fire", spell.getDescription());
        assertEquals(Spell.SpellSchool.ARCANE, spell.getSchool());
        assertEquals(3, spell.getLevel());
        assertEquals(2.5, spell.getBaseCastingTime());
        assertEquals(Spell.SpellTarget.ALL_ENEMIES, spell.getTarget());
        assertEquals(Skill.SkillProgression.HARD, spell.getProgression());
        assertEquals(2, spell.getEffectIds().size());
        assertTrue(spell.getEffectIds().contains("101"));
    }
    
    @Test
    @DisplayName("Spell level is clamped to 1-10 range")
    void spellLevelClamped() {
        Spell lowLevel = new Spell(1, "Test", "Test", Spell.SpellSchool.ARCANE, 
                                   0, 1.0, Spell.SpellTarget.SELF, null, null);
        assertEquals(1, lowLevel.getLevel());
        
        Spell highLevel = new Spell(1, "Test", "Test", Spell.SpellSchool.ARCANE, 
                                    15, 1.0, Spell.SpellTarget.SELF, null, null);
        assertEquals(10, highLevel.getLevel());
    }
    
    @Test
    @DisplayName("Null school defaults to ARCANE")
    void nullSchoolDefaultsToArcane() {
        Spell spell = new Spell(1, "Test", "Test", null, 1, 1.0, 
                                Spell.SpellTarget.SELF, null, null);
        assertEquals(Spell.SpellSchool.ARCANE, spell.getSchool());
    }
    
    @Test
    @DisplayName("Null target defaults to SELF")
    void nullTargetDefaultsToSelf() {
        Spell spell = new Spell(1, "Test", "Test", Spell.SpellSchool.ARCANE, 
                                1, 1.0, null, null, null);
        assertEquals(Spell.SpellTarget.SELF, spell.getTarget());
    }
    
    @Test
    @DisplayName("Null progression defaults to NORMAL")
    void nullProgressionDefaultsToNormal() {
        Spell spell = new Spell(1, "Test", "Test", Spell.SpellSchool.ARCANE, 
                                1, 1.0, Spell.SpellTarget.SELF, null, null);
        assertEquals(Skill.SkillProgression.NORMAL, spell.getProgression());
    }
    
    @Test
    @DisplayName("Effect IDs list is immutable")
    void effectIdsImmutable() {
        List<String> effects = Arrays.asList("101");
        Spell spell = new Spell(1, "Test", "Test", Spell.SpellSchool.ARCANE, 
                                1, 1.0, Spell.SpellTarget.SELF, effects, null);
        
        assertThrows(UnsupportedOperationException.class, () -> {
            spell.getEffectIds().add("999");
        });
    }
    
    // --- SpellSchool Tests ---
    
    @ParameterizedTest
    @EnumSource(Spell.SpellSchool.class)
    @DisplayName("All spell schools have display names and required skill keys")
    void allSchoolsHaveRequiredFields(Spell.SpellSchool school) {
        assertNotNull(school.displayName);
        assertFalse(school.displayName.isEmpty());
        assertNotNull(school.requiredSkillKey);
        assertFalse(school.requiredSkillKey.isEmpty());
    }
    
    @ParameterizedTest
    @CsvSource({
        "ARCANE, Arcane, arcane_magic",
        "DIVINE, Divine, divine_magic",
        "PRIMAL, Primal, primal_magic",
        "OCCULT, Occult, occult_magic"
    })
    @DisplayName("Spell schools have correct display names and skill keys")
    void schoolsHaveCorrectValues(Spell.SpellSchool school, String displayName, String skillKey) {
        assertEquals(displayName, school.displayName);
        assertEquals(skillKey, school.requiredSkillKey);
    }
    
    @ParameterizedTest
    @CsvSource({
        "ARCANE, arcane",
        "DIVINE, Divine",
        "PRIMAL, PRIMAL",
        "OCCULT, occult"
    })
    @DisplayName("SpellSchool.fromString parses correctly (case-insensitive)")
    void schoolFromStringParses(Spell.SpellSchool expected, String input) {
        assertEquals(expected, Spell.SpellSchool.fromString(input));
    }
    
    @Test
    @DisplayName("SpellSchool.fromString returns ARCANE for invalid input")
    void schoolFromStringDefaultsToArcane() {
        assertEquals(Spell.SpellSchool.ARCANE, Spell.SpellSchool.fromString("invalid"));
        assertEquals(Spell.SpellSchool.ARCANE, Spell.SpellSchool.fromString(""));
        assertEquals(Spell.SpellSchool.ARCANE, Spell.SpellSchool.fromString(null));
    }
    
    // --- SpellTarget Tests ---
    
    @ParameterizedTest
    @EnumSource(Spell.SpellTarget.class)
    @DisplayName("All spell targets have keys")
    void allTargetsHaveKeys(Spell.SpellTarget target) {
        assertNotNull(target.key);
        assertFalse(target.key.isEmpty());
    }
    
    @ParameterizedTest
    @CsvSource({
        "SELF, self",
        "CURRENT_ENEMY, current_enemy",
        "EXPLICIT_MOB_TARGET, explicit_mob_target",
        "ITEM, item",
        "ALL_ENEMIES, all_enemies",
        "ALL_ALLIES, all_allies",
        "EVERYONE, everyone"
    })
    @DisplayName("SpellTarget.fromString parses correctly")
    void targetFromStringParses(Spell.SpellTarget expected, String input) {
        assertEquals(expected, Spell.SpellTarget.fromString(input));
    }
    
    @Test
    @DisplayName("SpellTarget.fromString handles hyphens and spaces")
    void targetFromStringHandlesFormats() {
        assertEquals(Spell.SpellTarget.CURRENT_ENEMY, Spell.SpellTarget.fromString("current-enemy"));
        assertEquals(Spell.SpellTarget.CURRENT_ENEMY, Spell.SpellTarget.fromString("current enemy"));
        assertEquals(Spell.SpellTarget.EXPLICIT_MOB_TARGET, Spell.SpellTarget.fromString("explicit-mob-target"));
    }
    
    @Test
    @DisplayName("SpellTarget.fromString is case-insensitive")
    void targetFromStringCaseInsensitive() {
        assertEquals(Spell.SpellTarget.ALL_ENEMIES, Spell.SpellTarget.fromString("ALL_ENEMIES"));
        assertEquals(Spell.SpellTarget.ALL_ENEMIES, Spell.SpellTarget.fromString("all_enemies"));
        assertEquals(Spell.SpellTarget.ALL_ENEMIES, Spell.SpellTarget.fromString("All_Enemies"));
    }
    
    @Test
    @DisplayName("SpellTarget.fromString returns SELF for invalid input")
    void targetFromStringDefaultsToSelf() {
        assertEquals(Spell.SpellTarget.SELF, Spell.SpellTarget.fromString("invalid"));
        assertEquals(Spell.SpellTarget.SELF, Spell.SpellTarget.fromString(""));
        assertEquals(Spell.SpellTarget.SELF, Spell.SpellTarget.fromString(null));
    }
}
