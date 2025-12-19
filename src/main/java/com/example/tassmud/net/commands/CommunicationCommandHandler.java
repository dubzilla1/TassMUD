package com.example.tassmud.net.commands;

import java.util.Set;

/**
 * Handles communication commands (say, chat, yell, whisper, gmchat, groupchat)
 */
public class CommunicationCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED = Set.of("say","chat","yell","whisper","gmchat","groupchat");

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
