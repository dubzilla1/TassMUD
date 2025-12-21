package com.example.tassmud.net.commands;

import java.util.Set;

/**
 * Handles communication commands (chat, yell, whisper, gmchat)
 * NOTE: Only list commands that are actually implemented in handleCommunication().
 * Commands like say and groupchat are still in the main switch statement.
 */
public class CommunicationCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED = Set.of("chat", "yell", "whisper", "gmchat");

    @Override
    public boolean handle(CommandContext ctx) {
        ctx.handler.handleCommunication(ctx.cmd, ctx.playerName, ctx.character, ctx.dao);
        return true;
    }

    @Override
    public boolean supports(String commandName) {
        return SUPPORTED.contains(commandName);
    }
}
