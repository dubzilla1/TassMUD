package com.example.tassmud.net.commands;

import com.example.tassmud.net.CommandDefinition;
import com.example.tassmud.net.CommandRegistry;

import java.util.*;

/**
 * Dispatches commands to their appropriate category handlers.
 * This breaks up the monolithic switch statement in ClientHandler into
 * manageable, category-specific handler classes.
 */
public class CommandDispatcher {
    
    private static final Map<CommandDefinition.Category, CommandHandler> handlers = new EnumMap<>(CommandDefinition.Category.class);
    private static boolean initialized = false;
    
    /**
     * Initialize all command handlers. Called once at startup.
     */
    public static synchronized void initialize() {
        if (initialized) return;
        
        // Register category handlers
        handlers.put(CommandDefinition.Category.ITEMS, new ItemCommandHandler());
        handlers.put(CommandDefinition.Category.MOVEMENT, new MovementCommandHandler());
        handlers.put(CommandDefinition.Category.INFORMATION, new InformationCommandHandler());
        handlers.put(CommandDefinition.Category.COMMUNICATION, new CommunicationCommandHandler());
        handlers.put(CommandDefinition.Category.COMBAT, new CombatCommandHandler());
        handlers.put(CommandDefinition.Category.GROUP, new GroupCommandHandler());
        handlers.put(CommandDefinition.Category.SYSTEM, new SystemCommandHandler());
        handlers.put(CommandDefinition.Category.GM, new GmCommandHandler());
       
        initialized = true;
    }
    
    /**
     * Attempt to dispatch a command to its category handler.
     * 
     * @param ctx the command context
     * @return true if the command was handled by a category handler, false if it should
     *         fall through to the legacy switch statement in ClientHandler
     */
    public static boolean dispatch(CommandContext ctx) {
        if (!initialized) initialize();
        
        String cmdName = ctx.getCommandName();
        
        // Check for paralysis - block most commands except a few exceptions
        if (ctx.characterId != null && com.example.tassmud.effect.ParalyzedEffect.isParalyzed(ctx.characterId)) {
            // Allow only basic info commands when paralyzed
            if (!isAllowedWhileParalyzed(cmdName)) {
                ctx.send("\u001B[31mYou are paralyzed and cannot move!\u001B[0m");
                return true; // Handled (blocked)
            }
        }
        
        // Look up the command's category
        CommandDefinition def = CommandRegistry.getCommand(cmdName);
        if (def == null) {
            return false; // Unknown command, let ClientHandler handle it
        }
        
        // Get the handler for this category
        CommandHandler handler = handlers.get(def.getCategory());
        if (handler == null) {
            return false; // No handler registered for this category yet
        }
        
        // Check if this specific handler supports this command
        if (!handler.supports(cmdName)) {
            return false; // Handler doesn't support this command yet
        }
        
        // Execute the command
        return handler.handle(ctx);
    }
    
    /**
     * Check if a command is allowed while paralyzed.
     * Only basic informational commands should be allowed.
     */
    private static boolean isAllowedWhileParalyzed(String cmdName) {
        // Allow basic status/info commands
        return switch (cmdName) {
            case "score", "sc", "inventory", "inv", "i", "equipment", "eq",
                 "look", "l", "time", "who", "help", "affects", "aff",
                 "prompt", "quit", "save", "effects", "combat" -> true;
            default -> false;
        };
    }
    
    /**
     * Register a custom handler for a category. Used for testing or extensions.
     */
    public static void registerHandler(CommandDefinition.Category category, CommandHandler handler) {
        handlers.put(category, handler);
    }
}
