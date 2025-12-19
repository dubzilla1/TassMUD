package com.example.tassmud.net.commands;

import java.util.Set;

/**
 * Delegates GM commands to ClientHandler.handleGmCommand
 */
public class GmCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED = Set.of(
        "cflag","cset","cskill","cspell","dbinfo","debug","genmap","gmchat","gminvis","goto","ifind","ilist","istat","mstat","peace","promote","restore","slay","spawn","system"
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
