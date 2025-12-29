package com.example.tassmud.net;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Area;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.net.commands.CommandDispatcher;
import com.example.tassmud.net.commands.MovementCommandHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.GameClock;
import com.example.tassmud.util.PasswordUtil;
import com.example.tassmud.util.RegenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;

/**
 * Per-client handler for the telnet server.
 * Reads lines, parses simple commands, and responds.
 */
public class ClientHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);
    private final Socket socket;
    private final GameClock gameClock;
    // Registry of active sessions
    public static final Set<ClientHandler> sessions = ConcurrentHashMap.newKeySet();
    public static final ConcurrentHashMap<String, ClientHandler> nameToSession = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, ClientHandler> charIdToSession = new ConcurrentHashMap<>();

    // Per-session state used for routing messages
    public volatile PrintWriter out = null;
    public volatile String playerName = null;
    public volatile Integer currentRoomId = null;
    private volatile Integer characterId = null;
    private volatile String promptFormat = "<%h/%Hhp %m/%Mmp %v/%Vmv> ";
    public volatile boolean debugChannelEnabled = false;  // GM-only debug output
    public volatile boolean gmInvisible = false;  // GM-only perfect invisibility
    
    public Socket getSocket() {
        return this.socket;
    }

    public String getPromptFormat() {
        return this.promptFormat;
    }

    public void setPromptFormat(String promptFormat) {
        this.promptFormat = promptFormat;
    }

    public ClientHandler(Socket socket, GameClock gameClock) {
        this.socket = socket;
        this.gameClock = gameClock;
    }

    private void registerSession() {
        sessions.add(this);
        if (playerName != null) nameToSession.put(playerName.toLowerCase(), this);
        if (characterId != null) charIdToSession.put(characterId, this);
    }

    private void unregisterSession() {
        sessions.remove(this);
        if (playerName != null) nameToSession.remove(playerName.toLowerCase());
        if (characterId != null) charIdToSession.remove(characterId);
    }

    public void sendRaw(String msg) {
        try {
            PrintWriter o = out;
            if (o != null) {
                o.println(msg);
                o.flush();
            }
        } catch (Exception ignored) {}
    }

    public static void broadcastAll(String msg) {
        for (ClientHandler s : sessions) s.sendRaw(msg);
    }

    @SuppressWarnings("unused") // Utility method for future chat/communication features
    private static void broadcastRoom(Integer roomId, String msg) {
        if (roomId == null) return;
        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId)) s.sendRaw(msg);
        }
    }

    public static void broadcastArea(CharacterDAO dao, Integer areaId, String msg) {
        if (areaId == null) return;
        for (ClientHandler s : sessions) {
            Integer rId = s.currentRoomId;
            if (rId == null) continue;
            Room r = dao.getRoomById(rId);
            if (r != null && Integer.valueOf(r.getAreaId()).equals(areaId)) {
                s.sendRaw(msg);
            }
        }
    }

    @SuppressWarnings("unused") // Utility method for future chat/communication features
    private static void whisperTo(String targetName, String fromName, String msg) {
        if (targetName == null) return;
        ClientHandler t = nameToSession.get(targetName.toLowerCase());
        if (t != null) {
            t.sendRaw("[whisper] " + fromName + " -> you: " + msg);
        }
    }

    public static void gmBroadcast(CharacterDAO dao, String fromName, String msg) {
        for (ClientHandler s : sessions) {
            String pn = s.playerName;
            if (pn == null) continue;
            try {
                if (dao.isCharacterFlagTrueByName(pn, "is_gm")) {
                    s.sendRaw("[gm] " + fromName + ": " + msg);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Handle a local 'say' command: broadcast to everyone in the same room,
     * formatting the sender's own view as "You say: ..." and others as
     * "<name> says: ...".
     */

    /**
     * Send a debug message to this session (only if debug channel is enabled).
     * Use this for verbose debugging output that GMs can toggle on/off.
     */
    public void sendDebug(String msg) {
        if (debugChannelEnabled && out != null) {
            out.println("[DEBUG] " + msg);
        }
    }
    
    /**
     * Check if this session has the debug channel enabled.
     */
    public boolean isDebugEnabled() {
        return debugChannelEnabled;
    }
    
    // === PUBLIC MESSAGING API FOR COMBAT SYSTEM ===
    
    /**
     * Send a message to all players in a specific room.
     * Called by combat system to broadcast combat messages.
     */
    public static void broadcastRoomMessage(Integer roomId, String msg) {
        if (roomId == null) return;
        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId)) s.sendRaw(msg);
        }
    }
    
    /**
     * Send a message to a specific character by their character ID.
     * Called by combat system for targeted messages.
     */
    public static void sendToCharacter(Integer characterId, String msg) {
        if (characterId == null) return;
        ClientHandler handler = charIdToSession.get(characterId);
        if (handler != null) {
            handler.sendRaw(msg);
        }
    }
    
    /**
     * Send a debug message to all players in a room who have debug channel enabled.
     * Used for detailed combat roll information.
     */
    public static void sendDebugToRoom(Integer roomId, String msg) {
        if (roomId == null || msg == null) return;
        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId) && s.debugChannelEnabled) {
                s.sendRaw("[DEBUG] " + msg);
            }
        }
    }
    
    /**
     * Send a debug message to a specific character if they have debug channel enabled.
     * Used for detailed debugging output for items, effects, etc.
     */
    public static void sendDebugToCharacter(Integer characterId, String msg) {
        if (characterId == null || msg == null) return;
        ClientHandler handler = charIdToSession.get(characterId);
        if (handler != null && handler.debugChannelEnabled) {
            handler.sendRaw("[DEBUG] " + msg);
        }
    }
    
    // === ROOM ANNOUNCEMENT SYSTEM ===
    
    /**
     * Announce a message to all players in a room, excluding specified characters.
     * This is the foundation for arrival/departure notifications and supports
     * visibility filtering (invisibility, hiding, sneaking, etc.).
     * 
     * @param roomId the room to announce in
     * @param msg the message to send
     * @param excludeCharacterId character ID to exclude from receiving the message (usually the mover)
     * @param isVisible whether the actor is visible - if false, only players who can see invisible will get the message
     */
    public static void roomAnnounce(Integer roomId, String msg, Integer excludeCharacterId, boolean isVisible) {
        if (roomId == null || msg == null || msg.isEmpty()) return;

        // Determine whether this announcement looks like an arrival/departure
        String lower = msg.toLowerCase();
        boolean isArrivalOrDeparture = lower.contains("arrive") || lower.contains("leave") || lower.contains("flees") || lower.contains("flee") || lower.contains("disappear") || lower.contains("vanish");

        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId)) {
                // Skip the excluded character (usually the mover themselves)
                if (excludeCharacterId != null && excludeCharacterId.equals(s.characterId)) {
                    continue;
                }

                // Suppress arrival/departure lines for sleeping players
                if (isArrivalOrDeparture && s.characterId != null) {
                    try {
                        Stance stance = RegenerationService.getInstance().getPlayerStance(s.characterId);
                        if (stance == Stance.SLEEPING) {
                            continue; // don't wake sleeping players with arrival/departure noise
                        }
                    } catch (Exception ignored) {}
                }

                // If the actor is invisible, check if this player can see invisible
                if (!isVisible) {
                    boolean canSeeInvis = com.example.tassmud.effect.EffectRegistry.canSeeInvisible(s.characterId);
                    if (!canSeeInvis) {
                        continue; // Can't see the invisible actor
                    }
                    // Add (INVIS) indicator to the message for those who can see
                    s.sendRaw(msg + " (INVIS)");
                } else {
                    s.sendRaw(msg);
                }

                // If this was an arrival/departure style announcement, send a prompt
                // so the user's client shows the prompt again (useful for telnet-like clients).
                if (isArrivalOrDeparture) {
                    try {
                        s.sendPrompt();
                    } catch (Exception ignored) {}
                }
            }
        }
    }
    
    /**
     * Simplified room announce that excludes no one and assumes visibility.
     */
    public static void roomAnnounce(Integer roomId, String msg) {
        roomAnnounce(roomId, msg, null, true);
    }
    
    /**
     * Room announce that automatically checks the actor's invisibility status.
     * Use this for player actions where we want to respect their invisibility.
     */
    public static void roomAnnounceFromActor(Integer roomId, String msg, Integer actorCharacterId) {
        if (roomId == null || msg == null || msg.isEmpty()) return;
        boolean isVisible = !com.example.tassmud.effect.EffectRegistry.isInvisible(actorCharacterId);
        roomAnnounce(roomId, msg, actorCharacterId, isVisible);
    }
    
    /**
     * Get the opposite direction for arrival messages.
     * e.g., if someone went "north", observers see them arrive "from the south"
     */
    private static String getOppositeDirection(String direction) {
        if (direction == null) return null;
        switch (direction.toLowerCase()) {
            case "north": return "south";
            case "south": return "north";
            case "east": return "west";
            case "west": return "east";
            case "up": return "below";
            case "down": return "above";
            default: return null;
        }
    }
    
    /**
     * Generate an arrival message for a character entering a room.
     * @param name the name of the arriving character
     * @param fromDirection the direction they came from (null for teleport/magical arrival)
     */
    public static String makeArrivalMessage(String name, String fromDirection) {
        if (fromDirection == null) {
            return name + " arrives from out of thin air.";
        }
        String opposite = getOppositeDirection(fromDirection);
        if (opposite == null) {
            return name + " arrives from out of thin air.";
        }
        if (opposite.equals("above") || opposite.equals("below")) {
            return name + " arrives from " + opposite + ".";
        }
        return name + " arrives from the " + opposite + ".";
    }
    
    /**
     * Generate a departure message for a character leaving a room.
     * @param name the name of the departing character
     * @param toDirection the direction they are going (null for teleport/magical departure)
     */
    public static String makeDepartureMessage(String name, String toDirection) {
        if (toDirection == null) {
            return name + " disappears in a puff of smoke.";
        }
        if (toDirection.equalsIgnoreCase("up") || toDirection.equalsIgnoreCase("down")) {
            return name + " leaves " + toDirection.toLowerCase() + ".";
        }
        return name + " leaves to the " + toDirection.toLowerCase() + ".";
    }
    
    /**
     * Send a formatted prompt to a specific character by their character ID.
     * Called by combat system after each round to let players know they can input commands.
     */
    public static void sendPromptToCharacter(Integer characterId) {
        if (characterId == null) return;
        ClientHandler handler = charIdToSession.get(characterId);
        if (handler != null) {
            handler.sendPrompt();
        }
    }
    
    /**
     * Send prompts to all players in a specific room.
     * Called when combat ends in a room.
     */
    public static void sendPromptsToRoom(Integer roomId) {
        if (roomId == null) return;
        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId)) {
                s.sendPrompt();
            }
        }
    }
    
   
    /**
     * Called by CombatManager when a player dies.
     * Sets the player's stance to SLEEPING and updates their current room.
     * @param characterId the character ID
     */
    public static void handlePlayerDeathStance(Integer characterId) {
        if (characterId == null) return;
        
        ClientHandler handler = charIdToSession.get(characterId);
        if (handler == null) return;
        
        // Update the handler's current room to the recall point
        handler.currentRoomId = 3041;
        
        // Set the player's stance to SLEEPING via RegenerationService
        RegenerationService.getInstance().setPlayerStance(characterId, Stance.SLEEPING);
        
        // Send the prompt (which will show their new low stats)
        handler.sendPrompt();
    }
    
    /**
     * Get a ClientHandler by character ID.
     * @param characterId the character ID
     * @return the ClientHandler or null if not found
     */
    public static ClientHandler getHandlerByCharacterId(Integer characterId) {
        if (characterId == null) return null;
        return charIdToSession.get(characterId);
    }
    
    /**
     * Get the current room ID for this handler.
     * @return the room ID or null
     */
    public Integer getCurrentRoomId() {
        return this.currentRoomId;
    }
    
    /**
     * Build a Character object from a CharacterRecord for use in combat.
     * @param rec the character record from the database
     * @param characterId the character ID
     * @return a Character object suitable for combat
     */
    public static GameCharacter buildCharacterForCombat(CharacterDAO.CharacterRecord rec, Integer characterId) {
        if (rec == null) return null;
        
        GameCharacter playerChar = new GameCharacter(
            rec.name, rec.age, rec.description,
            rec.hpMax, rec.hpCur, rec.mpMax, rec.mpCur, rec.mvMax, rec.mvCur,
            rec.currentRoom,
            rec.str, rec.dex, rec.con, rec.intel, rec.wis, rec.cha,
            rec.getArmorTotal(), rec.getFortitudeTotal(), 
            rec.getReflexTotal(), rec.getWillTotal()
        );
        
        // Load persisted modifiers for this character (if any) so they apply in combat
        if (characterId != null) {
            CharacterDAO dao = new CharacterDAO();
            java.util.List<com.example.tassmud.model.Modifier> mods = dao.getModifiersForCharacter(characterId);
            for (com.example.tassmud.model.Modifier m : mods) {
                playerChar.addModifier(m);
            }
        }
        
        return playerChar;
    }

    /**
     * Send the formatted prompt to this client.
     */
    private void sendPrompt() {
        try {
            PrintWriter o = out;
            if (o != null && playerName != null) {
                CharacterDAO dao = new CharacterDAO();
                CharacterRecord rec = dao.findByName(playerName);
                String prompt = formatPrompt(promptFormat, rec, dao);
                o.println();  // blank line for visual separation
                o.print(prompt);
                o.flush();
            }
        } catch (Exception ignored) {}
    }
    
    /**
     * Get the character ID for a session by player name.
     */
    public static Integer getCharacterIdByName(String name) {
        if (name == null) return null;
        ClientHandler handler = nameToSession.get(name.toLowerCase());
        return handler != null ? handler.characterId : null;
    }
    
    /**
     * Get all character IDs for currently connected players.
     * Used by RegenerationService to apply regen ticks.
     */
    public static java.util.Set<Integer> getConnectedCharacterIds() {
        return new java.util.HashSet<>(charIdToSession.keySet());
    }
    
    /**
     * Check if a character is currently GM-invisible.
     * Used by MobileRoamingService to skip aggro checks.
     * @param characterId the character to check
     * @return true if the character is online and GM-invisible
     */
    public static boolean isGmInvisible(Integer characterId) {
        if (characterId == null) return false;
        ClientHandler handler = charIdToSession.get(characterId);
        return handler != null && handler.gmInvisible;
    }
    
    /**
     * Get the display name for an item, preferring customName over template name.
     * Generated loot items (like trash) have custom names that override the template.
     * 
     * @param ri the RoomItem containing both instance and template
     * @return the display name to show players
     */
    public static String getItemDisplayName(ItemDAO.RoomItem ri) {
        if (ri == null) return "an item";
        String name;
        if (ri.instance != null && ri.instance.customName != null && !ri.instance.customName.isEmpty()) {
            name = ri.instance.customName;
        } else if (ri.template != null && ri.template.name != null) {
            name = ri.template.name;
        } else {
            name = "an item";
        }
        // Add (MAGICAL) suffix for items that cast spells when used
        if (ri.template != null && ri.template.hasMagicalUse()) {
            name = name + " (MAGICAL)";
        }
        return name;
    }
    
    /**
     * Get the display name for an item from separate instance and template.
     * Used when we have ItemInstance and ItemTemplate separately (e.g., equipped items).
     * 
     * @param inst the ItemInstance (may be null)
     * @param tmpl the ItemTemplate (may be null)
     * @return the display name to show players
     */
    public static String getItemDisplayName(ItemInstance inst, ItemTemplate tmpl) {
        String name;
        if (inst != null && inst.customName != null && !inst.customName.isEmpty()) {
            name = inst.customName;
        } else if (tmpl != null && tmpl.name != null) {
            name = tmpl.name;
        } else {
            name = "an item";
        }
        // Add (MAGICAL) suffix for items that cast spells when used
        if (tmpl != null && tmpl.hasMagicalUse()) {
            name = name + " (MAGICAL)";
        }
        return name;
    }

    private String formatPrompt(String fmt, CharacterDAO.CharacterRecord rec, CharacterDAO dao) {
        if (fmt == null) return "> ";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fmt.length(); i++) {
            char ch = fmt.charAt(i);
            if (ch != '%') { out.append(ch); continue; }
            // peek next
            if (i + 1 >= fmt.length()) { out.append('%'); break; }
            char t = fmt.charAt(++i);
            switch (t) {
                case '%': out.append('%'); break;
                case 'h': out.append(rec != null ? Integer.toString(rec.hpCur) : "0"); break;
                case 'H': out.append(rec != null ? Integer.toString(rec.hpMax) : "0"); break;
                case 'm': out.append(rec != null ? Integer.toString(rec.mpCur) : "0"); break;
                case 'M': out.append(rec != null ? Integer.toString(rec.mpMax) : "0"); break;
                case 'v': out.append(rec != null ? Integer.toString(rec.mvCur) : "0"); break;
                case 'V': out.append(rec != null ? Integer.toString(rec.mvMax) : "0"); break;
                case 'c': out.append(rec != null && rec.name != null ? rec.name : "<nochar>"); break;
                case 'r': {
                    String rn = "<nowhere>";
                    if (rec != null && rec.currentRoom != null) {
                        Room rr = dao.getRoomById(rec.currentRoom);
                        if (rr != null && rr.getName() != null) rn = rr.getName();
                    }
                    out.append(rn);
                } break;
                case 'a': {
                    String an = "<noarea>";
                    if (rec != null && rec.currentRoom != null) {
                        Room rr = dao.getRoomById(rec.currentRoom);
                        if (rr != null) {
                            Area a = dao.getAreaById(rr.getAreaId());
                            if (a != null && a.getName() != null) an = a.getName();
                        }
                    }
                    out.append(an);
                } break;
                case 'T': out.append(gameClock != null ? gameClock.getCurrentDateString() : "<time>"); break;
                case 'e': {
                    StringBuilder sb = new StringBuilder();
                    if (rec != null && rec.currentRoom != null) {
                        Room rr = dao.getRoomById(rec.currentRoom);
                        if (rr != null) {
                            if (rr.getExitN() != null) sb.append("north");
                            if (rr.getExitE() != null) { if (sb.length() > 0) sb.append(","); sb.append("east"); }
                            if (rr.getExitS() != null) { if (sb.length() > 0) sb.append(","); sb.append("south"); }
                            if (rr.getExitW() != null) { if (sb.length() > 0) sb.append(","); sb.append("west"); }
                            if (rr.getExitU() != null) { if (sb.length() > 0) sb.append(","); sb.append("up"); }
                            if (rr.getExitD() != null) { if (sb.length() > 0) sb.append(","); sb.append("down"); }
                        }
                    }
                    String s = sb.length() == 0 ? "none" : sb.toString();
                    out.append(s);
                } break;
                default:
                    // unknown token, emit as-is
                    out.append('%').append(t);
            }
        }
        return out.toString();
    }

    private CharacterDAO runLogin(BufferedReader in, PrintWriter pw) throws Exception {
        try {
                java.io.InputStream titleStream = getClass().getClassLoader().getResourceAsStream("asciiart/title.txt");
                if (titleStream != null) {
                    try (BufferedReader titleReader = new BufferedReader(new InputStreamReader(titleStream))) {
                        String line;
                        while ((line = titleReader.readLine()) != null) {
                            pw.println(line);
                        }
                    }
                    pw.println();
                }
            } catch (Exception e) {
                // Silently ignore if title art can't be loaded
            }
            
            pw.println("Welcome to TassMUD!");
            pw.flush();
            try {
                // brief pause to avoid socket output interleaving in some telnet clients
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }

            CharacterDAO dao = new CharacterDAO();

            // Login / character creation flow
            out = this.out; // local alias
            out.print("Enter character name: "); out.flush();
            String name = in.readLine();
            if (name == null) return dao;
            name = name.trim();
            if (name.isEmpty()) {
                out.println("Invalid name. Disconnecting.");
                socket.close();
                return dao;
            }

            CharacterRecord rec = dao.findByName(name);
            if (rec == null) {
                // Character creation flow using extensible helper method
                rec = runCharacterCreation(name, in, dao);
                if (rec == null) {
                    // Creation failed or was aborted
                    socket.close();
                    return dao;
                }
            } else {
                // login flow
                boolean authenticated = false;
                for (int tries = 3; tries > 0; tries--) {
                    out.print("Password: "); out.flush();
                    String pwAttempt = in.readLine();
                    if (pwAttempt == null) return dao;
                    if (dao.verifyPassword(name, pwAttempt.toCharArray())) {
                        authenticated = true;
                        out.println("Welcome back, " + name + "!");
                        break;
                    } else {
                        out.println("Invalid password. " + (tries - 1) + " attempts remaining.");
                    }
                }
                if (!authenticated) {
                    out.println("Too many failed attempts. Goodbye.");
                    socket.close();
                    return dao;
                }
            }

            // Ensure player's saved room exists; if not, place them into the debug Void (id 0)
            if (rec != null) {
                Integer cur = rec.currentRoom;
                Room check = (cur == null) ? null : dao.getRoomById(cur);
                if (check == null) {
                    out.println("Notice: your character was not in a valid room; placing you in The Void.");
                    dao.updateCharacterRoom(name, 0);
                    rec = dao.findByName(name);
                }
            }

            // register player name, character ID, and room for routing
            this.playerName = name;
            this.characterId = dao.getCharacterIdByName(name);
            this.currentRoomId = rec != null ? rec.currentRoom : null;
            nameToSession.put(name.toLowerCase(), this);
            if (this.characterId != null) {
                charIdToSession.put(this.characterId, this);
                // Register with regeneration service for HP/MP/MV regen ticks
                RegenerationService.getInstance().registerPlayer(this.characterId);
            }

            // Show MOTD (if any) after login/creation
            try {
                String motd = dao.getSetting("motd");
                if (motd != null && !motd.trim().isEmpty()) {
                    out.println("--- Message of the Day ---");
                    String[] motdLines = motd.split("\\r?\\n");
                    for (String ml : motdLines) out.println(ml);
                    out.println("--- End of MOTD ---");
                }
            } catch (Exception ignored) {}

            out.println("Type 'look', 'say <text>', 'chat <text>', 'yell <text>', 'whisper <who> <text>', 'motd', or 'quit'");
            return dao;
    }
    
    @Override
    public void run() {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter pw = new PrintWriter(socket.getOutputStream(), true)
        ) {
            this.out = pw;
            registerSession();
            CharacterDAO dao = runLogin(in, pw);
            String name = this.playerName;
            CharacterRecord rec = dao.findByName(name);
            
            while (true) {
                // print blank line before prompt for cleaner separation
                out.println();
                // print formatted prompt (reload rec to get fresh vitals)
                try {
                    rec = dao.findByName(name);
                    out.print(formatPrompt(promptFormat, rec, dao));
                    out.flush();
                } catch (Exception e) {
                    out.print("> "); out.flush();
                }

                String line = in.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                Command cmd = CommandParser.parse(line);
                if (cmd == null) {
                    out.println("Unknown command.");
                    continue;
                }

                String cmdName = cmd.getName().toLowerCase();
                
                // Combat lock: block non-combat commands when in combat
                if (characterId != null) {
                    Combat combat = CombatManager.getInstance().getCombatInRoom(currentRoomId);
                    if (combat != null && combat.containsCharacter(characterId)) {
                        CommandDefinition cmdDef = CommandRegistry.getCommand(cmdName);
                        if (cmdDef != null && !cmdDef.isAllowedInCombat()) {
                            out.println("You can't do that while fighting! (flee first)");
                            continue;
                        }
                    }
                }
                
                // Try to dispatch to category handlers first (reduces method size)
                boolean isGmForDispatch = dao.isCharacterFlagTrueByName(name, "is_gm");
                boolean inCombatForDispatch = characterId != null && 
                    CombatManager.getInstance().getCombatForCharacter(characterId) != null;
                CommandContext cmdCtx = new CommandContext(
                    cmd, name, characterId, currentRoomId, rec, dao, out,
                    isGmForDispatch, inCombatForDispatch, this
                );
                if (CommandDispatcher.dispatch(cmdCtx)) {
                    // Command was handled by a category handler
                    // Refresh rec in case it changed
                    rec = dao.findByName(name);
                    continue;
                }
            }
        } catch (IOException e) {
            logger.warn("Client connection error: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Generic error: {}", e.getMessage(), e);
        } finally {
            // Attempt to persist character state and modifiers on disconnect
            try {
                CharacterDAO dao = new CharacterDAO();
                if (characterId != null) {
                    // Persist vitals/state
                    CharacterDAO.CharacterRecord latest = dao.findById(characterId);
                    if (latest != null) {
                        dao.saveCharacterStateByName(latest.name, latest.hpCur, latest.mpCur, latest.mvCur, latest.currentRoom);
                    }

                    // If in combat, try to persist modifiers from the combatant's Character instance
                    com.example.tassmud.combat.CombatManager cm = com.example.tassmud.combat.CombatManager.getInstance();
                    com.example.tassmud.combat.Combatant combatant = cm.getCombatantForCharacter(characterId);
                    if (combatant != null) {
                        GameCharacter ch = combatant.getAsCharacter();
                        if (ch != null) {
                            dao.saveModifiersForCharacter(characterId, ch);
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Unregister from regeneration service
            if (characterId != null) {
                RegenerationService.getInstance().unregisterPlayer(characterId);
            }
            // Unregister from session tracking
            unregisterSession();
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // =========================================================================
    // CHARACTER CREATION FLOW
    // =========================================================================
    // This is an extensible character creation system. Each step is handled
    // sequentially and can be easily expanded to include race selection,
    // stat allocation, etc.
    // =========================================================================

    /**
     * Runs the full character creation flow.
     * Returns the CharacterRecord if successful, null if creation failed/aborted.
     */
    public CharacterRecord runCharacterCreation(String name, BufferedReader in, CharacterDAO dao) throws IOException {
        out.println("Character '" + name + "' not found. Creating new character.");
        out.println();

        // Step 1: Password
        String passwordHash = null;
        String passwordSalt = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            out.print("Create password: "); out.flush();
            String p1 = in.readLine();
            if (p1 == null) return null;
            out.print("Re-type password: "); out.flush();
            String p2 = in.readLine();
            if (p2 == null) return null;
            if (!p1.equals(p2)) {
                out.println("Passwords do not match. Try again.");
                continue;
            }
            passwordSalt = PasswordUtil.generateSaltBase64();
            passwordHash = PasswordUtil.hashPasswordBase64(p1.toCharArray(), 
                java.util.Base64.getDecoder().decode(passwordSalt));
            break;
        }
        if (passwordHash == null) {
            out.println("Failed to set password after several attempts. Goodbye.");
            return null;
        }
        out.println();

        // Step 2: Class Selection
        CharacterClass selectedClass = runClassSelection(in);
        if (selectedClass == null) {
            out.println("Class selection failed. Goodbye.");
            return null;
        }
        out.println();

        // Step 3: Age
        out.print("Enter your character's age (number): "); out.flush();
        String ageLine = in.readLine();
        int age = 0;
        try {
            if (ageLine != null) age = Integer.parseInt(ageLine.trim());
        } catch (Exception ignored) {}
        if (age < 1) age = 18; // default age
        out.println();

        // Step 4: Description
        out.print("Enter a short description of your character: "); out.flush();
        String desc = in.readLine();
        if (desc == null) desc = "";
        out.println();

        // Calculate initial stats based on class
        // Base values
        int baseHp = 100, baseMp = 50, baseMv = 100;
        // Add class bonuses at level 1
        int hpMax = baseHp + selectedClass.hpPerLevel;
        int mpMax = baseMp + selectedClass.mpPerLevel;
        int mvMax = baseMv + selectedClass.mvPerLevel;
        int hpCur = hpMax, mpCur = mpMax, mvCur = mvMax;

        // Default ability scores (can be expanded later with stat allocation)
        int str = 10, dex = 10, con = 10, intel = 10, wis = 10, cha = 10;
        // Default saves
        int armor = 10, fortitude = 10, reflex = 10, will = 10;

        // Default starting room: The Encampment (1000) - tutorial start
        int startRoomId = 1000;
        Room startRoom = dao.getRoomById(startRoomId);
        if (startRoom == null) {
            int anyRoomId = dao.getAnyRoomId();
            startRoomId = anyRoomId > 0 ? anyRoomId : 0;
        }
        Integer currentRoom = startRoomId > 0 ? startRoomId : null;

        // Create the character
        GameCharacter ch = new GameCharacter(name, age, desc, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur,
                currentRoom, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will);
        boolean ok = dao.createCharacter(ch, passwordHash, passwordSalt);
        if (!ok) {
            out.println("Failed to create character (name may be taken). Try a different name.");
            return null;
        }

        // Get the character ID for class assignment
        Integer characterId = dao.getCharacterIdByName(name);
        if (characterId == null) {
            out.println("Failed to retrieve character ID after creation.");
            return null;
        }

        // Set up initial class progress: level 1, xp 0
        CharacterClassDAO classDao = new CharacterClassDAO();
        classDao.setCharacterCurrentClass(characterId, selectedClass.id);
        // Also set current_class_id on the characters table for convenience
        dao.updateCharacterClass(characterId, selectedClass.id);

        out.println("=========================================");
        out.println("Character created successfully!");
        out.println("Name: " + name);
        out.println("Class: " + selectedClass.name + " (Level 1)");
        out.println("HP: " + hpMax + " | MP: " + mpMax + " | MV: " + mvMax);
        out.println("=========================================");
        out.println("Welcome to TassMUD, " + name + "!");
        out.println();

        return dao.findByName(name);
    }

    /**
     * Handles class selection during character creation.
     * Returns the selected CharacterClass, or null if selection failed.
     */
    public CharacterClass runClassSelection(BufferedReader in) throws IOException {
        CharacterClassDAO classDao = new CharacterClassDAO();
        
        // Load classes from YAML if not already loaded
        try {
            classDao.loadClassesFromYamlResource("/data/classes.yaml");
        } catch (Exception e) {
            out.println("Warning: Could not load class data.");
        }
        
        java.util.List<CharacterClass> allClasses = classDao.getAllClasses();
        if (allClasses.isEmpty()) {
            out.println("Error: No classes available!");
            return null;
        }
        
        // Sort classes by ID for consistent display
        allClasses.sort((a, b) -> Integer.compare(a.id, b.id));
        
        out.println("=== SELECT YOUR CLASS ===");
        out.println();
        for (CharacterClass cls : allClasses) {
            out.println(String.format("  [%d] %s", cls.id, cls.name));
            out.println(String.format("      HP/lvl: %+d  MP/lvl: %+d  MV/lvl: %+d", 
                cls.hpPerLevel, cls.mpPerLevel, cls.mvPerLevel));
        }
        out.println();
        out.println("Enter a number to see more details, or type the class name to select it.");
        out.println();
        
        // Allow up to 10 attempts to select a valid class
        for (int attempt = 0; attempt < 10; attempt++) {
            out.print("Your choice: "); out.flush();
            String input = in.readLine();
            if (input == null) return null;
            input = input.trim();
            if (input.isEmpty()) continue;
            
            // Check if input is a number (show details)
            try {
                int classId = Integer.parseInt(input);
                CharacterClass cls = classDao.getClassById(classId);
                if (cls != null) {
                    showClassDetails(cls);
                    out.println();
                    out.print("Select " + cls.name + "? (yes/no): "); out.flush();
                    String confirm = in.readLine();
                    if (confirm != null && (confirm.trim().equalsIgnoreCase("yes") || confirm.trim().equalsIgnoreCase("y"))) {
                        out.println("You have chosen the path of the " + cls.name + "!");
                        return cls;
                    }
                    // Show the list again
                    out.println();
                    out.println("Enter a number to see more details, or type the class name to select it.");
                    continue;
                } else {
                    out.println("Invalid class number. Please try again.");
                    continue;
                }
            } catch (NumberFormatException ignored) {
                // Not a number, check if it's a class name
            }
            
            // Try to match by name (case-insensitive, partial match)
            CharacterClass match = null;
            for (CharacterClass cls : allClasses) {
                if (cls.name.equalsIgnoreCase(input)) {
                    match = cls;
                    break;
                }
            }
            // If no exact match, try prefix match
            if (match == null) {
                for (CharacterClass cls : allClasses) {
                    if (cls.name.toLowerCase().startsWith(input.toLowerCase())) {
                        match = cls;
                        break;
                    }
                }
            }
            
            if (match != null) {
                showClassDetails(match);
                out.println();
                out.print("Select " + match.name + "? (yes/no): "); out.flush();
                String confirm = in.readLine();
                if (confirm != null && (confirm.trim().equalsIgnoreCase("yes") || confirm.trim().equalsIgnoreCase("y"))) {
                    out.println("You have chosen the path of the " + match.name + "!");
                    return match;
                }
                out.println();
                out.println("Enter a number to see more details, or type the class name to select it.");
            } else {
                out.println("Unknown class '" + input + "'. Please enter a valid class number or name.");
            }
        }
        
        out.println("Too many invalid attempts.");
        return null;
    }

    /**
     * Display detailed information about a character class.
     */
    public void showClassDetails(CharacterClass cls) {
        out.println();
        out.println("=== " + cls.name.toUpperCase() + " ===");
        out.println(cls.description);
        out.println();
        out.println("Stats per level:");
        out.println("  HP: +" + cls.hpPerLevel + "  MP: +" + cls.mpPerLevel + "  MV: +" + cls.mvPerLevel);
        out.println();
        // Show some early skills if available
        java.util.List<CharacterClass.ClassSkillGrant> grants = cls.getSkillsAtLevel(10);
        if (!grants.isEmpty()) {
            out.println("Early skills (levels 1-10):");
            CharacterDAO dao = new CharacterDAO();
            for (CharacterClass.ClassSkillGrant grant : grants) {
                Skill skillDef = dao.getSkillById(grant.skillId);
                String skillName = skillDef != null ? skillDef.getName() : "Skill #" + grant.skillId;
                out.println("  Level " + grant.classLevel + ": " + skillName);
            }
        }
    }

    // =========================================================================
    // HELPER METHODS FOR CHARACTER SHEET
    // =========================================================================

    /**
     * Truncate a string to a maximum length, adding "..." if truncated.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Format the talent point cost to train an ability from its current total.
     */
    public static String formatTrainCost(int currentTotal) {
        int cost = CharacterDAO.getAbilityTrainingCost(currentTotal);
        if (cost < 0) return "MAX";
        return cost + " pt" + (cost == 1 ? "" : "s");
    }

    /**
     * Get an HP bar visual based on percentage.
     */
    public static String getHpBar(int hpPct) {
        if (hpPct > 75) return "[####]";      // Healthy
        if (hpPct > 50) return "[### ]";      // Hurt
        if (hpPct > 25) return "[##  ]";      // Wounded
        if (hpPct > 10) return "[#   ]";      // Critical
        return "[    ]";                       // Near death
    }

    /**
     * Repeat a string n times.
     */
    public static String repeat(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    /**
     * Format damage as dice notation (e.g., "2d6 + 5").
     */
    public static String formatDamage(int multiplier, int baseDie, int bonus) {
        if (multiplier <= 0) multiplier = 1;
        if (baseDie <= 0) return "0";
        
        StringBuilder sb = new StringBuilder();
        sb.append(multiplier).append("d").append(baseDie);
        if (bonus > 0) {
            sb.append(" + ").append(bonus);
        } else if (bonus < 0) {
            sb.append(" - ").append(Math.abs(bonus));
        }
        return sb.toString();
    }

    /**
     * Format a duration (seconds) into H:MM:SS or M:SS depending on length.
     */
    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "0s";
        long hrs = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hrs > 0) return String.format("%d:%02d:%02d", hrs, mins, secs);
        return String.format("%d:%02d", mins, secs);
    }

    /**
     * Get the ability bonus for a weapon based on its ability score scaling.
     */
    public static int getAbilityBonus(String abilityScore, double multiplier, CharacterDAO.CharacterRecord rec) {
        if (abilityScore == null || abilityScore.isEmpty() || multiplier == 0) return 0;
        
        int abilityValue = 10;
        switch (abilityScore.toLowerCase()) {
            case "str": case "strength": abilityValue = rec.str; break;
            case "dex": case "dexterity": abilityValue = rec.dex; break;
            case "con": case "constitution": abilityValue = rec.con; break;
            case "int": case "intel": case "intelligence": abilityValue = rec.intel; break;
            case "wis": case "wisdom": abilityValue = rec.wis; break;
            case "cha": case "charisma": abilityValue = rec.cha; break;
        }
        
        int modifier = (abilityValue - 10) / 2;
        return (int) Math.round(modifier * multiplier);
    }

    /**
     * Get the stat modifier for a given ability score (for hit bonus display).
     */
    public static int getStatModifier(String abilityScore, CharacterDAO.CharacterRecord rec) {
        if (abilityScore == null || abilityScore.isEmpty()) return 0;
        
        int abilityValue = 10;
        switch (abilityScore.toLowerCase()) {
            case "str": case "strength": abilityValue = rec.str; break;
            case "dex": case "dexterity": abilityValue = rec.dex; break;
            case "con": case "constitution": abilityValue = rec.con; break;
            case "int": case "intel": case "intelligence": abilityValue = rec.intel; break;
            case "wis": case "wisdom": abilityValue = rec.wis; break;
            case "cha": case "charisma": abilityValue = rec.cha; break;
        }
        
        return (abilityValue - 10) / 2;
    }

    /**
     * Format a hit bonus as "1d20+X" or "1d20-X" for display.
     */
    public static String formatHitBonus(int bonus) {
        if (bonus >= 0) {
            return "1d20+" + bonus;
        } else {
            return "1d20" + bonus; // negative sign already included
        }
    }

    /**
     * Print a row of up to 3 commands, evenly spaced.
     */
    public void printCommandRow(String cmd1, String cmd2, String cmd3) {
        out.println(String.format("    %-20s %-20s %-20s", cmd1, cmd2, cmd3));
    }

    /**
     * Centralized handler for combat-related commands.
     */
    

    public static void groupBroadcast(CharacterDAO dao, String playerName2, String t) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'groupBroadcast'");
    }

    public boolean executeAutoflee(Combat combat) {
        if (playerName == null || characterId == null || currentRoomId == null) {
            return false;
        }
        
        CharacterDAO dao = new CharacterDAO();
        CharacterRecord rec = dao.findByName(playerName);
        if (rec == null) return false;
        
        Combatant userCombatant = combat.findByCharacterId(characterId);
        if (userCombatant == null) return false;
        
        // Get current room and available exits
        Room curRoom = dao.getRoomById(currentRoomId);
        if (curRoom == null) return false;
        
        // Build list of available exits
        java.util.List<String> availableExits = new java.util.ArrayList<>();
        java.util.Map<String, Integer> exitRooms = new java.util.HashMap<>();
        if (curRoom.getExitN() != null) { availableExits.add("north"); exitRooms.put("north", curRoom.getExitN()); }
        if (curRoom.getExitE() != null) { availableExits.add("east"); exitRooms.put("east", curRoom.getExitE()); }
        if (curRoom.getExitS() != null) { availableExits.add("south"); exitRooms.put("south", curRoom.getExitS()); }
        if (curRoom.getExitW() != null) { availableExits.add("west"); exitRooms.put("west", curRoom.getExitW()); }
        if (curRoom.getExitU() != null) { availableExits.add("up"); exitRooms.put("up", curRoom.getExitU()); }
        if (curRoom.getExitD() != null) { availableExits.add("down"); exitRooms.put("down", curRoom.getExitD()); }
        
        if (availableExits.isEmpty()) {
            if (out != null) out.println("Panic! But there's nowhere to flee!");
            return false;
        }
        
        // Get user's level for opposed check
        CharacterClassDAO classDao = new CharacterClassDAO();
        int userLevel = rec.currentClassId != null 
            ? classDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
        
        // Find the highest level opponent for opposed check
        int opponentLevel = 1;
        for (Combatant c : combat.getCombatants()) {
            if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                int cLevel;
                if (c.isPlayer()) {
                    Integer cCharId = c.getCharacterId();
                    CharacterRecord cRec = dao.getCharacterById(cCharId);
                    cLevel = cRec != null && cRec.currentClassId != null 
                        ? classDao.getCharacterClassLevel(cCharId, cRec.currentClassId) : 1;
                } else if (c.getMobile() != null) {
                    cLevel = c.getMobile().getLevel();
                } else {
                    cLevel = 1;
                }
                if (cLevel > opponentLevel) {
                    opponentLevel = cLevel;
                }
            }
        }
        
        // Perform opposed check at 100% proficiency (innate skill)
        int roll = (int)(Math.random() * 100) + 1;
        int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(
            userLevel, opponentLevel, 100);
        
        boolean fleeSucceeded = roll <= successChance;
        
        if (!fleeSucceeded) {
            // Failed to flee
            if (out != null) out.println("You panic and try to flee but your opponents block your escape!");
            roomAnnounce(currentRoomId, playerName + " panics and tries to flee but fails!", this.characterId, true);
            return false;
        }
        
        // Flee succeeded - pick a random exit
        String fleeDirection = availableExits.get((int)(Math.random() * availableExits.size()));
        Integer destRoomId = exitRooms.get(fleeDirection);
        
        // Check movement cost
        int moveCost = dao.getMoveCostForRoom(destRoomId);
        
        if (rec.mvCur < moveCost) {
            // Insufficient MV - fall prone instead of escaping
            userCombatant.setProne();
            if (out != null) out.println("You break free but stumble and fall prone from exhaustion!");
            if (!this.gmInvisible) {
                roomAnnounce(currentRoomId, playerName + " panics and tries to flee but collapses from exhaustion!", this.characterId, true);
            }
            return false;
        }
        
        // Deduct movement points
        if (!dao.deductMovementPoints(playerName, moveCost)) {
            userCombatant.setProne();
            if (out != null) out.println("You break free but stumble and fall prone from exhaustion!");
            if (!this.gmInvisible) {
                roomAnnounce(currentRoomId, playerName + " panics and tries to flee but collapses from exhaustion!", this.characterId, true);
            }
            return false;
        }
        
        // Remove from combat
        combat.removeCombatant(userCombatant);
        
        // Announce departure
        if (out != null) out.println("Panic overwhelms you and you flee " + fleeDirection + "!");
        if (!this.gmInvisible) {
            roomAnnounce(currentRoomId, playerName + " panics and flees " + fleeDirection + "!", this.characterId, true);
        }
        
        // Move to new room
        boolean moved = dao.updateCharacterRoom(playerName, destRoomId);
        if (!moved) {
            if (out != null) out.println("Something strange happened during your escape.");
            return true; // Still count as success for combat purposes
        }
        
        // Update cached room and show new location
        rec = dao.findByName(playerName);
        this.currentRoomId = rec != null ? rec.currentRoom : null;
        Room newRoom = dao.getRoomById(destRoomId);
        
        // Announce arrival
        if (!this.gmInvisible) {
            roomAnnounce(destRoomId, makeArrivalMessage(playerName, fleeDirection), this.characterId, true);
        }
        
        if (newRoom != null && out != null) {
            boolean isGmForDispatch = dao.isCharacterFlagTrueByName(playerName, "is_gm");
            boolean inCombatForDispatch = characterId != null &&  CombatManager.getInstance().getCombatForCharacter(characterId) != null;
            CommandContext cmdCtx = new CommandContext(null, playerName, characterId, currentRoomId, rec, dao, out, isGmForDispatch, inCombatForDispatch, this);
            MovementCommandHandler.showRoom(newRoom, destRoomId, cmdCtx);
        }
        
        return true;
    }
}
