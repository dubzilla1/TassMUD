package com.example.tassmud;

import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.model.Stat;
import com.example.tassmud.model.StatBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GameCharacter modifier system: ADD/MULTIPLY/OVERRIDE ops,
 * expiry, cache invalidation, stacking, and convenience getters.
 */
class ModifierSystemTest {

    private GameCharacter ch;
    private static final StatBlock BASE_STATS = StatBlock.builder()
            .str(14).dex(12).con(10).intel(16).wis(8).cha(10)
            .armor(15).fortitude(12).reflex(11).will(13)
            .build();

    @BeforeEach
    void setUp() {
        ch = new GameCharacter("Tester", 25, "A test character",
                100, 100, 50, 50, 80, 80, 1001, BASE_STATS);
    }

    // ===== ADD modifier tests =====

    @Nested
    @DisplayName("ADD modifiers")
    class AddModifiers {

        @Test
        void singleAddModifier() {
            ch.addModifier(new Modifier("buff", Stat.STRENGTH, Modifier.Op.ADD, 4.0, 0, 0));
            assertEquals(18.0, ch.getStat(Stat.STRENGTH), "14 base + 4 ADD = 18");
        }

        @Test
        void multipleAddModifiersStack() {
            ch.addModifier(new Modifier("buff1", Stat.STRENGTH, Modifier.Op.ADD, 2.0, 0, 0));
            ch.addModifier(new Modifier("buff2", Stat.STRENGTH, Modifier.Op.ADD, 3.0, 0, 0));
            assertEquals(19.0, ch.getStat(Stat.STRENGTH), "14 + 2 + 3 = 19");
        }

        @Test
        void negativeAddActsAsDebuff() {
            ch.addModifier(new Modifier("curse", Stat.DEXTERITY, Modifier.Op.ADD, -5.0, 0, 0));
            assertEquals(7.0, ch.getStat(Stat.DEXTERITY), "12 - 5 = 7");
        }

        @Test
        void addDoesNotAffectOtherStats() {
            ch.addModifier(new Modifier("buff", Stat.STRENGTH, Modifier.Op.ADD, 10.0, 0, 0));
            assertEquals(12.0, ch.getStat(Stat.DEXTERITY), "DEX should remain unaffected");
        }
    }

    // ===== MULTIPLY modifier tests =====

    @Nested
    @DisplayName("MULTIPLY modifiers")
    class MultiplyModifiers {

        @Test
        void singleMultiplyModifier() {
            ch.addModifier(new Modifier("haste", Stat.DEXTERITY, Modifier.Op.MULTIPLY, 1.5, 0, 0));
            assertEquals(18.0, ch.getStat(Stat.DEXTERITY), "12 * 1.5 = 18");
        }

        @Test
        void multipleMultipliesCompound() {
            ch.addModifier(new Modifier("buff1", Stat.STRENGTH, Modifier.Op.MULTIPLY, 2.0, 0, 0));
            ch.addModifier(new Modifier("buff2", Stat.STRENGTH, Modifier.Op.MULTIPLY, 1.5, 0, 0));
            assertEquals(42.0, ch.getStat(Stat.STRENGTH), "14 * 2.0 * 1.5 = 42");
        }

        @Test
        void multiplyByZeroZerosOut() {
            ch.addModifier(new Modifier("nullify", Stat.INTELLIGENCE, Modifier.Op.MULTIPLY, 0.0, 0, 0));
            assertEquals(0.0, ch.getStat(Stat.INTELLIGENCE), "16 * 0 = 0");
        }
    }

    // ===== Combined ADD + MULTIPLY =====

    @Nested
    @DisplayName("Combined ADD + MULTIPLY")
    class CombinedModifiers {

        @Test
        void addBeforeMultiply() {
            // Formula: (base + addSum) * mulProduct
            ch.addModifier(new Modifier("flat", Stat.STRENGTH, Modifier.Op.ADD, 6.0, 0, 0));
            ch.addModifier(new Modifier("pct", Stat.STRENGTH, Modifier.Op.MULTIPLY, 2.0, 0, 0));
            assertEquals(40.0, ch.getStat(Stat.STRENGTH), "(14 + 6) * 2.0 = 40");
        }

