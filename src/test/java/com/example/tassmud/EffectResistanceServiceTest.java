package com.example.tassmud;

import com.example.tassmud.combat.Combatant;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectResistanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EffectResistanceService}: registration, evaluation, and
 * helper classifications on {@link Combatant.StatusFlag} and
 * {@link EffectDefinition} tags.
 */
class EffectResistanceServiceTest {

    @BeforeEach
    void setUp() {
        EffectResistanceService.clearAll();
    }

    @AfterEach
    void tearDown() {
        EffectResistanceService.clearAll();
    }

    // -----------------------------------------------------------------------
    // Registration & count
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("starts with zero checks")
    void startsEmpty() {
        assertEquals(0, EffectResistanceService.registeredCheckCount());
    }

    @Test
    @DisplayName("register adds checks and count reflects it")
    void registerIncrementsCount() {
        EffectResistanceService.register((id, cats) -> null);
        assertEquals(1, EffectResistanceService.registeredCheckCount());
        EffectResistanceService.register((id, cats) -> null);
        assertEquals(2, EffectResistanceService.registeredCheckCount());
    }

    @Test
    @DisplayName("register ignores null check")
    void registerIgnoresNull() {
        EffectResistanceService.register(null);
        assertEquals(0, EffectResistanceService.registeredCheckCount());
    }

    @Test
    @DisplayName("clearAll removes all checks")
    void clearAllRemovesChecks() {
        EffectResistanceService.register((id, cats) -> null);
        EffectResistanceService.register((id, cats) -> null);
        EffectResistanceService.clearAll();
        assertEquals(0, EffectResistanceService.registeredCheckCount());
    }

    // -----------------------------------------------------------------------
    // Resistance evaluation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no checks registered → null (no resistance)")
    void noChecksReturnsNull() {
        assertNull(EffectResistanceService.checkResistance(1, Set.of("debuff")));
    }

    @Test
    @DisplayName("check that always returns message → returns that message")
    void alwaysResistCheck() {
        EffectResistanceService.register((id, cats) -> "Resisted!");
        assertEquals("Resisted!", EffectResistanceService.checkResistance(1, Set.of("debuff")));
    }

    @Test
    @DisplayName("check that returns null → effect proceeds")
    void alwaysAllowCheck() {
        EffectResistanceService.register((id, cats) -> null);
        assertNull(EffectResistanceService.checkResistance(1, Set.of("debuff")));
    }

    @Test
    @DisplayName("first non-null result wins (short-circuits)")
    void firstNonNullWins() {
        EffectResistanceService.register((id, cats) -> null);               // passes
        EffectResistanceService.register((id, cats) -> "Second resists");   // blocks
        EffectResistanceService.register((id, cats) -> "Third resists");    // should not be reached
        assertEquals("Second resists", EffectResistanceService.checkResistance(1, Set.of("debuff")));
    }

    @Test
    @DisplayName("check receives correct targetId and categories")
    void checkReceivesCorrectArgs() {
        final int[] capturedId = {0};
        final Set<String>[] capturedCats = new Set[]{null};

        EffectResistanceService.register((id, cats) -> {
            capturedId[0] = id;
            capturedCats[0] = cats;
            return null;
        });

        EffectResistanceService.checkResistance(42, Set.of("debuff", "mind"));
        assertEquals(42, capturedId[0]);
        assertTrue(capturedCats[0].contains("debuff"));
        assertTrue(capturedCats[0].contains("mind"));
    }

    @Test
    @DisplayName("null targetId short-circuits to null")
    void nullTargetIdReturnsNull() {
        EffectResistanceService.register((id, cats) -> "Should not reach");
        assertNull(EffectResistanceService.checkResistance(null, Set.of("debuff")));
    }

    @Test
    @DisplayName("empty categories short-circuits to null")
    void emptyCategoriesReturnsNull() {
        EffectResistanceService.register((id, cats) -> "Should not reach");
        assertNull(EffectResistanceService.checkResistance(1, Set.of()));
    }

    @Test
    @DisplayName("null categories short-circuits to null")
    void nullCategoriesReturnsNull() {
        EffectResistanceService.register((id, cats) -> "Should not reach");
        assertNull(EffectResistanceService.checkResistance(1, (Set<String>) null));
    }

    @Test
    @DisplayName("single-category convenience overload works")
    void singleCategoryOverload() {
        EffectResistanceService.register((id, cats) -> cats.contains("mind") ? "Mind blocked" : null);
        assertEquals("Mind blocked", EffectResistanceService.checkResistance(1, "mind"));
        assertNull(EffectResistanceService.checkResistance(1, "physical"));
    }

    @Test
    @DisplayName("single-category null short-circuits to null")
    void singleCategoryNullReturnsNull() {
        EffectResistanceService.register((id, cats) -> "Should not reach");
        assertNull(EffectResistanceService.checkResistance(1, (String) null));
    }

    // -----------------------------------------------------------------------
    // Category-selective checks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("check can filter by category")
    void categoryFiltering() {
        EffectResistanceService.register((id, cats) ->
                cats.contains("mind") ? "Mind shielded" : null);

        assertNull(EffectResistanceService.checkResistance(1, Set.of("debuff", "physical")));
        assertEquals("Mind shielded", EffectResistanceService.checkResistance(1, Set.of("debuff", "mind")));
    }

