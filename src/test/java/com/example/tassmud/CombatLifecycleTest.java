package com.example.tassmud;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatState;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.StatBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Combat lifecycle: state transitions, combatant management,
 * initiative order, round management, aggro system, and end conditions.
 */
class CombatLifecycleTest {

    private Combat combat;
    private GameCharacter playerChar;
    private Mobile mob;
    private static final int ROOM_ID = 1001;

    private static final StatBlock PLAYER_STATS = StatBlock.builder()
            .str(14).dex(12).con(10).intel(10).wis(10).cha(10)
            .armor(15).fortitude(12).reflex(11).will(13)
            .build();

    @BeforeEach
    void setUp() {
        combat = new Combat(1L, ROOM_ID);

        playerChar = new GameCharacter("Hero", 25, "A hero",
                100, 100, 50, 50, 80, 80, ROOM_ID, PLAYER_STATS);

        MobileTemplate template = MobileTemplate.builder()
                .id(1).key("goblin").name("a goblin")
                .shortDesc("a goblin").longDesc("A goblin stands here.")
                .level(3).hpMax(30).mpMax(0).mvMax(50)
                .str(10).dex(12).con(8).intel(6).wis(6).cha(4)
                .armor(12).fortitude(10).reflex(12).will(8)
                .baseDamage(4).damageBonus(1).attackBonus(2)
                .build();
        mob = new Mobile(100L, template, ROOM_ID);
    }

    // ===== Initial state =====

    @Nested
    @DisplayName("Initial state")
    class InitialState {
        @Test
        void startsInInitializingState() {
            assertEquals(CombatState.INITIALIZING, combat.getState());
        }

        @Test
        void notActiveBeforeStart() {
            assertFalse(combat.isActive());
        }

        @Test
        void notEndedBeforeStart() {
            assertFalse(combat.hasEnded());
        }

        @Test
        void roomIdSet() {
            assertEquals(ROOM_ID, combat.getRoomId());
        }

        @Test
        void combatIdSet() {
            assertEquals(1L, combat.getCombatId());
        }
    }

    // ===== Combatant management =====

    @Nested
    @DisplayName("Combatant management")
    class CombatantManagement {
        @Test
        void addPlayerCombatant() {
            Combatant c = combat.addPlayerCombatant(playerChar, 42);
            assertNotNull(c);
            assertTrue(c.isPlayer());
            assertEquals("Hero", c.getName());
        }

        @Test
        void addMobileCombatant() {
            Combatant c = combat.addMobileCombatant(mob);
            assertNotNull(c);
            assertTrue(c.isMobile());
            assertEquals("a goblin", c.getName());
        }

        @Test
        void playerAllianceIsZero() {
            Combatant p = combat.addPlayerCombatant(playerChar, 42);
            // Players get PLAYER_ALLIANCE (0)
            Combatant m = combat.addMobileCombatant(mob);
            assertTrue(p.isHostileTo(m), "Player should be hostile to mob");
        }

        @Test
        void containsCharacterAfterAdd() {
            combat.addPlayerCombatant(playerChar, 42);
            assertTrue(combat.containsCharacter(42));
            assertFalse(combat.containsCharacter(999));
        }

        @Test
        void containsMobileAfterAdd() {
            combat.addMobileCombatant(mob);
            assertTrue(combat.containsMobile(100L));
            assertFalse(combat.containsMobile(999L));
        }

        @Test
        void findByCharacterId() {
            combat.addPlayerCombatant(playerChar, 42);
            Combatant found = combat.findByCharacterId(42);
            assertNotNull(found);
            assertEquals("Hero", found.getName());
        }

        @Test
        void findByMobileInstanceId() {
            combat.addMobileCombatant(mob);
            Combatant found = combat.findByMobileInstanceId(100L);
            assertNotNull(found);
            assertEquals("a goblin", found.getName());
        }

        @Test
        void findByNameCaseInsensitive() {
            combat.addPlayerCombatant(playerChar, 42);
            assertNotNull(combat.findByName("hero"));
            assertNotNull(combat.findByName("HERO"));
            assertNotNull(combat.findByName("Her")); // prefix match
        }

        @Test
        void removeCombatantMarksInactive() {
            Combatant c = combat.addPlayerCombatant(playerChar, 42);
            combat.removeCombatant(c);
            assertFalse(c.isActive());
            // Still in combatants map
            assertTrue(combat.containsCombatant(c));
            // But not in active list
            assertFalse(combat.getActiveCombatants().contains(c));
        }

        @Test
        void getPlayersAndMobsSeparately() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            assertEquals(1, combat.getPlayerCombatants().size());
            assertEquals(1, combat.getMobileCombatants().size());
            assertEquals(2, combat.getActiveCombatants().size());
        }

