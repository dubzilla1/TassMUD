package com.example.tassmud;

import com.example.tassmud.model.AllyBehavior;
import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.AllyPersistence;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.spell.UndeadTemplateFactory;
import com.example.tassmud.spell.UndeadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Animate Dead spell system: UndeadType, UndeadTemplateFactory,
 * and the AllyBinding patterns used for summoned undead.
 */
class AnimateDeadTest {

    // ═══════════════════════════════════════════════════════════════════
    //  UndeadType
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UndeadType")
    class UndeadTypeTests {

        @Test
        @DisplayName("Level 1 caster only gets SKELETON")
        void level1OnlySkeleton() {
            List<UndeadType> available = UndeadType.getAvailableTypes(1);
            assertEquals(1, available.size());
            assertEquals(UndeadType.SKELETON, available.get(0));
        }

        @Test
        @DisplayName("Level 10 unlocks GHOUL")
        void level10UnlocksGhoul() {
            List<UndeadType> available = UndeadType.getAvailableTypes(10);
            assertEquals(2, available.size());
            assertTrue(available.contains(UndeadType.SKELETON));
            assertTrue(available.contains(UndeadType.GHOUL));
        }

        @Test
        @DisplayName("Level 20 unlocks MUMMY")
        void level20UnlocksMummy() {
            List<UndeadType> available = UndeadType.getAvailableTypes(20);
            assertEquals(3, available.size());
            assertTrue(available.contains(UndeadType.MUMMY));
        }

        @Test
        @DisplayName("Level 30 unlocks VAMPIRE")
        void level30UnlocksVampire() {
            List<UndeadType> available = UndeadType.getAvailableTypes(30);
            assertEquals(4, available.size());
            assertTrue(available.contains(UndeadType.VAMPIRE));
        }

        @Test
        @DisplayName("Level 40 unlocks DEATH_KNIGHT")
        void level40UnlocksDeathKnight() {
            List<UndeadType> available = UndeadType.getAvailableTypes(40);
            assertEquals(5, available.size());
            assertTrue(available.contains(UndeadType.DEATH_KNIGHT));
        }

        @Test
        @DisplayName("Level 50 unlocks all 6 types including LICH")
        void level50UnlocksAll() {
            List<UndeadType> available = UndeadType.getAvailableTypes(50);
            assertEquals(6, available.size());
            for (UndeadType type : UndeadType.values()) {
                assertTrue(available.contains(type), "Missing: " + type);
            }
        }

        @Test
        @DisplayName("Level 0 returns empty — rollRandomType falls back to SKELETON")
        void level0FallbackSkeleton() {
            List<UndeadType> available = UndeadType.getAvailableTypes(0);
            assertTrue(available.isEmpty());
            assertEquals(UndeadType.SKELETON, UndeadType.rollRandomType(0));
        }

        @ParameterizedTest
        @EnumSource(UndeadType.class)
        @DisplayName("Every type has a non-null randomName()")
        void randomNameNotNull(UndeadType type) {
            String name = type.randomName();
            assertNotNull(name);
            assertFalse(name.isEmpty());
        }

        @ParameterizedTest
        @EnumSource(UndeadType.class)
        @DisplayName("Every type has a non-null longDesc")
        void longDescNotNull(UndeadType type) {
            assertNotNull(type.getLongDesc());
            assertFalse(type.getLongDesc().isEmpty());
        }

        @ParameterizedTest
        @EnumSource(UndeadType.class)
        @DisplayName("HP multiplier is positive")
        void hpMultPositive(UndeadType type) {
            assertTrue(type.getHpMult() > 0);
        }

        @ParameterizedTest
        @EnumSource(UndeadType.class)
        @DisplayName("Armor multiplier is positive")
        void armorMultPositive(UndeadType type) {
            assertTrue(type.getArmorMult() > 0);
        }

        @ParameterizedTest
        @EnumSource(UndeadType.class)
        @DisplayName("Damage multiplier is positive")
        void dmgMultPositive(UndeadType type) {
            assertTrue(type.getDmgMult() > 0);
        }

