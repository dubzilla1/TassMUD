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
        registerCombat("inventory", "List items you are carrying", Category.INFORMATION, List.of("i"));
        registerCombat("skills", "List skills you have learned", Category.INFORMATION);
        registerCombat("spells", "List spells you have learned", Category.INFORMATION);
        register("consider", "Evaluate how dangerous a target would be to fight", Category.INFORMATION, List.of("con"));
        
        // ===== MOVEMENT =====
        // Movement NOT allowed in combat (use flee instead)
        register("north", "Move north", Category.MOVEMENT, List.of("n"));
        register("south", "Move south", Category.MOVEMENT, List.of("s"));
        register("east", "Move east", Category.MOVEMENT, List.of("e"));
        register("west", "Move west", Category.MOVEMENT, List.of("w"));
        register("up", "Move up", Category.MOVEMENT, List.of("u"));
        register("down", "Move down", Category.MOVEMENT, List.of("d"));
        register("recall", "Teleport back to the Mead-Gaard Inn (home base)", Category.MOVEMENT);
        registerCombat("look", "Look at your surroundings or examine something", Category.MOVEMENT, List.of("l"));
        register("open", "Open a door or exit", Category.MOVEMENT);
        register("close", "Close a door or exit", Category.MOVEMENT);
        
        // ===== STANCE =====
        // Stance changes NOT allowed in combat
        register("sit", "Sit down to rest (5x regen rate)", Category.MOVEMENT);
        register("sleep", "Go to sleep (10x regen rate)", Category.MOVEMENT, List.of("rest"));
        register("stand", "Stand up from sitting or sleeping", Category.MOVEMENT, List.of("wake"));
        
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
        register("sacrifice", "Sacrifice an item on the ground for 1 XP", Category.ITEMS, List.of("sac"));
        register("equip", "Equip an item from your inventory", Category.ITEMS, List.of("wear"));
        register("remove", "Remove an equipped item", Category.ITEMS, List.of("dequip"));
        registerCombat("quaff", "Drink a potion from your inventory", Category.ITEMS, List.of("drink"));
        registerCombat("use", "Use a magical item to cast its spell", Category.ITEMS);
        
        // ===== SHOP COMMANDS =====
        // Shop commands require a shopkeeper in the room
        register("list", "List items for sale from shopkeepers in the room", Category.ITEMS);
        register("buy", "Buy an item from a shopkeeper", Category.ITEMS);
        register("sell", "Sell an item to a shopkeeper", Category.ITEMS);
        
        // ===== COMBAT & SKILLS =====
        // All combat commands allowed in combat
        registerCombat("kill", "Attack a target and initiate combat", Category.COMBAT, List.of("k", "attack", "fight"));
        registerCombat("combat", "Display current combat status", Category.COMBAT);
        registerCombat("flee", "Attempt to escape from combat", Category.COMBAT);
        registerCombat("cast", "Cast a spell", Category.COMBAT);
        registerCombat("kick", "Deliver a powerful kick to your enemy", Category.COMBAT);
        registerCombat("bash", "Bash an enemy with your shield, stunning them", Category.COMBAT);
        registerCombat("heroic", "Execute a Heroic Strike, granting guaranteed critical hits", Category.COMBAT);
        register("infuse", "Infuse your staff with arcane energy for ranged attacks", Category.COMBAT);
        register("hide", "Become invisible to others", Category.COMBAT);
        register("visible", "Become visible again, dropping invisibility", Category.COMBAT, List.of("unhide"));
        
        // ===== SYSTEM =====
        // save, quit allowed in combat; prompt, motd not critical
        registerCombat("save", "Save your character", Category.SYSTEM);
        register("prompt", "Configure your prompt display", Category.SYSTEM);
        register("motd", "Display the message of the day", Category.SYSTEM);
        registerCombat("quit", "Exit the game", Category.SYSTEM);
        register("train", "Spend talent points to improve abilities, skills, or spells", Category.SYSTEM);
        register("autoloot", "Toggle automatic looting of items from corpses", Category.SYSTEM);
        register("autogold", "Toggle automatic looting of gold from corpses", Category.SYSTEM);
        register("autosac", "Toggle automatic sacrifice of empty corpses (requires autoloot and autogold)", Category.SYSTEM);
        register("autojunk", "Toggle automatic sale of junk", Category.SYSTEM);
        registerCombat("autoflee", "Configure automatic flee threshold (0-100)", Category.SYSTEM);
        
        // ===== GM COMMANDS =====
        // All GM commands allowed in combat (GMs need full control)
        registerGm("cflag", "Manage per-character key/value flags");
        registerGm("cset", "Set a character attribute value");
        registerGm("cskill", "Grant a skill to a character");
        registerGm("cspell", "Grant a spell to a character");
        registerGm("dbinfo", "Inspect database table schemas");
        registerGm("debug", "Toggle debug channel output");
        registerGm("genmap", "Generate ASCII map for an area");
        registerGm("gmchat", "Send a message on the GM channel");
        registerGm("gminvis", "Toggle perfect GM invisibility (invisible to all non-GMs)");
        registerGm("goto", "Teleport to a room by ID");
        registerGm("ifind", "Find all instances of an item template");
        registerGm("ilist", "Search item templates by name");
        registerGm("mlist", "Search mobile templates by name");
        registerGm("mfind", "Find mobile instances by template ID");
        registerGm("istat", "Show detailed stats of an inventory item");
        registerGm("mstat", "Show detailed stats of a mobile in the room");
        registerGm("peace", "End all combat in the current room");
        registerGm("promote", "Level up a character");
        registerGm("restore", "Restore a character's HP/MP/MV to full");
        registerGm("slay", "Instantly kill a target mob");
        registerGm("spawn", "Create item or mob instances");
        registerGm("seedtemplates", "Seed item & mob templates into the running server (GM only)");
        registerGm("system", "Send a system-wide announcement");
        registerGm("checktemplate", "Check the validity of a template");
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
