package com.example.tassmud.net;


import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Area;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Direction;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.net.commands.CommandDispatcher;
import com.example.tassmud.net.commands.MovementCommandHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.GameClock;
import com.example.tassmud.util.RegenerationService;
import com.example.tassmud.util.AllyManager;
import com.example.tassmud.util.MobileRegistry;
import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.Mobile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
    public volatile String lastTellSender = null;  // For reply command
    
    public Socket getSocket() {
        return this.socket;
    }

    public GameClock getGameClock() {
        return this.gameClock;
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
        if (characterId != null) {
            charIdToSession.remove(characterId);
            // Clean up group membership on disconnect
            com.example.tassmud.util.GroupManager.getInstance().handlePlayerLogout(characterId);
        }
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

    public static void broadcastArea(CharacterDAO dao, Integer areaId, String msg) {
        if (areaId == null) return;
        for (ClientHandler s : sessions) {
            Integer rId = s.currentRoomId;
            if (rId == null) continue;
            Room r = DaoProvider.rooms().getRoomById(rId);
            if (r != null && Integer.valueOf(r.getAreaId()).equals(areaId)) {
                s.sendRaw(msg);
            }
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
     * Execute an action for every connected session in the given room.
     * This is the shared iteration helper used by all room-scoped messaging methods.
     */
    public static void forEachInRoom(Integer roomId, java.util.function.Consumer<ClientHandler> action) {
        if (roomId == null) return;
        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId)) {
                action.accept(s);
            }
        }
    }

    /**
     * Send a message to all players in a specific room.
     * Called by combat system to broadcast combat messages.
     */
    public static void broadcastRoomMessage(Integer roomId, String msg) {
        forEachInRoom(roomId, s -> s.sendRaw(msg));
    }

    /**
     * Send a room message to every player in the room except the one with the
     * given character ID.  Used so the acting player sees only their own
     * first-person message without also receiving the third-person broadcast.
     */
    public static void broadcastRoomMessage(Integer roomId, String msg, Integer excludeCharacterId) {
        forEachInRoom(roomId, s -> {
            if (!s.characterId.equals(excludeCharacterId)) s.sendRaw(msg);
        });
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
        if (msg == null) return;
        forEachInRoom(roomId, s -> {
            if (s.debugChannelEnabled) s.sendRaw("[DEBUG] " + msg);
        });
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
     * Room announce that automatically checks the actor's invisibility and sneak status.
     * Use this for player actions where we want to respect their invisibility/sneaking.
     */
    public static void roomAnnounceFromActor(Integer roomId, String msg, Integer actorCharacterId) {
        if (roomId == null || msg == null || msg.isEmpty()) return;
        // Sneaking characters are silent - don't announce their movements
        if (isSneaking(actorCharacterId)) return;
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
        forEachInRoom(roomId, ClientHandler::sendPrompt);
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
        
        // Use total stats (base + trained) for ability scores
        com.example.tassmud.model.StatBlock combatStats = new com.example.tassmud.model.StatBlock(
            rec.getStrTotal(), rec.getDexTotal(), rec.getConTotal(),
            rec.getIntTotal(), rec.getWisTotal(), rec.getChaTotal(),
            rec.getArmorTotal(), rec.getFortitudeTotal(),
            rec.getReflexTotal(), rec.getWillTotal()
        );
        GameCharacter playerChar = new GameCharacter(
            rec.name, rec.age, rec.description,
            rec.hpMax, rec.hpCur, rec.mpMax, rec.mpCur, rec.mvMax, rec.mvCur,
            rec.currentRoom,
            combatStats
        );
        
        // Load persisted modifiers for this character (if any) so they apply in combat
        if (characterId != null) {
            CharacterDAO dao = DaoProvider.characters();
            java.util.List<com.example.tassmud.model.Modifier> mods = dao.getModifiersForCharacter(characterId);
            for (com.example.tassmud.model.Modifier m : mods) {
                playerChar.addModifier(m);
            }
            
            // Apply passive critical threshold bonuses from mastered skills
            // These reduce the threshold (negative bonus = lower roll needed to crit)
            int critBonus = 0;
            
            // Improved Critical (id=23) - lowers threshold by 1
            com.example.tassmud.model.CharacterSkill improvedCrit = DaoProvider.skills().getCharacterSkill(characterId, 23);
            if (improvedCrit != null && improvedCrit.getProficiency() >= 100) {
                critBonus -= 1;
            }
            
            // Greater Critical (id=24) - lowers threshold by additional 1
            com.example.tassmud.model.CharacterSkill greaterCrit = DaoProvider.skills().getCharacterSkill(characterId, 24);
            if (greaterCrit != null && greaterCrit.getProficiency() >= 100) {
                critBonus -= 1;
            }
            
            // Superior Critical (id=25) - lowers threshold by additional 1
            com.example.tassmud.model.CharacterSkill superiorCrit = DaoProvider.skills().getCharacterSkill(characterId, 25);
            if (superiorCrit != null && superiorCrit.getProficiency() >= 100) {
                critBonus -= 1;
            }
            
            // Apply the total critical threshold bonus as a modifier (permanent, no expiry)
            if (critBonus != 0) {
                playerChar.addModifier(new com.example.tassmud.model.Modifier(
                    "passive_critical_skills",
                    com.example.tassmud.model.Stat.CRITICAL_THRESHOLD_BONUS,
                    com.example.tassmud.model.Modifier.Op.ADD,
                    critBonus,
                    0L,  // no expiry
                    0    // default priority
                ));
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
                CharacterDAO dao = DaoProvider.characters();
                CharacterRecord rec = dao.findByName(playerName);
                String prompt = formatPrompt(promptFormat, rec, dao);
                o.println();  // blank line for visual separation
                o.print(com.example.tassmud.util.Colors.prompt(prompt));
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
     * Check if a character is currently sneaking.
     * Used by room announcements to suppress arrival/departure messages
     * and by aggro checks to prevent aggressive mobs from attacking.
     * @param characterId the character to check
     * @return true if the character has the is_sneaking flag set to true
     */
    public static boolean isSneaking(Integer characterId) {
        if (characterId == null) return false;
        CharacterDAO dao = DaoProvider.characters();
        String sneakFlag = dao.getCharacterFlag(characterId, "is_sneaking");
        return "true".equalsIgnoreCase(sneakFlag);
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
        return getItemDisplayName(ri.instance, ri.template);
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
                case 'k': out.append(rec != null ? Integer.toString(rec.kiCur) : "0"); break;
                case 'K': out.append(rec != null ? Integer.toString(rec.kiMax) : "0"); break;
                case 'c': out.append(rec != null && rec.name != null ? rec.name : "<nochar>"); break;
                case 'r': {
                    String rn = "<nowhere>";
                    if (rec != null && rec.currentRoom != null) {
                        Room rr = DaoProvider.rooms().getRoomById(rec.currentRoom);
                        if (rr != null && rr.getName() != null) rn = rr.getName();
                    }
                    out.append(rn);
                } break;
                case 'a': {
                    String an = "<noarea>";
                    if (rec != null && rec.currentRoom != null) {
                        Room rr = DaoProvider.rooms().getRoomById(rec.currentRoom);
                        if (rr != null) {
                            Area a = DaoProvider.rooms().getAreaById(rr.getAreaId());
                            if (a != null && a.getName() != null) an = a.getName();
                        }
                    }
                    out.append(an);
                } break;
                case 'T': out.append(gameClock != null ? gameClock.getCurrentDateString() : "<time>"); break;
                case 'e': {
                    StringBuilder sb = new StringBuilder();
                    if (rec != null && rec.currentRoom != null) {
                        Room rr = DaoProvider.rooms().getRoomById(rec.currentRoom);
                        if (rr != null) {
                            for (Direction dir : rr.getExits().keySet()) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(dir.fullName());
                            }
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

    /**
     * Delegates to {@link CharacterCreationHandler} for the interactive login/creation flow,
     * then registers the resulting session state on this handler.
     *
     * @return the CharacterDAO to use for the session, or null if the connection should close
     */
    private CharacterDAO runLogin(BufferedReader in, PrintWriter pw) throws Exception {
        CharacterCreationHandler handler = new CharacterCreationHandler(pw);
        CharacterCreationHandler.LoginResult result = handler.runLogin(in);

        if (result == null) {
            // Login failed or client disconnected — close socket
            socket.close();
            return DaoProvider.characters();
        }

        // Apply session state from the login result
        this.playerName = result.playerName();
        this.characterId = result.characterId();
        this.currentRoomId = result.currentRoomId();
        nameToSession.put(result.playerName().toLowerCase(), this);
        if (this.characterId != null) {
            charIdToSession.put(this.characterId, this);
            // Register with regeneration service for HP/MP/MV regen ticks
            RegenerationService.getInstance().registerPlayer(this.characterId);
        }

        return DaoProvider.characters();
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
            
            // Monks use ki in their prompt instead of mp
            if (rec != null && rec.currentClassId != null && rec.currentClassId == 8) {
                this.promptFormat = "<%h/%Hhp %k/%Kki %v/%Vmv> ";
                // Ensure ki max is initialized from wisdom modifier on login
                if (rec.kiMax <= 0) {
                    int wisMod = (rec.getWisTotal() - 10) / 2;
                    int newKiMax = Math.max(1, wisMod);
                    dao.saveKiByName(name, newKiMax, Math.min(rec.kiCur, newKiMax));
                    rec = dao.findByName(name); // refresh
                }
            }
            
            while (true) {
                // print blank line before prompt for cleaner separation
                out.println();
                // print formatted prompt (reload rec to get fresh vitals)
                try {
                    rec = dao.findByName(name);
                    out.print(com.example.tassmud.util.Colors.prompt(formatPrompt(promptFormat, rec, dao)));
                    out.flush();
                } catch (Exception e) {
                    out.print(com.example.tassmud.util.Colors.prompt("> ")); out.flush();
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
            if (isClientDisconnect(e)) {
                String who = (playerName != null) ? playerName : socket.getRemoteSocketAddress().toString();
                logger.info("Client disconnected: {} ({})", who, e.getMessage());
            } else {
                logger.warn("Client connection error: {}", e.getMessage(), e);
            }
        } catch (Exception e) {
            logger.error("Generic error: {}", e.getMessage(), e);
        } finally {
            // Attempt to persist character state and modifiers on disconnect
            try {
                CharacterDAO dao = DaoProvider.characters();
                if (characterId != null) {
                    // Persist vitals/state
                    CharacterDAO.CharacterRecord latest = dao.findById(characterId);
                    if (latest != null) {
                        dao.saveCharacterStateByName(latest.name, latest.hpCur, latest.mpCur, latest.mvCur, latest.kiCur, latest.currentRoom);
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

    /**
     * Returns true if the IOException is a routine client disconnect
     * (socket closed, connection reset, broken pipe, etc.) rather than
     * an unexpected I/O failure worth investigating.
     */
    private static boolean isClientDisconnect(IOException e) {
        if (e instanceof java.net.SocketException || e instanceof java.io.EOFException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("connection reset")
            || lower.contains("socket closed")
            || lower.contains("broken pipe")
            || lower.contains("connection aborted")
            || lower.contains("stream closed");
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
     * Format damage as dice notation (e.g., "2d6+5").
     */
    public static String formatDamage(int multiplier, int baseDie, int bonus) {
        if (multiplier <= 0) multiplier = 1;
        if (baseDie <= 0) return "0";
        
        StringBuilder sb = new StringBuilder();
        sb.append(multiplier).append("d").append(baseDie);
        if (bonus > 0) {
            sb.append("+").append(bonus);
        } else if (bonus < 0) {
            sb.append("-").append(Math.abs(bonus));
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
        if (hrs > 0) return "%d:%02d:%02d".formatted(hrs, mins, secs);
        return "%d:%02d".formatted(mins, secs);
    }

    /**
     * Resolve the total ability score value for a named ability.
     * Accepts short ("str") or long ("strength") names, case-insensitive.
     * Returns 10 (neutral) for null/empty/unrecognised names.
     */
    public static int resolveAbilityValue(String abilityScore, CharacterDAO.CharacterRecord rec) {
        if (abilityScore == null || abilityScore.isEmpty()) return 10;
        return switch (abilityScore.toLowerCase()) {
            case "str", "strength"                  -> rec.getStrTotal();
            case "dex", "dexterity"                  -> rec.getDexTotal();
            case "con", "constitution"               -> rec.getConTotal();
            case "int", "intel", "intelligence"      -> rec.getIntTotal();
            case "wis", "wisdom"                     -> rec.getWisTotal();
            case "cha", "charisma"                   -> rec.getChaTotal();
            default -> 10;
        };
    }

    /**
     * Get the ability bonus for a weapon based on its ability score scaling.
     * Uses total ability score (base + trained).
     */
    public static int getAbilityBonus(String abilityScore, double multiplier, CharacterDAO.CharacterRecord rec) {
        if (abilityScore == null || abilityScore.isEmpty() || multiplier == 0) return 0;
        int modifier = (resolveAbilityValue(abilityScore, rec) - 10) / 2;
        return (int) Math.floor(modifier * multiplier);
    }

    /**
     * Get the stat modifier for a given ability score (for hit bonus display).
     * Uses total ability score (base + trained).
     */
    public static int getStatModifier(String abilityScore, CharacterDAO.CharacterRecord rec) {
        if (abilityScore == null || abilityScore.isEmpty()) return 0;
        return (resolveAbilityValue(abilityScore, rec) - 10) / 2;
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
        out.println("    %-20s %-20s %-20s".formatted(cmd1, cmd2, cmd3));
    }

    /**
     * Centralized handler for combat-related commands.
     */
    

    public static void groupBroadcast(CharacterDAO dao, String playerName, String message) {
        // Get the sender's character ID
        Integer senderId = dao.getCharacterIdByName(playerName);
        if (senderId == null) {
            return;
        }

        // Get the sender's group
        com.example.tassmud.util.GroupManager gm = com.example.tassmud.util.GroupManager.getInstance();
        java.util.Optional<com.example.tassmud.model.Group> groupOpt = gm.getGroupForCharacter(senderId);
        
        if (groupOpt.isEmpty()) {
            // Send error to sender
            ClientHandler senderHandler = charIdToSession.get(senderId);
            if (senderHandler != null) {
                senderHandler.out.println("You are not in a group.");
                sendPromptToCharacter(senderId);
            }
            return;
        }

        com.example.tassmud.model.Group group = groupOpt.get();

        // Send to all group members
        for (int memberId : group.getMemberIds()) {
            ClientHandler memberHandler = charIdToSession.get(memberId);
            if (memberHandler != null) {
                if (memberId == senderId) {
                    memberHandler.out.println("\u001B[36m[Group] You: " + message + "\u001B[0m");
                } else {
                    memberHandler.out.println("\u001B[36m[Group] " + playerName + ": " + message + "\u001B[0m");
                }
                sendPromptToCharacter(memberId);
            }
        }
    }

    public boolean executeAutoflee(Combat combat) {
        if (playerName == null || characterId == null || currentRoomId == null) {
            return false;
        }
        
        CharacterDAO dao = DaoProvider.characters();
        CharacterRecord rec = dao.findByName(playerName);
        if (rec == null) return false;
        
        Combatant userCombatant = combat.findByCharacterId(characterId);
        if (userCombatant == null) return false;
        
        // Get current room and available exits
        Room curRoom = DaoProvider.rooms().getRoomById(currentRoomId);
        if (curRoom == null) return false;
        
        // Build list of available exits
        java.util.List<String> availableExits = new java.util.ArrayList<>();
        java.util.Map<String, Integer> exitRooms = new java.util.HashMap<>();
        for (var entry : curRoom.getExits().entrySet()) {
            availableExits.add(entry.getKey().fullName());
            exitRooms.put(entry.getKey().fullName(), entry.getValue());
        }
        
        if (availableExits.isEmpty()) {
            if (out != null) out.println("Panic! But there's nowhere to flee!");
            return false;
        }
        
        // Get user's level for opposed check
        CharacterClassDAO classDao = DaoProvider.classes();
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
        int roll = ThreadLocalRandom.current().nextInt(1, 101);
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
        String fleeDirection = availableExits.get(ThreadLocalRandom.current().nextInt(availableExits.size()));
        Integer destRoomId = exitRooms.get(fleeDirection);
        
        // Check movement cost
        int moveCost = DaoProvider.rooms().getMoveCostForRoom(destRoomId);
        
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
        Room newRoom = DaoProvider.rooms().getRoomById(destRoomId);
        
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

        // Move PERMANENT allies to the same destination room
        if (characterId != null) {
            final int fromRoomForAlly = combat.getRoomId();
            java.util.List<AllyBinding> fleeingAllies =
                    AllyManager.getInstance().getAlliesFollowingOwner(characterId, fromRoomForAlly);
            for (AllyBinding binding : fleeingAllies) {
                Mobile allyMob = MobileRegistry.getInstance().getById(binding.getMobInstanceId());
                if (allyMob == null) continue;
                allyMob.setCurrentRoom(destRoomId);
                try {
                    DaoProvider.mobiles().updateInstance(allyMob);
                    MobileRegistry.getInstance().moveToRoom(allyMob.getInstanceId(), fromRoomForAlly, destRoomId);
                    roomAnnounce(destRoomId, allyMob.getName() + " arrives following " + playerName + ".",
                            null, false);
                } catch (Exception e) {
                    logger.warn("[ClientHandler] Failed to move fleeing ally mob {}: {}",
                            allyMob.getInstanceId(), e.getMessage());
                }
            }
        }

        return true;
    }
}
