package com.example.tassmud;

import com.example.tassmud.model.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Direction enum: opposites, fromString parsing, fullName/shortName.
 */
class DirectionTest {

    @ParameterizedTest(name = "{0} ↔ {1}")
    @CsvSource({"NORTH,SOUTH", "EAST,WEST", "UP,DOWN"})
    @DisplayName("Opposite pairs")
    void oppositePairs(Direction a, Direction b) {
        assertEquals(b, a.opposite());
        assertEquals(a, b.opposite());
    }

    @Test
    @DisplayName("Opposite is involution (double opposite = identity)")
    void doubleOppositeIsIdentity() {
        for (Direction d : Direction.values()) {
            assertSame(d, d.opposite().opposite());
        }
    }

    @ParameterizedTest(name = "fromString(\"{0}\") = {1}")
    @CsvSource({
            "north, NORTH", "n, NORTH", "NORTH, NORTH",
            "south, SOUTH", "s, SOUTH",
            "east, EAST",   "e, EAST",
            "west, WEST",   "w, WEST",
            "up, UP",       "u, UP",
            "down, DOWN",   "d, DOWN"
    })
    @DisplayName("fromString parses all formats")
    void fromStringParsesAllFormats(String input, Direction expected) {
        assertEquals(expected, Direction.fromString(input));
    }

    @Test
    @DisplayName("fromString returns null for invalid input")
    void fromStringNull() {
        assertNull(Direction.fromString(null));
        assertNull(Direction.fromString(""));
        assertNull(Direction.fromString("northeast"));
        assertNull(Direction.fromString("x"));
    }

    @Test
    @DisplayName("fullName and shortName")
    void namesCorrect() {
        assertEquals("north", Direction.NORTH.fullName());
        assertEquals("n", Direction.NORTH.shortName());
        assertEquals("n", Direction.NORTH.columnSuffix());
    }

    @Test
    @DisplayName("All 6 directions present")
    void allDirectionsPresent() {
        assertEquals(6, Direction.values().length);
    }
}
