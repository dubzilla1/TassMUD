package com.example.tassmud.net.commands;

import com.example.tassmud.net.CommandDefinition;
import com.example.tassmud.net.CommandDefinition.Category;
import java.util.Set;

/**
 * Handles movement-related commands by delegating to ClientHandler.
 * NOTE: Only list commands that are actually implemented in handleLookAndMovement().
 * Commands like recall, sit, sleep, rest, stand, wake, prompt are still in the main switch.
 */
public class MovementCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED = Set.of(
        "north", "south", "east", "west", "up", "down",
        "n", "s", "e", "w", "u", "d",
        "look"
    );

    @Override
    public boolean handle(CommandContext ctx) {
        // delegate to existing ClientHandler method
        ctx.handler.handleLookAndMovement(ctx.cmd, ctx.playerName, ctx.character, ctx.dao);
        return true;
    }

    @Override
    public boolean supports(String commandName) {
        return SUPPORTED.contains(commandName);
    }
}
