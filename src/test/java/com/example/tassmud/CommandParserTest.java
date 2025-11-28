package com.example.tassmud;

import com.example.tassmud.net.CommandParser;
import com.example.tassmud.net.CommandParser.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CommandParser Tests")
public class CommandParserTest {

    @Test
    @DisplayName("parse returns command with correct name for 'look'")
    void parseLook() {
        Command c = CommandParser.parse("look");
        assertNotNull(c);
        assertEquals("look", c.getName());
    }

    @Test
    @DisplayName("parse returns command with name and args for 'say'")
    void parseSay() {
        Command c = CommandParser.parse("say Hello world");
        assertNotNull(c);
        assertEquals("say", c.getName());
        assertEquals("Hello world", c.getArgs());
    }
    
    @Test
    @DisplayName("parse returns null for null input")
    void parseNullReturnsNull() {
        assertNull(CommandParser.parse(null));
    }
    
    @Test
    @DisplayName("parse returns null for empty input")
    void parseEmptyReturnsNull() {
        assertNull(CommandParser.parse(""));
    }
    
    @Test
    @DisplayName("parse returns null for blank input")
    void parseBlankReturnsNull() {
        assertNull(CommandParser.parse("   "));
        assertNull(CommandParser.parse("\t\n"));
    }
    
    @Test
    @DisplayName("parse returns null for unknown command")
    void parseUnknownCommandReturnsNull() {
        assertNull(CommandParser.parse("unknowncommand"));
        assertNull(CommandParser.parse("xyz123"));
    }
    
    // --- Prefix Resolution Tests ---
    
    @ParameterizedTest
    @CsvSource({
        "l, look",
        "lo, look",
        "loo, look",
        "look, look"
    })
    @DisplayName("parse resolves 'look' command with prefix matching")
    void parseLookPrefixMatching(String input, String expected) {
        Command c = CommandParser.parse(input);
        assertNotNull(c, "Command should not be null for input: " + input);
        assertEquals(expected, c.getName());
    }
    
    @ParameterizedTest
    @CsvSource({
        "g, get",
        "ge, get",
        "get, get"
    })
    @DisplayName("parse resolves 'get' command with prefix matching")
    void parseGetPrefixMatching(String input, String expected) {
        Command c = CommandParser.parse(input);
        assertNotNull(c, "Command should not be null for input: " + input);
        assertEquals(expected, c.getName());
    }
    
    @ParameterizedTest
    @CsvSource({
        "i, i",
        "in, inventory",
        "inv, inventory",
        "inventory, inventory"
    })
    @DisplayName("parse resolves 'inventory' command with prefix matching")
    void parseInventoryPrefixMatching(String input, String expected) {
        Command c = CommandParser.parse(input);
        assertNotNull(c, "Command should not be null for input: " + input);
        assertEquals(expected, c.getName());
    }
    
    @ParameterizedTest
    @CsvSource({
        "ca, cast",
        "cas, cast",
        "cast, cast"
    })
    @DisplayName("parse resolves 'cast' command with prefix matching")
    void parseCastPrefixMatching(String input, String expected) {
        Command c = CommandParser.parse(input);
        assertNotNull(c, "Command should not be null for input: " + input);
        assertEquals(expected, c.getName());
    }
    
    // --- Movement Commands ---
    // Single-letter aliases: n, e, s, w, u, d are registered
    
    @ParameterizedTest
    @CsvSource({
        "n, n",
        "north, north",
        "e, e",
        "east, east",
        "s, s",
        "south, south",
        "w, w",
        "west, west",
        "u, u",
        "up, up",
        "d, d",
        "do, down",
        "down, down"
    })
    @DisplayName("parse resolves movement commands correctly")
    void parseMovementCommands(String input, String expected) {
        Command c = CommandParser.parse(input);
        assertNotNull(c, "Command should not be null for input: " + input);
        assertEquals(expected, c.getName());
    }
    
    // --- Case Sensitivity ---
    
    @Test
    @DisplayName("parse is case-insensitive")
    void parseIsCaseInsensitive() {
        Command c1 = CommandParser.parse("LOOK");
        Command c2 = CommandParser.parse("Look");
        Command c3 = CommandParser.parse("look");
        
        assertNotNull(c1);
        assertNotNull(c2);
        assertNotNull(c3);
        assertEquals("look", c1.getName());
        assertEquals("look", c2.getName());
        assertEquals("look", c3.getName());
    }
    
    // --- Arguments Handling ---
    
    @Test
    @DisplayName("parse preserves argument case")
    void parsePreservesArgumentCase() {
        Command c = CommandParser.parse("say Hello World");
        assertEquals("Hello World", c.getArgs());
    }
    
    @Test
    @DisplayName("parse returns empty string args when no arguments provided")
    void parseEmptyArgsWhenNoArguments() {
        Command c = CommandParser.parse("look");
        assertNotNull(c);
        assertEquals("", c.getArgs());
    }
    
    @Test
    @DisplayName("parse handles multiple spaces between command and args")
    void parseHandlesMultipleSpaces() {
        Command c = CommandParser.parse("say    Hello World");
        assertNotNull(c);
        assertEquals("say", c.getName());
        // Note: split("\\s+", 2) collapses leading spaces in args
        assertEquals("Hello World", c.getArgs());
    }
    
    // --- getCanonicalCommands ---
    
    @Test
    @DisplayName("getCanonicalCommands returns non-empty array")
    void getCanonicalCommandsNotEmpty() {
        String[] commands = CommandParser.getCanonicalCommands();
        assertNotNull(commands);
        assertTrue(commands.length > 0);
    }
    
    @Test
    @DisplayName("getCanonicalCommands returns a copy")
    void getCanonicalCommandsReturnsCopy() {
        String[] commands1 = CommandParser.getCanonicalCommands();
        String[] commands2 = CommandParser.getCanonicalCommands();
        
        assertNotSame(commands1, commands2);
    }
    
    @Test
    @DisplayName("getCanonicalCommands includes essential commands")
    void getCanonicalCommandsIncludesEssentials() {
        String[] commands = CommandParser.getCanonicalCommands();
        java.util.List<String> list = java.util.Arrays.asList(commands);
        
        assertTrue(list.contains("look"), "Should contain 'look'");
        assertTrue(list.contains("get"), "Should contain 'get'");
        assertTrue(list.contains("drop"), "Should contain 'drop'");
        assertTrue(list.contains("inventory"), "Should contain 'inventory'");
        assertTrue(list.contains("cast"), "Should contain 'cast'");
        assertTrue(list.contains("spells"), "Should contain 'spells'");
        assertTrue(list.contains("equip"), "Should contain 'equip'");
        assertTrue(list.contains("quit"), "Should contain 'quit'");
    }
    
    // --- Specific Command Tests ---
    
    @Test
    @DisplayName("parse handles 'equip' command with args")
    void parseEquipWithArgs() {
        Command c = CommandParser.parse("equip sword");
        assertNotNull(c);
        assertEquals("equip", c.getName());
        assertEquals("sword", c.getArgs());
    }
    
    @Test
    @DisplayName("parse handles 'drop' command with args")
    void parseDropWithArgs() {
        Command c = CommandParser.parse("drop gold coin");
        assertNotNull(c);
        assertEquals("drop", c.getName());
        assertEquals("gold coin", c.getArgs());
    }
    
    @Test
    @DisplayName("parse handles 'cast' command with spell name and target")
    void parseCastWithSpellAndTarget() {
        Command c = CommandParser.parse("cast magic missile goblin");
        assertNotNull(c);
        assertEquals("cast", c.getName());
        assertEquals("magic missile goblin", c.getArgs());
    }
}
