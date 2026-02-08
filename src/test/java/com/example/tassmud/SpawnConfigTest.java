package com.example.tassmud;

import com.example.tassmud.event.SpawnConfig;
import com.example.tassmud.event.SpawnConfig.SpawnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpawnConfig: construction, clamping, ID generation, container logic.
 */
class SpawnConfigTest {

    @Test
    @DisplayName("Basic construction")
    void basicConstruction() {
        SpawnConfig sc = new SpawnConfig(SpawnType.MOB, 42, 2, 4, 1001);
        assertEquals(SpawnType.MOB, sc.type);
        assertEquals(42, sc.templateId);
        assertEquals(2, sc.quantity);
        assertEquals(4, sc.frequencyHours);
        assertEquals(1001, sc.roomId);
        assertFalse(sc.hasContainer());
    }

    @Test
    @DisplayName("Quantity clamped to minimum 1")
    void quantityClampedToOne() {
        SpawnConfig sc = new SpawnConfig(SpawnType.ITEM, 1, 0, 1, 1001);
        assertEquals(1, sc.quantity, "0 → 1");

        SpawnConfig neg = new SpawnConfig(SpawnType.ITEM, 1, -5, 1, 1001);
        assertEquals(1, neg.quantity, "-5 → 1");
    }

    @Test
    @DisplayName("Frequency clamped to [1, 24]")
    void frequencyClamped() {
        SpawnConfig low = new SpawnConfig(SpawnType.MOB, 1, 1, 0, 1001);
        assertEquals(1, low.frequencyHours, "0 → 1");

        SpawnConfig high = new SpawnConfig(SpawnType.MOB, 1, 1, 100, 1001);
        assertEquals(24, high.frequencyHours, "100 → 24");
    }

    @Test
    @DisplayName("Container template ID logic")
    void containerTemplateId() {
        SpawnConfig noContainer = new SpawnConfig(SpawnType.ITEM, 10, 1, 1, 1001);
        assertEquals(-1, noContainer.containerTemplateId);
        assertFalse(noContainer.hasContainer());

        SpawnConfig withContainer = new SpawnConfig(SpawnType.ITEM, 10, 1, 1, 1001, 50, null);
        assertEquals(50, withContainer.containerTemplateId);
        assertTrue(withContainer.hasContainer());
    }

    @Test
    @DisplayName("Spawn ID generation")
    void spawnIdGeneration() {
        SpawnConfig mob = new SpawnConfig(SpawnType.MOB, 42, 1, 1, 1001);
        assertEquals("spawn-mob-42-room1001", mob.getSpawnId());

        SpawnConfig item = new SpawnConfig(SpawnType.ITEM, 99, 1, 1, 2000, 50, null);
        assertEquals("spawn-item-99-room2000-in50", item.getSpawnId());
    }

    @Test
    @DisplayName("Delay conversion: 1 game hour = 60000ms")
    void delayMs() {
        SpawnConfig sc = new SpawnConfig(SpawnType.MOB, 1, 1, 3, 1001);
        assertEquals(180000L, sc.getDelayMs(), "3 hours * 60000 = 180000ms");
    }

    @Test
    @DisplayName("Equipment list defaults to empty")
    void equipmentDefaultsEmpty() {
        SpawnConfig sc = new SpawnConfig(SpawnType.MOB, 1, 1, 1, 1001);
        assertNotNull(sc.equipment);
        assertTrue(sc.equipment.isEmpty());
    }

    @Test
    @DisplayName("Equipment list is immutable copy")
    void equipmentIsCopy() {
        List<Map<String, Object>> equip = new java.util.ArrayList<>();
        equip.add(Map.of("item_vnum", 100, "wear_loc", "MAIN_HAND"));
        SpawnConfig sc = new SpawnConfig(SpawnType.MOB, 1, 1, 1, 1001, -1, equip);

        assertEquals(1, sc.equipment.size());
        assertThrows(UnsupportedOperationException.class,
                () -> sc.equipment.add(Map.of("item_vnum", 999)));
    }

    @Test
    @DisplayName("Inventory list defaults to empty")
    void inventoryDefaultsEmpty() {
        SpawnConfig sc = new SpawnConfig(SpawnType.MOB, 1, 1, 1, 1001);
        assertNotNull(sc.inventory);
        assertTrue(sc.inventory.isEmpty());
    }

    @Test
    @DisplayName("toString includes key fields")
    void toStringIncludesFields() {
        SpawnConfig sc = new SpawnConfig(SpawnType.MOB, 42, 3, 6, 1001);
        String s = sc.toString();
        assertTrue(s.contains("MOB"));
        assertTrue(s.contains("42"));
        assertTrue(s.contains("1001"));
    }
}