    @Test
    @DisplayName("check can filter by targetId")
    void targetIdFiltering() {
        // Only target 99 is immune
        EffectResistanceService.register((id, cats) -> id == 99 ? "Immune NPC" : null);

        assertNull(EffectResistanceService.checkResistance(1, Set.of("debuff")));
        assertEquals("Immune NPC", EffectResistanceService.checkResistance(99, Set.of("debuff")));
    }

    // -----------------------------------------------------------------------
    // StatusFlag.isNegative()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("StatusFlag.isNegative()")
    class StatusFlagNegative {

        @Test
        @DisplayName("ADVANTAGE is not negative")
        void advantageNotNegative() {
            assertFalse(Combatant.StatusFlag.ADVANTAGE.isNegative());
        }

        @Test
        @DisplayName("STUNNED is negative")
        void stunnedIsNegative() {
            assertTrue(Combatant.StatusFlag.STUNNED.isNegative());
        }

        @Test
        @DisplayName("BLINDED is negative")
        void blindedIsNegative() {
            assertTrue(Combatant.StatusFlag.BLINDED.isNegative());
        }

        @Test
        @DisplayName("PRONE is negative")
        void proneIsNegative() {
            assertTrue(Combatant.StatusFlag.PRONE.isNegative());
        }

        @Test
        @DisplayName("SILENCED is negative")
        void silencedIsNegative() {
            assertTrue(Combatant.StatusFlag.SILENCED.isNegative());
        }

        @Test
        @DisplayName("DISARMED is negative")
        void disarmedIsNegative() {
            assertTrue(Combatant.StatusFlag.DISARMED.isNegative());
        }
    }

    // -----------------------------------------------------------------------
    // StatusFlag.resistanceCategory()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("StatusFlag.resistanceCategory()")
    class StatusFlagCategory {

        @Test
        @DisplayName("STUNNED is physical")
        void stunnedCategory() {
            assertEquals("physical", Combatant.StatusFlag.STUNNED.resistanceCategory());
        }

        @Test
        @DisplayName("BLINDED is sense")
        void blindedCategory() {
            assertEquals("sense", Combatant.StatusFlag.BLINDED.resistanceCategory());
        }

        @Test
        @DisplayName("CONFUSED is mind")
        void confusedCategory() {
            assertEquals("mind", Combatant.StatusFlag.CONFUSED.resistanceCategory());
        }

        @Test
        @DisplayName("DISARMED is physical")
        void disarmedCategory() {
            assertEquals("physical", Combatant.StatusFlag.DISARMED.resistanceCategory());
        }

        @Test
        @DisplayName("ADVANTAGE has none category")
        void advantageCategory() {
            assertEquals("none", Combatant.StatusFlag.ADVANTAGE.resistanceCategory());
        }
    }

    // -----------------------------------------------------------------------
    // EffectDefinition tags
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EffectDefinition tags")
    class EffectDefinitionTags {

        /** Helper to build a minimal EffectDefinition without tags. */
        private EffectDefinition minimalDef() {
            return new EffectDefinition("1", "test",
                    EffectDefinition.Type.MODIFIER, Collections.emptyMap(),
                    10.0, 0.0, null, 1, null,
                    EffectDefinition.StackPolicy.UNIQUE, false, 0);
        }

        /** Helper to build a minimal EffectDefinition with the given tags. */
        private EffectDefinition defWithTags(Set<String> tags) {
            return new EffectDefinition("1", "test",
                    EffectDefinition.Type.MODIFIER, Collections.emptyMap(),
                    10.0, 0.0, null, 1, null,
                    EffectDefinition.StackPolicy.UNIQUE, false, 0, tags);
        }

        @Test
        @DisplayName("default constructor has no tags")
        void defaultNoTags() {
            EffectDefinition def = minimalDef();
            assertTrue(def.getTags().isEmpty());
        }

        @Test
        @DisplayName("constructor with tags preserves them")
        void constructorWithTags() {
            Set<String> tags = Set.of("debuff", "magical", "mind");
            EffectDefinition def = defWithTags(tags);
            assertEquals(3, def.getTags().size());
            assertTrue(def.hasTag("debuff"));
            assertTrue(def.hasTag("magical"));
            assertTrue(def.hasTag("mind"));
        }

        @Test
        @DisplayName("hasTag returns false for missing tag")
        void hasTagMissing() {
            EffectDefinition def = defWithTags(Set.of("debuff"));
            assertFalse(def.hasTag("mind"));
        }

        @Test
        @DisplayName("tags are unmodifiable")
        void tagsUnmodifiable() {
            // Use a mutable set as input — tags should be stored as an unmodifiable copy
            Set<String> mutable = new java.util.HashSet<>();
            mutable.add("debuff");
            EffectDefinition def = defWithTags(mutable);
            // The returned set should reject mutation
            assertThrows(UnsupportedOperationException.class, () -> def.getTags().add("hack"));
        }
    }
}
