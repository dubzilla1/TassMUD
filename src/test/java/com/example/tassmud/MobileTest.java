package com.example.tassmud;

import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Mobile, MobileTemplate, and MobileBehavior classes.
 */
@DisplayName("Mobile Tests")
public class MobileTest {

    private MobileTemplate createTestTemplate() {
        return new MobileTemplate(
            1, "test_goblin", "Test Goblin", 
            "A test goblin is here.", "This is a test goblin for unit tests.",
            Arrays.asList("goblin", "test"),
            3, // level
            25, // hpMax
            10, // mpMax
            100, // mvMax
            12, 14, 10, 8, 8, 6, // str, dex, con, int, wis, cha
            14, 1, 2, 0, // armor, fort, ref, will
            6, 1, 2, // baseDamage, damageBonus, attackBonus
            Arrays.asList(MobileBehavior.AGGRESSIVE, MobileBehavior.SCAVENGER),
            0, // aggroRange
            25, // experienceValue
            2, 8, // goldMin, goldMax
            180, // respawnSeconds
            0, // autoflee
            null // templateJson
        );
    }
    
    // ==================== MobileBehavior Enum Tests ====================
    
    @Test
    @DisplayName("MobileBehavior should have all expected values")
    void behaviorEnumValues() {
        // Combat behaviors
        assertNotNull(MobileBehavior.AGGRESSIVE);
        assertNotNull(MobileBehavior.PASSIVE);
        assertNotNull(MobileBehavior.DEFENSIVE);
        assertNotNull(MobileBehavior.COWARDLY);
        
        // Role behaviors
        assertNotNull(MobileBehavior.GUARD);
        assertNotNull(MobileBehavior.SHOPKEEPER);
        assertNotNull(MobileBehavior.HEALER);
        assertNotNull(MobileBehavior.TRAINER);
        assertNotNull(MobileBehavior.QUESTGIVER);
        assertNotNull(MobileBehavior.BANKER);
        
        // Personality behaviors
        assertNotNull(MobileBehavior.BEGGAR);
        assertNotNull(MobileBehavior.THIEF);
        assertNotNull(MobileBehavior.WANDERER);
        assertNotNull(MobileBehavior.SENTINEL);
        
        // Special behaviors
        assertNotNull(MobileBehavior.IMMORTAL);
        assertNotNull(MobileBehavior.SCAVENGER);
        assertNotNull(MobileBehavior.ASSISTS);
        assertNotNull(MobileBehavior.NOCTURNAL);
        assertNotNull(MobileBehavior.DIURNAL);
    }
    
    @Test
    @DisplayName("MobileBehavior fromString should work case-insensitively")
    void behaviorFromString() {
        assertEquals(MobileBehavior.AGGRESSIVE, MobileBehavior.fromString("AGGRESSIVE"));
        assertEquals(MobileBehavior.AGGRESSIVE, MobileBehavior.fromString("aggressive"));
        assertEquals(MobileBehavior.AGGRESSIVE, MobileBehavior.fromString("Aggressive"));
        assertEquals(MobileBehavior.SHOPKEEPER, MobileBehavior.fromString("shopkeeper"));
        assertEquals(MobileBehavior.IMMORTAL, MobileBehavior.fromString("IMMORTAL"));
        
        assertNull(MobileBehavior.fromString(null));
        assertNull(MobileBehavior.fromString(""));
        assertNull(MobileBehavior.fromString("INVALID"));
    }
    
    @Test
    @DisplayName("MobileBehavior should have descriptions")
    void behaviorDescriptions() {
        assertNotNull(MobileBehavior.AGGRESSIVE.getDescription());
        assertFalse(MobileBehavior.AGGRESSIVE.getDescription().isEmpty());
        
        assertNotNull(MobileBehavior.SHOPKEEPER.getDescription());
        assertNotNull(MobileBehavior.IMMORTAL.getDescription());
    }
    
    // ==================== MobileTemplate Tests ====================
    
