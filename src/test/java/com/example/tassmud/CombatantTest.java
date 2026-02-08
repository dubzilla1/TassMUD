package com.example.tassmud;

import com.example.tassmud.combat.Combatant;
import com.example.tassmud.combat.Combatant.StatusFlag;
import com.example.tassmud.combat.CombatCommand;
import com.example.tassmud.combat.CombatResult;
import com.example.tassmud.combat.Combat;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.StatBlock;
import com.example.tassmud.model.ArmorCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Combatant: status flags, alliance logic, auto-flee,
 * round state management, damage/heal, and armor damage tracking.
 */
class CombatantTest {

    private GameCharacter player;
    private Combatant combatant;
    private static final StatBlock STATS = StatBlock.builder()
            .str(14).dex(12).con(10).intel(10).wis(10).cha(10)
            .armor(15).fortitude(12).reflex(11).will(13)
            .build();

    /** Minimal CombatCommand stub for queue tests. */
    private static CombatCommand stubCommand(String name) {
        return new CombatCommand() {
            public String getName() { return name; }
            public String getDisplayName() { return name; }
            public long getCooldownMs() { return 0; }
            public boolean canUse(Combatant user, Combat combat) { return true; }
            public CombatResult execute(Combatant user, Combatant target, Combat combat) { return null; }
            public long getCooldownEndTime(Combatant user) { return 0; }
            public boolean requiresTarget() { return false; }
        };
    }

    @BeforeEach
    void setUp() {
        player = new GameCharacter("Hero", 25, "A hero",
                100, 100, 50, 50, 80, 80, 1001, STATS);
        combatant = new Combatant(1L, player, 42, 1); // combatantId=1, characterId=42, alliance=1
    }

    // ===== Identity =====

    @Nested
    @DisplayName("Identity")
    class Identity {
        @Test
        void playerCombatantIsPlayer() {
            assertTrue(combatant.isPlayer());
            assertFalse(combatant.isMobile());
        }

        @Test
        void getName() {
            assertEquals("Hero", combatant.getName());
        }

        @Test
        void getAsCharacterReturnsUnderlyingChar() {
            assertSame(player, combatant.getAsCharacter());
        }
    }

    // ===== Alliance =====

    @Nested
    @DisplayName("Alliance logic")
    class AllianceLogic {
        @Test
        void samAllianceIsAllied() {
            Combatant ally = new Combatant(2L, player, 99, 1);
            assertTrue(combatant.isAlliedWith(ally));
            assertFalse(combatant.isHostileTo(ally));
        }

        @Test
        void differentAllianceIsHostile() {
            GameCharacter enemy = new GameCharacter("Foe", 25, "An enemy",
                    80, 80, 30, 30, 60, 60, 1001, STATS);
            Combatant foe = new Combatant(2L, enemy, 99, 2);
            assertTrue(combatant.isHostileTo(foe));
            assertFalse(combatant.isAlliedWith(foe));
        }
    }

    // ===== Status flags =====

    @Nested
    @DisplayName("Status flags")
    class StatusFlags {
        @Test
        void addAndCheckFlag() {
            assertFalse(combatant.hasStatusFlag(StatusFlag.STUNNED));
            combatant.addStatusFlag(StatusFlag.STUNNED);
            assertTrue(combatant.hasStatusFlag(StatusFlag.STUNNED));
            assertTrue(combatant.isStunned());
        }

        @Test
        void removeFlag() {
            combatant.addStatusFlag(StatusFlag.BLINDED);
            combatant.removeStatusFlag(StatusFlag.BLINDED);
            assertFalse(combatant.hasStatusFlag(StatusFlag.BLINDED));
        }

        @Test
        void clearAllFlags() {
            combatant.addStatusFlag(StatusFlag.STUNNED);
            combatant.addStatusFlag(StatusFlag.SLOWED);
            combatant.addStatusFlag(StatusFlag.PRONE);
            combatant.clearStatusFlags();
            assertFalse(combatant.isStunned());
            assertFalse(combatant.isSlowed());
            assertFalse(combatant.isProne());
        }

        @Test
        void consumeInterruptedReturnsAndClears() {
            combatant.addStatusFlag(StatusFlag.INTERRUPTED);
            assertTrue(combatant.consumeInterrupted(), "Should return true when present");
            assertFalse(combatant.isInterrupted(), "Should be cleared after consume");
            assertFalse(combatant.consumeInterrupted(), "Second consume returns false");
        }

        @Test
        void consumeStunnedReturnsAndClears() {
            combatant.addStatusFlag(StatusFlag.STUNNED);
            assertTrue(combatant.consumeStunned());
            assertFalse(combatant.isStunned());
        }