        @Test
        void multipleAddsAndMultiplies() {
            ch.addModifier(new Modifier("a1", Stat.CONSTITUTION, Modifier.Op.ADD, 5.0, 0, 0));
            ch.addModifier(new Modifier("a2", Stat.CONSTITUTION, Modifier.Op.ADD, 5.0, 0, 0));
            ch.addModifier(new Modifier("m1", Stat.CONSTITUTION, Modifier.Op.MULTIPLY, 1.5, 0, 0));
            ch.addModifier(new Modifier("m2", Stat.CONSTITUTION, Modifier.Op.MULTIPLY, 2.0, 0, 0));
            assertEquals(60.0, ch.getStat(Stat.CONSTITUTION), "(10 + 5 + 5) * 1.5 * 2.0 = 60");
        }
    }

    // ===== OVERRIDE modifier tests =====

    @Nested
    @DisplayName("OVERRIDE modifiers")
    class OverrideModifiers {

        @Test
        void overrideTakesPrecedence() {
            ch.addModifier(new Modifier("flat", Stat.STRENGTH, Modifier.Op.ADD, 100.0, 0, 0));
            ch.addModifier(new Modifier("override", Stat.STRENGTH, Modifier.Op.OVERRIDE, 50.0, 0, 10));
            assertEquals(50.0, ch.getStat(Stat.STRENGTH), "OVERRIDE ignores base and ADDs");
        }

        @Test
        void highestPriorityOverrideWins() {
            ch.addModifier(new Modifier("low", Stat.ARMOR, Modifier.Op.OVERRIDE, 99.0, 0, 1));
            ch.addModifier(new Modifier("high", Stat.ARMOR, Modifier.Op.OVERRIDE, 50.0, 0, 10));
            assertEquals(50.0, ch.getStat(Stat.ARMOR), "Priority 10 override wins over priority 1");
        }

        @Test
        void overrideIgnoresMultiplies() {
            ch.addModifier(new Modifier("pct", Stat.DEXTERITY, Modifier.Op.MULTIPLY, 10.0, 0, 0));
            ch.addModifier(new Modifier("lock", Stat.DEXTERITY, Modifier.Op.OVERRIDE, 1.0, 0, 5));
            assertEquals(1.0, ch.getStat(Stat.DEXTERITY), "OVERRIDE ignores MULTIPLY too");
        }
    }

    // ===== Remove modifier tests =====

    @Nested
    @DisplayName("Remove modifiers")
    class RemoveModifiers {

        @Test
        void removeRestoresBase() {
            Modifier m = new Modifier("buff", Stat.STRENGTH, Modifier.Op.ADD, 10.0, 0, 0);
            UUID id = ch.addModifier(m);
            assertEquals(24.0, ch.getStat(Stat.STRENGTH));

            assertTrue(ch.removeModifier(id), "Should report removal");
            assertEquals(14.0, ch.getStat(Stat.STRENGTH), "Back to base after removal");
        }

        @Test
        void removeNonexistentReturnsFalse() {
            assertFalse(ch.removeModifier(UUID.randomUUID()));
        }

        @Test
        void removeOneLeaveOthers() {
            Modifier m1 = new Modifier("a", Stat.CHARISMA, Modifier.Op.ADD, 5.0, 0, 0);
            Modifier m2 = new Modifier("b", Stat.CHARISMA, Modifier.Op.ADD, 3.0, 0, 0);
            ch.addModifier(m1);
            ch.addModifier(m2);
            assertEquals(18.0, ch.getStat(Stat.CHARISMA), "10 + 5 + 3 = 18");

            ch.removeModifier(m1.id());
            assertEquals(13.0, ch.getStat(Stat.CHARISMA), "10 + 3 = 13 after removing first");
        }
    }

    // ===== Expiry tests =====

    @Nested
    @DisplayName("Modifier expiry")
    class ModifierExpiry {

        @Test
        void nonExpiringModifierNeverExpires() {
            Modifier m = new Modifier("perm", Stat.STRENGTH, Modifier.Op.ADD, 5.0, 0, 0);
            assertFalse(m.isExpired(), "expiresAtMillis=0 means permanent");
        }

        @Test
        void pastExpiryIsExpired() {
            long pastMs = Instant.now().toEpochMilli() - 1000;
            Modifier m = new Modifier("old", Stat.STRENGTH, Modifier.Op.ADD, 5.0, pastMs, 0);
            assertTrue(m.isExpired());
        }