    @Test
    @DisplayName("MobileTemplate should store all fields correctly")
    void templateStoresFields() {
        MobileTemplate template = createTestTemplate();
        
        assertEquals(1, template.getId());
        assertEquals("test_goblin", template.getKey());
        assertEquals("Test Goblin", template.getName());
        assertEquals("A test goblin is here.", template.getShortDesc());
        assertEquals(3, template.getLevel());
        assertEquals(25, template.getHpMax());
        assertEquals(10, template.getMpMax());
        assertEquals(100, template.getMvMax());
        assertEquals(12, template.getStr());
        assertEquals(14, template.getDex());
        assertEquals(10, template.getCon());
        assertEquals(8, template.getIntel());
        assertEquals(8, template.getWis());
        assertEquals(6, template.getCha());
        assertEquals(14, template.getArmor());
        assertEquals(1, template.getFortitude());
        assertEquals(2, template.getReflex());
        assertEquals(0, template.getWill());
        assertEquals(6, template.getBaseDamage());
        assertEquals(1, template.getDamageBonus());
        assertEquals(2, template.getAttackBonus());
        assertEquals(0, template.getAggroRange());
        assertEquals(25, template.getExperienceValue());
        assertEquals(2, template.getGoldMin());
        assertEquals(8, template.getGoldMax());
        assertEquals(180, template.getRespawnSeconds());
    }
    
    @Test
    @DisplayName("MobileTemplate should have multiple behaviors")
    void templateHasMultipleBehaviors() {
        MobileTemplate template = createTestTemplate();
        
        List<MobileBehavior> behaviors = template.getBehaviors();
        assertEquals(2, behaviors.size());
        assertTrue(behaviors.contains(MobileBehavior.AGGRESSIVE));
        assertTrue(behaviors.contains(MobileBehavior.SCAVENGER));
        assertFalse(behaviors.contains(MobileBehavior.PASSIVE));
    }
    
    @Test
    @DisplayName("MobileTemplate hasBehavior should work correctly")
    void templateHasBehavior() {
        MobileTemplate template = createTestTemplate();
        
        assertTrue(template.hasBehavior(MobileBehavior.AGGRESSIVE));
        assertTrue(template.hasBehavior(MobileBehavior.SCAVENGER));
        assertFalse(template.hasBehavior(MobileBehavior.PASSIVE));
        assertFalse(template.hasBehavior(MobileBehavior.IMMORTAL));
    }
    
    @Test
    @DisplayName("MobileTemplate convenience methods should work")
    void templateConvenienceMethods() {
        MobileTemplate aggressive = createTestTemplate();
        assertTrue(aggressive.isAggressive());
        assertFalse(aggressive.isImmortal());
        assertFalse(aggressive.isShopkeeper());
        
        MobileTemplate shopkeeper = new MobileTemplate(
            2, "key", "Shopkeeper", "Short", "Long", Collections.emptyList(),
            1, 10, 0, 100, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0,
            4, 0, 0, Arrays.asList(MobileBehavior.SHOPKEEPER, MobileBehavior.IMMORTAL),
            0, 0, 0, 0, 60, 0, null
        );
        assertTrue(shopkeeper.isShopkeeper());
        assertTrue(shopkeeper.isImmortal());
        assertFalse(shopkeeper.isAggressive());
    }
    
