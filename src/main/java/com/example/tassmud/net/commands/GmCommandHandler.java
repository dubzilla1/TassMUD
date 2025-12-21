package com.example.tassmud.net.commands;

import java.util.Set;

/**
 * Delegates GM commands to ClientHandler.handleGmCommand
 * NOTE: Only list commands that are actually implemented in handleGmCommand().
 * Commands like debug, gmchat, gminvis, peace, promote, restore, slay, spawn, system
 * are still in the main switch statement.
 */
public class GmCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED = Set.of(
        "cflag", "cset", "cskill", "cspell", "dbinfo", "genmap", "goto", "ifind", "ilist", "istat", "mstat"
    );

    @Override
    public boolean handle(CommandContext ctx) {
        ctx.handler.handleGmCommand(ctx.cmd, ctx.playerName, ctx.character, ctx.dao);
        return true;
    }

    @Override
    public boolean supports(String commandName) {
        return SUPPORTED.contains(commandName);
    }
}
