package com.example.tassmud;

import com.example.tassmud.net.CommandDefinition;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.net.CommandParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to ensure the command registry is complete and consistent.
 */
public class CommandRegistryTest {

    @Test
    @DisplayName("All registered commands should resolve correctly")
    void allCommandsResolve() {
        for (CommandDefinition cmd : CommandRegistry.getAllCommands()) {
            String resolved = CommandRegistry.resolveCommand(cmd.getName());
            assertNotNull(resolved, "Command '" + cmd.getName() + "' should resolve");
            assertEquals(cmd.getName(), resolved, "Command should resolve to its canonical name");
        }
    }

    @Test
    @DisplayName("All aliases should resolve to their canonical command")
    void allAliasesResolve() {
        for (CommandDefinition cmd : CommandRegistry.getAllCommands()) {
            for (String alias : cmd.getAliases()) {
                String resolved = CommandRegistry.resolveCommand(alias);
                assertNotNull(resolved, "Alias '" + alias + "' should resolve");
                assertEquals(cmd.getName(), resolved, 
                    "Alias '" + alias + "' should resolve to '" + cmd.getName() + "'");
            }
        }
    }

    @Test
    @DisplayName("Command prefixes should resolve correctly")
    void prefixResolution() {
        // Test some common prefix resolutions
        assertEquals("north", CommandRegistry.resolveCommand("nor"));
        assertEquals("south", CommandRegistry.resolveCommand("sou"));
        assertEquals("help", CommandRegistry.resolveCommand("hel"));
        assertEquals("inventory", CommandRegistry.resolveCommand("inv"));
        
        // Single letter aliases
        assertEquals("north", CommandRegistry.resolveCommand("n"));
        assertEquals("south", CommandRegistry.resolveCommand("s"));
        assertEquals("east", CommandRegistry.resolveCommand("e"));
        assertEquals("west", CommandRegistry.resolveCommand("w"));
        assertEquals("inventory", CommandRegistry.resolveCommand("i"));
    }

    @Test
    @DisplayName("Every category should have at least one command")
    void allCategoriesHaveCommands() {
        for (CommandDefinition.Category category : CommandDefinition.Category.values()) {
            List<CommandDefinition> cmds = CommandRegistry.getCommandsByCategory(category);
            assertFalse(cmds.isEmpty(), 
                "Category '" + category.getDisplayName() + "' should have at least one command");
        }
    }

    @Test
    @DisplayName("All commands should have a non-empty description")
    void allCommandsHaveDescription() {
        for (CommandDefinition cmd : CommandRegistry.getAllCommands()) {
            assertNotNull(cmd.getDescription(), 
                "Command '" + cmd.getName() + "' should have a description");
            assertFalse(cmd.getDescription().isEmpty(), 
                "Command '" + cmd.getName() + "' description should not be empty");
        }
    }

    @Test
    @DisplayName("GM commands should be marked as gmOnly")
    void gmCommandsAreMarkedCorrectly() {
        for (CommandDefinition cmd : CommandRegistry.getCommandsByCategory(CommandDefinition.Category.GM)) {
            assertTrue(cmd.isGmOnly(), 
                "GM command '" + cmd.getName() + "' should be marked as gmOnly");
        }
    }

    @Test
    @DisplayName("Non-GM commands should not be marked as gmOnly")
    void nonGmCommandsAreMarkedCorrectly() {
        for (CommandDefinition.Category category : CommandDefinition.Category.values()) {
            if (category == CommandDefinition.Category.GM) continue;
            
            for (CommandDefinition cmd : CommandRegistry.getCommandsByCategory(category)) {
                assertFalse(cmd.isGmOnly(), 
                    "Non-GM command '" + cmd.getName() + "' should not be marked as gmOnly");
            }
        }
    }

    @Test
    @DisplayName("CommandParser should use CommandRegistry for resolution")
    void commandParserUsesRegistry() {
        // Verify parser resolves commands the same way as registry
        CommandParser.Command parsed = CommandParser.parse("n");
        assertNotNull(parsed);
        assertEquals("north", parsed.getName());
        
        parsed = CommandParser.parse("inv");
        assertNotNull(parsed);
        assertEquals("inventory", parsed.getName());
        
        parsed = CommandParser.parse("goto 3001");
        assertNotNull(parsed);
        assertEquals("goto", parsed.getName());
        assertEquals("3001", parsed.getArgs());
    }

    @Test
    @DisplayName("All registered commands should have a case in ClientHandler")
    void allCommandsHaveImplementation() throws IOException {
        // Read ClientHandler source file
        Path clientHandlerPath = Path.of("src/main/java/com/example/tassmud/net/ClientHandler.java");
        
        // Skip if file doesn't exist (e.g., running from different directory)
        if (!Files.exists(clientHandlerPath)) {
            System.out.println("Skipping implementation check - source file not found at: " + clientHandlerPath);
            return;
        }
        
        String source = Files.readString(clientHandlerPath);
        
        // Find all case statements
        Pattern casePattern = Pattern.compile("case\\s+\"(\\w+)\"\\s*:");
        Matcher matcher = casePattern.matcher(source);
        Set<String> implementedCommands = new HashSet<>();
        while (matcher.find()) {
            implementedCommands.add(matcher.group(1).toLowerCase());
        }
        
        // Check each registered command
        List<String> missingImplementations = new ArrayList<>();
        for (CommandDefinition cmd : CommandRegistry.getAllCommands()) {
            // Check if command or any alias is implemented
            boolean found = implementedCommands.contains(cmd.getName());
            if (!found) {
                for (String alias : cmd.getAliases()) {
                    if (implementedCommands.contains(alias)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                missingImplementations.add(cmd.getName());
            }
        }
        
        assertTrue(missingImplementations.isEmpty(),
            "Commands missing implementation in ClientHandler: " + missingImplementations);
    }

    @Test
    @DisplayName("No duplicate command names or aliases")
    void noDuplicateNames() {
        Set<String> allNames = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        
        for (CommandDefinition cmd : CommandRegistry.getAllCommands()) {
            if (!allNames.add(cmd.getName())) {
                duplicates.add(cmd.getName());
            }
            for (String alias : cmd.getAliases()) {
                if (!allNames.add(alias)) {
                    duplicates.add(alias);
                }
            }
        }
        
        assertTrue(duplicates.isEmpty(), "Duplicate command names/aliases found: " + duplicates);
    }

    @Test
    @DisplayName("Unknown commands should not resolve")
    void unknownCommandsDoNotResolve() {
        assertNull(CommandRegistry.resolveCommand("xyzzy"));
        assertNull(CommandRegistry.resolveCommand("foobar"));
        assertNull(CommandRegistry.resolveCommand(""));
        assertNull(CommandRegistry.resolveCommand(null));
    }
}
