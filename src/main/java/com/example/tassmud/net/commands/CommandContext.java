package com.example.tassmud.net.commands;

import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;

import java.io.PrintWriter;

/**
 * Context object passed to command handlers containing all the state needed
 * to execute a command. This decouples command handlers from ClientHandler internals.
 */
public class CommandContext {
    public final Command cmd;
    public final String playerName;
    public final Integer characterId;
    public final Integer currentRoomId;
    public final CharacterRecord character;
    public final CharacterDAO dao;
    public final PrintWriter out;
    public final boolean isGm;
    public final boolean inCombat;
    
    public CommandContext(
            Command cmd,
            String playerName,
            Integer characterId,
            Integer currentRoomId,
            CharacterRecord character,
            CharacterDAO dao,
            PrintWriter out,
            boolean isGm,
            boolean inCombat
    ) {
        this.cmd = cmd;
        this.playerName = playerName;
        this.characterId = characterId;
        this.currentRoomId = currentRoomId;
        this.character = character;
        this.dao = dao;
        this.out = out;
        this.isGm = isGm;
        this.inCombat = inCombat;
    }
    
    /**
     * Get the command arguments as a string.
     */
    public String getArgs() {
        return cmd.getArgs();
    }
    
    /**
     * Get the canonical command name.
     */
    public String getCommandName() {
        return cmd.getName();
    }
    
    /**
     * Send a message to the player.
     */
    public void send(String message) {
        out.println(message);
    }
}
