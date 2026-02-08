package com.example.tassmud.net.commands;

import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.net.ClientHandler;

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
    public final ClientHandler handler;
    
    public CommandContext(
            Command cmd,
            String playerName,
            Integer characterId,
            Integer currentRoomId,
            CharacterRecord character,
            CharacterDAO dao,
            PrintWriter out,
            boolean isGm,
            boolean inCombat,
            ClientHandler handler
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
        this.handler = handler;
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

    /**
     * Returns the character record if present, otherwise prints an error and
     * returns {@code null}. Handlers that require a logged-in character can
     * call this at the top of their method and bail on {@code null}.
     */
    public CharacterRecord requireRecord() {
        if (character != null) return character;
        out.println("You must be logged in to do that.");
        return null;
    }

    /**
     * Returns the character record, requiring a non-null room. Prints an
     * appropriate error and returns {@code null} if either is missing.
     */
    public CharacterRecord requireRecordInRoom() {
        if (character == null) {
            out.println("You must be logged in to do that.");
            return null;
        }
        if (character.currentRoom == null) {
            out.println("You must be in a room to do that.");
            return null;
        }
        return character;
    }

    /**
     * Returns a fresh {@link CharacterRecord} from the database. Useful after
     * a handler has mutated state and needs the latest values.
     */
    public CharacterRecord freshRecord() {
        return dao.findByName(playerName);
    }

    /**
     * Returns characterId, resolving from the database if the context value is
     * null. The resolved value is NOT cached — the field is final.
     */
    public Integer resolveCharacterId() {
        if (characterId != null) return characterId;
        return dao.getCharacterIdByName(playerName);
    }
}