    @Test
    @DisplayName("MobileTemplate behaviors should be unmodifiable")
    void templateBehaviorsUnmodifiable() {
        MobileTemplate template = createTestTemplate();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            template.getBehaviors().add(MobileBehavior.IMMORTAL);
        });
    }
    
    @Test
    @DisplayName("MobileTemplate keywords should be unmodifiable")
    void templateKeywordsUnmodifiable() {
        MobileTemplate template = createTestTemplate();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            template.getKeywords().add("new_keyword");
        });
    }
    
    @Test
    @DisplayName("MobileTemplate matchesKeyword should work with prefixes")
    void templateMatchesKeyword() {
        MobileTemplate template = createTestTemplate();
        
        // Should match keywords
        assertTrue(template.matchesKeyword("goblin"));
        assertTrue(template.matchesKeyword("gob"));
        assertTrue(template.matchesKeyword("test"));
        assertTrue(template.matchesKeyword("te"));
        
        // Should match name
        assertTrue(template.matchesKeyword("Test"));
        assertTrue(template.matchesKeyword("test goblin"));
        
        // Should not match random strings
        assertFalse(template.matchesKeyword("orc"));
        assertFalse(template.matchesKeyword("xyz"));
        
        // Edge cases
        assertFalse(template.matchesKeyword(null));
        assertFalse(template.matchesKeyword(""));
    }
    
    @Test
    @DisplayName("MobileTemplate with null keywords/behaviors should use empty list")
    void templateNullCollections() {
        MobileTemplate template = new MobileTemplate(
            1, "key", "Name", "Short", "Long", null,
            1, 10, 0, 100, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0,
            4, 0, 0, null, 0, 10, 0, 0, 60, 0, null
        );
        
        assertNotNull(template.getKeywords());
        assertTrue(template.getKeywords().isEmpty());
        assertNotNull(template.getBehaviors());
        assertTrue(template.getBehaviors().isEmpty());
    }
    
    // ==================== Mobile Tests ====================
    
    @Test
    @DisplayName("Mobile should inherit stats from template")
    void mobileInheritsFromTemplate() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        assertEquals("Test Goblin", mobile.getName());
        assertEquals(25, mobile.getHpMax());
        assertEquals(25, mobile.getHpCur()); // Starts at full HP
        assertEquals(10, mobile.getMpMax());
        assertEquals(10, mobile.getMpCur());
        assertEquals(100, mobile.getMvMax());
        assertEquals(100, mobile.getMvCur());
        assertEquals(12, mobile.getStr());
        assertEquals(14, mobile.getDex());
        assertEquals(10, mobile.getCon());
    }
    
    @Test
    @DisplayName("Mobile should inherit behaviors from template")
    void mobileInheritsBehaviors() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        List<MobileBehavior> behaviors = mobile.getBehaviors();
        assertEquals(2, behaviors.size());
        assertTrue(mobile.hasBehavior(MobileBehavior.AGGRESSIVE));
        assertTrue(mobile.hasBehavior(MobileBehavior.SCAVENGER));
        assertTrue(mobile.isAggressive());
    }
    
    @Test
    @DisplayName("Mobile should track instance-specific data")
    void mobileTracksInstanceData() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(42L, template, 1000);
        
        assertEquals(42L, mobile.getInstanceId());
        assertEquals(1, mobile.getTemplateId());
        assertEquals(Integer.valueOf(1000), mobile.getSpawnRoomId());
        assertEquals(Integer.valueOf(1000), mobile.getCurrentRoom());
        assertFalse(mobile.isDead());
    }
    
    @Test
    @DisplayName("Mobile should handle death correctly")
    void mobileDeathBehavior() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        assertFalse(mobile.isDead());
        assertEquals(25, mobile.getHpCur());
        
        mobile.die();
        
        assertTrue(mobile.isDead());
        assertEquals(0, mobile.getHpCur());
        assertTrue(mobile.getDiedAt() > 0);
        assertFalse(mobile.hasTarget());
    }
    
    @Test
    @DisplayName("Mobile should handle respawn correctly")
    void mobileRespawnBehavior() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        // Move to different room and take damage
        mobile.setCurrentRoom(2000);
        mobile.setHpCur(5);
        mobile.setMpCur(2);
        mobile.die();
        
        // Respawn
        mobile.respawn();
        
        assertFalse(mobile.isDead());
        assertEquals(25, mobile.getHpCur()); // Full HP
        assertEquals(10, mobile.getMpCur()); // Full MP
        assertEquals(100, mobile.getMvCur()); // Full MV
        assertEquals(Integer.valueOf(1000), mobile.getCurrentRoom()); // Back to spawn room
        assertEquals(0, mobile.getDiedAt());
    }
    
    @Test
    @DisplayName("Mobile should track combat targets")
    void mobileCombatTargets() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        assertFalse(mobile.hasTarget());
        assertNull(mobile.getTargetCharacterId());
        assertNull(mobile.getTargetMobileId());
        
        // Target a player
        mobile.setTargetCharacterId(5);
        assertTrue(mobile.hasTarget());
        assertEquals(Integer.valueOf(5), mobile.getTargetCharacterId());
        
        // Clear target
        mobile.clearTarget();
        assertFalse(mobile.hasTarget());
        assertNull(mobile.getTargetCharacterId());
    }
    
    @Test
    @DisplayName("Mobile behavior checks should work correctly")
    void mobileBehaviorChecks() {
        MobileTemplate aggressive = new MobileTemplate(
            1, "key", "Name", "Short", "Long", Collections.emptyList(),
            1, 10, 0, 100, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0,
            4, 0, 0, Arrays.asList(MobileBehavior.AGGRESSIVE), 0, 10, 0, 0, 60, 0, null
        );
        MobileTemplate passive = new MobileTemplate(
            2, "key2", "Name2", "Short", "Long", Collections.emptyList(),
            1, 10, 0, 100, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0,
            4, 0, 0, Arrays.asList(MobileBehavior.PASSIVE), 0, 10, 0, 0, 60, 0, null
        );
        MobileTemplate immortalShopkeeper = new MobileTemplate(
            3, "key3", "Name3", "Short", "Long", Collections.emptyList(),
            1, 10, 0, 100, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0,
            4, 0, 0, Arrays.asList(MobileBehavior.SHOPKEEPER, MobileBehavior.IMMORTAL), 0, 0, 0, 0, 60, 0, null
        );
        MobileTemplate cowardly = new MobileTemplate(
            4, "key4", "Name4", "Short", "Long", Collections.emptyList(),
            1, 100, 0, 100, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0, // 100 HP for flee test
            4, 0, 0, Arrays.asList(MobileBehavior.COWARDLY), 0, 10, 0, 0, 60, 0, null
        );
        
        Mobile aggMob = new Mobile(1L, aggressive, 1000);
        Mobile pasMob = new Mobile(2L, passive, 1000);
        Mobile shopMob = new Mobile(3L, immortalShopkeeper, 1000);
        Mobile cowMob = new Mobile(4L, cowardly, 1000);
        
        assertTrue(aggMob.isAggressive());
        assertFalse(aggMob.isPassive());
        
        assertTrue(pasMob.isPassive());
        assertFalse(pasMob.isAggressive());
        
        assertTrue(shopMob.isShopkeeper());
        assertTrue(shopMob.isImmortal());
        assertFalse(shopMob.canBeKilled());
        
        assertTrue(cowMob.isCowardly());
        assertTrue(cowMob.canBeKilled());
        assertFalse(cowMob.shouldFlee()); // At full HP
        cowMob.setHpCur(20); // 20% HP
        assertTrue(cowMob.shouldFlee()); // Below 25%
    }
    
    @Test
    @DisplayName("Mobile getRoomLine should work correctly")
    void mobileGetRoomLine() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        assertEquals("A test goblin is here.", mobile.getRoomLine());
        
        mobile.die();
        assertNull(mobile.getRoomLine()); // Dead mobs don't show
    }
    
    @Test
    @DisplayName("Mobile rollDamage should produce valid damage")
    void mobileRollDamage() {
        MobileTemplate template = createTestTemplate();
        Mobile mobile = new Mobile(1L, template, 1000);
        
        // Roll damage 100 times and check it's within expected range
        // baseDamage=6, damageBonus=1, STR=12 (mod +1)
        // Expected range: 1d6 + 1 + 1 = 3-8, minimum capped at 1
        for (int i = 0; i < 100; i++) {
            int damage = mobile.rollDamage();
            assertTrue(damage >= 1, "Damage should be at least 1");
            assertTrue(damage <= 10, "Damage should not exceed max possible");
        }
    }
}
