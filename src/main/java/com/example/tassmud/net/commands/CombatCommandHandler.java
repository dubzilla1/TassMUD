package com.example.tassmud.net.commands;

import java.util.Set;

/**
 * Handles combat-related commands by delegating to ClientHandler.
 * NOTE: Only list commands that are actually implemented in handleCombatCommand().
 * Commands like kill, hide, cast are still in the main switch statement.
 */
public class CombatCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED = Set.of(
        "combat", "flee", "kick", "bash", "heroic", "infuse"
    );

    @Override
    public boolean handle(CommandContext ctx) {
        ctx.handler.handleCombatCommand(ctx.cmd, ctx.playerName, ctx.character, ctx.dao);
        return true;
    }

    @Override
    public boolean supports(String commandName) {
        return SUPPORTED.contains(commandName);
    }
}