        @Test
        void futureExpiryIsNotExpired() {
            long futureMs = Instant.now().toEpochMilli() + 60_000;
            Modifier m = new Modifier("fresh", Stat.STRENGTH, Modifier.Op.ADD, 5.0, futureMs, 0);
            assertFalse(m.isExpired());
        }

        @Test
        void expiredModifierCleanedUpOnGetStat() {
            long pastMs = Instant.now().toEpochMilli() - 1000;
            ch.addModifier(new Modifier("expired", Stat.WISDOM, Modifier.Op.ADD, 100.0, pastMs, 0));
            // getStat should strip expired modifiers and return base
            assertEquals(8.0, ch.getStat(Stat.WISDOM), "Expired modifier should be cleaned up");
        }

        @Test
        void mixedExpiredAndActiveModifiers() {
            long pastMs = Instant.now().toEpochMilli() - 1000;
            long futureMs = Instant.now().toEpochMilli() + 60_000;

            ch.addModifier(new Modifier("expired", Stat.WISDOM, Modifier.Op.ADD, 100.0, pastMs, 0));
            ch.addModifier(new Modifier("active", Stat.WISDOM, Modifier.Op.ADD, 2.0, futureMs, 0));

            assertEquals(10.0, ch.getStat(Stat.WISDOM), "8 base + 2 active = 10 (expired stripped)");
        }
    }

    // ===== Cache invalidation tests =====

    @Nested
    @DisplayName("Cache invalidation")
    class CacheInvalidation {

        @Test
        void setterInvalidatesCache() {
            // Prime the cache
            assertEquals(14.0, ch.getStat(Stat.STRENGTH));

            // Directly set the base stat
            ch.setStr(20);
            assertEquals(20.0, ch.getStat(Stat.STRENGTH), "Cache should be invalidated by setter");
        }

        @Test
        void modifyAfterCachePrime() {
            // Prime the cache
            ch.getStat(Stat.DEXTERITY);

            // Add modifier after cache was primed
            ch.addModifier(new Modifier("late", Stat.DEXTERITY, Modifier.Op.ADD, 3.0, 0, 0));
            assertEquals(15.0, ch.getStat(Stat.DEXTERITY), "New modifier should invalidate cache");
        }

        @Test
        void removeAfterCachePrime() {
            Modifier m = new Modifier("temp", Stat.INTELLIGENCE, Modifier.Op.ADD, 4.0, 0, 0);
            ch.addModifier(m);
            assertEquals(20.0, ch.getStat(Stat.INTELLIGENCE)); // prime

            ch.removeModifier(m.id());
            assertEquals(16.0, ch.getStat(Stat.INTELLIGENCE), "Removal should invalidate cache");
        }
    }

    // ===== Convenience getter tests =====

    @Nested
    @DisplayName("Convenience getters delegate through modifier system")
    class ConvenienceGetters {

        @Test
        void attackHitBonusDefaults() {
            assertEquals(0, ch.getAttackHitBonus(), "Default should be 0");
        }

        @Test
        void attackHitBonusWithModifier() {
            ch.addModifier(new Modifier("enchant", Stat.ATTACK_HIT_BONUS, Modifier.Op.ADD, 3.0, 0, 0));
            assertEquals(3, ch.getAttackHitBonus());
        }

        @Test
        void criticalThresholdBonusWithModifier() {
            ch.addModifier(new Modifier("crit1", Stat.CRITICAL_THRESHOLD_BONUS, Modifier.Op.ADD, -1.0, 0, 0));
            ch.addModifier(new Modifier("crit2", Stat.CRITICAL_THRESHOLD_BONUS, Modifier.Op.ADD, -1.0, 0, 0));
            assertEquals(-2, ch.getCriticalThresholdBonus(), "Two -1 modifiers = -2 threshold shift");
        }

        @Test
        void meleeDamageReductionIncludesFortitude() {
            // Formula: getStat(MELEE_DAMAGE_REDUCTION) + getFortitude() - 10
            // Fort = 12, base MDR = 0 → 0 + 12 - 10 = 2
            assertEquals(2, ch.getMeleeDamageReduction());

            ch.addModifier(new Modifier("shield", Stat.MELEE_DAMAGE_REDUCTION, Modifier.Op.ADD, 5.0, 0, 0));
            assertEquals(7, ch.getMeleeDamageReduction(), "5 + 12 - 10 = 7");
        }
    }

