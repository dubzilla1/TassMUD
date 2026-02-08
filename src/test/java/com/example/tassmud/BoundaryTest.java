package com.example.tassmud;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatCalculator;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary and edge-case tests for game-critical behaviors:
 * zero-HP combatants, vital boundaries, combat with dead targets,
 * extreme stat values, modifier overflow, skill progression edges.
 */
class BoundaryTest {

    private static final StatBlock STATS = StatBlock.builder()
            .str(10).dex(10).con(10).intel(10).wis(10).cha(10)
            .armor(10).fortitude(10).reflex(10).will(10)
            .build();

    private GameCharacter makeChar(String name, int hp) {
        return new GameCharacter(name, 25, "test",
                hp, hp, 50, 50, 80, 80, 1001, STATS);
    }

    // ===== Zero-HP combatant behavior =====

    @Nested
    @DisplayName("Zero-HP combatant behavior")
    class ZeroHpBehavior {
        @Test
        void deadCombatantCannotAct() {
            GameCharacter ch = makeChar("Dead", 100);
            Combatant c = new Combatant(1L, ch, 1, 1);
            c.setActive(true);
            c.damage(100);
            assertEquals(0, c.getHpCurrent());
            assertFalse(c.isAlive());
            assertFalse(c.canAct());
        }

        @Test
        void damageDoesNotGoNegative() {
            GameCharacter ch = makeChar("Tanky", 100);
            ch.setHpCur(5);
            ch.setHpCur(ch.getHpCur() - 999);
            // The setHpCur clamps to 0
            assertEquals(0, ch.getHpCur());
        }

        @Test
        void healFromZero() {
            GameCharacter ch = makeChar("Reviving", 100);
            ch.setHpCur(0);
            ch.heal(30);
            assertEquals(30, ch.getHpCur());
        }

        @Test
        void shouldAutofleeAtOneHp() {
            GameCharacter ch = makeChar("Low", 100);
            Combatant c = new Combatant(1L, ch, 1, 1);
            c.damage(99); // 1 HP remaining
            assertTrue(c.shouldAutoflee(50), "1% < 50% threshold");
        }
    }

    // ===== Vital boundary conditions =====

    @Nested
    @DisplayName("Vital boundaries")
    class VitalBoundaries {
        @Test
        void hpMaxCannotBeZero() {
            GameCharacter ch = makeChar("Test", 100);
            ch.setHpMax(0);
            assertEquals(1, ch.getHpMax(), "Min HP max = 1");
        }

        @Test
        void mpMaxCannotBeZero() {
            GameCharacter ch = makeChar("Test", 100);
            ch.setMpMax(0);
            assertEquals(1, ch.getMpMax(), "Min MP max = 1");
        }

        @Test
        void settingHpMaxBelowCurrentDoesNotClamp() {
            // setHpMax doesn't auto-clamp hpCur — but next setHpCur call will
            GameCharacter ch = makeChar("Test", 100);
            ch.setHpMax(50);
            // hpCur was 100 but hpMax is now 50
            // Without explicit clamp, hpCur may exceed max
            // Verify that heal/setHpCur clamps correctly
            ch.setHpCur(ch.getHpCur()); // trigger clamp
            assertEquals(50, ch.getHpCur(), "HpCur should be clamped to new max");
        }

        @Test
        void stanceSafeFromNull() {
            GameCharacter ch = makeChar("Test", 100);
            ch.setStance(null);
            assertEquals(Stance.STANDING, ch.getStance(), "null → STANDING default");
        }
    }

    // ===== Combat with extreme levels =====

    @Nested
    @DisplayName("Extreme level combat calculations")
    class ExtremeLevels {
        @Test
        void levelZeroVsLevelFifty() {
            CombatCalculator calc = new CombatCalculator();
            int bonus = calc.calculateLevelAttackBonus(0, 50);
            // Bonus is capped at -5
            assertEquals(-5, bonus, "Level 0 vs 50 should cap at -5");
        }

        @Test
        void levelFiftyVsLevelZero() {
            CombatCalculator calc = new CombatCalculator();
            int bonus = calc.calculateLevelAttackBonus(50, 0);
            assertEquals(5, bonus, "Level 50 vs 0 should cap at +5");
        }

        @Test
        void sameLevelZeroBonus() {
            CombatCalculator calc = new CombatCalculator();
            assertEquals(0, calc.calculateLevelAttackBonus(25, 25));
        }

        @Test
        void damageMultiplierNeverNegative() {
            CombatCalculator calc = new CombatCalculator();
            // With 0 proficiency in both, formula = 0.5 + avg(0, 0) = 0.5
            double mult = calc.calculateDamageMultiplier(0.0, 0.0, 0.0, 0.0);
            assertTrue(mult >= 0.5, "Damage multiplier floor is 0.5");
        }

