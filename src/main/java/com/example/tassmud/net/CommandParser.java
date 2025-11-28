package com.example.tassmud.net;

/**
 * Very small command parser that splits the command name and arguments.
 */
public class CommandParser {

        // Sorted list of known command names used for prefix matching. Includes canonical names
        // plus common single-letter movement aliases (n/e/s/w/u/d). Keep this list sorted
        // so prefix resolution returns the first alphabetical match.
        private static final String[] KNOWN_COMMANDS = new String[] {
            "cast",
            "cflag",
            "chat",
            "d",
            "dbinfo",
            "dequip",
            "down",
            "drop",
            "e",
            "east",
            "equip",
            "get",
            "gmchat",
            "groupchat",
            "help",
            "i",
            "inventory",
            "look",
            "motd",
            "n",
            "north",
            "pickup",
            "prompt",
            "quit",
            "remove",
            "s",
            "save",
            "say",
            "south",
            "spawn",
            "spells",
            "system",
            "u",
            "up",
            "w",
            "wear",
            "west",
            "whisper",
            "yell"
        };

        // Canonical commands (no single-letter aliases) used for command listings/fallbacks
        private static final String[] CANONICAL_COMMANDS = new String[] {
            "cast",
            "look",
                "save",
            "spawn",
            "get",
            "drop",
            "equip",
            "remove",
            "inventory",
            "dbinfo",
            "say",
            "prompt",
            "quit",
            "north",
            "east",
            "south",
            "west",
            "up",
            "down",
            "chat",
            "yell",
            "whisper",
            "groupchat",
            "gmchat",
            "system",
            "motd",
            "help",
            "spells",
            "cflag"
        };

    public static Command parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        String resolved = resolveCommandName(cmd);
        if (resolved == null) return null;
        return new Command(resolved, args);
    }

    // Resolve a potentially abbreviated command name to a known command.
    // Exact matches win; otherwise the first command in alphabetical order
    // whose name starts with the given prefix is used.
    private static String resolveCommandName(String input) {
        if (input == null || input.isEmpty()) return null;
        // exact match first
        for (String c : KNOWN_COMMANDS) {
            if (c.equals(input)) return c;
        }
        // then prefix match, first in sorted list
        for (String c : KNOWN_COMMANDS) {
            if (c.startsWith(input)) return c;
        }
        return null;
    }

    public static String[] getCanonicalCommands() {
        return CANONICAL_COMMANDS.clone();
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
