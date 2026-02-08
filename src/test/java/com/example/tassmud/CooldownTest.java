package com.example.tassmud;

import com.example.tassmud.model.Cooldown;
import com.example.tassmud.model.CooldownType;
import com.example.tassmud.util.CooldownManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cooldown model and CooldownManager lifecycle:
 * tick decrement, expiry, key generation, player/mob cooldowns.
 */
class CooldownTest {

    // ===== Cooldown model tests =====

    @Nested
    @DisplayName("Cooldown model")
    class CooldownModel {

        @Test
        void initialRemainingSeconds() {
            Cooldown cd = new Cooldown(CooldownType.SKILL, 100, 5.0);
            assertEquals(5.0, cd.getRemainingSeconds(), 0.001);
            assertFalse(cd.isExpired());
        }

        @Test
        void negativeInitialClampedToZero() {
            Cooldown cd = new Cooldown(CooldownType.SKILL, 1, -3.0);
            assertEquals(0.0, cd.getRemainingSeconds());
            assertTrue(cd.isExpired());
        }

        @Test
        void tickDecrements() {
            Cooldown cd = new Cooldown(CooldownType.SKILL, 1, 3.0);
            assertFalse(cd.tick(1.0), "Still 2s remaining");
            assertEquals(2.0, cd.getRemainingSeconds(), 0.001);
        }

        @Test
        void tickExpiresReturnTrue() {
            Cooldown cd = new Cooldown(CooldownType.SKILL, 1, 1.0);
            assertTrue(cd.tick(1.0), "Should expire when reaching 0");
            assertTrue(cd.isExpired());
        }

        @Test
        void tickOvershootClampsToZero() {
            Cooldown cd = new Cooldown(CooldownType.SPELL, 5, 0.5);
            assertTrue(cd.tick(2.0), "Expired with overshoot");
            assertEquals(0.0, cd.getRemainingSeconds(), "Should not go negative");
        }

        @Test
        void keyFormat() {
            Cooldown cd = new Cooldown(CooldownType.SKILL, 42, 1.0);
            assertEquals("SKILL:42", cd.getKey());
        }

        @Test
        void makeKeyMatchesGetKey() {
            Cooldown cd = new Cooldown(CooldownType.SPELL, 7, 1.0);
            assertEquals(cd.getKey(), Cooldown.makeKey(CooldownType.SPELL, 7));
        }
    }

    // ===== CooldownManager tests =====

    @Nested
    @DisplayName("CooldownManager (player cooldowns)")
    class ManagerPlayerCooldowns {

        private CooldownManager mgr;

        @BeforeEach
        void setUp() {
            // Get the singleton — but its state may carry over; clear first
            mgr = CooldownManager.getInstance();
            mgr.clearAllPlayerCooldowns("testplayer");
        }

        @Test
        void setAndCheckCooldown() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 5.0);
            assertTrue(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 100));
        }

        @Test
        void caseInsensitiveLookup() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 5.0);
            assertTrue(mgr.isPlayerOnCooldown("testplayer", CooldownType.SKILL, 100));
        }

        @Test
        void differentAbilityNotOnCooldown() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 5.0);
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 200));
        }

        @Test
        void differentTypeNotOnCooldown() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 5.0);
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SPELL, 100));
        }

        @Test
        void remainingSeconds() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SPELL, 5, 10.0);
            double remaining = mgr.getPlayerCooldownRemaining("TestPlayer", CooldownType.SPELL, 5);
            assertTrue(remaining > 0 && remaining <= 10.0);
        }

        @Test
        void clearSpecificCooldown() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 5.0);
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 200, 5.0);
            mgr.clearPlayerCooldown("TestPlayer", CooldownType.SKILL, 100);
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 100), "Cleared");
            assertTrue(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 200), "Other intact");
        }

        @Test
        void clearAllPlayerCooldowns() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 5.0);
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SPELL, 200, 5.0);
            mgr.clearAllPlayerCooldowns("TestPlayer");
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 100));
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SPELL, 200));
        }

        @Test
        void nullCharacterNameSafe() {
            // Should not throw
            mgr.setPlayerCooldown(null, CooldownType.SKILL, 1, 5.0);
            assertFalse(mgr.isPlayerOnCooldown(null, CooldownType.SKILL, 1));
            mgr.clearPlayerCooldown(null, CooldownType.SKILL, 1);
            mgr.clearAllPlayerCooldowns(null);
        }

        @Test
        void zeroDurationNotAdded() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, 0);
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 100));
        }

        @Test
        void negativeDurationNotAdded() {
            mgr.setPlayerCooldown("TestPlayer", CooldownType.SKILL, 100, -5.0);
            assertFalse(mgr.isPlayerOnCooldown("TestPlayer", CooldownType.SKILL, 100));
        }

        @Test
        void noRemainingForUnsetCooldown() {
            assertEquals(0.0, mgr.getPlayerCooldownRemaining("nobody", CooldownType.SKILL, 999));
        }
    }

    // ===== CooldownManager mobile cooldowns =====

    @Nested
    @DisplayName("CooldownManager (mobile cooldowns)")
    class ManagerMobileCooldowns {

        private CooldownManager mgr;

        @BeforeEach
        void setUp() {
            mgr = CooldownManager.getInstance();
            mgr.clearAllMobileCooldowns(12345L);
        }

        @Test
        void setAndCheckMobileCooldown() {
            mgr.setMobileCooldown(12345L, CooldownType.SKILL, 50, 3.0);
            assertTrue(mgr.isMobileOnCooldown(12345L, CooldownType.SKILL, 50));
        }

        @Test
        void differentMobileNotAffected() {
            mgr.setMobileCooldown(12345L, CooldownType.SKILL, 50, 3.0);
            assertFalse(mgr.isMobileOnCooldown(99999L, CooldownType.SKILL, 50));
        }
    }
}