        @Test
        void consumeSlowedReturnsAndClears() {
            combatant.addStatusFlag(StatusFlag.SLOWED);
            assertTrue(combatant.consumeSlowed());
            assertFalse(combatant.isSlowed());
        }

        @Test
        void proneSetAndStandUp() {
            combatant.setProne();
            assertTrue(combatant.isProne());
            assertTrue(combatant.standUp(), "standUp returns true when was prone");
            assertFalse(combatant.isProne());
            assertFalse(combatant.standUp(), "standUp returns false when not prone");
        }
    }

    // ===== Advantage / Disadvantage =====

    @Nested
    @DisplayName("Advantage and Disadvantage")
    class AdvantageDisadvantage {
        @Test
        void advantageGivesPlusOne() {
            combatant.addStatusFlag(StatusFlag.ADVANTAGE);
            assertEquals(1, combatant.getAttackLevelModifier());
        }

        @Test
        void disadvantageGivesMinusOne() {
            combatant.addStatusFlag(StatusFlag.DISADVANTAGE);
            assertEquals(-1, combatant.getAttackLevelModifier());
        }

        @Test
        void advantageAndDisadvantageCancelOut() {
            combatant.addStatusFlag(StatusFlag.ADVANTAGE);
            combatant.addStatusFlag(StatusFlag.DISADVANTAGE);
            assertEquals(0, combatant.getAttackLevelModifier());
        }

        @Test
        void neitherGivesZero() {
            assertEquals(0, combatant.getAttackLevelModifier());
        }

        @Test
        void consumeAdvantageClearsIt() {
            combatant.addStatusFlag(StatusFlag.ADVANTAGE);
            assertTrue(combatant.consumeAdvantage());
            assertFalse(combatant.hasAdvantage());
        }
    }

    // ===== Autoflee =====

    @Nested
    @DisplayName("Auto-flee threshold")
    class Autoflee {
        @Test
        void shouldAutofleeWhenBelowThreshold() {
            player.setHpCur(10); // 10% of 100
            assertTrue(combatant.shouldAutoflee(20), "10% < 20% threshold");
        }

        @Test
        void shouldNotAutofleeWhenAboveThreshold() {
            player.setHpCur(50); // 50% of 100
            assertFalse(combatant.shouldAutoflee(20));
        }

        @Test
        void autofleeZeroDisabled() {
            player.setHpCur(1);
            assertFalse(combatant.shouldAutoflee(0), "Autoflee=0 means disabled");
        }

        @Test
        void autofleeNegativeDisabled() {
            player.setHpCur(1);
            assertFalse(combatant.shouldAutoflee(-10));
        }

        @Test
        void autofleeAtExactThresholdDoesNotFlee() {
            player.setHpCur(20); // exactly 20%
            assertFalse(combatant.shouldAutoflee(20), "20% is not < 20%");
        }
    }

    // ===== Round state management =====

    @Nested
    @DisplayName("Round management")
    class RoundManagement {
        @Test
        void resetForNewRoundClearsActedFlag() {
            combatant.setHasActedThisRound(true);
            combatant.resetForNewRound();
            assertFalse(combatant.hasActedThisRound());
        }

        @Test
        void resetForNewRoundClearsAdvantageAndDisadvantage() {
            combatant.addStatusFlag(StatusFlag.ADVANTAGE);
            combatant.addStatusFlag(StatusFlag.DISADVANTAGE);
            combatant.resetForNewRound();
            assertFalse(combatant.hasAdvantage());
            assertFalse(combatant.hasDisadvantage());
        }

        @Test
        void resetForNewRoundGrantsBaseAttacks() {
            combatant.resetForNewRound();
            assertTrue(combatant.hasAttacksRemaining(), "Should have at least 1 attack");
            assertEquals(1, combatant.getAttacksRemaining(), "Base attacks = 1");
        }

        @Test
        void resetForNewRoundAddsRiposteBonus() {
            combatant.addRiposteAttack();
            combatant.addRiposteAttack();
            combatant.resetForNewRound();
            assertEquals(3, combatant.getAttacksRemaining(), "1 base + 2 riposte = 3");
            assertEquals(0, combatant.getPendingRiposteAttacks(), "Pending ripostes consumed");
        }

        @Test
        void resetForNewRoundSlowedLimitsToOne() {
            combatant.addRiposteAttack();
            combatant.addRiposteAttack();
            combatant.addStatusFlag(StatusFlag.SLOWED);
            combatant.resetForNewRound();
            assertEquals(1, combatant.getAttacksRemaining(), "SLOWED limits to 1 attack");
            assertFalse(combatant.isSlowed(), "Slowed consumed by reset");
        }