        @Test
        void damageMultiplierCeiling() {
            CombatCalculator calc = new CombatCalculator();
            // Max proficiency = 1.0 in both → 0.5 + avg(1.0, 1.0) = 1.5
            double mult = calc.calculateDamageMultiplier(1.0, 1.0, 0.0, 0.0);
            assertTrue(mult <= 1.5, "Damage multiplier ceiling is 1.5");
        }
    }

    // ===== Modifier extremes =====

    @Nested
    @DisplayName("Modifier extremes")
    class ModifierExtremes {
        @Test
        void manyModifiersStack() {
            GameCharacter ch = makeChar("Buffed", 100);
            for (int i = 0; i < 100; i++) {
                ch.addModifier(new Modifier("buff" + i, Stat.STRENGTH, Modifier.Op.ADD, 1.0, 0, 0));
            }
            assertEquals(110.0, ch.getStat(Stat.STRENGTH), "10 base + 100 adds = 110");
        }

        @Test
        void zeroMultiplyNukesStat() {
            GameCharacter ch = makeChar("Nulled", 100);
            ch.addModifier(new Modifier("nuke", Stat.ARMOR, Modifier.Op.MULTIPLY, 0.0, 0, 0));
            assertEquals(0.0, ch.getStat(Stat.ARMOR));
        }

        @Test
        void negativeMultiply() {
            GameCharacter ch = makeChar("Reversed", 100);
            ch.addModifier(new Modifier("invert", Stat.STRENGTH, Modifier.Op.MULTIPLY, -1.0, 0, 0));
            assertEquals(-10.0, ch.getStat(Stat.STRENGTH), "10 * -1 = -10");
        }

        @Test
        void overrideWithZeroPriority() {
            GameCharacter ch = makeChar("Test", 100);
            ch.addModifier(new Modifier("lock", Stat.DEXTERITY, Modifier.Op.OVERRIDE, 1.0, 0, 0));
            assertEquals(1.0, ch.getStat(Stat.DEXTERITY));
        }
    }

    // ===== Combat shouldEnd edge cases =====

    @Nested
    @DisplayName("Combat shouldEnd edge cases")
    class ShouldEndEdges {
        @Test
        void emptyCombatShouldEnd() {
            Combat combat = new Combat(1L, 1001);
            combat.start(); // no combatants → 0 alliances
            assertTrue(combat.shouldEnd(), "No combatants = should end");
        }

        @Test
        void singleCombatantShouldEnd() {
            Combat combat = new Combat(1L, 1001);
            GameCharacter ch = makeChar("Solo", 100);
            combat.addPlayerCombatant(ch, 1);
            combat.start();
            assertTrue(combat.shouldEnd(), "Only one alliance = should end");
        }
    }

    // ===== Cooldown edge cases =====

    @Nested
    @DisplayName("Cooldown edge cases")
    class CooldownEdges {
        @Test
        void exactlyZeroCooldownIsExpired() {
            com.example.tassmud.model.Cooldown cd = new com.example.tassmud.model.Cooldown(
                    CooldownType.SKILL, 1, 0.0);
            assertTrue(cd.isExpired());
        }

        @Test
        void verySmallCooldownTick() {
            com.example.tassmud.model.Cooldown cd = new com.example.tassmud.model.Cooldown(
                    CooldownType.SKILL, 1, 0.001);
            assertFalse(cd.isExpired());
            assertTrue(cd.tick(0.001), "Should expire at exact boundary");
        }

        @Test
        void largeCooldownDoesNotOverflow() {
            com.example.tassmud.model.Cooldown cd = new com.example.tassmud.model.Cooldown(
                    CooldownType.SKILL, 1, 999999.0);
            assertFalse(cd.tick(1.0));
            assertTrue(cd.getRemainingSeconds() > 999990);
        }
    }

    // ===== needsRegen edge cases =====

    @Nested
    @DisplayName("Character regen detection")
    class RegenDetection {
        @Test
        void needsRegenWhenOnlyMpLow() {
            GameCharacter ch = makeChar("Test", 100);
            ch.setMpCur(49);
            assertTrue(ch.needsRegen(), "MP below max should trigger regen");
        }

        @Test
        void needsRegenWhenOnlyMvLow() {
            GameCharacter ch = makeChar("Test", 100);
            ch.setMvCur(79);
            assertTrue(ch.needsRegen());
        }

        @Test
        void fullVitalsNoRegen() {
            GameCharacter ch = makeChar("Test", 100);
            assertFalse(ch.needsRegen());
        }
    }
}
