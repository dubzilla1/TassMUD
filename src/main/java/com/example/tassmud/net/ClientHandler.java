package com.example.tassmud.net;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Area;
import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.GameClock;
import com.example.tassmud.util.HelpManager;
import com.example.tassmud.util.HelpPage;
import com.example.tassmud.util.PasswordUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;

/**
 * Per-client handler for the telnet server.
 * Reads lines, parses simple commands, and responds.
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final GameClock gameClock;
    // Registry of active sessions
    private static final Set<ClientHandler> sessions = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, ClientHandler> nameToSession = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ClientHandler> charIdToSession = new ConcurrentHashMap<>();

    // Per-session state used for routing messages
    private volatile PrintWriter out = null;
    private volatile String playerName = null;
    private volatile Integer currentRoomId = null;
    private volatile Integer characterId = null;
    private volatile String promptFormat = "<%h/%Hhp %m/%Mmp %v/%Vmv> ";
    
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

    private void sendRaw(String msg) {
        try {
            PrintWriter o = out;
            if (o != null) {
                o.println(msg);
                o.flush();
            }
        } catch (Exception ignored) {}
    }

    /** Display a room to this client (used by look and movement) */
    private void showRoom(Room room, int roomId) {
        // Room name
        out.println(room.getName());
        // Room description (indented with tab)
        out.println("\t" + room.getLongDesc());
        // Exits - only show available exits in order: north east south west up down
        StringBuilder exits = new StringBuilder();
        exits.append("[Exits:");
        if (room.getExitN() != null) exits.append(" north");
        if (room.getExitE() != null) exits.append(" east");
        if (room.getExitS() != null) exits.append(" south");
        if (room.getExitW() != null) exits.append(" west");
        if (room.getExitU() != null) exits.append(" up");
        if (room.getExitD() != null) exits.append(" down");
        exits.append("]");
        out.println(exits.toString());
        // Blank line
        out.println();
        // Mobs in this room
        MobileDAO mobDao = new MobileDAO();
        java.util.List<Mobile> roomMobs = mobDao.getMobilesInRoom(roomId);
        for (Mobile mob : roomMobs) {
            String desc = mob.getShortDesc();
            if (desc != null && !desc.isEmpty()) {
                out.println(desc);
            } else {
                out.println(mob.getName() + " is here.");
            }
        }
        // Items on the floor in this room
        ItemDAO itemDao = new ItemDAO();
        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(roomId);
        for (ItemDAO.RoomItem ri : roomItems) {
            String desc = ri.template.description;
            if (desc != null && !desc.isEmpty()) {
                out.println(desc);
            } else {
                out.println("A " + ri.template.name + " lies here.");
            }
        }
    }

    private static void broadcastAll(String msg) {
        for (ClientHandler s : sessions) s.sendRaw(msg);
    }

    private static void broadcastRoom(Integer roomId, String msg) {
        if (roomId == null) return;
        for (ClientHandler s : sessions) {
            Integer r = s.currentRoomId;
            if (r != null && r.equals(roomId)) s.sendRaw(msg);
        }
    }

    private static void broadcastArea(CharacterDAO dao, Integer areaId, String msg) {
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

    private static void whisperTo(String targetName, String fromName, String msg) {
        if (targetName == null) return;
        ClientHandler t = nameToSession.get(targetName.toLowerCase());
        if (t != null) {
            t.sendRaw("[whisper] " + fromName + " -> you: " + msg);
        }
    }

    private static void gmBroadcast(CharacterDAO dao, String fromName, String msg) {
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

    @Override
    public void run() {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter pw = new PrintWriter(socket.getOutputStream(), true)
        ) {
            this.out = pw;
            registerSession();
            
            // Display ASCII art title screen
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
            if (name == null) return;
            name = name.trim();
            if (name.isEmpty()) {
                out.println("Invalid name. Disconnecting.");
                socket.close();
                return;
            }

            CharacterRecord rec = dao.findByName(name);
            if (rec == null) {
                // Character creation flow using extensible helper method
                rec = runCharacterCreation(name, in, dao);
                if (rec == null) {
                    // Creation failed or was aborted
                    socket.close();
                    return;
                }
            } else {
                // login flow
                boolean authenticated = false;
                for (int tries = 3; tries > 0; tries--) {
                    out.print("Password: "); out.flush();
                    String pwAttempt = in.readLine();
                    if (pwAttempt == null) return;
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
                    return;
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
                
                switch (cmdName) {
                    case "help":
                        String a = cmd.getArgs();
                        boolean isGm = dao.isCharacterFlagTrueByName(name, "is_gm");
                        if (a == null || a.trim().isEmpty()) {
                            // Display categorized command list
                            displayHelpCommandList(isGm);
                        } else {
                            String arg0 = a.trim().split("\\s+",2)[0];
                            if (arg0.equalsIgnoreCase("commands")) {
                                displayHelpCommandList(isGm);
                                break;
                            }
                            if (arg0.equalsIgnoreCase("reload")) {
                                if (!isGm) {
                                    out.println("You do not have permission to reload help files.");
                                } else {
                                    HelpManager.reloadPages();
                                    out.println("Help pages reloaded.");
                                }
                            } else {
                                String page = HelpManager.getPage(arg0, isGm);
                                if (page == null) {
                                    out.println("No help available for '" + a + "'");
                                } else {
                                    // print man-style page
                                    String[] lines = page.split("\\n");
                                    for (String L : lines) out.println(L);
                                }
                            }
                        }
                        break;
                    case "look": {
                        // Show the room the character is currently in (if any)
                        if (rec == null) {
                            out.println("You seem to be nowhere. (no character record found)");
                            break;
                        }
                        Integer lookRoomId = rec.currentRoom;
                        if (lookRoomId == null) {
                            out.println("You are not located in any room.");
                            break;
                        }
                        Room room = dao.getRoomById(lookRoomId);
                        if (room == null) {
                            out.println("You are in an unknown place (room id " + lookRoomId + ").");
                            break;
                        }
                        
                        String lookArgs = cmd.getArgs();
                        if (lookArgs == null || lookArgs.trim().isEmpty()) {
                            // No argument - show the room
                            showRoom(room, lookRoomId);
                        } else if (lookArgs.trim().toLowerCase().startsWith("in ")) {
                            // "look in <container>" - search for container and show contents
                            String containerSearch = lookArgs.trim().substring(3).trim().toLowerCase();
                            if (containerSearch.isEmpty()) {
                                out.println("Look in what?");
                                break;
                            }
                            
                            ItemDAO itemDao = new ItemDAO();
                            Integer charId = dao.getCharacterIdByName(name);
                            
                            // Search for container in room first, then inventory
                            java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(lookRoomId);
                            java.util.List<ItemDAO.RoomItem> invItems = charId != null ? itemDao.getItemsByCharacter(charId) : new java.util.ArrayList<>();
                            
                            // Combine both lists for searching
                            java.util.List<ItemDAO.RoomItem> allItems = new java.util.ArrayList<>();
                            allItems.addAll(roomItems);
                            allItems.addAll(invItems);
                            
                            // Smart search for container
                            ItemDAO.RoomItem matchedContainer = null;
                            
                            // Priority 1: Exact name match
                            for (ItemDAO.RoomItem ri : allItems) {
                                if (ri.template.isContainer() && ri.template.name != null 
                                    && ri.template.name.equalsIgnoreCase(containerSearch)) {
                                    matchedContainer = ri;
                                    break;
                                }
                            }
                            
                            // Priority 2: Name starts with search term
                            if (matchedContainer == null) {
                                for (ItemDAO.RoomItem ri : allItems) {
                                    if (ri.template.isContainer() && ri.template.name != null 
                                        && ri.template.name.toLowerCase().startsWith(containerSearch)) {
                                        matchedContainer = ri;
                                        break;
                                    }
                                }
                            }
                            
                            // Priority 3: Name word starts with search term
                            if (matchedContainer == null) {
                                for (ItemDAO.RoomItem ri : allItems) {
                                    if (ri.template.isContainer() && ri.template.name != null) {
                                        String[] words = ri.template.name.toLowerCase().split("\\s+");
                                        for (String w : words) {
                                            if (w.startsWith(containerSearch)) {
                                                matchedContainer = ri;
                                                break;
                                            }
                                        }
                                        if (matchedContainer != null) break;
                                    }
                                }
                            }
                            
                            // Priority 4: Keyword match
                            if (matchedContainer == null) {
                                for (ItemDAO.RoomItem ri : allItems) {
                                    if (ri.template.isContainer() && ri.template.keywords != null) {
                                        for (String kw : ri.template.keywords) {
                                            if (kw.toLowerCase().startsWith(containerSearch)) {
                                                matchedContainer = ri;
                                                break;
                                            }
                                        }
                                        if (matchedContainer != null) break;
                                    }
                                }
                            }
                            
                            // Priority 5: Name contains search term
                            if (matchedContainer == null) {
                                for (ItemDAO.RoomItem ri : allItems) {
                                    if (ri.template.isContainer() && ri.template.name != null 
                                        && ri.template.name.toLowerCase().contains(containerSearch)) {
                                        matchedContainer = ri;
                                        break;
                                    }
                                }
                            }
                            
                            if (matchedContainer == null) {
                                out.println("You don't see a container called '" + lookArgs.trim().substring(3).trim() + "' here.");
                                break;
                            }
                            
                            // Get items in the container
                            java.util.List<ItemDAO.RoomItem> contents = itemDao.getItemsInContainer(matchedContainer.instance.instanceId);
                            
                            out.println(matchedContainer.template.name + " contains:");
                            if (contents.isEmpty()) {
                                out.println("  Nothing.");
                            } else {
                                // Group by template name and count
                                java.util.Map<String, Integer> itemCounts = new java.util.LinkedHashMap<>();
                                for (ItemDAO.RoomItem ci : contents) {
                                    String itemName = ci.template.name != null ? ci.template.name : "an item";
                                    itemCounts.put(itemName, itemCounts.getOrDefault(itemName, 0) + 1);
                                }
                                for (java.util.Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                                    if (entry.getValue() > 1) {
                                        out.println("  " + entry.getKey() + " (x" + entry.getValue() + ")");
                                    } else {
                                        out.println("  " + entry.getKey());
                                    }
                                }
                            }
                        } else {
                            // Look at something specific
                            String searchTerm = lookArgs.trim().toLowerCase();
                            boolean found = false;
                            
                            // Search mobs in the room first
                            MobileDAO mobDao = new MobileDAO();
                            java.util.List<Mobile> roomMobs = mobDao.getMobilesInRoom(lookRoomId);
                            for (Mobile mob : roomMobs) {
                                String mobName = mob.getName().toLowerCase();
                                if (mobName.startsWith(searchTerm)) {
                                    out.println(mob.getName());
                                    String longDesc = mob.getDescription();
                                    if (longDesc != null && !longDesc.isEmpty()) {
                                        out.println(longDesc);
                                    } else {
                                        out.println("You see nothing special about " + mob.getName() + ".");
                                    }
                                    found = true;
                                    break;
                                }
                            }
                            
                            if (!found) {
                                // Search items in the room
                                ItemDAO itemDao = new ItemDAO();
                                java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(lookRoomId);
                                for (ItemDAO.RoomItem ri : roomItems) {
                                    String itemName = ri.template.name != null ? ri.template.name.toLowerCase() : "";
                                    boolean keywordMatch = false;
                                    if (ri.template.keywords != null) {
                                        for (String kw : ri.template.keywords) {
                                            if (kw.toLowerCase().startsWith(searchTerm)) {
                                                keywordMatch = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (itemName.startsWith(searchTerm) || keywordMatch) {
                                        out.println(ri.template.name);
                                        String desc = ri.template.description;
                                        if (desc != null && !desc.isEmpty()) {
                                            out.println(desc);
                                        } else {
                                            out.println("You see nothing special about it.");
                                        }
                                        found = true;
                                        break;
                                    }
                                }
                            }
                            
                            if (!found) {
                                // Search items in inventory
                                Integer charId = dao.getCharacterIdByName(name);
                                if (charId != null) {
                                    ItemDAO itemDao = new ItemDAO();
                                    java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
                                    for (ItemDAO.RoomItem ii : invItems) {
                                        String itemName = ii.template.name != null ? ii.template.name.toLowerCase() : "";
                                        boolean keywordMatch = false;
                                        if (ii.template.keywords != null) {
                                            for (String kw : ii.template.keywords) {
                                                if (kw.toLowerCase().startsWith(searchTerm)) {
                                                    keywordMatch = true;
                                                    break;
                                                }
                                            }
                                        }
                                        if (itemName.startsWith(searchTerm) || keywordMatch) {
                                            out.println(ii.template.name);
                                            String desc = ii.template.description;
                                            if (desc != null && !desc.isEmpty()) {
                                                out.println(desc);
                                            } else {
                                                out.println("You see nothing special about it.");
                                            }
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            if (!found) {
                                out.println("You don't see '" + lookArgs.trim() + "' here.");
                            }
                        }
                        break;
                    }
                    case "say": {
                        String text = cmd.getArgs();
                        if (rec == null || rec.currentRoom == null) { out.println("You are nowhere to say that."); break; }
                        Integer roomId = rec.currentRoom;
                        // Send to everyone in the same room
                        for (ClientHandler s : sessions) {
                            if (s.currentRoomId != null && s.currentRoomId.equals(roomId)) {
                                if (s == this) s.sendRaw("You say: " + text);
                                else s.sendRaw(this.playerName + " says: " + text);
                            }
                        }
                        break;
                    }
                    case "north":
                    case "n":
                    case "east":
                    case "e":
                    case "south":
                    case "s":
                    case "west":
                    case "w":
                    case "up":
                    case "u":
                    case "down":
                    case "d":
                        if (rec == null) {
                            out.println("No character record found.");
                            break;
                        }
                        Integer curRoomId = rec.currentRoom;
                        if (curRoomId == null) {
                            out.println("You are not located in any room.");
                            break;
                        }
                        Room curRoom = dao.getRoomById(curRoomId);
                        if (curRoom == null) {
                            out.println("You seem to be in an unknown place.");
                            break;
                        }
                        Integer destId = null;
                        String directionName = null;
                        switch (cmdName) {
                            case "north":
                            case "n": destId = curRoom.getExitN(); directionName = "north"; break;
                            case "east":
                            case "e": destId = curRoom.getExitE(); directionName = "east"; break;
                            case "south":
                            case "s": destId = curRoom.getExitS(); directionName = "south"; break;
                            case "west":
                            case "w": destId = curRoom.getExitW(); directionName = "west"; break;
                            case "up":
                            case "u": destId = curRoom.getExitU(); directionName = "up"; break;
                            case "down":
                            case "d": destId = curRoom.getExitD(); directionName = "down"; break;
                        }
                        if (destId == null) {
                            out.println("You can't go that way.");
                            break;
                        }
                        // If the destination room id refers to a missing room (broken world), redirect to The Void (id 0)
                        Room maybeDest = (destId == null) ? null : dao.getRoomById(destId);
                        if (destId != null && maybeDest == null) {
                            // send player to void so they don't get stuck
                            destId = 0;
                        }
                        // Persist movement
                        boolean moved = dao.updateCharacterRoom(name, destId);
                        if (!moved) {
                            out.println("You try to move but something prevents you.");
                            break;
                        }
                        // Refresh character record and show new room
                        rec = dao.findByName(name);
                        // update our cached room id
                        this.currentRoomId = rec != null ? rec.currentRoom : null;
                        Room newRoom = dao.getRoomById(destId);
                        if (newRoom == null) {
                            out.println("You arrive at an unknown place.");
                            break;
                        }
                        out.println("You move " + directionName + ".");
                        showRoom(newRoom, destId);
                        break;
                    case "prompt": {
                        String promptArgs = cmd.getArgs();
                        if (promptArgs == null || promptArgs.trim().isEmpty()) {
                            out.println("Prompt: " + promptFormat);
                        } else {
                            // set new prompt format for this session
                            promptFormat = promptArgs;
                            out.println("Prompt set.");
                        }
                        break;
                    }
                    case "cflag": {
                        // GM-only: CFLAG SET <char> <flag> <value>  OR  CFLAG CHECK <char> <flag>
                        if (rec == null) { out.println("No character record found."); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use GM commands.");
                            break;
                        }
                        String cfArgs2b = cmd.getArgs();
                        if (cfArgs2b == null || cfArgs2b.trim().isEmpty()) {
                            out.println("Usage: CFLAG SET <char> <flag> <value>   |   CFLAG CHECK <char> <flag>");
                            break;
                        }
                        String[] parts = cfArgs2b.trim().split("\\s+");
                        String verb = parts.length > 0 ? parts[0].toLowerCase() : "";
                        if (verb.equals("set")) {
                            if (parts.length < 4) { out.println("Usage: CFLAG SET <char> <flag> <value>"); break; }
                            String targetName2 = parts[1];
                            String flagName2 = parts[2];
                            // join remaining parts as the value to allow spaces
                            StringBuilder sbv = new StringBuilder();
                            for (int i = 3; i < parts.length; i++) { if (sbv.length() > 0) sbv.append(' '); sbv.append(parts[i]); }
                            String flagVal2 = sbv.toString();
                            boolean ok3 = dao.setCharacterFlagByName(targetName2, flagName2, flagVal2);
                            if (ok3) out.println("Flag set for " + targetName2 + ": " + flagName2 + " = " + flagVal2);
                            else out.println("Failed to set flag for " + targetName2 + ".");
                            break;
                        } else if (verb.equals("check") || verb.equals("get")) {
                            if (parts.length < 3) { out.println("Usage: CFLAG CHECK <char> <flag>"); break; }
                            String targetName3 = parts[1];
                            String flagName3 = parts[2];
                            String v = dao.getCharacterFlagByName(targetName3, flagName3);
                            if (v == null || v.isEmpty()) {
                                out.println("No flag set for " + targetName3 + " (" + flagName3 + ")");
                            } else {
                                out.println("Flag for " + targetName3 + ": " + flagName3 + " = " + v);
                            }
                            break;
                        } else {
                            out.println("Usage: CFLAG SET <char> <flag> <value>   |   CFLAG CHECK <char> <flag>");
                            break;
                        }
                    }
                    case "cskill": {
                        // GM-only: CSKILL <character> <skill_id> [amount]  OR  CSKILL LIST
                        // Grants a skill to a character at a given proficiency (default 100%)
                        if (rec == null) { out.println("No character record found."); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use GM commands.");
                            break;
                        }
                        String cskillArgs = cmd.getArgs();
                        if (cskillArgs == null || cskillArgs.trim().isEmpty()) {
                            out.println("Usage: CSKILL <character> <skill_id> [amount]");
                            out.println("       CSKILL LIST - List all available skills");
                            out.println("  Grants a skill to a character at the specified proficiency.");
                            out.println("  Amount defaults to 100 (mastered) if not specified.");
                            break;
                        }
                        String[] cskillParts = cskillArgs.trim().split("\\s+");
                        
                        // Handle CSKILL LIST
                        if (cskillParts[0].equalsIgnoreCase("list")) {
                            java.util.List<Skill> allSkills = dao.getAllSkills();
                            if (allSkills.isEmpty()) {
                                out.println("No skills found in the database.");
                                break;
                            }
                            
                            out.println();
                            out.println("===================================================================");
                            out.println("                       AVAILABLE SKILLS");
                            out.println("===================================================================");
                            out.println();
                            
                            // Format: "SkillName (ID)" in 3 columns
                            java.util.List<String> skillDisplays = new java.util.ArrayList<>();
                            for (Skill sk : allSkills) {
                                skillDisplays.add(String.format("%s (%d)", sk.getName(), sk.getId()));
                            }
                            
                            // Print in rows of 3
                            for (int i = 0; i < skillDisplays.size(); i += 3) {
                                String c1 = skillDisplays.get(i);
                                String c2 = (i + 1 < skillDisplays.size()) ? skillDisplays.get(i + 1) : "";
                                String c3 = (i + 2 < skillDisplays.size()) ? skillDisplays.get(i + 2) : "";
                                out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
                            }
                            
                            out.println();
                            out.println("-------------------------------------------------------------------");
                            out.println(String.format("  Total: %d skills", allSkills.size()));
                            out.println("===================================================================");
                            out.println();
                            break;
                        }
                        
                        if (cskillParts.length < 2) {
                            out.println("Usage: CSKILL <character> <skill_id> [amount]");
                            out.println("       CSKILL LIST - List all available skills");
                            break;
                        }
                        String targetCharName = cskillParts[0];
                        int skillId;
                        try {
                            skillId = Integer.parseInt(cskillParts[1]);
                        } catch (NumberFormatException e) {
                            out.println("Invalid skill ID. Must be a number.");
                            break;
                        }
                        int amount = 100; // default to mastered
                        if (cskillParts.length >= 3) {
                            try {
                                amount = Integer.parseInt(cskillParts[2]);
                                if (amount < 1) amount = 1;
                                if (amount > 100) amount = 100;
                            } catch (NumberFormatException e) {
                                out.println("Invalid amount. Must be a number between 1 and 100.");
                                break;
                            }
                        }
                        // Look up target character
                        Integer targetCharId = dao.getCharacterIdByName(targetCharName);
                        if (targetCharId == null) {
                            out.println("Character '" + targetCharName + "' not found.");
                            break;
                        }
                        // Check if skill exists
                        Skill skillDef = dao.getSkillById(skillId);
                        String skillName = skillDef != null ? skillDef.getName() : "Skill #" + skillId;
                        // Set the skill level (will create if doesn't exist, or update if it does)
                        boolean ok = dao.setCharacterSkillLevel(targetCharId, skillId, amount);
                        if (ok) {
                            String profStr = amount >= 100 ? "MASTERED" : amount + "%";
                            out.println("Granted " + targetCharName + " the skill '" + skillName + "' at " + profStr + ".");
                        } else {
                            out.println("Failed to grant skill to " + targetCharName + ".");
                        }
                        break;
                    }
                    case "cspell": {
                        // GM-only: CSPELL <character> <spell_id> [amount]  OR  CSPELL LIST
                        // Grants a spell to a character at a given proficiency (default 100%)
                        if (rec == null) { out.println("No character record found."); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use GM commands.");
                            break;
                        }
                        String cspellArgs = cmd.getArgs();
                        if (cspellArgs == null || cspellArgs.trim().isEmpty()) {
                            out.println("Usage: CSPELL <character> <spell_id> [amount]");
                            out.println("       CSPELL LIST - List all available spells");
                            out.println("  Grants a spell to a character at the specified proficiency.");
                            out.println("  Amount defaults to 100 (mastered) if not specified.");
                            break;
                        }
                        String[] cspellParts = cspellArgs.trim().split("\\s+");
                        
                        // Handle CSPELL LIST
                        if (cspellParts[0].equalsIgnoreCase("list")) {
                            java.util.List<Spell> allSpells = dao.getAllSpells();
                            if (allSpells.isEmpty()) {
                                out.println("No spells found in the database.");
                                break;
                            }
                            
                            // Sort alphabetically by name
                            allSpells.sort((sp1, sp2) -> sp1.getName().compareToIgnoreCase(sp2.getName()));
                            
                            out.println();
                            out.println("===================================================================");
                            out.println("                       AVAILABLE SPELLS");
                            out.println("===================================================================");
                            out.println();
                            
                            // Format: "SpellName (ID)" in 3 columns
                            java.util.List<String> spellDisplays = new java.util.ArrayList<>();
                            for (Spell sp : allSpells) {
                                spellDisplays.add(String.format("%s (%d)", sp.getName(), sp.getId()));
                            }
                            
                            // Print in rows of 3
                            for (int i = 0; i < spellDisplays.size(); i += 3) {
                                String c1 = spellDisplays.get(i);
                                String c2 = (i + 1 < spellDisplays.size()) ? spellDisplays.get(i + 1) : "";
                                String c3 = (i + 2 < spellDisplays.size()) ? spellDisplays.get(i + 2) : "";
                                out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
                            }
                            
                            out.println();
                            out.println("-------------------------------------------------------------------");
                            out.println(String.format("  Total: %d spells", allSpells.size()));
                            out.println("===================================================================");
                            out.println();
                            break;
                        }
                        
                        if (cspellParts.length < 2) {
                            out.println("Usage: CSPELL <character> <spell_id> [amount]");
                            out.println("       CSPELL LIST - List all available spells");
                            break;
                        }
                        String targetSpellCharName = cspellParts[0];
                        int spellId;
                        try {
                            spellId = Integer.parseInt(cspellParts[1]);
                        } catch (NumberFormatException e) {
                            out.println("Invalid spell ID. Must be a number.");
                            break;
                        }
                        int spellAmount = 100; // default to mastered
                        if (cspellParts.length >= 3) {
                            try {
                                spellAmount = Integer.parseInt(cspellParts[2]);
                                if (spellAmount < 1) spellAmount = 1;
                                if (spellAmount > 100) spellAmount = 100;
                            } catch (NumberFormatException e) {
                                out.println("Invalid amount. Must be a number between 1 and 100.");
                                break;
                            }
                        }
                        // Look up target character
                        Integer targetSpellCharId = dao.getCharacterIdByName(targetSpellCharName);
                        if (targetSpellCharId == null) {
                            out.println("Character '" + targetSpellCharName + "' not found.");
                            break;
                        }
                        // Check if spell exists
                        Spell spellDef = dao.getSpellById(spellId);
                        String spellName = spellDef != null ? spellDef.getName() : "Spell #" + spellId;
                        // Set the spell level (will create if doesn't exist, or update if it does)
                        boolean spellOk = dao.setCharacterSpellLevel(targetSpellCharId, spellId, spellAmount);
                        if (spellOk) {
                            String spellProfStr = spellAmount >= 100 ? "MASTERED" : spellAmount + "%";
                            out.println("Granted " + targetSpellCharName + " the spell '" + spellName + "' at " + spellProfStr + ".");
                        } else {
                            out.println("Failed to grant spell to " + targetSpellCharName + ".");
                        }
                        break;
                    }
                    case "ilist": {
                        // GM-only: ILIST <search_string>
                        // Finds all item templates matching the given string
                        if (rec == null) { out.println("No character record found."); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use GM commands.");
                            break;
                        }
                        String ilistArgs = cmd.getArgs();
                        if (ilistArgs == null || ilistArgs.trim().isEmpty()) {
                            out.println("Usage: ILIST <search_string>");
                            out.println("  Searches item templates by name.");
                            break;
                        }
                        String searchStr = ilistArgs.trim();
                        ItemDAO ilistItemDao = new ItemDAO();
                        java.util.List<ItemTemplate> matches = ilistItemDao.searchItemTemplates(searchStr);
                        if (matches.isEmpty()) {
                            out.println("No item templates found matching '" + searchStr + "'.");
                            break;
                        }
                        out.println();
                        out.println(String.format("%-6s %-25s %-12s %s", "ID", "Name", "Type", "Description"));
                        out.println(repeat("-", 75));
                        for (ItemTemplate t : matches) {
                            String typeName = t.type != null ? t.type : "";
                            String desc = t.description != null ? truncate(t.description, 28) : "";
                            out.println(String.format("%-6d %-25s %-12s %s",
                                t.id,
                                truncate(t.name, 25),
                                truncate(typeName, 12),
                                desc));
                        }
                        out.println(repeat("-", 75));
                        out.println(matches.size() + " item(s) found.");
                        out.println();
                        break;
                    }
                    case "ifind": {
                        // GM-only: IFIND <template_id> [world|char|bags|all]
                        // Finds all instances of an item template in the game
                        if (rec == null) { out.println("No character record found."); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use GM commands.");
                            break;
                        }
                        String ifindArgs = cmd.getArgs();
                        if (ifindArgs == null || ifindArgs.trim().isEmpty()) {
                            out.println("Usage: IFIND <template_id> [world|char|bags|all]");
                            out.println("  Finds all instances of a given item template.");
                            out.println("  world = items in rooms, char = in character inventories,");
                            out.println("  bags = in containers, all = all locations (default)");
                            break;
                        }
                        String[] ifindParts = ifindArgs.trim().split("\\s+");
                        int ifindTemplateId;
                        try {
                            ifindTemplateId = Integer.parseInt(ifindParts[0]);
                        } catch (NumberFormatException e) {
                            out.println("Invalid template ID. Must be a number.");
                            break;
                        }
                        String scope = ifindParts.length >= 2 ? ifindParts[1].toLowerCase() : "all";
                        if (!scope.equals("world") && !scope.equals("char") && !scope.equals("bags") && !scope.equals("all")) {
                            out.println("Invalid scope. Use: world, char, bags, or all");
                            break;
                        }
                        
                        ItemDAO ifindItemDao = new ItemDAO();
                        ItemTemplate ifindTemplate = ifindItemDao.getTemplateById(ifindTemplateId);
                        String templateName = ifindTemplate != null ? ifindTemplate.name : "Unknown";
                        
                        java.util.List<ItemInstance> allInstances = ifindItemDao.findInstancesByTemplateId(ifindTemplateId);
                        if (allInstances.isEmpty()) {
                            out.println("No instances of template #" + ifindTemplateId + " (" + templateName + ") found.");
                            break;
                        }
                        
                        // Separate by location type
                        java.util.List<ItemInstance> inRooms = new java.util.ArrayList<>();
                        java.util.List<ItemInstance> inChars = new java.util.ArrayList<>();
                        java.util.List<ItemInstance> inContainers = new java.util.ArrayList<>();
                        
                        for (ItemInstance inst : allInstances) {
                            if (inst.locationRoomId != null) inRooms.add(inst);
                            else if (inst.ownerCharacterId != null) inChars.add(inst);
                            else if (inst.containerInstanceId != null) inContainers.add(inst);
                        }
                        
                        out.println();
                        out.println("=== Instances of #" + ifindTemplateId + " (" + templateName + ") ===");
                        
                        // Items in rooms (world)
                        if (scope.equals("world") || scope.equals("all")) {
                            out.println();
                            out.println("--- In Rooms (" + inRooms.size() + ") ---");
                            if (inRooms.isEmpty()) {
                                out.println("  (none)");
                            } else {
                                out.println(String.format("  %-10s %-6s %-20s %-6s %s", "InstID", "TplID", "Name", "RoomID", "Room Name"));
                                for (ItemInstance inst : inRooms) {
                                    Room rm = dao.getRoomById(inst.locationRoomId);
                                    String roomName = rm != null ? rm.getName() : "Unknown";
                                    out.println(String.format("  %-10d %-6d %-20s %-6d %s",
                                        inst.instanceId, inst.templateId, truncate(templateName, 20),
                                        inst.locationRoomId, truncate(roomName, 25)));
                                }
                            }
                        }
                        
                        // Items on characters (char)
                        if (scope.equals("char") || scope.equals("all")) {
                            out.println();
                            out.println("--- In Character Inventories (" + inChars.size() + ") ---");
                            if (inChars.isEmpty()) {
                                out.println("  (none)");
                            } else {
                                out.println(String.format("  %-10s %-6s %-20s %-12s %-6s %s", "InstID", "TplID", "Name", "CharName", "RoomID", "Room Name"));
                                for (ItemInstance inst : inChars) {
                                    String charName = dao.getCharacterNameById(inst.ownerCharacterId);
                                    if (charName == null) charName = "Char#" + inst.ownerCharacterId;
                                    CharacterRecord charRec = dao.getCharacterById(inst.ownerCharacterId);
                                    Integer charRoomId = charRec != null ? charRec.currentRoom : null;
                                    Room charRoom = charRoomId != null ? dao.getRoomById(charRoomId) : null;
                                    String charRoomName = charRoom != null ? charRoom.getName() : "Unknown";
                                    out.println(String.format("  %-10d %-6d %-20s %-12s %-6s %s",
                                        inst.instanceId, inst.templateId, truncate(templateName, 20),
                                        truncate(charName, 12),
                                        charRoomId != null ? String.valueOf(charRoomId) : "?",
                                        truncate(charRoomName, 20)));
                                }
                            }
                        }
                        
                        // Items in containers (bags)
                        if (scope.equals("bags") || scope.equals("all")) {
                            out.println();
                            out.println("--- In Containers (" + inContainers.size() + ") ---");
                            if (inContainers.isEmpty()) {
                                out.println("  (none)");
                            } else {
                                out.println(String.format("  %-10s %-6s %-20s %-10s %-6s %s", "InstID", "TplID", "Name", "ContInstID", "ContID", "Container Name"));
                                for (ItemInstance inst : inContainers) {
                                    // Look up the container instance to get its template
                                    ItemInstance containerInst = ifindItemDao.getInstanceById(inst.containerInstanceId);
                                    int containerId = containerInst != null ? containerInst.templateId : 0;
                                    ItemTemplate containerTpl = containerInst != null ? ifindItemDao.getTemplateById(containerInst.templateId) : null;
                                    String containerName = containerTpl != null ? containerTpl.name : "Unknown";
                                    out.println(String.format("  %-10d %-6d %-20s %-10d %-6d %s",
                                        inst.instanceId, inst.templateId, truncate(templateName, 20),
                                        inst.containerInstanceId, containerId, truncate(containerName, 20)));
                                }
                            }
                        }
                        
                        out.println();
                        int total = (scope.equals("world") ? inRooms.size() : 0)
                                  + (scope.equals("char") ? inChars.size() : 0)
                                  + (scope.equals("bags") ? inContainers.size() : 0)
                                  + (scope.equals("all") ? allInstances.size() : 0);
                        out.println("Total: " + total + " instance(s) found.");
                        out.println();
                        break;
                    }
                    case "goto": {
                        // GM-only: GOTO <room_id>
                        // Teleport directly to a room
                        if (rec == null) { out.println("No character record found."); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use GM commands.");
                            break;
                        }
                        String gotoArgs = cmd.getArgs();
                        if (gotoArgs == null || gotoArgs.trim().isEmpty()) {
                            out.println("Usage: GOTO <room_id>");
                            out.println("  Teleports you directly to a room.");
                            break;
                        }
                        int gotoRoomId;
                        try {
                            gotoRoomId = Integer.parseInt(gotoArgs.trim());
                        } catch (NumberFormatException e) {
                            out.println("Invalid room ID. Must be a number.");
                            break;
                        }
                        Room gotoRoom = dao.getRoomById(gotoRoomId);
                        if (gotoRoom == null) {
                            out.println("Room #" + gotoRoomId + " does not exist.");
                            break;
                        }
                        // Teleport the character
                        dao.updateCharacterRoom(name, gotoRoomId);
                        rec = dao.findByName(name);
                        out.println();
                        out.println("You vanish and reappear in " + gotoRoom.getName() + ".");
                        out.println();
                        // Show the new room
                        out.println(gotoRoom.getName());
                        out.println(gotoRoom.getShortDesc());
                        break;
                    }
                    case "kill":
                    case "attack":
                    case "fight": {
                        // KILL <target> - initiate combat with a mobile in the room
                        if (rec == null) {
                            out.println("No character record found.");
                            break;
                        }
                        if (rec.currentRoom == null) {
                            out.println("You are not in any room.");
                            break;
                        }
                        String killArgs = cmd.getArgs();
                        if (killArgs == null || killArgs.trim().isEmpty()) {
                            out.println("Usage: kill <target>");
                            out.println("  Initiate combat with a mobile in the room.");
                            break;
                        }
                        
                        // Check if already in combat
                        CombatManager combatMgr = CombatManager.getInstance();
                        Integer charId = this.characterId;
                        if (charId == null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        if (combatMgr.isInCombat(charId)) {
                            out.println("You are already in combat!");
                            out.println("Use combat commands or wait for combat to end.");
                            break;
                        }
                        
                        // Find a mobile in the room matching the target name
                        MobileDAO mobileDao = new MobileDAO();
                        List<Mobile> mobilesInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
                        if (mobilesInRoom.isEmpty()) {
                            out.println("There is nothing here to attack.");
                            break;
                        }
                        
                        // Smart matching for target
                        String targetSearch = killArgs.trim().toLowerCase();
                        Mobile targetMob = null;
                        
                        // Priority 1: Exact name match
                        for (Mobile mob : mobilesInRoom) {
                            if (mob.getName().equalsIgnoreCase(targetSearch)) {
                                targetMob = mob;
                                break;
                            }
                        }
                        
                        // Priority 2: Name starts with search
                        if (targetMob == null) {
                            for (Mobile mob : mobilesInRoom) {
                                if (mob.getName().toLowerCase().startsWith(targetSearch)) {
                                    targetMob = mob;
                                    break;
                                }
                            }
                        }
                        
                        // Priority 3: Name contains search
                        if (targetMob == null) {
                            for (Mobile mob : mobilesInRoom) {
                                if (mob.getName().toLowerCase().contains(targetSearch)) {
                                    targetMob = mob;
                                    break;
                                }
                            }
                        }
                        
                        // Priority 4: Short desc contains search
                        if (targetMob == null) {
                            for (Mobile mob : mobilesInRoom) {
                                String shortDesc = mob.getShortDesc();
                                if (shortDesc != null && shortDesc.toLowerCase().contains(targetSearch)) {
                                    targetMob = mob;
                                    break;
                                }
                            }
                        }
                        
                        if (targetMob == null) {
                            out.println("You don't see '" + killArgs + "' here.");
                            break;
                        }
                        
                        // Check if target can be attacked
                        if (targetMob.isImmortal()) {
                            out.println(targetMob.getName() + " cannot be attacked.");
                            break;
                        }
                        
                        if (targetMob.isDead()) {
                            out.println(targetMob.getName() + " is already dead.");
                            break;
                        }
                        
                        // Create a Character object for this player for combat
                        Character playerChar = new Character(
                            rec.name, rec.age, rec.description,
                            rec.hpMax, rec.hpCur, rec.mpMax, rec.mpCur, rec.mvMax, rec.mvCur,
                            rec.currentRoom,
                            rec.str, rec.dex, rec.con, rec.intel, rec.wis, rec.cha,
                            rec.getArmorTotal(), rec.getFortitudeTotal(), 
                            rec.getReflexTotal(), rec.getWillTotal()
                        );
                        
                        // Initiate combat
                        Combat combat = combatMgr.initiateCombat(playerChar, charId, targetMob, rec.currentRoom);
                        if (combat != null) {
                            out.println("You attack " + targetMob.getName() + "!");
                            out.println("=== COMBAT BEGINS ===");
                        } else {
                            out.println("Failed to initiate combat.");
                        }
                        break;
                    }
                    case "combat": {
                        // COMBAT - show current combat status
                        Integer charId = this.characterId;
                        if (charId == null && name != null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        CombatManager combatMgr = CombatManager.getInstance();
                        if (!combatMgr.isInCombat(charId)) {
                            out.println("You are not in combat.");
                            break;
                        }
                        Combat combat = combatMgr.getCombatForCharacter(charId);
                        if (combat == null) {
                            out.println("You are not in combat.");
                            break;
                        }
                        
                        out.println("=== Combat Status ===");
                        out.println("Round: " + combat.getCurrentRound());
                        out.println("State: " + combat.getState().getDisplayName());
                        out.println();
                        out.println("Combatants:");
                        for (Combatant c : combat.getActiveCombatants()) {
                            String indicator = c.isPlayer() ? "[Player] " : "[Mob] ";
                            int hpPct = c.getHpMax() > 0 ? (c.getHpCurrent() * 100) / c.getHpMax() : 0;
                            String hpBar = getHpBar(hpPct);
                            out.println("  " + indicator + c.getName() + " " + hpBar + " (" + c.getHpCurrent() + "/" + c.getHpMax() + " HP)");
                        }
                        out.println();
                        out.println("Recent combat log:");
                        String log = combat.getRecentLog(5);
                        if (!log.isEmpty()) {
                            for (String logLine : log.split("\n")) {
                                out.println("  " + logLine);
                            }
                        } else {
                            out.println("  (no events yet)");
                        }
                        break;
                    }
                    case "flee": {
                        // FLEE - attempt to escape from combat (not fully implemented yet)
                        Integer charId = this.characterId;
                        if (charId == null && name != null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        CombatManager combatMgr = CombatManager.getInstance();
                        if (!combatMgr.isInCombat(charId)) {
                            out.println("You are not in combat.");
                            break;
                        }
                        out.println("You attempt to flee...");
                        // TODO: Implement flee logic (check DEX, allow escape, remove from combat)
                        out.println("Fleeing is not yet implemented. Fight to the death!");
                        break;
                    }
                    case "quit":
                        out.println("Goodbye!");
                        socket.close();
                        return;
                    case "who": {
                        // WHO - List all connected players
                        out.println();
                        out.println("===================================================================");
                        out.println("  Players Online");
                        out.println("===================================================================");
                        
                        CharacterClassDAO classDao = new CharacterClassDAO();
                        try {
                            classDao.loadClassesFromYamlResource("/data/classes.yaml");
                        } catch (Exception ignored) {}
                        
                        int count = 0;
                        for (ClientHandler session : sessions) {
                            String pName = session.playerName;
                            if (pName == null) continue; // Not yet logged in
                            
                            // Look up character info
                            CharacterRecord pRec = dao.findByName(pName);
                            if (pRec == null) continue;
                            
                            Integer pCharId = dao.getCharacterIdByName(pName);
                            Integer pClassId = pRec.currentClassId;
                            CharacterClass pClass = pClassId != null ? classDao.getClassById(pClassId) : null;
                            int pLevel = (pClassId != null && pCharId != null) 
                                ? classDao.getCharacterClassLevel(pCharId, pClassId) : 0;
                            
                            String className = pClass != null ? pClass.name : "Adventurer";
                            String desc = pRec.description != null && !pRec.description.isEmpty() 
                                ? pRec.description : "";
                            
                            // Format: [Lv ##] ClassName     PlayerName - Description
                            out.println(String.format("  [Lv %2d] %-12s  %-15s %s",
                                pLevel, className, pName, 
                                desc.isEmpty() ? "" : "- " + truncate(desc, 30)));
                            count++;
                        }
                        
                        out.println("-------------------------------------------------------------------");
                        out.println(String.format("  %d player%s online", count, count == 1 ? "" : "s"));
                        out.println("===================================================================");
                        out.println();
                        break;
                    }
                    case "chat": {
                        String t = cmd.getArgs();
                        if (t == null || t.trim().isEmpty()) { out.println("Usage: chat <message>"); break; }
                        broadcastAll("[chat] " + this.playerName + ": " + t);
                        break;
                    }
                    case "yell": {
                        String t = cmd.getArgs();
                        if (t == null || t.trim().isEmpty()) { out.println("Usage: yell <message>"); break; }
                        // find our area
                        Integer roomId = rec != null ? rec.currentRoom : null;
                        if (roomId == null) { out.println("You are nowhere to yell from."); break; }
                        Room roomObj = dao.getRoomById(roomId);
                        if (roomObj == null) { out.println("You are nowhere to yell from."); break; }
                        int areaId = roomObj.getAreaId();
                        broadcastArea(dao, areaId, "[yell] " + this.playerName + ": " + t);
                        break;
                    }
                    case "whisper": {
                        String args = cmd.getArgs();
                        if (args == null || args.trim().isEmpty()) { out.println("Usage: whisper <target> <message>"); break; }
                        String[] parts = args.trim().split("\\s+", 2);
                        if (parts.length < 2) { out.println("Usage: whisper <target> <message>"); break; }
                        String target = parts[0];
                        String msg = parts[1];
                        ClientHandler t = nameToSession.get(target.toLowerCase());
                        if (t == null) { out.println("No such player online: " + target); break; }
                        t.sendRaw("[whisper] " + this.playerName + " -> you: " + msg);
                        this.sendRaw("[whisper] you -> " + target + ": " + msg);
                        break;
                    }
                    case "gmchat": {
                        String t = cmd.getArgs();
                        if (t == null || t.trim().isEmpty()) { out.println("Usage: gmchat <message>"); break; }
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to use gmchat."); break; }
                        gmBroadcast(dao, this.playerName, t);
                        break;
                    }
                    case "dbinfo": {
                        // GM-only: prints table schema information
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to use dbinfo."); break; }
                        // Ensure item tables/migrations are applied by constructing ItemDAO
                        try { new ItemDAO(); } catch (Exception ignored) {}

                        String targs = cmd.getArgs();
                        String[] tables;
                        if (targs == null || targs.trim().isEmpty()) {
                            tables = new String[] { "item_template", "item_instance", "character_equipment" };
                        } else {
                            tables = new String[] { targs.trim() };
                        }
                        for (String tbl : tables) {
                            out.println("Table: " + tbl);
                            java.util.List<String> cols = dao.listTableColumns(tbl);
                            if (cols == null || cols.isEmpty()) {
                                out.println("  <no columns or table does not exist>");
                            } else {
                                for (String c : cols) out.println("  " + c);
                            }
                        }
                        break;
                    }
                    case "motd": {
                        String margs = cmd.getArgs();
                        if (margs == null || margs.trim().isEmpty()) {
                            String motd = dao.getSetting("motd");
                            if (motd == null || motd.trim().isEmpty()) out.println("No MOTD is set.");
                            else {
                                out.println("--- Message of the Day ---");
                                String[] motdLines = motd.split("\\r?\\n");
                                for (String ml : motdLines) out.println(ml);
                                out.println("--- End of MOTD ---");
                            }
                        } else {
                            // GM-only: motd set <message>
                            String[] ma = margs.trim().split("\\s+", 2);
                            String verb = ma[0].toLowerCase();
                            if (verb.equals("set")) {
                                if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to set the MOTD."); break; }
                                String val = ma.length > 1 ? ma[1] : "";
                                // support literal "\n" sequences for multi-line MOTD
                                try {
                                    if (val != null) {
                                        val = val.replace("\\r\\n", "\n").replace("\\n", "\n");
                                    }
                                } catch (Exception ignored) {}
                                boolean ok = dao.setSetting("motd", val == null ? "" : val);
                                if (ok) out.println("MOTD updated (\n sequences allowed for newlines)."); else out.println("Failed to update MOTD.");
                            } else if (verb.equals("clear") || verb.equals("remove")) {
                                if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to clear the MOTD."); break; }
                                boolean ok = dao.setSetting("motd", "");
                                if (ok) out.println("MOTD cleared."); else out.println("Failed to clear MOTD.");
                            } else {
                                out.println("Usage: motd [set <message>|clear]");
                            }
                        }
                        break;
                    }
                    case "save": {
                        // Persist mutable character state for this player
                        if (rec == null) { out.println("No character record found."); break; }
                        boolean ok = dao.saveCharacterStateByName(name, rec.hpCur, rec.mpCur, rec.mvCur, rec.currentRoom);
                        if (ok) out.println("Character saved."); else out.println("Failed to save character.");
                        break;
                    }
                    case "spawn": {
                        // GM-only: SPAWN ITEM <template_id> [room_id]   or   SPAWN MOB <mob_id> [room_id]
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use spawn.");
                            break;
                        }
                        String spawnArgs = cmd.getArgs();
                        if (spawnArgs == null || spawnArgs.trim().isEmpty()) {
                            out.println("Usage: SPAWN ITEM <template_id> [room_id]");
                            out.println("       SPAWN MOB <template_id> [room_id]");
                            break;
                        }
                        String[] sp = spawnArgs.trim().split("\\s+");
                        if (sp.length < 2) {
                            out.println("Usage: SPAWN ITEM <template_id> [room_id]");
                            out.println("       SPAWN MOB <template_id> [room_id]");
                            break;
                        }
                        String spawnType = sp[0].toUpperCase();
                        if (spawnType.equals("ITEM")) {
                            int templateId;
                            try {
                                templateId = Integer.parseInt(sp[1]);
                            } catch (NumberFormatException e) {
                                out.println("Invalid template ID: " + sp[1]);
                                break;
                            }
                            Integer targetRoomId = rec != null ? rec.currentRoom : null;
                            if (sp.length >= 3) {
                                try {
                                    targetRoomId = Integer.parseInt(sp[2]);
                                } catch (NumberFormatException e) {
                                    out.println("Invalid room ID: " + sp[2]);
                                    break;
                                }
                            }
                            if (targetRoomId == null) {
                                out.println("No room specified and you are not in a room.");
                                break;
                            }
                            // Validate template exists
                            ItemDAO itemDao = new ItemDAO();
                            if (!itemDao.templateExists(templateId)) {
                                out.println("No item template found with ID " + templateId);
                                break;
                            }
                            // Create the instance in the target room
                            long instanceId = itemDao.createInstance(templateId, targetRoomId, null);
                            if (instanceId < 0) {
                                out.println("Failed to create item instance.");
                                break;
                            }
                            ItemTemplate tmpl = itemDao.getTemplateById(templateId);
                            String itemName = tmpl != null && tmpl.name != null ? tmpl.name : "item #" + templateId;
                            out.println("Spawned " + itemName + " (instance #" + instanceId + ") in room " + targetRoomId + ".");
                        } else if (spawnType.equals("MOB")) {
                            int mobTemplateId;
                            try {
                                mobTemplateId = Integer.parseInt(sp[1]);
                            } catch (NumberFormatException e) {
                                out.println("Invalid mob template ID: " + sp[1]);
                                break;
                            }
                            Integer targetRoomId = rec != null ? rec.currentRoom : null;
                            if (sp.length >= 3) {
                                try {
                                    targetRoomId = Integer.parseInt(sp[2]);
                                } catch (NumberFormatException e) {
                                    out.println("Invalid room ID: " + sp[2]);
                                    break;
                                }
                            }
                            if (targetRoomId == null) {
                                out.println("No room specified and you are not in a room.");
                                break;
                            }
                            // Validate template exists
                            MobileDAO mobDao = new MobileDAO();
                            MobileTemplate mobTemplate = mobDao.getTemplateById(mobTemplateId);
                            if (mobTemplate == null) {
                                out.println("No mob template found with ID " + mobTemplateId);
                                break;
                            }
                            // Spawn the mobile instance in the target room
                            Mobile spawnedMob = mobDao.spawnMobile(mobTemplate, targetRoomId);
                            if (spawnedMob == null) {
                                out.println("Failed to spawn mobile instance.");
                                break;
                            }
                            out.println("Spawned " + spawnedMob.getName() + " (instance #" + spawnedMob.getInstanceId() + ") in room " + targetRoomId + ".");
                        } else {
                            out.println("Unknown spawn type: " + spawnType);
                            out.println("Usage: SPAWN ITEM <template_id> [room_id]");
                        }
                        break;
                    }
                    case "system": {
                        String t = cmd.getArgs();
                        if (t == null || t.trim().isEmpty()) { out.println("Usage: system <message>"); break; }
                        // system messages are broadcast to all
                        broadcastAll("[system] " + t);
                        break;
                    }
                    case "peace": {
                        // GM-only: End all combat in the current room
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use peace.");
                            break;
                        }
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You are not in a room.");
                            break;
                        }
                        Combat roomCombat = CombatManager.getInstance().getCombatInRoom(rec.currentRoom);
                        if (roomCombat == null || !roomCombat.isActive()) {
                            out.println("There is no active combat in this room.");
                            break;
                        }
                        CombatManager.getInstance().endCombat(roomCombat);
                        out.println("You wave your hand and combat ceases.");
                        broadcastRoomMessage(rec.currentRoom, name + " waves their hand and all combat in the room ceases.");
                        break;
                    }
                    case "restore": {
                        // GM-only: RESTORE [character] - restore HP/MP/MV to full
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use restore.");
                            break;
                        }
                        String restoreArgs = cmd.getArgs();
                        String targetName = (restoreArgs == null || restoreArgs.trim().isEmpty()) 
                            ? name : restoreArgs.trim();
                        
                        CharacterDAO.CharacterRecord targetRec = dao.findByName(targetName);
                        if (targetRec == null) {
                            out.println("Character '" + targetName + "' not found.");
                            break;
                        }
                        
                        // Restore to full
                        dao.saveCharacterStateByName(targetName, 
                            targetRec.hpMax, targetRec.mpMax, targetRec.mvMax, 
                            targetRec.currentRoom);
                        
                        // If target is in combat, also update their combatant
                        Integer targetCharId = dao.getCharacterIdByName(targetName);
                        if (targetCharId != null) {
                            Combat combat = CombatManager.getInstance().getCombatForCharacter(targetCharId);
                            if (combat != null) {
                                Combatant combatant = combat.findByCharacterId(targetCharId);
                                if (combatant != null) {
                                    Character c = combatant.getAsCharacter();
                                    if (c != null) {
                                        c.setHpCur(c.getHpMax());
                                        c.setMpCur(c.getMpMax());
                                        c.setMvCur(c.getMvMax());
                                    }
                                }
                            }
                        }
                        
                        if (targetName.equalsIgnoreCase(name)) {
                            out.println("You feel fully restored!");
                        } else {
                            out.println("You restore " + targetRec.name + " to full health.");
                            // Notify target if online
                            ClientHandler targetHandler = nameToSession.get(targetName.toLowerCase());
                            if (targetHandler != null) {
                                targetHandler.sendRaw("You feel a surge of divine energy and are fully restored!");
                            }
                        }
                        break;
                    }
                    case "promote": {
                        // GM-only: PROMOTE <char> [level_target]
                        // Levels up a character. If level_target specified, levels them up to that level.
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use promote.");
                            break;
                        }
                        String promoteArgs = cmd.getArgs();
                        if (promoteArgs == null || promoteArgs.trim().isEmpty()) {
                            out.println("Usage: PROMOTE <character> [level_target]");
                            out.println("  Levels up a character once, or to the specified level.");
                            break;
                        }
                        
                        String[] promoteParts = promoteArgs.trim().split("\\s+");
                        String promoteTargetName = promoteParts[0];
                        Integer targetLevel = null;
                        
                        if (promoteParts.length > 1) {
                            try {
                                targetLevel = Integer.parseInt(promoteParts[1]);
                            } catch (NumberFormatException e) {
                                out.println("Invalid level target: " + promoteParts[1]);
                                break;
                            }
                        }
                        
                        // Find character
                        Integer promoteCharId = dao.getCharacterIdByName(promoteTargetName);
                        if (promoteCharId == null) {
                            out.println("Character '" + promoteTargetName + "' not found.");
                            break;
                        }
                        
                        CharacterClassDAO classDAO = new CharacterClassDAO();
                        Integer promoteClassId = classDAO.getCharacterCurrentClassId(promoteCharId);
                        if (promoteClassId == null) {
                            out.println(promoteTargetName + " has no class assigned.");
                            break;
                        }
                        
                        int currentLevel = classDAO.getCharacterClassLevel(promoteCharId, promoteClassId);
                        
                        // Validate target level if specified
                        if (targetLevel != null) {
                            if (targetLevel < 1 || targetLevel > 60) {
                                out.println("Level target must be between 1 and 60.");
                                break;
                            }
                            if (targetLevel <= currentLevel) {
                                out.println(promoteTargetName + " is already level " + currentLevel + 
                                    ". Cannot promote to level " + targetLevel + " (demotion not implemented).");
                                break;
                            }
                        } else {
                            // Default: promote by one level
                            targetLevel = currentLevel + 1;
                            if (targetLevel > 60) {
                                out.println(promoteTargetName + " is already at maximum level.");
                                break;
                            }
                        }
                        
                        // Get target handler for messaging
                        ClientHandler promoteTargetHandler = nameToSession.get(promoteTargetName.toLowerCase());
                        java.util.function.Consumer<String> msgCallback = null;
                        if (promoteTargetHandler != null) {
                            final ClientHandler handler = promoteTargetHandler;
                            msgCallback = msg -> handler.sendRaw(msg);
                        }
                        
                        // Loop: set XP to 1000 and trigger level-up until we reach target level
                        int levelsGained = 0;
                        while (currentLevel < targetLevel) {
                            // Set XP to trigger level-up
                            boolean leveledUp = classDAO.addXpToCurrentClass(promoteCharId, CharacterClass.XP_PER_LEVEL);
                            if (!leveledUp) {
                                out.println("Failed to level up " + promoteTargetName + " (unexpected error).");
                                break;
                            }
                            
                            currentLevel++;
                            levelsGained++;
                            
                            // Notify target of level-up
                            if (promoteTargetHandler != null) {
                                promoteTargetHandler.sendRaw("You have reached level " + currentLevel + "!");
                            }
                            
                            // Process level-up benefits
                            classDAO.processLevelUp(promoteCharId, currentLevel, msgCallback);
                        }
                        
                        if (levelsGained > 0) {
                            out.println("Promoted " + promoteTargetName + " by " + levelsGained + 
                                " level(s) to level " + currentLevel + ".");
                        }
                        break;
                    }
                    case "groupchat": {
                        out.println("Group chat is not implemented yet.");
                        break;
                    }
                    case "get":
                    case "pickup": {
                        // GET <item_name>  or  GET ALL  or  GET <item> <container>  or  GET ALL <container>
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You are nowhere to pick anything up.");
                            break;
                        }
                        String getArgs = cmd.getArgs();
                        if (getArgs == null || getArgs.trim().isEmpty()) {
                            out.println("Usage: get <item_name>  or  get all  or  get <item> <container>");
                            break;
                        }
                        String itemArg = getArgs.trim();
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }

                        // Check if we're getting from a container
                        // Parse: "get <item> <container>" or "get all <container>"
                        // Strategy: try to find a container match from the end of the args
                        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
                        java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
                        java.util.List<ItemDAO.RoomItem> allContainers = new java.util.ArrayList<>();
                        for (ItemDAO.RoomItem ri : roomItems) {
                            if (ri.template.isContainer()) allContainers.add(ri);
                        }
                        for (ItemDAO.RoomItem ri : invItems) {
                            if (ri.template.isContainer()) allContainers.add(ri);
                        }
                        
                        // Try to match a container from the args
                        ItemDAO.RoomItem matchedContainer = null;
                        String itemSearchPart = itemArg;
                        
                        // Try progressively shorter suffixes to find a container match
                        String[] words = itemArg.split("\\s+");
                        for (int i = words.length - 1; i >= 1; i--) {
                            // Build container search from words[i] to end
                            StringBuilder containerSearch = new StringBuilder();
                            for (int j = i; j < words.length; j++) {
                                if (containerSearch.length() > 0) containerSearch.append(" ");
                                containerSearch.append(words[j]);
                            }
                            String containerSearchLower = containerSearch.toString().toLowerCase();
                            
                            // Try to match container
                            for (ItemDAO.RoomItem ri : allContainers) {
                                String cname = ri.template.name != null ? ri.template.name.toLowerCase() : "";
                                boolean match = cname.equalsIgnoreCase(containerSearchLower) 
                                    || cname.startsWith(containerSearchLower)
                                    || cname.contains(containerSearchLower);
                                if (!match && ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.toLowerCase().startsWith(containerSearchLower)) {
                                            match = true;
                                            break;
                                        }
                                    }
                                }
                                if (match) {
                                    matchedContainer = ri;
                                    // Build item search part from words[0] to words[i-1]
                                    StringBuilder itemPart = new StringBuilder();
                                    for (int j = 0; j < i; j++) {
                                        if (itemPart.length() > 0) itemPart.append(" ");
                                        itemPart.append(words[j]);
                                    }
                                    itemSearchPart = itemPart.toString();
                                    break;
                                }
                            }
                            if (matchedContainer != null) break;
                        }
                        
                        // If getting from a container
                        if (matchedContainer != null) {
                            java.util.List<ItemDAO.RoomItem> containerContents = itemDao.getItemsInContainer(matchedContainer.instance.instanceId);
                            
                            if (itemSearchPart.equalsIgnoreCase("all")) {
                                // Get all from container
                                if (containerContents.isEmpty()) {
                                    out.println(matchedContainer.template.name + " is empty.");
                                    break;
                                }
                                int count = 0;
                                for (ItemDAO.RoomItem ci : containerContents) {
                                    itemDao.moveInstanceToCharacter(ci.instance.instanceId, charId);
                                    out.println("You get " + (ci.template.name != null ? ci.template.name : "an item") + " from " + matchedContainer.template.name + ".");
                                    count++;
                                }
                                if (count > 1) out.println("Got " + count + " items from " + matchedContainer.template.name + ".");
                                break;
                            } else {
                                // Get specific item from container
                                String searchLower = itemSearchPart.toLowerCase();
                                ItemDAO.RoomItem matchedItem = null;
                                
                                // Smart match within container contents
                                // Priority 1: Exact name match
                                for (ItemDAO.RoomItem ci : containerContents) {
                                    if (ci.template.name != null && ci.template.name.equalsIgnoreCase(itemSearchPart)) {
                                        matchedItem = ci;
                                        break;
                                    }
                                }
                                // Priority 2: Name word starts with search
                                if (matchedItem == null) {
                                    for (ItemDAO.RoomItem ci : containerContents) {
                                        if (ci.template.name != null) {
                                            String[] nameWords = ci.template.name.toLowerCase().split("\\s+");
                                            for (String w : nameWords) {
                                                if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                                    matchedItem = ci;
                                                    break;
                                                }
                                            }
                                            if (matchedItem != null) break;
                                        }
                                    }
                                }
                                // Priority 3: Keyword match
                                if (matchedItem == null) {
                                    for (ItemDAO.RoomItem ci : containerContents) {
                                        if (ci.template.keywords != null) {
                                            for (String kw : ci.template.keywords) {
                                                if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                                    matchedItem = ci;
                                                    break;
                                                }
                                            }
                                            if (matchedItem != null) break;
                                        }
                                    }
                                }
                                // Priority 4: Name starts with search
                                if (matchedItem == null) {
                                    for (ItemDAO.RoomItem ci : containerContents) {
                                        if (ci.template.name != null && ci.template.name.toLowerCase().startsWith(searchLower)) {
                                            matchedItem = ci;
                                            break;
                                        }
                                    }
                                }
                                // Priority 5: Name contains search
                                if (matchedItem == null) {
                                    for (ItemDAO.RoomItem ci : containerContents) {
                                        if (ci.template.name != null && ci.template.name.toLowerCase().contains(searchLower)) {
                                            matchedItem = ci;
                                            break;
                                        }
                                    }
                                }
                                
                                if (matchedItem == null) {
                                    out.println("You don't see '" + itemSearchPart + "' in " + matchedContainer.template.name + ".");
                                    break;
                                }
                                
                                itemDao.moveInstanceToCharacter(matchedItem.instance.instanceId, charId);
                                out.println("You get " + (matchedItem.template.name != null ? matchedItem.template.name : "an item") + " from " + matchedContainer.template.name + ".");
                                break;
                            }
                        }

                        // Handle "get all" (from room)
                        if (itemArg.equalsIgnoreCase("all")) {
                            if (roomItems.isEmpty()) {
                                out.println("There is nothing here to pick up.");
                                break;
                            }
                            int count = 0;
                            int skipped = 0;
                            for (ItemDAO.RoomItem ri : roomItems) {
                                // Skip immobile items
                                if (ri.template.isImmobile()) {
                                    skipped++;
                                    continue;
                                }
                                itemDao.moveInstanceToCharacter(ri.instance.instanceId, charId);
                                out.println("You pick up " + (ri.template.name != null ? ri.template.name : "an item") + ".");
                                count++;
                            }
                            if (count > 1) out.println("Picked up " + count + " items.");
                            else if (count == 0 && skipped > 0) out.println("There is nothing here you can pick up.");
                            break;
                        }

                        // Smart matching: find items in the room whose name or keywords match
                        if (roomItems.isEmpty()) {
                            out.println("There is nothing here to pick up.");
                            break;
                        }

                        // Try to find best match
                        ItemDAO.RoomItem matched = null;
                        String searchLower = itemArg.toLowerCase();

                        // Priority 1: Exact name match (case-insensitive)
                        for (ItemDAO.RoomItem ri : roomItems) {
                            if (ri.template.name != null && ri.template.name.equalsIgnoreCase(itemArg)) {
                                matched = ri;
                                break;
                            }
                        }

                        // Priority 2: Name contains the search term as a word
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                if (ri.template.name != null) {
                                    String nameLower = ri.template.name.toLowerCase();
                                    // Check if any word in the name matches
                                    String[] nameWords = nameLower.split("\\s+");
                                    for (String w : nameWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }

                        // Priority 3: Keyword match
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                if (ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }

                        // Priority 4: Partial name match (name starts with search term)
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        // Priority 5: Name contains search term anywhere
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        if (matched == null) {
                            out.println("You don't see '" + itemArg + "' here.");
                            break;
                        }

                        // Check if item is immobile
                        if (matched.template.isImmobile()) {
                            out.println("You can't pick that up.");
                            break;
                        }

                        // Move item to character's inventory
                        itemDao.moveInstanceToCharacter(matched.instance.instanceId, charId);
                        out.println("You pick up " + (matched.template.name != null ? matched.template.name : "an item") + ".");
                        break;
                    }
                    case "drop": {
                        // DROP <item_name>  or  DROP ALL
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You are nowhere to drop anything.");
                            break;
                        }
                        String dropArgs = cmd.getArgs();
                        if (dropArgs == null || dropArgs.trim().isEmpty()) {
                            out.println("Usage: drop <item_name>  or  drop all");
                            break;
                        }
                        String dropArg = dropArgs.trim();
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }

                        // Get equipped items to check if item is equipped
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
                        for (Long iid : equippedMap.values()) {
                            if (iid != null) equippedInstanceIds.add(iid);
                        }

                        // Get inventory items
                        java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
                        if (invItems.isEmpty()) {
                            out.println("You are not carrying anything.");
                            break;
                        }

                        // Handle "drop all"
                        if (dropArg.equalsIgnoreCase("all")) {
                            int count = 0;
                            int skipped = 0;
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (equippedInstanceIds.contains(ri.instance.instanceId)) {
                                    skipped++;
                                    continue; // skip equipped items
                                }
                                itemDao.moveInstanceToRoom(ri.instance.instanceId, rec.currentRoom);
                                out.println("You drop " + (ri.template.name != null ? ri.template.name : "an item") + ".");
                                count++;
                            }
                            if (count > 1) out.println("Dropped " + count + " items.");
                            if (skipped > 0) out.println("Skipped " + skipped + " equipped item(s). Unequip first to drop.");
                            if (count == 0 && skipped == 0) out.println("You have nothing to drop.");
                            break;
                        }

                        // Smart matching: find items in inventory whose name or keywords match
                        ItemDAO.RoomItem matched = null;
                        String searchLower = dropArg.toLowerCase();

                        // Priority 1: Exact name match (case-insensitive)
                        for (ItemDAO.RoomItem ri : invItems) {
                            if (ri.template.name != null && ri.template.name.equalsIgnoreCase(dropArg)) {
                                matched = ri;
                                break;
                            }
                        }

                        // Priority 2: Name contains the search term as a word
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.name != null) {
                                    String nameLower = ri.template.name.toLowerCase();
                                    String[] nameWords = nameLower.split("\\s+");
                                    for (String w : nameWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }

                        // Priority 3: Keyword match
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }

                        // Priority 4: Partial name match (name starts with search term)
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        // Priority 5: Name contains search term anywhere
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        if (matched == null) {
                            out.println("You don't have '" + dropArg + "' in your inventory.");
                            break;
                        }

                        // Check if item is equipped
                        if (equippedInstanceIds.contains(matched.instance.instanceId)) {
                            out.println("You cannot drop " + (matched.template.name != null ? matched.template.name : "that item") + " because it is currently equipped. Unequip it first.");
                            break;
                        }

                        // Move item to the room
                        itemDao.moveInstanceToRoom(matched.instance.instanceId, rec.currentRoom);
                        out.println("You drop " + (matched.template.name != null ? matched.template.name : "an item") + ".");
                        break;
                    }
                    case "put": {
                        // PUT <item> <container> - put an item from inventory into a container
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You are nowhere.");
                            break;
                        }
                        String putArgs = cmd.getArgs();
                        if (putArgs == null || putArgs.trim().isEmpty()) {
                            out.println("Usage: put <item> <container>");
                            break;
                        }
                        
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        // Get inventory items and find containers (room + inventory)
                        java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
                        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
                        
                        if (invItems.isEmpty()) {
                            out.println("You are not carrying anything to put in a container.");
                            break;
                        }
                        
                        // Get equipped items to prevent putting equipped items
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
                        for (Long iid : equippedMap.values()) {
                            if (iid != null) equippedInstanceIds.add(iid);
                        }
                        
                        // Build list of available containers (room + inventory)
                        java.util.List<ItemDAO.RoomItem> allContainers = new java.util.ArrayList<>();
                        for (ItemDAO.RoomItem ri : roomItems) {
                            if (ri.template.isContainer()) allContainers.add(ri);
                        }
                        for (ItemDAO.RoomItem ri : invItems) {
                            if (ri.template.isContainer()) allContainers.add(ri);
                        }
                        
                        if (allContainers.isEmpty()) {
                            out.println("There are no containers here or in your inventory.");
                            break;
                        }
                        
                        // Parse args to find container (from end) and item (from start)
                        String[] words = putArgs.trim().split("\\s+");
                        if (words.length < 2) {
                            out.println("Usage: put <item> <container>");
                            break;
                        }
                        
                        ItemDAO.RoomItem matchedContainer = null;
                        String itemSearchPart = null;
                        
                        // Try progressively shorter suffixes to find a container match
                        for (int i = words.length - 1; i >= 1; i--) {
                            // Build container search from words[i] to end
                            StringBuilder containerSearch = new StringBuilder();
                            for (int j = i; j < words.length; j++) {
                                if (containerSearch.length() > 0) containerSearch.append(" ");
                                containerSearch.append(words[j]);
                            }
                            String containerSearchLower = containerSearch.toString().toLowerCase();
                            
                            // Try to match container
                            for (ItemDAO.RoomItem ri : allContainers) {
                                String cname = ri.template.name != null ? ri.template.name.toLowerCase() : "";
                                boolean match = cname.equalsIgnoreCase(containerSearchLower) 
                                    || cname.startsWith(containerSearchLower)
                                    || cname.contains(containerSearchLower);
                                if (!match && ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.toLowerCase().startsWith(containerSearchLower)) {
                                            match = true;
                                            break;
                                        }
                                    }
                                }
                                if (match) {
                                    matchedContainer = ri;
                                    // Build item search part from words[0] to words[i-1]
                                    StringBuilder itemPart = new StringBuilder();
                                    for (int j = 0; j < i; j++) {
                                        if (itemPart.length() > 0) itemPart.append(" ");
                                        itemPart.append(words[j]);
                                    }
                                    itemSearchPart = itemPart.toString();
                                    break;
                                }
                            }
                            if (matchedContainer != null) break;
                        }
                        
                        if (matchedContainer == null) {
                            out.println("You don't see that container here.");
                            break;
                        }
                        
                        if (itemSearchPart == null || itemSearchPart.isEmpty()) {
                            out.println("Put what in " + matchedContainer.template.name + "?");
                            break;
                        }
                        
                        // Smart match item in inventory
                        String searchLower = itemSearchPart.toLowerCase();
                        ItemDAO.RoomItem matchedItem = null;
                        
                        // Priority 1: Exact name match
                        for (ItemDAO.RoomItem ri : invItems) {
                            if (ri.template.name != null && ri.template.name.equalsIgnoreCase(itemSearchPart)) {
                                matchedItem = ri;
                                break;
                            }
                        }
                        // Priority 2: Name word starts with search
                        if (matchedItem == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.name != null) {
                                    String[] nameWords = ri.template.name.toLowerCase().split("\\s+");
                                    for (String w : nameWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            matchedItem = ri;
                                            break;
                                        }
                                    }
                                    if (matchedItem != null) break;
                                }
                            }
                        }
                        // Priority 3: Keyword match
                        if (matchedItem == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matchedItem = ri;
                                            break;
                                        }
                                    }
                                    if (matchedItem != null) break;
                                }
                            }
                        }
                        // Priority 4: Name starts with search
                        if (matchedItem == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                                    matchedItem = ri;
                                    break;
                                }
                            }
                        }
                        // Priority 5: Name contains search
                        if (matchedItem == null) {
                            for (ItemDAO.RoomItem ri : invItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                                    matchedItem = ri;
                                    break;
                                }
                            }
                        }
                        
                        if (matchedItem == null) {
                            out.println("You don't have '" + itemSearchPart + "' in your inventory.");
                            break;
                        }
                        
                        // Check if trying to put container into itself
                        if (matchedItem.instance.instanceId == matchedContainer.instance.instanceId) {
                            out.println("You can't put something inside itself.");
                            break;
                        }
                        
                        // Check if item is equipped
                        if (equippedInstanceIds.contains(matchedItem.instance.instanceId)) {
                            out.println("You cannot put " + (matchedItem.template.name != null ? matchedItem.template.name : "that item") + " in a container because it is currently equipped. Unequip it first.");
                            break;
                        }
                        
                        // Move item to container
                        itemDao.moveInstanceToContainer(matchedItem.instance.instanceId, matchedContainer.instance.instanceId);
                        out.println("You put " + (matchedItem.template.name != null ? matchedItem.template.name : "an item") + " in " + matchedContainer.template.name + ".");
                        break;
                    }
                    case "equip":
                    case "wear": {
                        // EQUIP [item_name] - with no args, show current equipment; with args, equip item
                        if (rec == null) {
                            out.println("No character record found.");
                            break;
                        }
                        String equipArgs = cmd.getArgs();
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }

                        // Get currently equipped items
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);

                        // If no arguments, display current equipment loadout
                        if (equipArgs == null || equipArgs.trim().isEmpty()) {
                            out.println("Currently equipped:");
                            // Get all slots sorted by ID
                            EquipmentSlot[] slots = EquipmentSlot.values();
                            java.util.Arrays.sort(slots, (slotA, slotB) -> Integer.compare(slotA.id, slotB.id));
                            // Find max display name length for padding
                            int maxLen = 0;
                            for (EquipmentSlot s : slots) {
                                if (s.displayName.length() > maxLen) maxLen = s.displayName.length();
                            }
                            for (EquipmentSlot slot : slots) {
                                Long instanceId = equippedMap.get(slot.id);
                                String itemName = "(empty)";
                                if (instanceId != null) {
                                    ItemInstance inst = itemDao.getInstance(instanceId);
                                    if (inst != null) {
                                        ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                        if (tmpl != null && tmpl.name != null) itemName = tmpl.name;
                                    }
                                }
                                // Pad slot name to maxLen
                                String paddedSlot = String.format("%-" + maxLen + "s", slot.displayName);
                                out.println("  " + paddedSlot + ": " + itemName);
                            }
                            break;
                        }

                        String equipArg = equipArgs.trim();

                        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
                        for (Long iid : equippedMap.values()) {
                            if (iid != null) equippedInstanceIds.add(iid);
                        }

                        // Get inventory items (not equipped)
                        java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
                        // Filter out already equipped items
                        java.util.List<ItemDAO.RoomItem> unequippedItems = new java.util.ArrayList<>();
                        for (ItemDAO.RoomItem ri : invItems) {
                            if (!equippedInstanceIds.contains(ri.instance.instanceId)) {
                                unequippedItems.add(ri);
                            }
                        }

                        if (unequippedItems.isEmpty()) {
                            out.println("You have nothing in your inventory to equip.");
                            break;
                        }

                        // Smart matching to find the item
                        ItemDAO.RoomItem matched = null;
                        String searchLower = equipArg.toLowerCase();

                        // Priority 1: Exact name match
                        for (ItemDAO.RoomItem ri : unequippedItems) {
                            if (ri.template.name != null && ri.template.name.equalsIgnoreCase(equipArg)) {
                                matched = ri;
                                break;
                            }
                        }

                        // Priority 2: Word match
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : unequippedItems) {
                                if (ri.template.name != null) {
                                    String[] nameWords = ri.template.name.toLowerCase().split("\\s+");
                                    for (String w : nameWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }

                        // Priority 3: Keyword match
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : unequippedItems) {
                                if (ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }

                        // Priority 4: Name starts with search
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : unequippedItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        // Priority 5: Name contains search
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : unequippedItems) {
                                if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        if (matched == null) {
                            out.println("You don't have '" + equipArg + "' in your inventory to equip.");
                            break;
                        }

                        // Check if item is equipable (has a slot)
                        EquipmentSlot slot = itemDao.getTemplateEquipmentSlot(matched.template.id);
                        if (slot == null) {
                            out.println(matched.template.name + " cannot be equipped.");
                            break;
                        }

                        // Check armor proficiency requirement
                        if (matched.template.isArmor()) {
                            ArmorCategory armorCat = matched.template.getArmorCategory();
                            if (armorCat != null) {
                                String skillKey = armorCat.getSkillKey();
                                Skill armorSkill = dao.getSkillByKey(skillKey);
                                if (armorSkill == null || !dao.hasSkill(charId, armorSkill.getId())) {
                                    out.println("You lack proficiency in " + armorCat.getDisplayName() + " armor to equip " + matched.template.name + ".");
                                    break;
                                }
                            }
                        }

                        // Check if this is a two-handed weapon
                        boolean isTwoHanded = itemDao.isTemplateTwoHanded(matched.template.id);
                        
                        // Track what we're removing
                        java.util.List<String> removedItems = new java.util.ArrayList<>();
                        
                        // For two-handed weapons, need to clear both main and off hand
                        if (isTwoHanded) {
                            // Clear main hand if occupied
                            Long mainHandItem = equippedMap.get(EquipmentSlot.MAIN_HAND.id);
                            if (mainHandItem != null) {
                                ItemInstance inst = itemDao.getInstance(mainHandItem);
                                if (inst != null) {
                                    ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                    if (tmpl != null && tmpl.name != null) removedItems.add(tmpl.name);
                                }
                                dao.setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
                            }
                            // Clear off hand if occupied
                            Long offHandItem = equippedMap.get(EquipmentSlot.OFF_HAND.id);
                            if (offHandItem != null) {
                                ItemInstance inst = itemDao.getInstance(offHandItem);
                                if (inst != null) {
                                    ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                    if (tmpl != null && tmpl.name != null) removedItems.add(tmpl.name);
                                }
                                dao.setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
                            }
                        } else {
                            // For shields/off-hand items being equipped, check if a two-hander is in main hand
                            if (slot == EquipmentSlot.OFF_HAND) {
                                Long mainHandItem = equippedMap.get(EquipmentSlot.MAIN_HAND.id);
                                if (mainHandItem != null) {
                                    ItemInstance mainInst = itemDao.getInstance(mainHandItem);
                                    if (mainInst != null && itemDao.isTemplateTwoHanded(mainInst.templateId)) {
                                        ItemTemplate mainTmpl = itemDao.getTemplateById(mainInst.templateId);
                                        if (mainTmpl != null && mainTmpl.name != null) removedItems.add(mainTmpl.name);
                                        dao.setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
                                    }
                                }
                            }
                            // For one-handed weapons being equipped, check if a two-hander is in main hand
                            if (slot == EquipmentSlot.MAIN_HAND) {
                                Long mainHandItem = equippedMap.get(EquipmentSlot.MAIN_HAND.id);
                                if (mainHandItem != null) {
                                    ItemInstance mainInst = itemDao.getInstance(mainHandItem);
                                    if (mainInst != null && itemDao.isTemplateTwoHanded(mainInst.templateId)) {
                                        // Two-hander was taking both slots, clear off-hand too
                                        dao.setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
                                    }
                                }
                            }
                            // Check if target slot is already occupied - if so, auto-remove the old item
                            Long currentInSlot = equippedMap.get(slot.id);
                            if (currentInSlot != null) {
                                ItemInstance curInst = itemDao.getInstance(currentInSlot);
                                if (curInst != null) {
                                    ItemTemplate curTmpl = itemDao.getTemplateById(curInst.templateId);
                                    if (curTmpl != null && curTmpl.name != null) removedItems.add(curTmpl.name);
                                }
                                dao.setCharacterEquipment(charId, slot.id, null);
                            }
                        }

                        // Equip the new item to main hand
                        boolean equipped = dao.setCharacterEquipment(charId, slot.id, matched.instance.instanceId);
                        if (!equipped) {
                            out.println("Failed to equip " + matched.template.name + ".");
                            break;
                        }
                        
                        // For two-handed weapons, also mark off-hand as occupied (same instance)
                        if (isTwoHanded) {
                            dao.setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, matched.instance.instanceId);
                        }

                        // Recalculate and persist equipment bonuses
                        dao.recalculateEquipmentBonuses(charId, itemDao);

                        // Refresh character record
                        rec = dao.findByName(name);

                        // Build equip message
                        String slotDisplay = isTwoHanded ? "Both Hands" : slot.displayName;
                        if (!removedItems.isEmpty()) {
                            String removedStr = String.join(" and ", removedItems);
                            out.println("You remove " + removedStr + " and equip " + matched.template.name + " (" + slotDisplay + ").");
                        } else {
                            out.println("You equip " + matched.template.name + " (" + slotDisplay + ").");
                        }
                        
                        // Show new totals if any bonuses changed
                        int armorTotal = rec.getArmorTotal();
                        int fortTotal = rec.getFortitudeTotal();
                        int refTotal = rec.getReflexTotal();
                        int willTotal = rec.getWillTotal();
                        if (matched.template.armorSaveBonus != 0 || matched.template.fortSaveBonus != 0 || 
                            matched.template.refSaveBonus != 0 || matched.template.willSaveBonus != 0) {
                            out.println("  Saves: Armor " + armorTotal + ", Fort " + fortTotal + ", Ref " + refTotal + ", Will " + willTotal);
                        }
                        break;
                    }
                    case "remove":
                    case "dequip": {
                        // REMOVE <item_name> or REMOVE <slot_name> - unequip an item
                        if (rec == null) {
                            out.println("No character record found.");
                            break;
                        }
                        String removeArgs = cmd.getArgs();
                        if (removeArgs == null || removeArgs.trim().isEmpty()) {
                            out.println("Usage: remove <item_name>  or  remove <slot_name>");
                            break;
                        }
                        String removeArg = removeArgs.trim();
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }

                        // Get currently equipped items
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        if (equippedMap.isEmpty()) {
                            out.println("You have nothing equipped.");
                            break;
                        }

                        // Check if no items are actually equipped (all slots null)
                        boolean hasEquipped = false;
                        for (Long iid : equippedMap.values()) {
                            if (iid != null) { hasEquipped = true; break; }
                        }
                        if (!hasEquipped) {
                            out.println("You have nothing equipped.");
                            break;
                        }

                        // Try to match by slot name first
                        EquipmentSlot slotMatch = EquipmentSlot.fromKey(removeArg);
                        if (slotMatch != null) {
                            Long instanceInSlot = equippedMap.get(slotMatch.id);
                            if (instanceInSlot == null) {
                                out.println("You have nothing equipped in your " + slotMatch.displayName + " slot.");
                                break;
                            }
                            // Get item name for message
                            ItemInstance inst = itemDao.getInstance(instanceInSlot);
                            String itemName = "an item";
                            int armorBonus = 0, fortBonus = 0, refBonus = 0, willBonus = 0;
                            boolean isTwoHanded = false;
                            if (inst != null) {
                                ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                if (tmpl != null) {
                                    if (tmpl.name != null) itemName = tmpl.name;
                                    armorBonus = tmpl.armorSaveBonus;
                                    fortBonus = tmpl.fortSaveBonus;
                                    refBonus = tmpl.refSaveBonus;
                                    willBonus = tmpl.willSaveBonus;
                                    isTwoHanded = itemDao.isTemplateTwoHanded(tmpl.id);
                                }
                            }
                            // Clear the slot(s)
                            dao.setCharacterEquipment(charId, slotMatch.id, null);
                            // For two-handed weapons, clear both slots
                            if (isTwoHanded) {
                                dao.setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
                                dao.setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
                            }
                            dao.recalculateEquipmentBonuses(charId, itemDao);
                            rec = dao.findByName(name);
                            String slotDisplay = isTwoHanded ? "Both Hands" : slotMatch.displayName;
                            out.println("You remove " + itemName + " (" + slotDisplay + ").");
                            if (armorBonus != 0 || fortBonus != 0 || refBonus != 0 || willBonus != 0) {
                                out.println("  Saves: Armor " + rec.getArmorTotal() + ", Fort " + rec.getFortitudeTotal() + ", Ref " + rec.getReflexTotal() + ", Will " + rec.getWillTotal());
                            }
                            break;
                        }

                        // Otherwise, try to match by item name among equipped items
                        String searchLower = removeArg.toLowerCase();
                        Integer matchedSlotId = null;
                        Long matchedInstanceId = null;
                        ItemTemplate matchedTemplate = null;

                        // Build list of equipped items with their templates
                        java.util.List<Object[]> equippedItems = new java.util.ArrayList<>();
                        for (java.util.Map.Entry<Integer, Long> entry : equippedMap.entrySet()) {
                            if (entry.getValue() == null) continue;
                            ItemInstance inst = itemDao.getInstance(entry.getValue());
                            if (inst == null) continue;
                            ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                            if (tmpl == null) continue;
                            equippedItems.add(new Object[] { entry.getKey(), entry.getValue(), tmpl });
                        }

                        // Priority 1: Exact name match
                        for (Object[] arr : equippedItems) {
                            ItemTemplate tmpl = (ItemTemplate) arr[2];
                            if (tmpl.name != null && tmpl.name.equalsIgnoreCase(removeArg)) {
                                matchedSlotId = (Integer) arr[0];
                                matchedInstanceId = (Long) arr[1];
                                matchedTemplate = tmpl;
                                break;
                            }
                        }

                        // Priority 2: Word match
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemTemplate tmpl = (ItemTemplate) arr[2];
                                if (tmpl.name != null) {
                                    String[] words = tmpl.name.toLowerCase().split("\\s+");
                                    for (String w : words) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            matchedSlotId = (Integer) arr[0];
                                            matchedInstanceId = (Long) arr[1];
                                            matchedTemplate = tmpl;
                                            break;
                                        }
                                    }
                                    if (matchedSlotId != null) break;
                                }
                            }
                        }

                        // Priority 3: Keyword match
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemTemplate tmpl = (ItemTemplate) arr[2];
                                if (tmpl.keywords != null) {
                                    for (String kw : tmpl.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matchedSlotId = (Integer) arr[0];
                                            matchedInstanceId = (Long) arr[1];
                                            matchedTemplate = tmpl;
                                            break;
                                        }
                                    }
                                    if (matchedSlotId != null) break;
                                }
                            }
                        }

                        // Priority 4: Name starts with
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemTemplate tmpl = (ItemTemplate) arr[2];
                                if (tmpl.name != null && tmpl.name.toLowerCase().startsWith(searchLower)) {
                                    matchedSlotId = (Integer) arr[0];
                                    matchedInstanceId = (Long) arr[1];
                                    matchedTemplate = tmpl;
                                    break;
                                }
                            }
                        }

                        // Priority 5: Name contains
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemTemplate tmpl = (ItemTemplate) arr[2];
                                if (tmpl.name != null && tmpl.name.toLowerCase().contains(searchLower)) {
                                    matchedSlotId = (Integer) arr[0];
                                    matchedInstanceId = (Long) arr[1];
                                    matchedTemplate = tmpl;
                                    break;
                                }
                            }
                        }

                        if (matchedSlotId == null) {
                            out.println("You don't have '" + removeArg + "' equipped.");
                            break;
                        }

                        // Remove the item
                        EquipmentSlot slot = EquipmentSlot.fromId(matchedSlotId);
                        String slotName = slot != null ? slot.displayName : "unknown slot";
                        boolean isTwoHanded = itemDao.isTemplateTwoHanded(matchedTemplate.id);
                        
                        dao.setCharacterEquipment(charId, matchedSlotId, null);
                        // For two-handed weapons, clear both slots
                        if (isTwoHanded) {
                            dao.setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
                            dao.setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
                            slotName = "Both Hands";
                        }
                        dao.recalculateEquipmentBonuses(charId, itemDao);
                        rec = dao.findByName(name);

                        out.println("You remove " + (matchedTemplate.name != null ? matchedTemplate.name : "an item") + " (" + slotName + ").");
                        if (matchedTemplate.armorSaveBonus != 0 || matchedTemplate.fortSaveBonus != 0 || 
                            matchedTemplate.refSaveBonus != 0 || matchedTemplate.willSaveBonus != 0) {
                            out.println("  Saves: Armor " + rec.getArmorTotal() + ", Fort " + rec.getFortitudeTotal() + ", Ref " + rec.getReflexTotal() + ", Will " + rec.getWillTotal());
                        }
                        break;
                    }
                    case "inventory":
                    case "i": {
                        // INVENTORY - list items in inventory (not equipped, not in containers)
                        if (rec == null) {
                            out.println("No character record found.");
                            break;
                        }
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }

                        // Get equipped item instance IDs to exclude
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
                        for (Long iid : equippedMap.values()) {
                            if (iid != null) equippedInstanceIds.add(iid);
                        }

                        // Get all items owned by character
                        java.util.List<ItemDAO.RoomItem> allItems = itemDao.getItemsByCharacter(charId);

                        // Filter: exclude equipped items and items inside containers
                        java.util.List<String> itemNames = new java.util.ArrayList<>();
                        for (ItemDAO.RoomItem ri : allItems) {
                            // Skip equipped items
                            if (equippedInstanceIds.contains(ri.instance.instanceId)) continue;
                            // Skip items inside containers (container_instance_id is set)
                            if (ri.instance.containerInstanceId != null) continue;
                            // Add name
                            String itemName = ri.template.name != null ? ri.template.name : "(unnamed item)";
                            itemNames.add(itemName);
                        }

                        if (itemNames.isEmpty()) {
                            out.println("You are not carrying anything.");
                            break;
                        }

                        // Sort alphabetically
                        java.util.Collections.sort(itemNames, String.CASE_INSENSITIVE_ORDER);

                        out.println("You are carrying:");
                        for (String n : itemNames) {
                            out.println("  " + n);
                        }
                        break;
                    }
                    case "score":
                    case "stats": {
                        // CHARACTER SHEET - Display full character information
                        if (rec == null) {
                            out.println("You must be logged in to view your character sheet.");
                            break;
                        }
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        // Get class information
                        CharacterClassDAO classDao = new CharacterClassDAO();
                        try {
                            classDao.loadClassesFromYamlResource("/data/classes.yaml");
                        } catch (Exception ignored) {}
                        
                        Integer currentClassId = rec.currentClassId;
                        CharacterClass currentClass = currentClassId != null ? classDao.getClassById(currentClassId) : null;
                        int classLevel = currentClassId != null ? classDao.getCharacterClassLevel(charId, currentClassId) : 0;
                        int classXp = currentClassId != null ? classDao.getCharacterClassXp(charId, currentClassId) : 0;
                        int xpToNext = classLevel < CharacterClass.MAX_HERO_LEVEL ? CharacterClass.xpRequiredForLevel(classLevel + 1) - classXp : 0;
                        
                        // Get all class progress for multiclass display
                        java.util.List<CharacterClassDAO.ClassProgress> allProgress = classDao.getCharacterClassProgress(charId);
                        
                        // Get equipment for AC and weapon damage
                        ItemDAO itemDao = new ItemDAO();
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        
                        // Calculate ability modifiers (D&D style: (score - 10) / 2)
                        int strMod = (rec.str - 10) / 2;
                        int dexMod = (rec.dex - 10) / 2;
                        int conMod = (rec.con - 10) / 2;
                        int intMod = (rec.intel - 10) / 2;
                        int wisMod = (rec.wis - 10) / 2;
                        int chaMod = (rec.cha - 10) / 2;
                        
                        // Build the character sheet
                        StringBuilder sheet = new StringBuilder();
                        String divider = "===================================================================";
                        String thinDiv = "-------------------------------------------------------------------";
                        
                        // === HEADER ===
                        sheet.append("\n").append(divider).append("\n");
                        sheet.append(String.format("  %-30s %35s\n", name.toUpperCase(), "Age: " + rec.age));
                        sheet.append(String.format("  %-30s %35s\n", 
                            currentClass != null ? currentClass.name + " Level " + classLevel : "(No Class)",
                            rec.description != null && !rec.description.isEmpty() ? "\"" + truncate(rec.description, 30) + "\"" : ""));
                        sheet.append(divider).append("\n");
                        
                        // ═══ VITALS ═══
                        sheet.append("\n  [ VITALS ]\n");
                        sheet.append(String.format("  HP: %4d / %-4d    MP: %4d / %-4d    MV: %4d / %-4d\n",
                            rec.hpCur, rec.hpMax, rec.mpCur, rec.mpMax, rec.mvCur, rec.mvMax));
                        
                        // XP bar
                        if (currentClass != null && classLevel < CharacterClass.MAX_HERO_LEVEL) {
                            int xpForCurrent = CharacterClass.xpRequiredForLevel(classLevel);
                            int xpForNext = CharacterClass.xpRequiredForLevel(classLevel + 1);
                            int xpInLevel = classXp - xpForCurrent;
                            int xpNeeded = xpForNext - xpForCurrent;
                            int pct = xpNeeded > 0 ? (xpInLevel * 100) / xpNeeded : 100;
                            sheet.append(String.format("  XP: %d / %d  [%s%s] %d%%  (%d TNL)\n",
                                classXp, xpForNext,
                                repeat("#", pct / 5), repeat(".", 20 - pct / 5),
                                pct, xpToNext));
                        } else if (currentClass != null) {
                            sheet.append(String.format("  XP: %d  [MAX LEVEL]\n", classXp));
                        }
                        
                        // ═══ ABILITY SCORES ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ ABILITY SCORES ]\n");
                        sheet.append(String.format("  STR: %2d (%+d)    DEX: %2d (%+d)    CON: %2d (%+d)\n",
                            rec.str, strMod, rec.dex, dexMod, rec.con, conMod));
                        sheet.append(String.format("  INT: %2d (%+d)    WIS: %2d (%+d)    CHA: %2d (%+d)\n",
                            rec.intel, intMod, rec.wis, wisMod, rec.cha, chaMod));
                        
                        // ═══ SAVES & DEFENSES ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ SAVES & DEFENSES ]\n");
                        sheet.append(String.format("  Armor: %2d (%+d equip)    Fortitude: %2d (%+d equip)\n",
                            rec.getArmorTotal(), rec.armorEquipBonus, rec.getFortitudeTotal(), rec.fortitudeEquipBonus));
                        sheet.append(String.format("  Reflex: %2d (%+d equip)   Will: %2d (%+d equip)\n",
                            rec.getReflexTotal(), rec.reflexEquipBonus, rec.getWillTotal(), rec.willEquipBonus));
                        
                        // ═══ COMBAT ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ COMBAT ]\n");
                        
                        // Get weapon info from hands
                        Long mainHandId = equippedMap.get(EquipmentSlot.MAIN_HAND.getId());
                        Long offHandId = equippedMap.get(EquipmentSlot.OFF_HAND.getId());
                        
                        boolean hasWeapon = false;
                        boolean isTwoHanded = mainHandId != null && mainHandId.equals(offHandId);
                        
                        if (mainHandId != null) {
                            ItemInstance mainInst = itemDao.getInstance(mainHandId);
                            if (mainInst != null) {
                                ItemTemplate mainTmpl = itemDao.getTemplateById(mainInst.templateId);
                                if (mainTmpl != null && mainTmpl.baseDie > 0) {
                                    String dmgStr = formatDamage(mainTmpl.multiplier, mainTmpl.baseDie, 
                                        getAbilityBonus(mainTmpl.abilityScore, mainTmpl.abilityMultiplier, rec));
                                    String handLabel = isTwoHanded ? "Both Hands:" : "Main Hand: ";
                                    sheet.append(String.format("  %s %-20s  Damage: %s\n", 
                                        handLabel, truncate(mainTmpl.name, 20), dmgStr));
                                    hasWeapon = true;
                                }
                            }
                        }
                        if (offHandId != null && !isTwoHanded) {
                            ItemInstance offInst = itemDao.getInstance(offHandId);
                            if (offInst != null) {
                                ItemTemplate offTmpl = itemDao.getTemplateById(offInst.templateId);
                                if (offTmpl != null) {
                                    if (offTmpl.baseDie > 0) {
                                        String dmgStr = formatDamage(offTmpl.multiplier, offTmpl.baseDie,
                                            getAbilityBonus(offTmpl.abilityScore, offTmpl.abilityMultiplier, rec));
                                        sheet.append(String.format("  Off Hand:  %-20s  Damage: %s\n",
                                            truncate(offTmpl.name, 20), dmgStr));
                                    } else {
                                        // Shield or non-weapon (show armor bonus)
                                        sheet.append(String.format("  Off Hand:  %-20s  (Shield)\n",
                                            truncate(offTmpl.name, 20)));
                                    }
                                    hasWeapon = true;
                                }
                            }
                        }
                        if (!hasWeapon) {
                            // Unarmed combat - 1d4 + STR
                            String unarmedDmg = formatDamage(1, 4, strMod);
                            sheet.append(String.format("  Unarmed:   %-20s  Damage: %s\n", "Fists", unarmedDmg));
                        }
                        
                        // ═══ CLASS PROGRESSION ═══
                        if (!allProgress.isEmpty()) {
                            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                            sheet.append("  [ CLASS PROGRESSION ]\n");
                            for (CharacterClassDAO.ClassProgress cp : allProgress) {
                                CharacterClass cls = classDao.getClassById(cp.classId);
                                String className = cls != null ? cls.name : "Unknown";
                                String marker = cp.isCurrent ? " *" : "";
                                sheet.append(String.format("  %-15s Lv %2d  XP: %5d%s\n", 
                                    className, cp.level, cp.xp, marker));
                            }
                            sheet.append("  (* = active class)\n");
                        }
                        
                        // ═══ SKILLS ═══
                        java.util.List<CharacterSkill> knownSkills = dao.getAllCharacterSkills(charId);
                        if (!knownSkills.isEmpty()) {
                            sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                            sheet.append("  [ SKILLS ]\n");
                            // Sort by name
                            java.util.List<String> skillLines = new java.util.ArrayList<>();
                            for (CharacterSkill cs : knownSkills) {
                                Skill skillDef = dao.getSkillById(cs.getSkillId());
                                String skillName = skillDef != null ? skillDef.getName() : "Skill #" + cs.getSkillId();
                                int prof = cs.getProficiency();
                                String profStr = prof >= 100 ? "MASTERED" : prof + "%";
                                skillLines.add(String.format("%-22s %8s", truncate(skillName, 22), profStr));
                            }
                            java.util.Collections.sort(skillLines, String.CASE_INSENSITIVE_ORDER);
                            // Print in 2 columns
                            for (int i = 0; i < skillLines.size(); i += 2) {
                                String col1 = skillLines.get(i);
                                String col2 = i + 1 < skillLines.size() ? skillLines.get(i + 1) : "";
                                sheet.append(String.format("  %s  %s\n", col1, col2));
                            }
                        } else {
                            sheet.append("\n  [ SKILLS ]\n");
                            sheet.append("  No skills learned yet.\n");
                        }
                        
                        // ═══ SPELLS ═══
                        java.util.List<CharacterSpell> knownSpells = dao.getAllCharacterSpells(charId);
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ SPELLS ]\n");
                        if (!knownSpells.isEmpty()) {
                            // Group by spell level
                            java.util.Map<Integer, java.util.List<String>> spellsByLevel = new java.util.TreeMap<>();
                            for (CharacterSpell cs : knownSpells) {
                                Spell spellDef = dao.getSpellById(cs.getSpellId());
                                if (spellDef != null) {
                                    int lvl = spellDef.getLevel();
                                    spellsByLevel.computeIfAbsent(lvl, k -> new java.util.ArrayList<>());
                                    int prof = cs.getProficiency();
                                    String profStr = prof >= 100 ? "M" : prof + "%";
                                    spellsByLevel.get(lvl).add(spellDef.getName() + " [" + profStr + "]");
                                }
                            }
                            for (java.util.Map.Entry<Integer, java.util.List<String>> entry : spellsByLevel.entrySet()) {
                                sheet.append(String.format("  Level %d: ", entry.getKey()));
                                java.util.Collections.sort(entry.getValue(), String.CASE_INSENSITIVE_ORDER);
                                sheet.append(String.join(", ", entry.getValue())).append("\n");
                            }
                        } else {
                            sheet.append("  No spells known yet.\n");
                        }
                        
                        // ═══ ACTIVE EFFECTS ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ ACTIVE EFFECTS ]\n");
                        sheet.append("  (No active spell effects)\n");  // Placeholder for future implementation
                        
                        // ═══ FOOTER ═══
                        sheet.append("\n").append(divider).append("\n");
                        
                        out.print(sheet.toString());
                        break;
                    }
                    case "spells": {
                        // List all spells the character knows
                        if (rec == null) {
                            out.println("You must be logged in to view spells.");
                            break;
                        }
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        java.util.List<CharacterSpell> knownSpells = dao.getAllCharacterSpells(charId);
                        if (knownSpells.isEmpty()) {
                            out.println("You don't know any spells yet.");
                            break;
                        }
                        
                        // Group spells by school for nice display
                        java.util.Map<Spell.SpellSchool, java.util.List<Spell>> bySchool = new java.util.LinkedHashMap<>();
                        for (Spell.SpellSchool school : Spell.SpellSchool.values()) {
                            bySchool.put(school, new java.util.ArrayList<>());
                        }
                        
                        for (CharacterSpell cs : knownSpells) {
                            Spell spellDef = dao.getSpellById(cs.getSpellId());
                            if (spellDef != null) {
                                bySchool.get(spellDef.getSchool()).add(spellDef);
                            }
                        }
                        
                        out.println("=== Known Spells ===");
                        boolean anySpells = false;
                        for (java.util.Map.Entry<Spell.SpellSchool, java.util.List<Spell>> entry : bySchool.entrySet()) {
                            if (!entry.getValue().isEmpty()) {
                                anySpells = true;
                                out.println("\n" + entry.getKey().displayName + " Magic:");
                                // Sort by level then name
                                entry.getValue().sort((sp1, sp2) -> {
                                    int cmp = Integer.compare(sp1.getLevel(), sp2.getLevel());
                                    return cmp != 0 ? cmp : sp1.getName().compareToIgnoreCase(sp2.getName());
                                });
                                for (Spell sp : entry.getValue()) {
                                    // Find proficiency
                                    CharacterSpell cs = null;
                                    for (CharacterSpell csp : knownSpells) {
                                        if (csp.getSpellId() == sp.getId()) {
                                            cs = csp;
                                            break;
                                        }
                                    }
                                    String prof = cs != null ? cs.getProficiencyDisplay() : "";
                                    out.println(String.format("  [%d] %-20s %s", sp.getLevel(), sp.getName(), prof));
                                }
                            }
                        }
                        if (!anySpells) {
                            out.println("You don't know any spells yet.");
                        }
                        break;
                    }
                    case "skills": {
                        // List all skills the character knows with proficiency %
                        if (rec == null) {
                            out.println("You must be logged in to view skills.");
                            break;
                        }
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        java.util.List<CharacterSkill> knownSkills = dao.getAllCharacterSkills(charId);
                        if (knownSkills.isEmpty()) {
                            out.println("You don't know any skills yet.");
                            break;
                        }
                        
                        // Build list of skill name + proficiency pairs
                        java.util.List<String> skillDisplays = new java.util.ArrayList<>();
                        for (CharacterSkill cs : knownSkills) {
                            Skill skillDef = dao.getSkillById(cs.getSkillId());
                            if (skillDef != null) {
                                String display = String.format("%s %3d%%", skillDef.getName(), cs.getProficiency());
                                skillDisplays.add(display);
                            }
                        }
                        
                        // Sort alphabetically
                        skillDisplays.sort(String::compareToIgnoreCase);
                        
                        out.println();
                        out.println("===================================================================");
                        out.println("                          KNOWN SKILLS");
                        out.println("===================================================================");
                        out.println();
                        
                        // Print in rows of 3, formatted nicely
                        for (int i = 0; i < skillDisplays.size(); i += 3) {
                            String c1 = skillDisplays.get(i);
                            String c2 = (i + 1 < skillDisplays.size()) ? skillDisplays.get(i + 1) : "";
                            String c3 = (i + 2 < skillDisplays.size()) ? skillDisplays.get(i + 2) : "";
                            out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
                        }
                        
                        out.println();
                        out.println("-------------------------------------------------------------------");
                        out.println(String.format("  Total: %d skill%s known", skillDisplays.size(), skillDisplays.size() == 1 ? "" : "s"));
                        out.println("===================================================================");
                        out.println();
                        break;
                    }
                    case "cast": {
                        // CAST <spell_name> [target]
                        // Spells with EXPLICIT_MOB_TARGET or ITEM targets require a target name
                        if (rec == null) {
                            out.println("You must be logged in to cast spells.");
                            break;
                        }
                        String castArgs = cmd.getArgs();
                        if (castArgs == null || castArgs.trim().isEmpty()) {
                            out.println("Usage: cast <spell_name> [target]");
                            out.println("Example: cast magic missile");
                            break;
                        }
                        
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        // Get all spells the character knows
                        java.util.List<CharacterSpell> knownSpells = dao.getAllCharacterSpells(charId);
                        if (knownSpells.isEmpty()) {
                            out.println("You don't know any spells.");
                            break;
                        }
                        
                        // Parse: spell name and optional target
                        // We need to find the spell first, then remaining args are target
                        String argsLower = castArgs.toLowerCase().trim();
                        
                        // Build list of spell definitions for spells we know
                        java.util.List<Spell> knownSpellDefs = new java.util.ArrayList<>();
                        for (CharacterSpell cs : knownSpells) {
                            Spell spellDef = dao.getSpellById(cs.getSpellId());
                            if (spellDef != null) {
                                knownSpellDefs.add(spellDef);
                            }
                        }
                        
                        if (knownSpellDefs.isEmpty()) {
                            out.println("You don't know any spells.");
                            break;
                        }
                        
                        // Smart spell matching - try to find the best match
                        // We need to figure out which part is spell name vs target
                        Spell matchedSpell = null;
                        String targetArg = null;
                        
                        // Strategy: try progressively shorter prefixes of args as spell name
                        String[] words = castArgs.trim().split("\\s+");
                        
                        // Try matching from longest to shortest prefix
                        for (int wordCount = words.length; wordCount >= 1 && matchedSpell == null; wordCount--) {
                            StringBuilder spellNameBuilder = new StringBuilder();
                            for (int i = 0; i < wordCount; i++) {
                                if (i > 0) spellNameBuilder.append(" ");
                                spellNameBuilder.append(words[i]);
                            }
                            String spellSearch = spellNameBuilder.toString().toLowerCase();
                            
                            // Priority 1: Exact name match
                            for (Spell sp : knownSpellDefs) {
                                if (sp.getName().equalsIgnoreCase(spellSearch)) {
                                    matchedSpell = sp;
                                    // Remaining words are target
                                    if (wordCount < words.length) {
                                        StringBuilder targetBuilder = new StringBuilder();
                                        for (int i = wordCount; i < words.length; i++) {
                                            if (i > wordCount) targetBuilder.append(" ");
                                            targetBuilder.append(words[i]);
                                        }
                                        targetArg = targetBuilder.toString();
                                    }
                                    break;
                                }
                            }
                            
                            // Priority 2: Name starts with search (prefix match)
                            if (matchedSpell == null) {
                                for (Spell sp : knownSpellDefs) {
                                    if (sp.getName().toLowerCase().startsWith(spellSearch)) {
                                        matchedSpell = sp;
                                        if (wordCount < words.length) {
                                            StringBuilder targetBuilder = new StringBuilder();
                                            for (int i = wordCount; i < words.length; i++) {
                                                if (i > wordCount) targetBuilder.append(" ");
                                                targetBuilder.append(words[i]);
                                            }
                                            targetArg = targetBuilder.toString();
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Priority 3: Any word in name starts with search
                            if (matchedSpell == null) {
                                for (Spell sp : knownSpellDefs) {
                                    String[] spellWords = sp.getName().toLowerCase().split("\\s+");
                                    for (String sw : spellWords) {
                                        if (sw.startsWith(spellSearch) || sw.equals(spellSearch)) {
                                            matchedSpell = sp;
                                            if (wordCount < words.length) {
                                                StringBuilder targetBuilder = new StringBuilder();
                                                for (int i = wordCount; i < words.length; i++) {
                                                    if (i > wordCount) targetBuilder.append(" ");
                                                    targetBuilder.append(words[i]);
                                                }
                                                targetArg = targetBuilder.toString();
                                            }
                                            break;
                                        }
                                    }
                                    if (matchedSpell != null) break;
                                }
                            }
                            
                            // Priority 4: Name contains search substring
                            if (matchedSpell == null) {
                                for (Spell sp : knownSpellDefs) {
                                    if (sp.getName().toLowerCase().contains(spellSearch)) {
                                        matchedSpell = sp;
                                        if (wordCount < words.length) {
                                            StringBuilder targetBuilder = new StringBuilder();
                                            for (int i = wordCount; i < words.length; i++) {
                                                if (i > wordCount) targetBuilder.append(" ");
                                                targetBuilder.append(words[i]);
                                            }
                                            targetArg = targetBuilder.toString();
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        
                        if (matchedSpell == null) {
                            out.println("You don't know a spell matching '" + castArgs + "'.");
                            out.println("Type 'spells' to see your known spells.");
                            break;
                        }
                        
                        // Check if spell requires a target
                        Spell.SpellTarget targetType = matchedSpell.getTarget();
                        boolean needsTarget = (targetType == Spell.SpellTarget.EXPLICIT_MOB_TARGET || 
                                               targetType == Spell.SpellTarget.ITEM);
                        
                        if (needsTarget && (targetArg == null || targetArg.trim().isEmpty())) {
                            String targetDesc = targetType == Spell.SpellTarget.ITEM ? "an item" : "a target";
                            out.println(matchedSpell.getName() + " requires " + targetDesc + ".");
                            out.println("Usage: cast " + matchedSpell.getName().toLowerCase() + " <target>");
                            break;
                        }
                        
                        // Build the cast message based on target type
                        StringBuilder castMsg = new StringBuilder();
                        castMsg.append("You cast ").append(matchedSpell.getName());
                        
                        switch (targetType) {
                            case SELF:
                                castMsg.append(" on yourself.");
                                break;
                            case CURRENT_ENEMY:
                                castMsg.append(" at your current enemy.");
                                break;
                            case EXPLICIT_MOB_TARGET:
                                castMsg.append(" at ").append(targetArg).append(".");
                                break;
                            case ITEM:
                                castMsg.append(" on ").append(targetArg).append(".");
                                break;
                            case ALL_ENEMIES:
                                castMsg.append(", striking all enemies!");
                                break;
                            case ALL_ALLIES:
                                castMsg.append(", affecting all allies.");
                                break;
                            case EVERYONE:
                                castMsg.append(", affecting everyone in the room!");
                                break;
                        }
                        
                        out.println(castMsg.toString());
                        
                        // TODO: Implement actual spell effects here
                        // For now, spells don't do anything - effect logic will be added later
                        
                        break;
                    }
                    default:
                        out.println("Unhandled command: " + cmd.getName());
                }
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
        } finally {
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
    private CharacterRecord runCharacterCreation(String name, BufferedReader in, CharacterDAO dao) throws IOException {
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
        Character ch = new Character(name, age, desc, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur,
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
    private CharacterClass runClassSelection(BufferedReader in) throws IOException {
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
    private void showClassDetails(CharacterClass cls) {
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
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Get an HP bar visual based on percentage.
     */
    private static String getHpBar(int hpPct) {
        if (hpPct > 75) return "[####]";      // Healthy
        if (hpPct > 50) return "[### ]";      // Hurt
        if (hpPct > 25) return "[##  ]";      // Wounded
        if (hpPct > 10) return "[#   ]";      // Critical
        return "[    ]";                       // Near death
    }

    /**
     * Repeat a string n times.
     */
    private static String repeat(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    /**
     * Format damage as dice notation (e.g., "2d6 + 5").
     */
    private static String formatDamage(int multiplier, int baseDie, int bonus) {
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
     * Get the ability bonus for a weapon based on its ability score scaling.
     */
    private static int getAbilityBonus(String abilityScore, double multiplier, CharacterDAO.CharacterRecord rec) {
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
     * Display a formatted, categorized list of available commands.
     */
    private void displayHelpCommandList(boolean isGm) {
        out.println();
        out.println("===================================================================");
        out.println("                        AVAILABLE COMMANDS");
        out.println("===================================================================");
        
        // Display commands by category from the registry
        for (CommandDefinition.Category category : CommandDefinition.Category.values()) {
            // Skip GM category if not a GM
            if (category == CommandDefinition.Category.GM && !isGm) {
                continue;
            }
            
            java.util.List<CommandDefinition> cmds = CommandRegistry.getCommandsByCategory(category);
            if (cmds.isEmpty()) continue;
            
            out.println();
            out.println("  [ " + category.getDisplayName() + " ]");
            
            // Collect display names
            java.util.List<String> displayNames = new java.util.ArrayList<>();
            for (CommandDefinition cmd : cmds) {
                displayNames.add(cmd.getDisplayName());
            }
            
            // Print in rows of 3
            for (int i = 0; i < displayNames.size(); i += 3) {
                String c1 = displayNames.get(i);
                String c2 = (i + 1 < displayNames.size()) ? displayNames.get(i + 1) : "";
                String c3 = (i + 2 < displayNames.size()) ? displayNames.get(i + 2) : "";
                printCommandRow(c1, c2, c3);
            }
        }
        
        out.println();
        out.println("-------------------------------------------------------------------");
        out.println("  Type 'help <command>' for detailed information on any command.");
        out.println("===================================================================");
        out.println();
    }

    /**
     * Print a row of up to 3 commands, evenly spaced.
     */
    private void printCommandRow(String cmd1, String cmd2, String cmd3) {
        out.println(String.format("    %-20s %-20s %-20s", cmd1, cmd2, cmd3));
    }
}
