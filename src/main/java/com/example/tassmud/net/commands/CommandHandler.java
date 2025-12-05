package com.example.tassmud.net.commands;

/**
 * Interface for command handlers. Each handler processes one or more related commands.
 */
public interface CommandHandler {
    
    /**
     * Execute the command.
     * 
     * @param ctx the command context containing all necessary state
     * @return true if the command was handled, false if not recognized
     */
    boolean handle(CommandContext ctx);
    
    /**
     * Check if this handler can process the given command name.
     * 
     * @param commandName the canonical command name
     * @return true if this handler supports the command
     */
    boolean supports(String commandName);
}