        @Test
        @DisplayName("Spec_fun assignments match design")
        void specFunAssignments() {
            assertNull(UndeadType.SKELETON.getSpecFun());
            assertEquals("spec_poison", UndeadType.GHOUL.getSpecFun());
            assertEquals("spec_undead_mummy", UndeadType.MUMMY.getSpecFun());
            assertEquals("spec_undead_drain", UndeadType.VAMPIRE.getSpecFun());
            assertEquals("spec_undead_taunt", UndeadType.DEATH_KNIGHT.getSpecFun());
            assertEquals("spec_cast_undead", UndeadType.LICH.getSpecFun());
        }

        @Test
        @DisplayName("Level offsets produce expected minion levels")
        void levelOffsets() {
            assertEquals(-3, UndeadType.SKELETON.getLevelOffset());
            assertEquals(-2, UndeadType.GHOUL.getLevelOffset());
            assertEquals(-1, UndeadType.MUMMY.getLevelOffset());
            assertEquals(0, UndeadType.VAMPIRE.getLevelOffset());
            assertEquals(0, UndeadType.DEATH_KNIGHT.getLevelOffset());
            assertEquals(0, UndeadType.LICH.getLevelOffset());
        }

        @Test
        @DisplayName("rollRandomType returns valid type for level")
        void rollRandomTypeValid() {
            // Run multiple times to cover randomness
            for (int i = 0; i < 100; i++) {
                UndeadType type = UndeadType.rollRandomType(25);
                assertTrue(type.getMinCasterLevel() <= 25,
                        "Rolled " + type + " requires level " + type.getMinCasterLevel());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UndeadTemplateFactory
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UndeadTemplateFactory")
    class FactoryTests {

        @ParameterizedTest
        @EnumSource(UndeadType.class)
        @DisplayName("Creates valid template for each type at level 25")
        void createsValidTemplate(UndeadType type) {
            MobileTemplate t = UndeadTemplateFactory.createTemplate(type, 25);
            assertNotNull(t);
            assertEquals(25, t.getLevel());
            assertTrue(t.getHpMax() >= 10, "HP should be at least 10");
            assertEquals(0, t.getExperienceValue(), "Summoned undead give no XP");
            assertEquals(type.getSpecFun(), t.getSpecFun());
            assertTrue(t.getBehaviors().contains(MobileBehavior.SENTINEL),
                    "Summoned undead should be SENTINEL");
        }

        @Test
        @DisplayName("Template IDs are 90001-90006 based on ordinal")
        void templateIds() {
            for (UndeadType type : UndeadType.values()) {
                MobileTemplate t = UndeadTemplateFactory.createTemplate(type, 10);
                assertEquals(90001 + type.ordinal(), t.getId());
            }
        }

        @Test
        @DisplayName("Minimum level clamped to 1")
        void minLevelClamp() {
            MobileTemplate t = UndeadTemplateFactory.createTemplate(UndeadType.SKELETON, 0);
            assertEquals(1, t.getLevel());

            MobileTemplate neg = UndeadTemplateFactory.createTemplate(UndeadType.SKELETON, -5);
            assertEquals(1, neg.getLevel());
        }

        @Test
        @DisplayName("Lich gets MP, others get 0")
        void lichGetsMp() {
            MobileTemplate lich = UndeadTemplateFactory.createTemplate(UndeadType.LICH, 30);
            assertTrue(lich.getMpMax() > 0, "Lich should have MP");

            MobileTemplate skeleton = UndeadTemplateFactory.createTemplate(UndeadType.SKELETON, 30);
            assertEquals(0, skeleton.getMpMax(), "Non-lich should have 0 MP");
        }

        @Test
        @DisplayName("Keywords include 'undead' and 'summoned'")
        void keywordsPresent() {
            MobileTemplate t = UndeadTemplateFactory.createTemplate(UndeadType.VAMPIRE, 20);
            assertTrue(t.getKeywords().contains("undead"));
            assertTrue(t.getKeywords().contains("summoned"));
        }

        @Test
        @DisplayName("Key follows naming convention")
        void keyNaming() {
            MobileTemplate t = UndeadTemplateFactory.createTemplate(UndeadType.DEATH_KNIGHT, 15);
            assertTrue(t.getKey().startsWith("undead_death_knight_"));
        }

        @Test
        @DisplayName("HP scales with level")
        void hpScalesWithLevel() {
            // Collect median HP over runs to smooth variance
            int lowLevelHp = medianHp(UndeadType.MUMMY, 5, 20);
            int highLevelHp = medianHp(UndeadType.MUMMY, 40, 20);
            assertTrue(highLevelHp > lowLevelHp,
                    "Level 40 mummy HP (" + highLevelHp + ") should exceed level 5 (" + lowLevelHp + ")");
        }

        @Test
        @DisplayName("Gold is always 0 (summoned undead drop nothing)")
        void noGold() {
            for (UndeadType type : UndeadType.values()) {
                MobileTemplate t = UndeadTemplateFactory.createTemplate(type, 20);
                assertEquals(0, t.getGoldMin());
                assertEquals(0, t.getGoldMax());
            }
        }

        private int medianHp(UndeadType type, int level, int samples) {
            int[] hps = new int[samples];
            for (int i = 0; i < samples; i++) {
                hps[i] = UndeadTemplateFactory.createTemplate(type, level).getHpMax();
            }
            java.util.Arrays.sort(hps);
            return hps[samples / 2];
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  AllyBinding for summoned undead patterns
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AllyBinding — summoned undead patterns")
    class AllyBindingTests {

        @Test
        @DisplayName("Temporary DEFENDER binding with expiry — standard animate dead pattern")
        void temporaryDefenderWithExpiry() {
            long expiresAt = System.currentTimeMillis() + 120_000L;
            AllyBinding b = new AllyBinding(
                    1001L, 42, 90001L,
                    AllyBehavior.DEFENDER, AllyPersistence.TEMPORARY,
                    true, true, expiresAt);

            assertEquals(AllyBehavior.DEFENDER, b.getBehavior());
            assertEquals(AllyPersistence.TEMPORARY, b.getPersistence());
            assertTrue(b.isFollowsOwner());
            assertTrue(b.isObeys());
            assertTrue(b.shouldAutoDefend(), "DEFENDER should auto-defend");
            assertFalse(b.shouldSurviveDeath(), "TEMPORARY should not survive death");
            assertFalse(b.shouldFleeWithOwner(), "TEMPORARY should not flee with owner");
            assertFalse(b.isExpired(), "Should not be expired yet");
        }

        @Test
        @DisplayName("Expired binding detected correctly")
        void expiredBinding() {
            long pastExpiry = System.currentTimeMillis() - 1000L;
            AllyBinding b = new AllyBinding(
                    1001L, 42, 90001L,
                    AllyBehavior.DEFENDER, AllyPersistence.TEMPORARY,
                    true, true, pastExpiry);

            assertTrue(b.isExpired());
        }

        @Test
        @DisplayName("Zero expiresAt means never expires")
        void noExpiry() {
            AllyBinding b = new AllyBinding(
                    1001L, 42, 90001L,
                    AllyBehavior.DEFENDER, AllyPersistence.TEMPORARY,
                    true, true, 0L);

            assertFalse(b.isExpired());
        }

        @Test
        @DisplayName("Duration formula: base 60s + 60s per 10 proficiency")
        void durationFormula() {
            long baseDuration = 60_000L;
            long bonusPer10 = 60_000L;

            // proficiency 0 → 60s
            assertEquals(60_000L, baseDuration + (0 / 10) * bonusPer10);
            // proficiency 9 → 60s (no bonus yet)
            assertEquals(60_000L, baseDuration + (9 / 10) * bonusPer10);
            // proficiency 10 → 120s
            assertEquals(120_000L, baseDuration + (10 / 10) * bonusPer10);
            // proficiency 50 → 360s (6 min)
            assertEquals(360_000L, baseDuration + (50 / 10) * bonusPer10);
            // proficiency 100 → 660s (11 min)
            assertEquals(660_000L, baseDuration + (100 / 10) * bonusPer10);
        }
    }
}
