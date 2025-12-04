package com.example.tassmud;

import com.example.tassmud.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EquipmentSlot Tests")
class EquipmentSlotTest {

    @Test
    @DisplayName("All slots have unique IDs")
    void allSlotsHaveUniqueIds() {
        EquipmentSlot[] slots = EquipmentSlot.values();
        for (int i = 0; i < slots.length; i++) {
            for (int j = i + 1; j < slots.length; j++) {
                assertNotEquals(slots[i].id, slots[j].id, 
                    slots[i] + " and " + slots[j] + " have the same ID");
            }
        }
    }
    
    @Test
    @DisplayName("All slots have unique keys")
    void allSlotsHaveUniqueKeys() {
        EquipmentSlot[] slots = EquipmentSlot.values();
        for (int i = 0; i < slots.length; i++) {
            for (int j = i + 1; j < slots.length; j++) {
                assertNotEquals(slots[i].key, slots[j].key, 
                    slots[i] + " and " + slots[j] + " have the same key");
            }
        }
    }
    
    @ParameterizedTest
    @EnumSource(EquipmentSlot.class)
    @DisplayName("All slots have non-empty display names")
    void allSlotsHaveDisplayNames(EquipmentSlot slot) {
        assertNotNull(slot.displayName);
        assertFalse(slot.displayName.isEmpty());
    }
    
    @ParameterizedTest
    @EnumSource(EquipmentSlot.class)
    @DisplayName("All slots have non-empty keys")
    void allSlotsHaveKeys(EquipmentSlot slot) {
        assertNotNull(slot.key);
        assertFalse(slot.key.isEmpty());
    }
    
    @ParameterizedTest
    @CsvSource({
        "1, HEAD",
        "2, NECK",
        "3, SHOULDERS",
        "4, ARMS",
        "5, HANDS",
        "6, CHEST",
        "7, WAIST",
        "8, LEGS",
        "9, BOOTS",
        "10, BACK",
        "11, MAIN_HAND",
        "12, OFF_HAND"
    })
    @DisplayName("fromId returns correct slot")
    void fromIdReturnsCorrectSlot(int id, EquipmentSlot expected) {
        assertEquals(expected, EquipmentSlot.fromId(id));
    }
    
    @Test
    @DisplayName("fromId returns null for invalid ID")
    void fromIdReturnsNullForInvalidId() {
        assertNull(EquipmentSlot.fromId(0));
        assertNull(EquipmentSlot.fromId(-1));
        assertNull(EquipmentSlot.fromId(100));
    }
    
    @ParameterizedTest
    @CsvSource({
        "head, HEAD",
        "neck, NECK",
        "shoulders, SHOULDERS",
        "arms, ARMS",
        "hands, HANDS",
        "chest, CHEST",
        "waist, WAIST",
        "legs, LEGS",
        "boots, BOOTS",
        "back, BACK",
        "main_hand, MAIN_HAND",
        "off_hand, OFF_HAND"
    })
    @DisplayName("fromKey returns correct slot by key")
    void fromKeyReturnsCorrectSlotByKey(String key, EquipmentSlot expected) {
        assertEquals(expected, EquipmentSlot.fromKey(key));
    }
    
    @ParameterizedTest
    @CsvSource({
        "HEAD, HEAD",
        "CHEST, CHEST",
        "main_hand, MAIN_HAND",
        "OFF_HAND, OFF_HAND"
    })
    @DisplayName("fromKey accepts enum name as well as key")
    void fromKeyAcceptsEnumName(String input, EquipmentSlot expected) {
        assertEquals(expected, EquipmentSlot.fromKey(input));
    }
    
    @Test
    @DisplayName("fromKey is case-insensitive")
    void fromKeyIsCaseInsensitive() {
        assertEquals(EquipmentSlot.HEAD, EquipmentSlot.fromKey("HEAD"));
        assertEquals(EquipmentSlot.HEAD, EquipmentSlot.fromKey("head"));
        assertEquals(EquipmentSlot.HEAD, EquipmentSlot.fromKey("Head"));
        assertEquals(EquipmentSlot.MAIN_HAND, EquipmentSlot.fromKey("MAIN_HAND"));
        assertEquals(EquipmentSlot.MAIN_HAND, EquipmentSlot.fromKey("main_hand"));
    }
    
    @Test
    @DisplayName("fromKey trims whitespace")
    void fromKeyTrimsWhitespace() {
        assertEquals(EquipmentSlot.HEAD, EquipmentSlot.fromKey("  head  "));
        assertEquals(EquipmentSlot.CHEST, EquipmentSlot.fromKey("\tchest\n"));
    }
    
    @Test
    @DisplayName("fromKey returns null for null input")
    void fromKeyReturnsNullForNull() {
        assertNull(EquipmentSlot.fromKey(null));
    }
    
    @Test
    @DisplayName("fromKey returns null for invalid key")
    void fromKeyReturnsNullForInvalidKey() {
        assertNull(EquipmentSlot.fromKey("invalid"));
        assertNull(EquipmentSlot.fromKey(""));
        assertNull(EquipmentSlot.fromKey("   "));
    }
    
    @Test
    @DisplayName("getId matches id field")
    void getIdMatchesField() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            assertEquals(slot.id, slot.getId());
        }
    }
    
    @Test
    @DisplayName("getKey matches key field")
    void getKeyMatchesField() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            assertEquals(slot.key, slot.getKey());
        }
    }
    
    @Test
    @DisplayName("getDisplayName matches displayName field")
    void getDisplayNameMatchesField() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            assertEquals(slot.displayName, slot.getDisplayName());
        }
    }
    
    @Test
    @DisplayName("Hand slots have correct display names")
    void handSlotsHaveCorrectDisplayNames() {
        assertEquals("Main Hand", EquipmentSlot.MAIN_HAND.displayName);
        assertEquals("Off Hand", EquipmentSlot.OFF_HAND.displayName);
    }
    
    @Test
    @DisplayName("fromKey accepts hand slot aliases")
    void fromKeyAcceptsHandSlotAliases() {
        // Main hand aliases
        assertEquals(EquipmentSlot.MAIN_HAND, EquipmentSlot.fromKey("main"));
        assertEquals(EquipmentSlot.MAIN_HAND, EquipmentSlot.fromKey("mainhand"));
        assertEquals(EquipmentSlot.MAIN_HAND, EquipmentSlot.fromKey("MAIN"));
        assertEquals(EquipmentSlot.MAIN_HAND, EquipmentSlot.fromKey("MAINHAND"));
        
        // Off hand aliases
        assertEquals(EquipmentSlot.OFF_HAND, EquipmentSlot.fromKey("off"));
        assertEquals(EquipmentSlot.OFF_HAND, EquipmentSlot.fromKey("offhand"));
        assertEquals(EquipmentSlot.OFF_HAND, EquipmentSlot.fromKey("OFF"));
        assertEquals(EquipmentSlot.OFF_HAND, EquipmentSlot.fromKey("OFFHAND"));
    }

    @Test
    @DisplayName("There are exactly 12 equipment slots")
    void thereAre12Slots() {
        assertEquals(12, EquipmentSlot.values().length);
    }
}
