package com.example.tassmud.net;

import com.example.tassmud.net.CommandDefinition.Category;

import java.util.*;

/**
 * Central registry of all game commands with their metadata.
 * This is the single source of truth for command names, aliases, categories, and permissions.
 */
public class CommandRegistry {
    
    private static final List<CommandDefinition> COMMANDS = new ArrayList<>();
    private static final Map<String, CommandDefinition> BY_NAME = new HashMap<>();
    private static final Map<String, String> ALIAS_TO_CANONICAL = new HashMap<>();
    
    static {
        // ===== INFORMATION =====
        // help, score, look, inventory, spells allowed in combat for situational awareness
        registerCombat("help", "Display available commands or help on a specific command", Category.INFORMATION);
        registerCombat("score", "Display your character sheet and statistics", Category.INFORMATION, List.of("stats"));
        register("who", "List all players currently online", Category.INFORMATION);
        registerCombat("look", "Look at your surroundings or examine something", Category.INFORMATION, List.of("l"));
        registerCombat("inventory", "List items you are carrying", Category.INFORMATION, List.of("i"));
        registerCombat("skills", "List skills you have learned", Category.INFORMATION);
        registerCombat("spells", "List spells you have learned", Category.INFORMATION);
        
        // ===== MOVEMENT =====
        // Movement NOT allowed in combat (use flee instead)
        register("north", "Move north", Category.MOVEMENT, List.of("n"));
        register("south", "Move south", Category.MOVEMENT, List.of("s"));
        register("east", "Move east", Category.MOVEMENT, List.of("e"));
        register("west", "Move west", Category.MOVEMENT, List.of("w"));
        register("up", "Move up", Category.MOVEMENT, List.of("u"));
        register("down", "Move down", Category.MOVEMENT, List.of("d"));
        
        // ===== COMMUNICATION =====
        // All communication allowed in combat
        registerCombat("say", "Say something to others in the room", Category.COMMUNICATION);
        registerCombat("chat", "Send a message to the global chat channel", Category.COMMUNICATION);
        registerCombat("yell", "Yell something loudly", Category.COMMUNICATION);
        registerCombat("whisper", "Send a private message to another player", Category.COMMUNICATION);
        registerCombat("groupchat", "Send a message to your group", Category.COMMUNICATION);
        
        // ===== ITEMS & EQUIPMENT =====
        // Items NOT allowed in combat (can't equip/drop mid-fight)
        register("get", "Pick up an item from the room", Category.ITEMS, List.of("pickup"));
        register("drop", "Drop an item from your inventory", Category.ITEMS);
        register("put", "Put an item into a container", Category.ITEMS);
        register("equip", "Equip an item from your inventory", Category.ITEMS, List.of("wear"));
        register("remove", "Remove an equipped item", Category.ITEMS, List.of("dequip"));
        
        // ===== COMBAT & SKILLS =====
        // All combat commands allowed in combat
        registerCombat("kill", "Attack a target and initiate combat", Category.COMBAT, List.of("k", "attack", "fight"));
        registerCombat("combat", "Display current combat status", Category.COMBAT);
        registerCombat("flee", "Attempt to escape from combat", Category.COMBAT);
        registerCombat("cast", "Cast a spell", Category.COMBAT);
        registerCombat("kick", "Deliver a powerful kick to your enemy", Category.COMBAT);
        registerCombat("bash", "Bash an enemy with your shield, stunning them", Category.COMBAT);
        
        // ===== SYSTEM =====
        // save, quit allowed in combat; prompt, motd not critical
        registerCombat("save", "Save your character", Category.SYSTEM);
        register("prompt", "Configure your prompt display", Category.SYSTEM);
        register("motd", "Display the message of the day", Category.SYSTEM);
        registerCombat("quit", "Exit the game", Category.SYSTEM);
        
        // ===== GM COMMANDS =====
        // All GM commands allowed in combat (GMs need full control)
        registerGm("cflag", "Manage per-character key/value flags");
        registerGm("cskill", "Grant a skill to a character");
        registerGm("cspell", "Grant a spell to a character");
        registerGm("dbinfo", "Inspect database table schemas");
        registerGm("debug", "Toggle debug channel output");
        registerGm("gmchat", "Send a message on the GM channel");
        registerGm("goto", "Teleport to a room by ID");
        registerGm("ifind", "Find all instances of an item template");
        registerGm("ilist", "Search item templates by name");
        registerGm("peace", "End all combat in the current room");
        registerGm("promote", "Level up a character");
        registerGm("restore", "Restore a character's HP/MP/MV to full");
        registerGm("spawn", "Create item or mob instances");
        registerGm("system", "Send a system-wide announcement");
    }
    
