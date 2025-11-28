package com.example.tassmud.net;

/**
 * Command parser that splits input into command name and arguments,
 * resolving abbreviations and aliases via the CommandRegistry.
 */
public class CommandParser {

    public static Command parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        String resolved = CommandRegistry.resolveCommand(cmd);
        if (resolved == null) return null;
        return new Command(resolved, args);
    }

    /**
     * Get all canonical command names (for legacy compatibility).
     */
    public static String[] getCanonicalCommands() {
        return CommandRegistry.getCanonicalNames();
    }
    
    /**
     * Represents a parsed command with name and argument string.
     */
    public static class Command {
        private final String name;
        private final String args;

        public Command(String name, String args) {
            this.name = name;
            this.args = args;
        }

        public String getName() { return name; }
        public String getArgs() { return args; }
    }
}