    // ===== getAllModifiers / toStatBlock =====

    @Nested
    @DisplayName("Snapshot utilities")
    class Snapshots {

        @Test
        void getAllModifiersReturnsAll() {
            ch.addModifier(new Modifier("a", Stat.STRENGTH, Modifier.Op.ADD, 1.0, 0, 0));
            ch.addModifier(new Modifier("b", Stat.DEXTERITY, Modifier.Op.ADD, 2.0, 0, 0));
            ch.addModifier(new Modifier("c", Stat.ARMOR, Modifier.Op.MULTIPLY, 1.5, 0, 0));
            assertEquals(3, ch.getAllModifiers().size());
        }

        @Test
        void toStatBlockReflectsBaseNotModified() {
            ch.addModifier(new Modifier("buff", Stat.STRENGTH, Modifier.Op.ADD, 100.0, 0, 0));
            StatBlock snap = ch.toStatBlock();
            assertEquals(14, snap.str(), "toStatBlock returns base field, not modified value");
        }
    }

    // ===== Vital clamping tests =====

    @Nested
    @DisplayName("Vital clamping (HP/MP/MV)")
    class VitalClamping {

        @Test
        void hpCurClampedToMax() {
            ch.setHpCur(9999);
            assertEquals(100, ch.getHpCur(), "HP should be clamped to max");
        }

        @Test
        void hpCurClampedToZero() {
            ch.setHpCur(-50);
            assertEquals(0, ch.getHpCur(), "HP should not go below 0");
        }

        @Test
        void mpCurClampedToMax() {
            ch.setMpCur(9999);
            assertEquals(50, ch.getMpCur());
        }

        @Test
        void mvCurClampedToZero() {
            ch.setMvCur(-1);
            assertEquals(0, ch.getMvCur());
        }

        @Test
        void hpMaxClampedToOne() {
            ch.setHpMax(0);
            assertEquals(1, ch.getHpMax(), "HP max must be at least 1");
        }

        @Test
        void healClampsAtMax() {
            ch.setHpCur(90);
            ch.heal(50);
            assertEquals(100, ch.getHpCur(), "heal should cap at hpMax");
        }

        @Test
        void healZeroOrNegativeDoesNothing() {
            ch.setHpCur(50);
            ch.heal(0);
            assertEquals(50, ch.getHpCur());
            ch.heal(-10);
            assertEquals(50, ch.getHpCur());
        }
    }

    // ===== Stance and needsRegen =====

    @Nested
    @DisplayName("Stance and regeneration")
    class StanceAndRegen {

        @Test
        void defaultStanceIsStanding() {
            assertEquals(com.example.tassmud.model.Stance.STANDING, ch.getStance());
        }

        @Test
        void needsRegenWhenBelowMax() {
            ch.setHpCur(99);
            assertTrue(ch.needsRegen());
        }

        @Test
        void doesNotNeedRegenWhenFull() {
            // ch starts at 100/100, 50/50, 80/80
            assertFalse(ch.needsRegen());
        }
    }

    // ===== setStat dispatcher =====

    @Nested
    @DisplayName("setStat dispatches to correct setter")
    class SetStatDispatch {

        @Test
        void setStatStrength() {
            ch.setStat(Stat.STRENGTH, 20);
            assertEquals(20, ch.getStr());
            assertEquals(20.0, ch.getStat(Stat.STRENGTH));
        }

        @Test
        void setStatHpCurrent() {
            ch.setStat(Stat.HP_CURRENT, 42);
            assertEquals(42, ch.getHpCur());
        }

        @Test
        void addStatModifiesExisting() {
            ch.addStat(Stat.DEXTERITY, 3);
            assertEquals(15, ch.getDex(), "12 + 3 = 15");
        }

        @Test
        void addStatZeroDoesNothing() {
            ch.addStat(Stat.WISDOM, 0);
            assertEquals(8, ch.getWis());
        }

        @Test
        void addStatNullDoesNothing() {
            ch.addStat(null, 5);
            // should not throw
        }
    }
}
