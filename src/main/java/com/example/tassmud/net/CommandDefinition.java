package com.example.tassmud.net;

import java.util.Collections;
import java.util.List;

/**
 * Defines metadata for a single command in the game.
 */
public class CommandDefinition {
    
    public enum Category {
        INFORMATION("Information"),
        MOVEMENT("Movement"),
        COMMUNICATION("Communication"),
        ITEMS("Items & Equipment"),
        COMBAT("Combat & Skills"),
        GROUP("Group & Party"),
        SYSTEM("System"),
        GM("GM Commands");
        
        private final String displayName;
        
        Category(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    private final String name;
    private final String description;
    private final Category category;
    private final boolean gmOnly;
    private final boolean allowedInCombat;
    private final List<String> aliases;
    
    public CommandDefinition(String name, String description, Category category, boolean gmOnly, boolean allowedInCombat, List<String> aliases) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.gmOnly = gmOnly;
        this.allowedInCombat = allowedInCombat;
        this.aliases = aliases == null ? Collections.emptyList() : Collections.unmodifiableList(aliases);
    }
    
    public CommandDefinition(String name, String description, Category category, boolean gmOnly, List<String> aliases) {
        this(name, description, category, gmOnly, false, aliases);
    }
    
    public CommandDefinition(String name, String description, Category category, boolean gmOnly) {
        this(name, description, category, gmOnly, false, Collections.emptyList());
    }
    
    public CommandDefinition(String name, String description, Category category) {
        this(name, description, category, false, false, Collections.emptyList());
    }
    
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public boolean isGmOnly() { return gmOnly; }
    public boolean isAllowedInCombat() { return allowedInCombat; }
    public List<String> getAliases() { return aliases; }
    
    /**
     * Returns the display name for help listings (e.g., "north (n)" if has alias "n").
     */
    public String getDisplayName() {
        if (aliases.isEmpty()) {
            return name;
        }
        // Show first alias in parentheses
        return name + " (" + aliases.get(0) + ")";
    }
}