        @Test
        void mobsWithExplicitAllianceAreAllied() {
            MobileTemplate t2 = MobileTemplate.builder()
                    .id(2).key("orc").name("an orc")
                    .shortDesc("an orc").longDesc("An orc stands here.")
                    .level(4).hpMax(40).baseDamage(5)
                    .build();
            Mobile mob2 = new Mobile(101L, t2, ROOM_ID);

            Combatant c1 = combat.addMobileCombatant(mob, 5);
            Combatant c2 = combat.addMobileCombatant(mob2, 5);
            assertTrue(c1.isAlliedWith(c2), "Same alliance = allied");
        }
    }

    // ===== State transitions =====

    @Nested
    @DisplayName("State transitions")
    class StateTransitions {
        @Test
        void startTransitionsToActive() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            assertEquals(CombatState.ACTIVE, combat.getState());
            assertTrue(combat.isActive());
        }

        @Test
        void startOnlyFromInitializing() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.setState(CombatState.ENDED);
            combat.start(); // should be no-op
            assertEquals(CombatState.ENDED, combat.getState(), "start() should be no-op when not INITIALIZING");
        }

        @Test
        void endTransitionsToEnded() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            combat.end();
            assertTrue(combat.hasEnded());
            assertFalse(combat.isActive());
        }

        @Test
        void endTimestampRecorded() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            combat.end();
            assertTrue(combat.getEndedAt() > 0);
        }

        @Test
        void endSetsAllCombatantsInactive() {
            Combatant p = combat.addPlayerCombatant(playerChar, 42);
            Combatant m = combat.addMobileCombatant(mob);
            combat.start();
            combat.end();
            assertFalse(p.isActive());
            assertFalse(m.isActive());
        }
    }

    // ===== shouldEnd (pure query) =====

    @Nested
    @DisplayName("Combat end conditions")
    class EndConditions {
        @Test
        void shouldNotEndWithBothSides() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            assertFalse(combat.shouldEnd());
        }

        @Test
        void shouldEndWhenOnlyOneAllianceAlive() {
            Combatant p = combat.addPlayerCombatant(playerChar, 42);
            Combatant m = combat.addMobileCombatant(mob);
            combat.start();
            // Kill mob: HP = 0
            m.damage(999);
            assertTrue(combat.shouldEnd(), "Only player alliance alive");
        }

        @Test
        void shouldEndIsPureQuery() {
            Combatant p = combat.addPlayerCombatant(playerChar, 42);
            Combatant m = combat.addMobileCombatant(mob);
            combat.start();
            m.damage(999);
            combat.shouldEnd(); // query-only
            assertTrue(combat.isActive(), "shouldEnd() does not change state");
        }
    }

    // ===== Round management =====

    @Nested
    @DisplayName("Round management")
    class RoundManagement {
        @Test
        void startBeginsRoundOne() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            // After start(), currentRound should be 1 (start calls startNewRound which increments)
            assertTrue(combat.getCurrentRound() >= 1);
        }

        @Test
        void initiativeOrderNotEmpty() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            List<Combatant> order = combat.getInitiativeOrder();
            assertNotNull(order);
            assertEquals(2, order.size());
        }

        @Test
        void initiativeOrderIsCopy() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            List<Combatant> order = combat.getInitiativeOrder();
            assertThrows(UnsupportedOperationException.class, () -> order.add(null),
                    "Should be unmodifiable copy");
        }

        @Test
        void advanceTurnCyclesThroughAll() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();

            Combatant first = combat.getCurrentTurnCombatant();
            assertNotNull(first);

            boolean more = combat.advanceTurn();
            if (more) {
                Combatant second = combat.getCurrentTurnCombatant();
                assertNotNull(second);
                assertNotSame(first, second);
            }
        }

        @Test
        void roundCompletesAfterAllTurns() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();

            // Advance through all turns
            while (combat.advanceTurn()) {
                // consume turns
            }
            assertTrue(combat.isRoundComplete());
        }
    }

    // ===== Targeting =====

    @Nested
    @DisplayName("Targeting")
    class Targeting {
        @Test
        void validTargetsExcludeAllies() {
            Combatant p = combat.addPlayerCombatant(playerChar, 42);
            Combatant m = combat.addMobileCombatant(mob);
            combat.start();

            List<Combatant> targets = combat.getValidTargets(p);
            assertTrue(targets.contains(m), "Mob should be a valid target for player");
            assertFalse(targets.contains(p), "Player should not target self");
        }

        @Test
        void validTargetsExcludeDead() {
            Combatant p = combat.addPlayerCombatant(playerChar, 42);
            Combatant m = combat.addMobileCombatant(mob);
            combat.start();
            m.damage(999);

            List<Combatant> targets = combat.getValidTargets(p);
            assertFalse(targets.contains(m), "Dead mob should not be a valid target");
        }

        @Test
        void randomTargetFromValidTargets() {
            combat.addPlayerCombatant(playerChar, 42);
            Combatant m = combat.addMobileCombatant(mob);
            combat.start();

            // With only one enemy, random should always return it
            Combatant p = combat.findByCharacterId(42);
            Combatant target = combat.getRandomTarget(p);
            assertSame(m, target);
        }
    }

    // ===== Aggro system =====

    @Nested
    @DisplayName("Aggro system")
    class AggroSystem {
        @Test
        void initialAggroIsZero() {
            assertEquals(0L, combat.getAggro(42));
        }

        @Test
        void addAggroAccumulates() {
            combat.addAggro(42, 50);
            combat.addAggro(42, 30);
            assertEquals(80L, combat.getAggro(42));
        }

        @Test
        void attackAggroFormula() {
            // formula: 10 + max(0, damage)
            combat.addAttackAggro(42, 25);
            assertEquals(35L, combat.getAggro(42), "10 + 25 = 35");
        }

        @Test
        void damageSpellAggroFormula() {
            // formula: 10 * spellLevel + damage
            combat.addDamageSpellAggro(42, 3, 20);
            assertEquals(50L, combat.getAggro(42), "10*3 + 20 = 50");
        }

        @Test
        void utilitySpellAggroFormula() {
            // formula: 100 * spellLevel
            combat.addUtilitySpellAggro(42, 2);
            assertEquals(200L, combat.getAggro(42), "100*2 = 200");
        }

        @Test
        void resetAggroForOneCharacter() {
            combat.addAggro(42, 100);
            combat.addAggro(99, 200);
            combat.resetAggro(42);
            assertEquals(0L, combat.getAggro(42));
            assertEquals(200L, combat.getAggro(99), "Other character unaffected");
        }

        @Test
        void resetAllAggro() {
            combat.addAggro(42, 100);
            combat.addAggro(99, 200);
            combat.resetAllAggro();
            assertEquals(0L, combat.getAggro(42));
            assertEquals(0L, combat.getAggro(99));
        }

        @Test
        void maxAggro() {
            combat.addAggro(42, 100);
            combat.addAggro(99, 200);
            assertEquals(200L, combat.getMaxAggro());
        }

        @Test
        void setAggroOverrides() {
            combat.addAggro(42, 50);
            combat.setAggro(42, 999);
            assertEquals(999L, combat.getAggro(42));
        }

        @Test
        void highestAggroPlayerReturnsCorrect() {
            Combatant p1 = combat.addPlayerCombatant(playerChar, 42);

            GameCharacter player2 = new GameCharacter("Mage", 25, "A mage",
                    80, 80, 80, 80, 60, 60, ROOM_ID, PLAYER_STATS);
            Combatant p2 = combat.addPlayerCombatant(player2, 99);

            combat.addMobileCombatant(mob);
            combat.start();

            combat.addAggro(42, 50);
            combat.addAggro(99, 100);

            Combatant highest = combat.getHighestAggroPlayer();
            assertNotNull(highest);
            assertEquals(99, highest.getCharacterId());
        }
    }

    // ===== Combat log =====

    @Nested
    @DisplayName("Combat log")
    class CombatLog {
        @Test
        void logEventAddsToLog() {
            combat.logEvent("Test event");
            assertFalse(combat.getCombatLog().isEmpty());
        }

        @Test
        void combatLogIsUnmodifiable() {
            combat.logEvent("test");
            assertThrows(UnsupportedOperationException.class,
                    () -> combat.getCombatLog().add("illegal"));
        }

        @Test
        void recentLogReturnsLines() {
            combat.logEvent("event 1");
            combat.logEvent("event 2");
            combat.logEvent("event 3");
            String recent = combat.getRecentLog(2);
            // Should contain last 2 entries
            assertTrue(recent.contains("event 2") || recent.contains("event 3"));
        }
    }

    // ===== Duration tracking =====

    @Nested
    @DisplayName("Duration tracking")
    class DurationTracking {
        @Test
        void durationAfterStart() {
            combat.addPlayerCombatant(playerChar, 42);
            combat.addMobileCombatant(mob);
            combat.start();
            // Duration should be very small (just started)
            assertTrue(combat.getDurationMs() >= 0);
        }

        @Test
        void startedAtRecorded() {
            assertTrue(combat.getStartedAt() > 0);
        }
    }
}