    private static void register(String name, String description, Category category) {
        register(name, description, category, Collections.emptyList());
    }
    
    private static void register(String name, String description, Category category, List<String> aliases) {
        CommandDefinition def = new CommandDefinition(name, description, category, false, false, aliases);
        COMMANDS.add(def);
        BY_NAME.put(name, def);
        for (String alias : aliases) {
            ALIAS_TO_CANONICAL.put(alias, name);
            BY_NAME.put(alias, def);
        }
    }
    
    /**
     * Register a command that is allowed during combat.
     */
    private static void registerCombat(String name, String description, Category category) {
        registerCombat(name, description, category, Collections.emptyList());
    }
    
    /**
     * Register a command that is allowed during combat.
     */
    private static void registerCombat(String name, String description, Category category, List<String> aliases) {
        CommandDefinition def = new CommandDefinition(name, description, category, false, true, aliases);
        COMMANDS.add(def);
        BY_NAME.put(name, def);
        for (String alias : aliases) {
            ALIAS_TO_CANONICAL.put(alias, name);
            BY_NAME.put(alias, def);
        }
    }
    
    private static void registerGm(String name, String description) {
        registerGm(name, description, Collections.emptyList());
    }
    
    private static void registerGm(String name, String description, List<String> aliases) {
        // GM commands are always allowed in combat
        CommandDefinition def = new CommandDefinition(name, description, Category.GM, true, true, aliases);
        COMMANDS.add(def);
        BY_NAME.put(name, def);
        for (String alias : aliases) {
            ALIAS_TO_CANONICAL.put(alias, name);
            BY_NAME.put(alias, def);
        }
    }
    
    /**
     * Get all registered commands.
     */
    public static List<CommandDefinition> getAllCommands() {
        return Collections.unmodifiableList(COMMANDS);
    }
    
    /**
     * Get a command definition by name or alias.
     */
    public static CommandDefinition getCommand(String nameOrAlias) {
        return BY_NAME.get(nameOrAlias.toLowerCase());
    }
    
    /**
     * Get the canonical name for a command (resolves aliases).
     */
    public static String getCanonicalName(String nameOrAlias) {
        String lower = nameOrAlias.toLowerCase();
        if (ALIAS_TO_CANONICAL.containsKey(lower)) {
            return ALIAS_TO_CANONICAL.get(lower);
        }
        if (BY_NAME.containsKey(lower)) {
            return lower;
        }
        return null;
    }
    
    /**
     * Check if a command name or alias exists.
     */
    public static boolean exists(String nameOrAlias) {
        return BY_NAME.containsKey(nameOrAlias.toLowerCase());
    }
    
    /**
     * Get all commands in a specific category.
     */
    public static List<CommandDefinition> getCommandsByCategory(Category category) {
        List<CommandDefinition> result = new ArrayList<>();
        for (CommandDefinition cmd : COMMANDS) {
            if (cmd.getCategory() == category) {
                result.add(cmd);
            }
        }
        return result;
    }
    
    /**
     * Get all command names and aliases, sorted alphabetically.
     * Used for prefix matching in command parser.
     */
    public static String[] getAllNamesAndAliases() {
        Set<String> names = new TreeSet<>(BY_NAME.keySet());
        return names.toArray(new String[0]);
    }
    
    /**
     * Get all canonical command names (no aliases), sorted alphabetically.
     */
    public static String[] getCanonicalNames() {
        Set<String> names = new TreeSet<>();
        for (CommandDefinition cmd : COMMANDS) {
            names.add(cmd.getName());
        }
        return names.toArray(new String[0]);
    }
    
    /**
     * Resolve a potentially abbreviated command name to its canonical form.
     * Exact matches win; otherwise the first alphabetical prefix match is used.
     */
    public static String resolveCommand(String input) {
        if (input == null || input.isEmpty()) return null;
        String lower = input.toLowerCase();
        
        // Exact match first
        if (BY_NAME.containsKey(lower)) {
            // Return canonical name, not alias
            String canonical = ALIAS_TO_CANONICAL.get(lower);
            return canonical != null ? canonical : lower;
        }
        
        // Prefix match - find first alphabetically
        String[] allNames = getAllNamesAndAliases();
        for (String name : allNames) {
            if (name.startsWith(lower)) {
                // Return canonical name
                String canonical = ALIAS_TO_CANONICAL.get(name);
                return canonical != null ? canonical : name;
            }
        }
        
        return null;
    }
}
