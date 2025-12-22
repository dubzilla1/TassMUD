package com.example.tassmud.net.commands;

import java.io.PrintWriter;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.tassmud.model.Room;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterDAO;

/**
 * Handles communication commands (say, chat, yell, whisper, groupchat)
 */
public class CommunicationCommandHandler implements CommandHandler {

private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.COMMUNICATION).stream()
            .map(cmd -> cmd.getName())
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean supports(String commandName) {
        return SUPPORTED_COMMANDS.contains(commandName);
    }

    @Override
    public boolean handle(CommandContext ctx) {
        String cmdName = ctx.getCommandName();
        
        switch (cmdName) {
            case "say":
            case "chat":
            case "yell":
            case "whisper":
            case "groupchat":
                return handleCommunication(ctx);
            default: return false;
        }
    }

    public static boolean handleCommunication(CommandContext ctx) {
        try {
            PrintWriter out = ctx.out;
            CharacterDAO dao = ctx.dao;
            CharacterDAO.CharacterRecord rec = ctx.character;
            Command cmd = ctx.cmd;
            String cmdName = ctx.getCommandName().toLowerCase();
            switch (cmdName) {
                case "say": {
                    String text = cmd.getArgs();
                    if (rec == null || rec.currentRoom == null) { out.println("You are nowhere to say that."); return true; }
                    Integer roomId = rec.currentRoom;
                    if (roomId == null) return true;
                    for (ClientHandler s : ClientHandler.sessions) {
                        if (s.currentRoomId != null && s.currentRoomId.equals(roomId)) {
                            if (s == ctx.handler) s.sendRaw("You say: " + text);
                            else s.sendRaw(ctx.handler.playerName + " says: " + text);
                        }
                    }
                    return true;
                }
                case "chat": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: chat <message>"); return true; }
                    ClientHandler.broadcastAll("[chat] " + ctx.handler.playerName + ": " + t);
                    return true;
                }
                case "yell": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: yell <message>"); return true; }
                    Integer roomId = rec != null ? rec.currentRoom : null;
                    if (roomId == null) { out.println("You are nowhere to yell from."); return true; }
                    Room roomObj = dao.getRoomById(roomId);
                    if (roomObj == null) { out.println("You are nowhere to yell from."); return true; }
                    int areaId = roomObj.getAreaId();
                    ClientHandler.broadcastArea(dao, areaId, "[yell] " + ctx.handler.playerName + ": " + t);
                    return true;
                }
                case "whisper": {
                    String args = cmd.getArgs();
                    if (args == null || args.trim().isEmpty()) { out.println("Usage: whisper <target> <message>"); return true; }
                    String[] parts = args.trim().split("\\s+", 2);
                    if (parts.length < 2) { out.println("Usage: whisper <target> <message>"); return true; }
                    String target = parts[0];
                    String msg = parts[1];
                    ClientHandler t = ClientHandler.nameToSession.get(target.toLowerCase());
                    if (t == null) { out.println("No such player online: " + target); return true; }
                    t.sendRaw("[whisper] " + ctx.handler.playerName + " -> you: " + msg);
                    ctx.handler.sendRaw("[whisper] you -> " + target + ": " + msg);
                    return true;
                }
                case "gmchat": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: gmchat <message>"); return true; }
                    ClientHandler.gmBroadcast(dao, ctx.handler.playerName, t);
                    return true;
                }
                case "groupchat": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: groupchat <message>"); return true; }
                    ClientHandler.groupBroadcast(dao, ctx.handler.playerName, t);
                    return true;
                }
                default:
                    // Not a communication command; fall through to caller
                    return true;
            }
        } catch (Exception ignored) { return false;}
    }
}