        @Test
        void decrementAttacksRemaining() {
            combatant.setAttacksRemaining(3);
            combatant.decrementAttacksRemaining();
            assertEquals(2, combatant.getAttacksRemaining());
            combatant.decrementAttacksRemaining();
            combatant.decrementAttacksRemaining();
            assertEquals(0, combatant.getAttacksRemaining());
            combatant.decrementAttacksRemaining(); // should not go negative
            assertEquals(0, combatant.getAttacksRemaining());
        }
    }

    // ===== HP access / damage / heal =====

    @Nested
    @DisplayName("HP operations")
    class HpOperations {
        @Test
        void damageReducesHp() {
            combatant.damage(30);
            assertEquals(70, combatant.getHpCurrent());
        }

        @Test
        void healRestoresHp() {
            combatant.damage(60);
            combatant.heal(25);
            assertEquals(65, combatant.getHpCurrent());
        }

        @Test
        void hpMaxCorrect() {
            assertEquals(100, combatant.getHpMax());
        }

        @Test
        void isAliveWhenHpPositive() {
            assertTrue(combatant.isAlive());
        }

        @Test
        void isDeadWhenHpZero() {
            combatant.damage(100);
            assertFalse(combatant.isAlive());
        }

        @Test
        void canActWhenActiveAndAlive() {
            combatant.setActive(true);
            assertTrue(combatant.canAct());
        }

        @Test
        void cannotActWhenDead() {
            combatant.setActive(true);
            combatant.damage(100);
            assertFalse(combatant.canAct());
        }

        @Test
        void cannotActWhenInactive() {
            combatant.setActive(false);
            assertFalse(combatant.canAct());
        }
    }

    // ===== Armor damage counter tracking =====

    @Nested
    @DisplayName("Armor damage counters")
    class ArmorDamageCounters {
        @Test
        void recordAndRetrieve() {
            combatant.recordArmorDamage(ArmorCategory.PLATE, 15);
            combatant.recordArmorDamage(ArmorCategory.PLATE, 10);
            assertEquals(25, combatant.getArmorDamageCounter(ArmorCategory.PLATE));
        }

        @Test
        void differentCategoriesTrackedSeparately() {
            combatant.recordArmorDamage(ArmorCategory.LEATHER, 10);
            combatant.recordArmorDamage(ArmorCategory.MAIL, 20);
            assertEquals(10, combatant.getArmorDamageCounter(ArmorCategory.LEATHER));
            assertEquals(20, combatant.getArmorDamageCounter(ArmorCategory.MAIL));
        }

        @Test
        void unrecordedCategoryReturnsZero() {
            assertEquals(0, combatant.getArmorDamageCounter(ArmorCategory.CLOTH));
        }

        @Test
        void resetClearsAll() {
            combatant.recordArmorDamage(ArmorCategory.PLATE, 50);
            combatant.resetArmorDamageCounters();
            assertEquals(0, combatant.getArmorDamageCounter(ArmorCategory.PLATE));
        }

        @Test
        void nullCategoryIgnored() {
            combatant.recordArmorDamage(null, 10); // should not throw
            assertEquals(0, combatant.getArmorDamageCounter(ArmorCategory.PLATE));
        }

        @Test
        void zeroDamageIgnored() {
            combatant.recordArmorDamage(ArmorCategory.PLATE, 0);
            assertEquals(0, combatant.getArmorDamageCounter(ArmorCategory.PLATE));
        }
    }

    // ===== Command queue =====

    @Nested
    @DisplayName("Command queue")
    class CommandQueueTests {
        @Test
        void queueAndPoll() {
            CombatCommand kick = stubCommand("kick");
            CombatCommand flee = stubCommand("flee");
            combatant.queueCommand(kick);
            combatant.queueCommand(flee);
            assertSame(kick, combatant.pollNextCommand());
            assertSame(flee, combatant.pollNextCommand());
            assertNull(combatant.pollNextCommand(), "Empty queue returns null");
        }

        @Test
        void peekDoesNotRemove() {
            CombatCommand bash = stubCommand("bash");
            combatant.queueCommand(bash);
            assertSame(bash, combatant.peekNextCommand());
            assertSame(bash, combatant.peekNextCommand(), "peek does not consume");
        }

        @Test
        void clearCommandQueue() {
            combatant.queueCommand(stubCommand("a"));
            combatant.queueCommand(stubCommand("b"));
            combatant.clearCommandQueue();
            assertEquals(0, combatant.getQueuedCommandCount());
            assertNull(combatant.pollNextCommand());
        }
    }
}
