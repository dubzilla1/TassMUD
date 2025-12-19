package com.example.tassmud.net;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectRegistry;
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
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Shop;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.net.commands.CommandDispatcher;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.persistence.ShopDAO;
import com.example.tassmud.util.GameClock;
import com.example.tassmud.util.HelpManager;
import com.example.tassmud.util.LootGenerator;
import com.example.tassmud.util.MobileRoamingService;
import com.example.tassmud.util.PasswordUtil;
import com.example.tassmud.util.RegenerationService;
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
    private volatile boolean debugChannelEnabled = false;  // GM-only debug output
    private volatile boolean gmInvisible = false;  // GM-only perfect invisibility
    
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
        // Other players in this room (before mobs and items)
        CharacterDAO visDao = new CharacterDAO();
        boolean iAmGm = this.playerName != null && visDao.isCharacterFlagTrueByName(this.playerName, "is_gm");
        for (ClientHandler s : sessions) {
            // Skip self, skip sessions without a character, skip sessions not in this room
            if (s == this) continue;
            String otherName = s.playerName;
            if (otherName == null) continue;
            Integer otherRoomId = s.currentRoomId;
            if (otherRoomId == null || !otherRoomId.equals(roomId)) continue;
            
            // Check GM invisibility - only other GMs can see GM-invisible players
            if (s.gmInvisible && !iAmGm) {
                continue; // Can't see GM-invisible players
            }
            
            // Check normal invisibility - skip if we can't see them
            Integer otherCharId = s.characterId;
            boolean otherInvis = com.example.tassmud.effect.EffectRegistry.isInvisible(otherCharId);
            if (otherInvis) {
                // Check if we can see invisible
                if (!com.example.tassmud.effect.EffectRegistry.canSee(this.characterId, otherCharId)) {
                    continue; // Can't see them
                }
                // We can see them - show with (INVIS) tag
                String gmTag = s.gmInvisible ? "(GM-INVIS)" : "(INVIS)";
                out.println(otherName + " is here. " + gmTag);
            } else if (s.gmInvisible) {
                // GM invisible but not normal invisible
                out.println(otherName + " is here. (GM-INVIS)");
            } else {
                out.println(otherName + " is here.");
            }
        }
        // Mobs in this room
        MobileDAO mobDao = new MobileDAO();
        java.util.List<Mobile> roomMobs = mobDao.getMobilesInRoom(roomId);
        for (Mobile mob : roomMobs) {
            // Check if mob is invisible (mobiles use negative IDs for effect tracking)
            // For now, mobs don't have invisibility effects - that could be added later
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
            // Prefer custom description (for corpses, etc.), then template description
            String desc = ri.instance.customDescription != null && !ri.instance.customDescription.isEmpty() 
                ? ri.instance.customDescription 
                : ri.template.description;
            if (desc != null && !desc.isEmpty()) {
                out.println(desc);
            } else {
                out.println("A " + getItemDisplayName(ri) + " lies here.");
            }
        }
    }

    public CharacterRecord handleLookAndMovement(Command cmd, String name, CharacterRecord rec, CharacterDAO dao) {
        if (rec == null) {
            out.println("You seem to be nowhere. (no character record found)");
            return rec;
        }

        String cmdName = cmd.getName().toLowerCase();
        try {
            if ("look".equals(cmdName)) {
                Integer lookRoomId = rec.currentRoom;
                if (lookRoomId == null) {
                    out.println("You are not located in any room.");
                    return rec;
                }
                Room room = dao.getRoomById(lookRoomId);
                if (room == null) {
                    out.println("You are in an unknown place (room id " + lookRoomId + ").");
                    return rec;
                }

                String lookArgs = cmd.getArgs();
                if (lookArgs == null || lookArgs.trim().isEmpty()) {
                    showRoom(room, lookRoomId);
                    return rec;
                }

                if (lookArgs.trim().toLowerCase().startsWith("in ")) {
                    String containerSearch = lookArgs.trim().substring(3).trim().toLowerCase();
                    if (containerSearch.isEmpty()) {
                        out.println("Look in what?");
                        return rec;
                    }

                    ItemDAO itemDao = new ItemDAO();
                    Integer charId = dao.getCharacterIdByName(name);

                    java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(lookRoomId);
                    java.util.List<ItemDAO.RoomItem> invItems = charId != null ? itemDao.getItemsByCharacter(charId) : new java.util.ArrayList<>();

                    java.util.List<ItemDAO.RoomItem> allItems = new java.util.ArrayList<>();
                    allItems.addAll(roomItems);
                    allItems.addAll(invItems);

                    ItemDAO.RoomItem matchedContainer = null;
                    for (ItemDAO.RoomItem ri : allItems) {
                        if (ri.template.isContainer() && ri.template.name != null && ri.template.name.equalsIgnoreCase(containerSearch)) {
                            matchedContainer = ri;
                            break;
                        }
                    }
                    if (matchedContainer == null) {
                        for (ItemDAO.RoomItem ri : allItems) {
                            if (ri.template.isContainer() && ri.template.name != null && ri.template.name.toLowerCase().startsWith(containerSearch)) {
                                matchedContainer = ri;
                                break;
                            }
                        }
                    }
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
                    if (matchedContainer == null) {
                        for (ItemDAO.RoomItem ri : allItems) {
                            if (ri.template.isContainer() && ri.template.name != null && ri.template.name.toLowerCase().contains(containerSearch)) {
                                matchedContainer = ri;
                                break;
                            }
                        }
                    }

                    if (matchedContainer == null) {
                        out.println("You don't see a container called '" + lookArgs.trim().substring(3).trim() + "' here.");
                        return rec;
                    }

                    java.util.List<ItemDAO.RoomItem> contents = itemDao.getItemsInContainer(matchedContainer.instance.instanceId);
                    out.println(getItemDisplayName(matchedContainer) + " contains:");
                    if (contents.isEmpty()) {
                        out.println("  Nothing.");
                    } else {
                        java.util.Map<String, Integer> itemCounts = new java.util.LinkedHashMap<>();
                        for (ItemDAO.RoomItem ci : contents) {
                            String itemName = getItemDisplayName(ci);
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

                    return rec;
                }

                String searchTerm = lookArgs.trim().toLowerCase();
                boolean found = false;
                for (ClientHandler s : sessions) {
                    if (s == this) continue;
                    String otherName = s.playerName;
                    if (otherName == null) continue;
                    Integer otherRoomId = s.currentRoomId;
                    if (otherRoomId == null || !otherRoomId.equals(lookRoomId)) continue;
                    if (otherName.toLowerCase().startsWith(searchTerm)) {
                        CharacterRecord otherRec = dao.findByName(otherName);
                        out.println(otherName);
                        if (otherRec != null && otherRec.description != null && !otherRec.description.isEmpty()) {
                            out.println(otherRec.description);
                        } else {
                            out.println("You see nothing special about " + otherName + ".");
                        }
                        found = true;
                        break;
                    }
                }

                if (!found) {
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
                }

                if (!found) {
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

                return rec;
            }

            // Movement handling: use existing DAO/Room APIs to mirror original logic
            String dir = null;
            switch (cmdName) {
                case "north":
                case "n": dir = "north"; break;
                case "east":
                case "e": dir = "east"; break;
                case "south":
                case "s": dir = "south"; break;
                case "west":
                case "w": dir = "west"; break;
                case "up":
                case "u": dir = "up"; break;
                case "down":
                case "d": dir = "down"; break;
            }

            if (dir != null) {
                // Use the same stance check as the original code path
                Stance moveStance = RegenerationService.getInstance().getPlayerStance(characterId);
                if (!moveStance.canMove()) {
                    if (moveStance == Stance.SLEEPING) {
                        out.println("You are asleep! Type 'wake' to wake up first.");
                    } else {
                        out.println("You must stand up first. Type 'stand'.");
                    }
                    return rec;
                }

                Integer curRoomId = rec.currentRoom;
                if (curRoomId == null) {
                    out.println("You are not located in any room.");
                    return rec;
                }
                Room curRoom = dao.getRoomById(curRoomId);
                if (curRoom == null) {
                    out.println("You seem to be in an unknown place.");
                    return rec;
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
                    return rec;
                }

                // If the destination room id refers to a missing room (broken world), redirect to The Void (id 0)
                Room maybeDest = (destId == null) ? null : dao.getRoomById(destId);
                if (destId != null && maybeDest == null) {
                    destId = 0;
                }

                // Check and deduct movement points based on destination room/area
                int moveCost = dao.getMoveCostForRoom(destId);
                if (rec.mvCur < moveCost) {
                    out.println("You are too exhausted to move.");
                    return rec;
                }

                if (!dao.deductMovementPoints(name, moveCost)) {
                    out.println("You are too exhausted to move.");
                    return rec;
                }

                boolean moved = dao.updateCharacterRoom(name, destId);
                if (!moved) {
                    out.println("You try to move but something prevents you.");
                    return rec;
                }

                // Announce departure to the old room (respecting invisibility)
                Integer oldRoomId = curRoomId;
                if (!this.gmInvisible) {
                    roomAnnounceFromActor(oldRoomId, makeDepartureMessage(name, directionName), this.characterId);
                }

                // Refresh character record and show new room
                rec = dao.findByName(name);
                this.currentRoomId = rec != null ? rec.currentRoom : null;
                Room newRoom = dao.getRoomById(destId);
                if (newRoom == null) {
                    out.println("You arrive at an unknown place.");
                    return rec;
                }

                if (!this.gmInvisible) {
                    roomAnnounceFromActor(destId, makeArrivalMessage(name, directionName), this.characterId);
                }

                out.println("You move " + directionName + ".");
                showRoom(newRoom, destId);

                // Check for aggressive mobs in the new room
                {
                    CharacterClassDAO moveClassDao = new CharacterClassDAO();
                    int playerLevel = rec.currentClassId != null
                        ? moveClassDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
                    MobileRoamingService.getInstance().checkAggroOnPlayerEntry(destId, characterId, playerLevel);
                }

                return rec;
            }

        } catch (Exception ex) {
            out.println("An error occurred while processing the command.");
        }
        return rec;
    }

    private static void broadcastAll(String msg) {
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

    @SuppressWarnings("unused") // Utility method for future chat/communication features
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

    /**
     * Handle a local 'say' command: broadcast to everyone in the same room,
     * formatting the sender's own view as "You say: ..." and others as
     * "<name> says: ...".
     */
    public void handleSay(Command cmd, String name, CharacterRecord rec, CharacterDAO dao) {
        try {
            String text = cmd.getArgs();
            if (rec == null || rec.currentRoom == null) { out.println("You are nowhere to say that."); return; }
            Integer roomId = rec.currentRoom;
            if (roomId == null) return;
            for (ClientHandler s : sessions) {
                if (s.currentRoomId != null && s.currentRoomId.equals(roomId)) {
                    if (s == this) s.sendRaw("You say: " + text);
                    else s.sendRaw(this.playerName + " says: " + text);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Centralized handling for chat-style commands: `chat`, `yell`, `whisper`, and `gmchat`.
     */
    public void handleCommunication(Command cmd, String name, CharacterRecord rec, CharacterDAO dao) {
        try {
            String cmdName = cmd.getName().toLowerCase();
            switch (cmdName) {
                case "chat": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: chat <message>"); return; }
                    broadcastAll("[chat] " + this.playerName + ": " + t);
                    return;
                }
                case "yell": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: yell <message>"); return; }
                    Integer roomId = rec != null ? rec.currentRoom : null;
                    if (roomId == null) { out.println("You are nowhere to yell from."); return; }
                    Room roomObj = dao.getRoomById(roomId);
                    if (roomObj == null) { out.println("You are nowhere to yell from."); return; }
                    int areaId = roomObj.getAreaId();
                    broadcastArea(dao, areaId, "[yell] " + this.playerName + ": " + t);
                    return;
                }
                case "whisper": {
                    String args = cmd.getArgs();
                    if (args == null || args.trim().isEmpty()) { out.println("Usage: whisper <target> <message>"); return; }
                    String[] parts = args.trim().split("\\s+", 2);
                    if (parts.length < 2) { out.println("Usage: whisper <target> <message>"); return; }
                    String target = parts[0];
                    String msg = parts[1];
                    ClientHandler t = nameToSession.get(target.toLowerCase());
                    if (t == null) { out.println("No such player online: " + target); return; }
                    t.sendRaw("[whisper] " + this.playerName + " -> you: " + msg);
                    this.sendRaw("[whisper] you -> " + target + ": " + msg);
                    return;
                }
                case "gmchat": {
                    String t = cmd.getArgs();
                    if (t == null || t.trim().isEmpty()) { out.println("Usage: gmchat <message>"); return; }
                    if (!dao.isCharacterFlagTrueByName(name, "is_gm")) { out.println("You do not have permission to use gmchat."); return; }
                    gmBroadcast(dao, this.playerName, t);
                    return;
                }
                default:
                    // Not a communication command; fall through to caller
                    return;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Ensure the current session/character is a GM. Prints an appropriate
     * message and returns false if not allowed.
     */
    private boolean ensureGm(String name, CharacterRecord rec, CharacterDAO dao) {
        if (rec == null) { out.println("No character record found."); return false; }
        try {
            if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                out.println("You do not have permission to use GM commands.");
                return false;
            }
        } catch (Exception ignored) {
            out.println("Permission check failed.");
            return false;
        }
        return true;
    }

    /**
     * Centralized handler for GM commands. Moves GM case bodies here to keep
     * the main switch compact. Returns possibly-updated CharacterRecord.
     */
    public CharacterRecord handleGmCommand(Command cmd, String name, CharacterRecord rec, CharacterDAO dao) {
        if (cmd == null) return rec;
        String cmdName = cmd.getName().toLowerCase();
        try {
            switch (cmdName) {
                case "cflag": {
                    // GM-only: CFLAG SET <char> <flag> <value>  OR  CFLAG CHECK <char> <flag>
                    if (!ensureGm(name, rec, dao)) break;
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
                case "cset": {
                    // GM-only: CSET <char> <attribute> <value>  OR  CSET LIST
                    if (!ensureGm(name, rec, dao)) break;
                    String csetArgs = cmd.getArgs();
                    if (csetArgs == null || csetArgs.trim().isEmpty()) {
                        out.println("Usage: CSET <character> <attribute> <value>");
                        out.println("       CSET LIST - Show all settable attributes");
                        break;
                    }
                    String[] csetParts = csetArgs.trim().split("\\s+", 3);
                    
                    // Handle CSET LIST
                    if (csetParts[0].equalsIgnoreCase("list")) {
                        out.println();
                        out.println("═══════════════════════════════════════════════════════════════");
                        out.println("                    SETTABLE ATTRIBUTES");
                        out.println("═══════════════════════════════════════════════════════════════");
                        out.println();
                        out.println("  [ VITALS ]");
                        out.println("    hp, hpmax, mp, mpmax, mv, mvmax");
                        out.println();
                        out.println("  [ BASE ABILITIES ]");
                        out.println("    str, dex, con, int, wis, cha");
                        out.println();
                        out.println("  [ TRAINED ABILITIES ]");
                        out.println("    trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha");
                        out.println("    (aliases: tstr, tdex, tcon, tint, twis, tcha)");
                        out.println();
                        out.println("  [ SAVES ]");
                        out.println("    armor (ac), fortitude (fort), reflex (ref), will");
                        out.println();
                        out.println("  [ EQUIPMENT BONUSES ]");
                        out.println("    armor_equip, fort_equip, reflex_equip, will_equip");
                        out.println();
                        out.println("  [ CLASS & PROGRESSION ]");
                        out.println("    class, level, xp");
                        out.println();
                        out.println("  [ OTHER ]");
                        out.println("    age, room, autoflee, talents, gold, description");
                        out.println();
                        out.println("═══════════════════════════════════════════════════════════════");
                        out.println();
                        break;
                    }
                    
                    if (csetParts.length < 3) {
                        out.println("Usage: CSET <character> <attribute> <value>");
                        out.println("       CSET LIST - Show all settable attributes");
                        break;
                    }
                    
                    String targetName = csetParts[0];
                    String attrName = csetParts[1];
                    String attrValue = csetParts[2];
                    
                    // Find the character
                    Integer targetCharId = dao.getCharacterIdByName(targetName);
                    if (targetCharId == null) {
                        out.println("Character '" + targetName + "' not found.");
                        break;
                    }
                    
                    // Set the attribute
                    String result = dao.setCharacterAttribute(targetCharId, attrName, attrValue);
                    out.println(targetName + ": " + result);
                    
                    // Notify the target if they're online and it's not self
                    if (!targetName.equalsIgnoreCase(name)) {
                        ClientHandler targetHandler = nameToSession.get(targetName.toLowerCase());
                        if (targetHandler != null) {
                            targetHandler.sendRaw("[GM] Your " + attrName + " has been modified.");
                        }
                    }
                    break;
                }
                case "cskill": {
                    // GM-only: CSKILL <character> <skill_id> [amount]  OR  CSKILL LIST
                    // Grants a skill to a character at a given proficiency (default 100%)
                    if (!ensureGm(name, rec, dao)) break;
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
                    if (!ensureGm(name, rec, dao)) break;
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
                    if (!ensureGm(name, rec, dao)) break;
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
                        String typeName = t.types != null && !t.types.isEmpty() ? String.join(",", t.types) : "";
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
                    if (!ensureGm(name, rec, dao)) break;
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
                case "istat": {
                    // GM-only: ISTAT <item> - Show detailed stats of an inventory item
                    if (!ensureGm(name, rec, dao)) break;
                    String istatArgs = cmd.getArgs();
                    if (istatArgs == null || istatArgs.trim().isEmpty()) {
                        out.println("Usage: ISTAT <item>");
                        out.println("  Shows detailed stat block for an item in your inventory.");
                        break;
                    }
                    String istatSearch = istatArgs.trim().toLowerCase();
                    Integer charId = dao.getCharacterIdByName(name);
                    if (charId == null) { out.println("Failed to locate your character record."); break; }

                    ItemDAO istatItemDao = new ItemDAO();
                    java.util.List<ItemDAO.RoomItem> allItems = istatItemDao.getItemsByCharacter(charId);

                    ItemDAO.RoomItem matchedItem = null;
                    for (ItemDAO.RoomItem ri : allItems) {
                        if (ri.instance.customName != null && ri.instance.customName.toLowerCase().contains(istatSearch)) { matchedItem = ri; break; }
                        if (ri.template != null && ri.template.name != null && ri.template.name.toLowerCase().contains(istatSearch)) { matchedItem = ri; break; }
                        if (ri.template != null && ri.template.keywords != null) {
                            for (String kw : ri.template.keywords) {
                                if (kw.toLowerCase().contains(istatSearch)) { matchedItem = ri; break; }
                            }
                            if (matchedItem != null) break;
                        }
                    }

                    if (matchedItem == null) { out.println("You don't have '" + istatSearch + "' in your inventory."); break; }

                    ItemInstance inst = matchedItem.instance;
                    ItemTemplate tmpl = matchedItem.template;
                    String displayName = getItemDisplayName(matchedItem);

                    out.println();
                    out.println("=== ITEM STAT BLOCK ===");
                    out.println();
                    out.println("  Name:        " + displayName);
                    if (inst.customName != null) {
                        out.println("  Base Name:   " + (tmpl != null ? tmpl.name : "Unknown") + " (template)");
                    }
                    out.println("  Instance ID: " + inst.instanceId);
                    out.println("  Template ID: " + inst.templateId);
                    out.println("  Item Level:  " + inst.itemLevel);
                    out.println("  Generated:   " + (inst.isGenerated ? "Yes" : "No"));
                    out.println();

                    if (tmpl != null) {
                        out.println("--- TEMPLATE INFO ---");
                        out.println("  Key:         " + (tmpl.key != null ? tmpl.key : "-"));
                        out.println("  Types:       " + (tmpl.types != null && !tmpl.types.isEmpty() ? String.join(", ", tmpl.types) : "-"));
                        out.println("  Subtype:     " + (tmpl.subtype != null ? tmpl.subtype : "-"));
                        out.println("  Slot:        " + (tmpl.slot != null ? tmpl.slot : "-"));
                        out.println("  Weight:      " + tmpl.weight);
                        out.println("  Base Value:  " + tmpl.value + " gp");
                        out.println("  Traits:      " + (tmpl.traits != null && !tmpl.traits.isEmpty() ? String.join(", ", tmpl.traits) : "-"));
                        out.println("  Keywords:    " + (tmpl.keywords != null && !tmpl.keywords.isEmpty() ? String.join(", ", tmpl.keywords) : "-"));
                        out.println();
                    }

                    boolean isWeapon = tmpl != null && tmpl.hasType("weapon");
                    if (isWeapon || inst.baseDieOverride != null || inst.multiplierOverride != null) {
                        out.println("--- WEAPON STATS ---");
                        int baseDie = inst.getEffectiveBaseDie(tmpl);
                        int mult = inst.getEffectiveMultiplier(tmpl);
                        double abilMult = inst.getEffectiveAbilityMultiplier(tmpl);
                        String abilScore = tmpl != null ? tmpl.abilityScore : "STR";

                        out.println("  Base Die:        " + baseDie + (inst.baseDieOverride != null ? " (override)" : ""));
                        out.println("  Multiplier:      " + mult + (inst.multiplierOverride != null ? " (override)" : ""));
                        out.println("  Ability Score:   " + (abilScore != null ? abilScore : "STR"));
                        out.println("  Ability Mult:    " + String.format("%.1f", abilMult) + (inst.abilityMultOverride != null ? " (override)" : ""));
                        out.println("  Damage Formula:  " + mult + "d" + baseDie + " + " + String.format("%.1f", abilMult) + "x" + (abilScore != null ? abilScore : "STR") + " mod");
                        out.println("  Hands:           " + (tmpl != null ? tmpl.hands : 1));
                        if (tmpl != null) {
                            out.println("  Category:        " + (tmpl.weaponCategory != null ? tmpl.weaponCategory : "-"));
                            out.println("  Family:          " + (tmpl.weaponFamily != null ? tmpl.weaponFamily : "-"));
                        }
                        out.println();
                    }

                    boolean isArmor = tmpl != null && (tmpl.hasType("armor") || tmpl.hasType("shield"));
                    boolean hasSaves = inst.armorSaveOverride != null || inst.fortSaveOverride != null || inst.refSaveOverride != null || inst.willSaveOverride != null || (tmpl != null && (tmpl.armorSaveBonus > 0 || tmpl.fortSaveBonus > 0 || tmpl.refSaveBonus > 0 || tmpl.willSaveBonus > 0));
                    if (isArmor || hasSaves) {
                        out.println("--- ARMOR / SAVES ---");
                        int armorSave = inst.getEffectiveArmorSave(tmpl);
                        int fortSave = inst.getEffectiveFortSave(tmpl);
                        int refSave = inst.getEffectiveRefSave(tmpl);
                        int willSave = inst.getEffectiveWillSave(tmpl);

                        out.println("  Armor Save:      " + (armorSave != 0 ? "+" + armorSave : "0") + (inst.armorSaveOverride != null ? " (override)" : ""));
                        out.println("  Fortitude Save:  " + (fortSave != 0 ? "+" + fortSave : "0") + (inst.fortSaveOverride != null ? " (override)" : ""));
                        out.println("  Reflex Save:     " + (refSave != 0 ? "+" + refSave : "0") + (inst.refSaveOverride != null ? " (override)" : ""));
                        out.println("  Will Save:       " + (willSave != 0 ? "+" + willSave : "0") + (inst.willSaveOverride != null ? " (override)" : ""));
                        if (tmpl != null && tmpl.armorCategory != null) {
                            out.println("  Armor Category:  " + tmpl.armorCategory);
                        }
                        out.println();
                    }

                    String eff1 = inst.getEffectiveSpellEffect1(tmpl);
                    String eff2 = inst.getEffectiveSpellEffect2(tmpl);
                    String eff3 = inst.getEffectiveSpellEffect3(tmpl);
                    String eff4 = inst.getEffectiveSpellEffect4(tmpl);
                    if (eff1 != null || eff2 != null || eff3 != null || eff4 != null) {
                        out.println("--- SPELL EFFECTS ---");
                        if (eff1 != null) out.println("  Effect 1: " + eff1 + (inst.spellEffect1Override != null ? " (override)" : ""));
                        if (eff2 != null) out.println("  Effect 2: " + eff2 + (inst.spellEffect2Override != null ? " (override)" : ""));
                        if (eff3 != null) out.println("  Effect 3: " + eff3 + (inst.spellEffect3Override != null ? " (override)" : ""));
                        if (eff4 != null) out.println("  Effect 4: " + eff4 + (inst.spellEffect4Override != null ? " (override)" : ""));
                        out.println();
                    }

                    int effectiveValue = inst.getEffectiveValue(tmpl);
                    out.println("--- VALUE ---");
                    out.println("  Sell Value:  " + effectiveValue + " gp" + (inst.valueOverride != null ? " (override)" : ""));
                    out.println();

                    String desc = inst.customDescription != null ? inst.customDescription : (tmpl != null ? tmpl.description : null);
                    if (desc != null && !desc.isEmpty()) {
                        out.println("--- DESCRIPTION ---");
                        out.println("  " + desc);
                        out.println();
                    }

                    out.println("=======================");
                    out.println();
                    break;
                }
                case "mstat": {
                    // GM-only: MSTAT <mobile> - Show detailed stats of a mobile in the room
                    if (!ensureGm(name, rec, dao)) break;
                    String mstatArgs = cmd.getArgs();
                    if (mstatArgs == null || mstatArgs.trim().isEmpty()) {
                        out.println("Usage: MSTAT <mobile>");
                        out.println("  Shows detailed stat block for a mobile in your current room.");
                        break;
                    }
                    String mstatSearch = mstatArgs.trim().toLowerCase();

                    MobileDAO mstatMobDao = new MobileDAO();
                    java.util.List<Mobile> mobsInRoom = mstatMobDao.getMobilesInRoom(rec.currentRoom);

                    Mobile matchedMob = null;
                    for (Mobile mob : mobsInRoom) {
                        if (mob.isDead()) continue;
                        if (mob.getName() != null && mob.getName().toLowerCase().contains(mstatSearch)) { matchedMob = mob; break; }
                        if (mob.getShortDesc() != null && mob.getShortDesc().toLowerCase().contains(mstatSearch)) { matchedMob = mob; break; }
                    }

                    if (matchedMob == null) {
                        for (Mobile mob : mobsInRoom) {
                            if (mob.isDead()) continue;
                            MobileTemplate mobTmpl = mstatMobDao.getTemplateById(mob.getTemplateId());
                            if (mobTmpl != null && mobTmpl.matchesKeyword(mstatSearch)) { matchedMob = mob; break; }
                        }
                    }

                    if (matchedMob == null) { out.println("No mobile '" + mstatSearch + "' found in this room."); break; }

                    MobileTemplate mobTmpl = mstatMobDao.getTemplateById(matchedMob.getTemplateId());

                    out.println();
                    out.println("=== MOBILE STAT BLOCK ===");
                    out.println();
                    out.println("  Name:          " + matchedMob.getName());
                    out.println("  Short Desc:    " + (matchedMob.getShortDesc() != null ? matchedMob.getShortDesc() : "-"));
                    out.println("  Instance ID:   " + matchedMob.getInstanceId());
                    out.println("  Template ID:   " + matchedMob.getTemplateId());
                    out.println("  Level:         " + matchedMob.getLevel());
                    out.println("  Room:          " + matchedMob.getCurrentRoom());
                    out.println("  Spawn Room:    " + (matchedMob.getSpawnRoomId() != null ? matchedMob.getSpawnRoomId() : "-"));
                    out.println();

                    if (mobTmpl != null) {
                        out.println("--- TEMPLATE INFO ---");
                        out.println("  Key:           " + (mobTmpl.getKey() != null ? mobTmpl.getKey() : "-"));
                        out.println("  Long Desc:     " + (mobTmpl.getLongDesc() != null ? mobTmpl.getLongDesc() : "-"));
                        out.println("  Keywords:      " + (mobTmpl.getKeywords() != null && !mobTmpl.getKeywords().isEmpty() ? String.join(", ", mobTmpl.getKeywords()) : "-"));
                        out.println();
                    }

                    out.println("--- VITALS ---");
                    out.println("  HP:            " + matchedMob.getHpCur() + " / " + matchedMob.getHpMax());
                    out.println("  MP:            " + matchedMob.getMpCur() + " / " + matchedMob.getMpMax());
                    out.println("  MV:            " + matchedMob.getMvCur() + " / " + matchedMob.getMvMax());
                    out.println();

                    out.println("--- ABILITIES ---");
                    out.println("  STR: " + String.format("%2d", matchedMob.getStr()) + " (" + (matchedMob.getStr() >= 10 ? "+" : "") + ((matchedMob.getStr() - 10) / 2) + ")" + "   DEX: " + String.format("%2d", matchedMob.getDex()) + " (" + (matchedMob.getDex() >= 10 ? "+" : "") + ((matchedMob.getDex() - 10) / 2) + ")" + "   CON: " + String.format("%2d", matchedMob.getCon()) + " (" + (matchedMob.getCon() >= 10 ? "+" : "") + ((matchedMob.getCon() - 10) / 2) + ")");
                    out.println("  INT: " + String.format("%2d", matchedMob.getIntel()) + " (" + (matchedMob.getIntel() >= 10 ? "+" : "") + ((matchedMob.getIntel() - 10) / 2) + ")" + "   WIS: " + String.format("%2d", matchedMob.getWis()) + " (" + (matchedMob.getWis() >= 10 ? "+" : "") + ((matchedMob.getWis() - 10) / 2) + ")" + "   CHA: " + String.format("%2d", matchedMob.getCha()) + " (" + (matchedMob.getCha() >= 10 ? "+" : "") + ((matchedMob.getCha() - 10) / 2) + ")");
                    out.println();

                    out.println("--- DEFENSES ---");
                    out.println("  Armor:         " + matchedMob.getArmor());
                    out.println("  Fortitude:     " + matchedMob.getFortitude());
                    out.println("  Reflex:        " + matchedMob.getReflex());
                    out.println("  Will:          " + matchedMob.getWill());
                    out.println();

                    out.println("--- COMBAT ---");
                    out.println("  Attack Bonus:  " + (matchedMob.getAttackBonus() >= 0 ? "+" : "") + matchedMob.getAttackBonus());
                    out.println("  Base Damage:   " + matchedMob.getBaseDamage());
                    out.println("  Damage Bonus:  " + (matchedMob.getDamageBonus() >= 0 ? "+" : "") + matchedMob.getDamageBonus());
                    int strMod2 = (matchedMob.getStr() - 10) / 2;
                    out.println("  Damage Roll:   1d" + matchedMob.getBaseDamage() + " + " + matchedMob.getDamageBonus() + " + " + strMod2 + " (STR)");
                    out.println("  Autoflee HP%:  " + matchedMob.getAutoflee() + "%");
                    out.println();

                    out.println("--- BEHAVIORS ---");
                    if (matchedMob.getBehaviors() != null && !matchedMob.getBehaviors().isEmpty()) {
                        StringBuilder behaviorList = new StringBuilder();
                        for (MobileBehavior b : matchedMob.getBehaviors()) { if (behaviorList.length() > 0) behaviorList.append(", "); behaviorList.append(b.name()); }
                        out.println("  " + behaviorList);
                    } else {
                        out.println("  (none)");
                    }
                    out.println();

                    out.println("--- STATE ---");
                    out.println("  Dead:          " + (matchedMob.isDead() ? "Yes" : "No"));
                    out.println("  Has Target:    " + (matchedMob.hasTarget() ? "Yes" : "No"));
                    if (matchedMob.getTargetCharacterId() != null) out.println("  Target (PC):   Character ID " + matchedMob.getTargetCharacterId());
                    if (matchedMob.getTargetMobileId() != null) out.println("  Target (Mob):  Mobile ID " + matchedMob.getTargetMobileId());
                    out.println();

                    if (mobTmpl != null) {
                        out.println("--- REWARDS ---");
                        out.println("  XP Value:      " + matchedMob.getExperienceValue());
                        out.println("  Gold Range:    " + mobTmpl.getGoldMin() + " - " + mobTmpl.getGoldMax());
                        out.println("  Respawn Time:  " + mobTmpl.getRespawnSeconds() + " seconds");
                        if (mobTmpl.getAggroRange() > 0) out.println("  Aggro Range:   " + mobTmpl.getAggroRange() + " rooms");
                        out.println();
                    }

                    out.println("--- SPAWN INFO ---");
                    out.println("  Origin UUID:   " + (matchedMob.getOriginUuid() != null ? matchedMob.getOriginUuid() : "-"));
                    out.println("  Spawned At:    " + (matchedMob.getSpawnedAt() > 0 ? new java.util.Date(matchedMob.getSpawnedAt()).toString() : "-"));
                    if (matchedMob.isDead() && matchedMob.getDiedAt() > 0) out.println("  Died At:       " + new java.util.Date(matchedMob.getDiedAt()).toString());
                    out.println();

                    out.println("=========================");
                    out.println();
                    break;
                }
                case "goto": {
                    // GM-only: GOTO <room_id> or GOTO <player_name>
                    if (!ensureGm(name, rec, dao)) break;
                    String gotoArgs = cmd.getArgs();
                    if (gotoArgs == null || gotoArgs.trim().isEmpty()) {
                        out.println("Usage: GOTO <room_id> or GOTO <player_name>");
                        out.println("  Teleports you directly to a room or to another player.");
                        break;
                    }

                    int gotoRoomId = -1;
                    String gotoTarget = gotoArgs.trim();

                    try {
                        gotoRoomId = Integer.parseInt(gotoTarget);
                    } catch (NumberFormatException e) {
                        ClientHandler targetSession = null;
                        for (ClientHandler s : sessions) {
                            String pName = s.playerName;
                            if (pName != null && pName.toLowerCase().startsWith(gotoTarget.toLowerCase())) { targetSession = s; break; }
                        }

                        if (targetSession != null && targetSession.currentRoomId != null) {
                            gotoRoomId = targetSession.currentRoomId;
                            out.println("(Teleporting to " + targetSession.playerName + ")");
                        } else {
                            CharacterRecord targetRec = dao.findByName(gotoTarget);
                            if (targetRec != null && targetRec.currentRoom != null) {
                                gotoRoomId = targetRec.currentRoom;
                                out.println("(Teleporting to " + targetRec.name + "'s last location)");
                            } else {
                                out.println("No room or player found matching '" + gotoTarget + "'.");
                                break;
                            }
                        }
                    }

                    Room gotoRoom = dao.getRoomById(gotoRoomId);
                    if (gotoRoom == null) { out.println("Room #" + gotoRoomId + " does not exist."); break; }

                    Integer oldRoom = rec.currentRoom;
                    if (!this.gmInvisible) { roomAnnounce(oldRoom, makeDepartureMessage(name, null), this.characterId, true); }

                    dao.updateCharacterRoom(name, gotoRoomId);
                    this.currentRoomId = gotoRoomId;
                    rec = dao.findByName(name);

                    if (!this.gmInvisible) { roomAnnounce(gotoRoomId, makeArrivalMessage(name, null), this.characterId, true); }

                    out.println();
                    out.println("You vanish and reappear in " + gotoRoom.getName() + ".");
                    out.println();
                    showRoom(gotoRoom, gotoRoomId);
                    break;
                }
                // other GM commands inserted below
                case "dbinfo": {
                    // GM-only: prints table schema information
                    if (!ensureGm(name, rec, dao)) break;
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
                case "genmap": {
                    // GM-only: generate ASCII map for an area
                    if (!ensureGm(name, rec, dao)) break;
                    String mapArgs = cmd.getArgs();
                    int targetAreaId;
                    if (mapArgs == null || mapArgs.trim().isEmpty()) {
                        // Use current room's area
                        if (rec == null || rec.currentRoom == null) {
                            out.println("Usage: genmap [areaId]");
                            break;
                        }
                        com.example.tassmud.model.Room currentRoom = dao.getRoomById(rec.currentRoom);
                        if (currentRoom == null) {
                            out.println("Could not determine your current area.");
                            break;
                        }
                        targetAreaId = currentRoom.getAreaId();
                    } else {
                        try {
                            targetAreaId = Integer.parseInt(mapArgs.trim());
                        } catch (NumberFormatException e) {
                            out.println("Invalid area ID: " + mapArgs);
                            break;
                        }
                    }

                    // Generate the map
                    String mapResult = com.example.tassmud.tools.MapGenerator.generateMapForAreaInGame(targetAreaId);
                    if (mapResult != null) {
                        out.println(mapResult);
                    } else {
                        out.println("Failed to generate map for area " + targetAreaId);
                    }
                    break;
                }
                default:
                    // Not a GM command we handle here; leave caller to continue.
                    return rec;
            }
        } catch (Exception ex) {
            out.println("An error occurred while processing GM command.");
        }
        return rec;
    }
    
    /**
     * Send a debug message to this session (only if debug channel is enabled).
     * Use this for verbose debugging output that GMs can toggle on/off.
     */
    private void sendDebug(String msg) {
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
     * Trigger autoflee for a character.
     * Called by CombatManager when a player's HP drops below their autoflee threshold.
     * @param characterId the character ID
     * @param combat the current combat
     * @return true if flee succeeded, false otherwise
     */
    public static boolean triggerAutoflee(Integer characterId, Combat combat) {
        if (characterId == null || combat == null) return false;
        
        ClientHandler handler = charIdToSession.get(characterId);
        if (handler == null) return false;
        
        return handler.executeAutoflee(combat);
    }
    
    /**
     * Execute autoflee for this character.
     * Similar to manual flee but triggered automatically by combat.
     * @param combat the current combat
     * @return true if flee succeeded
     */
    private boolean executeAutoflee(Combat combat) {
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
            showRoom(newRoom, destRoomId);
        }
        
        return true;
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
    public static Character buildCharacterForCombat(CharacterDAO.CharacterRecord rec, Integer characterId) {
        if (rec == null) return null;
        
        Character playerChar = new Character(
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
    private static String getItemDisplayName(ItemDAO.RoomItem ri) {
        if (ri == null) return "an item";
        if (ri.instance != null && ri.instance.customName != null && !ri.instance.customName.isEmpty()) {
            return ri.instance.customName;
        }
        if (ri.template != null && ri.template.name != null) {
            return ri.template.name;
        }
        return "an item";
    }
    
    /**
     * Get the display name for an item from separate instance and template.
     * Used when we have ItemInstance and ItemTemplate separately (e.g., equipped items).
     * 
     * @param inst the ItemInstance (may be null)
     * @param tmpl the ItemTemplate (may be null)
     * @return the display name to show players
     */
    private static String getItemDisplayName(ItemInstance inst, ItemTemplate tmpl) {
        if (inst != null && inst.customName != null && !inst.customName.isEmpty()) {
            return inst.customName;
        }
        if (tmpl != null && tmpl.name != null) {
            return tmpl.name;
        }
        return "an item";
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
                
                // Shared temporaries for multiple case branches
                Room curRoom = null;
                int moveCost = 0;
                boolean moved = false;
                Room newRoom = null;
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
                    case "look":
                        rec = handleLookAndMovement(cmd, name, rec, dao);
                        break;
                    case "kill": {
                        // Initiate combat against a mobile in the current room
                        if (rec == null) { out.println("You must be logged in to attack."); break; }
                        String targetArg = cmd.getArgs();
                        if (targetArg == null || targetArg.trim().isEmpty()) {
                            out.println("Usage: kill <target>");
                            break;
                        }
                        if (characterId == null) {
                            out.println("Unable to determine your character."); break;
                        }

                        // Check stance allows initiating combat
                        Stance s = RegenerationService.getInstance().getPlayerStance(characterId);
                        if (!s.canInitiateCombat()) {
                            out.println("You must be standing to initiate combat.");
                            break;
                        }

                        Integer roomId = rec.currentRoom;
                        if (roomId == null) { out.println("You are nowhere to attack from."); break; }

                        MobileDAO mobDao = new MobileDAO();
                        java.util.List<Mobile> mobs = mobDao.getMobilesInRoom(roomId);
                        if (mobs == null || mobs.isEmpty()) { out.println("There are no creatures here to attack."); break; }

                        String search = targetArg.trim().toLowerCase();
                        Mobile matched = null;
                        for (Mobile m : mobs) {
                            if (m.isDead()) continue;
                            if (m.getName() != null && m.getName().toLowerCase().startsWith(search)) { matched = m; break; }
                            if (m.getShortDesc() != null && m.getShortDesc().toLowerCase().startsWith(search)) { matched = m; break; }
                        }
                        if (matched == null) {
                            // Try template keyword match
                            for (Mobile m : mobs) {
                                if (m.isDead()) continue;
                                MobileTemplate mt = mobDao.getTemplateById(m.getTemplateId());
                                if (mt != null && mt.matchesKeyword(search)) { matched = m; break; }
                            }
                        }

                        if (matched == null) { out.println("No such target here: " + targetArg); break; }

                        // Build attacker and initiate combat
                        Character attacker = buildCharacterForCombat(rec, characterId);
                        if (attacker == null) { out.println("Failed to prepare you for combat."); break; }

                        CombatManager cm = CombatManager.getInstance();
                        cm.initiateCombat(attacker, characterId, matched, roomId);
                        // Refresh record after initiating combat
                        rec = dao.findByName(name);
                        break;
                    }
                    case "say":
                        handleSay(cmd, name, rec, dao);
                        break;
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
                        rec = handleLookAndMovement(cmd, name, rec, dao);
                        break;
                    
                    case "recall": {
                        // Teleport back to the Mead-Gaard Inn (home base)
                        if (rec == null) {
                            out.println("No character record found.");
                            break;
                        }
                        
                        // Check stance - can't recall while sleeping
                        Stance recallStance = RegenerationService.getInstance().getPlayerStance(characterId);
                        if (!recallStance.canMove()) {
                            if (recallStance == Stance.SLEEPING) {
                                out.println("You are asleep! Type 'wake' to wake up first.");
                            } else {
                                out.println("You must stand up first. Type 'stand'.");
                            }
                            break;
                        }
                        
                        final int MEAD_GAARD_INN = 3041;
                        
                        // Already at the inn?
                        if (rec.currentRoom != null && rec.currentRoom == MEAD_GAARD_INN) {
                            out.println("You are already at the Mead-Gaard Inn.");
                            break;
                        }
                        
                        // Verify the destination exists
                        Room innRoom = dao.getRoomById(MEAD_GAARD_INN);
                        if (innRoom == null) {
                            out.println("The Mead-Gaard Inn seems to have vanished from reality. Something is very wrong.");
                            break;
                        }
                        
                        // Announce magical departure from old room (respecting invisibility)
                        Integer recallOldRoom = rec.currentRoom;
                        roomAnnounceFromActor(recallOldRoom, name + " closes their eyes and vanishes in a shimmer of light.", this.characterId);
                        
                        // Teleport the character
                        dao.updateCharacterRoom(name, MEAD_GAARD_INN);
                        this.currentRoomId = MEAD_GAARD_INN;
                        rec = dao.findByName(name);
                        
                        // Announce magical arrival in new room (respecting invisibility)
                        roomAnnounceFromActor(MEAD_GAARD_INN, name + " appears in a shimmer of light.", this.characterId);
                        
                        out.println();
                        out.println("You close your eyes and think of home...");
                        out.println("A warm sensation envelops you, and when you open your eyes, you find yourself at the Mead-Gaard Inn.");
                        out.println();
                        showRoom(innRoom, MEAD_GAARD_INN);
                        break;
                    }
                        
                    // ===== STANCE COMMANDS =====
                    case "sit": {
                        Stance currentStance = RegenerationService.getInstance().getPlayerStance(characterId);
                        if (currentStance == Stance.SITTING) {
                            out.println("You are already sitting.");
                            break;
                        }
                        if (currentStance == Stance.SLEEPING) {
                            out.println("You wake up and sit up.");
                        } else {
                            out.println("You sit down.");
                        }
                        RegenerationService.getInstance().setPlayerStance(characterId, Stance.SITTING);
                        roomAnnounceFromActor(currentRoomId, name + " sits down.", this.characterId);
                        break;
                    }
                    case "sleep":
                    case "rest": {
                        Stance currentStance = RegenerationService.getInstance().getPlayerStance(characterId);
                        if (currentStance == Stance.SLEEPING) {
                            out.println("You are already sleeping.");
                            break;
                        }
                        if (currentStance == Stance.STANDING) {
                            out.println("You lie down and go to sleep.");
                        } else {
                            out.println("You close your eyes and drift off to sleep.");
                        }
                        RegenerationService.getInstance().setPlayerStance(characterId, Stance.SLEEPING);
                        roomAnnounceFromActor(currentRoomId, name + " lies down and goes to sleep.", this.characterId);
                        break;
                    }
                    case "stand":
                    case "wake": {
                        // Check if in combat - combat stand from prone is a special skill
                        CombatManager combatMgr = CombatManager.getInstance();
                        Combat activeCombat = combatMgr.getCombatForCharacter(characterId);
                        
                        if (activeCombat != null) {
                            // In combat - check if prone
                            Combatant userCombatant = activeCombat.findByCharacterId(characterId);
                            if (userCombatant == null) {
                                out.println("Combat error: could not find your combatant.");
                                break;
                            }
                            
                            if (!userCombatant.isProne()) {
                                out.println("You are already standing.");
                                break;
                            }
                            
                            // Combat stand from prone - this is an innate skill
                            // Get user's level for opposed check
                            CharacterClassDAO standClassDao = new CharacterClassDAO();
                            int userLevel = rec.currentClassId != null 
                                ? standClassDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
                            
                            // Find an opponent to make the opposed check against
                            // Use the highest level opponent
                            int opponentLevel = 1;
                            Combatant mainOpponent = null;
                            for (Combatant c : activeCombat.getCombatants()) {
                                if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                                    int cLevel;
                                    if (c.isPlayer()) {
                                        Integer cCharId = c.getCharacterId();
                                        CharacterRecord cRec = dao.getCharacterById(cCharId);
                                        cLevel = cRec != null && cRec.currentClassId != null 
                                            ? standClassDao.getCharacterClassLevel(cCharId, cRec.currentClassId) : 1;
                                    } else if (c.getMobile() != null) {
                                        cLevel = c.getMobile().getLevel();
                                    } else {
                                        cLevel = 1;
                                    }
                                    if (cLevel > opponentLevel) {
                                        opponentLevel = cLevel;
                                        mainOpponent = c;
                                    }
                                }
                            }
                            
                            // Perform opposed check at 100% proficiency (innate skill)
                            int roll = (int)(Math.random() * 100) + 1;
                            int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(
                                userLevel, opponentLevel, 100); // 100% proficiency for innate skills
                            
                            boolean standSucceeded = roll <= successChance;
                            
                            // Regardless of success/failure, forfeit all attacks this round
                            userCombatant.setAttacksRemaining(0);
                            userCombatant.setHasActedThisRound(true);
                            
                            if (standSucceeded) {
                                // Success! Remove prone status
                                userCombatant.standUp();
                                out.println("You manage to get to your feet!");
                                roomAnnounce(currentRoomId, name + " scrambles to their feet.", this.characterId, true);
                            } else {
                                // Failure - remain prone
                                out.println("You struggle to stand but fail to get up.");
                                roomAnnounce(currentRoomId, name + " struggles to stand but remains on the ground.", this.characterId, true);
                            }
                            break;
                        }
                        
                        // Not in combat - normal stand behavior
                        Stance currentStance = RegenerationService.getInstance().getPlayerStance(characterId);
                        if (currentStance == Stance.STANDING) {
                            out.println("You are already standing.");
                            break;
                        }
                        if (currentStance == Stance.SLEEPING) {
                            out.println("You wake up and stand up.");
                            roomAnnounce(currentRoomId, name + " wakes up and stands up.", this.characterId, true);
                        } else {
                            out.println("You stand up.");
                            roomAnnounce(currentRoomId, name + " stands up.", this.characterId, true);
                        }
                        RegenerationService.getInstance().setPlayerStance(characterId, Stance.STANDING);
                        break;
                    }
                        
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
                    case "autoflee": {
                        // AUTOFLEE - Set/view automatic flee threshold
                        if (rec == null) {
                            out.println("You must be logged in to use autoflee.");
                            break;
                        }
                        Integer charId = this.characterId;
                        if (charId == null && name != null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        if (charId == null) {
                            out.println("Unable to find your character.");
                            break;
                        }
                        
                        String autofleeArgs = cmd.getArgs();
                        if (autofleeArgs == null || autofleeArgs.trim().isEmpty()) {
                            // Display current autoflee value
                            int currentAutoflee = dao.getAutoflee(charId);
                            if (currentAutoflee <= 0) {
                                out.println("Autoflee is disabled (set to 0).");
                            } else {
                                out.println("Autoflee is set to " + currentAutoflee + "% HP.");
                            }
                        } else {
                            // Set new autoflee value
                            try {
                                int newValue = Integer.parseInt(autofleeArgs.trim());
                                if (newValue < 0 || newValue > 100) {
                                    out.println("Autoflee must be between 0 and 100.");
                                    break;
                                }
                                boolean success = dao.setAutoflee(charId, newValue);
                                if (success) {
                                    if (newValue == 0) {
                                        out.println("Autoflee disabled.");
                                    } else {
                                        out.println("Autoflee set to " + newValue + "% HP.");
                                        out.println("You will automatically attempt to flee when HP drops below " + newValue + "%.");
                                    }
                                } else {
                                    out.println("Failed to set autoflee.");
                                }
                            } catch (NumberFormatException e) {
                                out.println("Usage: autoflee <0-100>");
                            }
                        }
                        break;
                    }
                    case "autoloot": {
                        // AUTOLOOT - Toggle automatic looting of items from corpses
                        if (rec == null) {
                            out.println("You must be logged in to use autoloot.");
                            break;
                        }
                        Integer charId = this.characterId;
                        if (charId == null && name != null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        if (charId == null) {
                            out.println("Unable to find your character.");
                            break;
                        }
                        
                        // Toggle current value
                        boolean currentValue = rec.autoloot;
                        boolean newValue = !currentValue;
                        boolean success = dao.setAutoloot(charId, newValue);
                        if (success) {
                            if (newValue) {
                                out.println("Autoloot enabled. You will automatically loot items from corpses.");
                            } else {
                                out.println("Autoloot disabled. You must manually loot items from corpses.");
                            }
                            // Refresh rec
                            rec = dao.findByName(name);
                        } else {
                            out.println("Failed to toggle autoloot.");
                        }
                        break;
                    }
                    case "autogold": {
                        // AUTOGOLD - Toggle automatic looting of gold from corpses
                        if (rec == null) {
                            out.println("You must be logged in to use autogold.");
                            break;
                        }
                        Integer charId = this.characterId;
                        if (charId == null && name != null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        if (charId == null) {
                            out.println("Unable to find your character.");
                            break;
                        }
                        
                        // Toggle current value
                        boolean currentValue = rec.autogold;
                        boolean newValue = !currentValue;
                        boolean success = dao.setAutogold(charId, newValue);
                        if (success) {
                            if (newValue) {
                                out.println("Autogold enabled. You will automatically loot gold from corpses.");
                            } else {
                                out.println("Autogold disabled. You must manually loot gold from corpses.");
                            }
                            // Refresh rec
                            rec = dao.findByName(name);
                        } else {
                            out.println("Failed to toggle autogold.");
                        }
                        break;
                    }
                    case "autosac": {
                        // AUTOSAC - Toggle automatic sacrifice of empty corpses
                        // Requires both autoloot and autogold to be enabled
                        if (rec == null) {
                            out.println("You must be logged in to use autosac.");
                            break;
                        }
                        Integer charId = this.characterId;
                        if (charId == null && name != null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        if (charId == null) {
                            out.println("Unable to find your character.");
                            break;
                        }
                        
                        // Toggle current value
                        boolean currentValue = rec.autosac;
                        boolean newValue = !currentValue;
                        
                        // If trying to enable, check that autoloot and autogold are both enabled
                        if (newValue && (!rec.autoloot || !rec.autogold)) {
                            out.println("You must enable both autoloot and autogold before you can enable autosac.");
                            break;
                        }
                        
                        boolean success = dao.setAutosac(charId, newValue);
                        if (success) {
                            if (newValue) {
                                out.println("Autosac enabled. You will automatically sacrifice empty corpses for 1 XP.");
                            } else {
                                out.println("Autosac disabled. Corpses will remain after looting.");
                            }
                            // Refresh rec
                            rec = dao.findByName(name);
                        } else {
                            out.println("Failed to toggle autosac.");
                        }
                        break;
                    }
                    case "cflag":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "cset":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "cskill":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "cspell":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "ilist":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "ifind":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "istat":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "mstat": {
                        // GM-only: MSTAT <mobile> - Show detailed stats of a mobile in the room
                        if (!ensureGm(name, rec, dao)) break;
                        String mstatArgs = cmd.getArgs();
                        if (mstatArgs == null || mstatArgs.trim().isEmpty()) {
                            out.println("Usage: MSTAT <mobile>");
                            out.println("  Shows detailed stat block for a mobile in your current room.");
                            break;
                        }
                        String mstatSearch = mstatArgs.trim().toLowerCase();
                        
                        // Get all mobiles in the room
                        MobileDAO mstatMobDao = new MobileDAO();
                        java.util.List<Mobile> mobsInRoom = mstatMobDao.getMobilesInRoom(rec.currentRoom);
                        
                        // Find matching mobile by name or keyword
                        Mobile matchedMob = null;
                        for (Mobile mob : mobsInRoom) {
                            if (mob.isDead()) continue;
                            
                            // Check name
                            if (mob.getName() != null && mob.getName().toLowerCase().contains(mstatSearch)) {
                                matchedMob = mob;
                                break;
                            }
                            // Check short desc
                            if (mob.getShortDesc() != null && mob.getShortDesc().toLowerCase().contains(mstatSearch)) {
                                matchedMob = mob;
                                break;
                            }
                        }
                        
                        // Also try template keywords
                        if (matchedMob == null) {
                            for (Mobile mob : mobsInRoom) {
                                if (mob.isDead()) continue;
                                MobileTemplate mobTmpl = mstatMobDao.getTemplateById(mob.getTemplateId());
                                if (mobTmpl != null && mobTmpl.matchesKeyword(mstatSearch)) {
                                    matchedMob = mob;
                                    break;
                                }
                            }
                        }
                        
                        if (matchedMob == null) {
                            out.println("No mobile '" + mstatSearch + "' found in this room.");
                            break;
                        }
                        
                        MobileTemplate mobTmpl = mstatMobDao.getTemplateById(matchedMob.getTemplateId());
                        
                        out.println();
                        out.println("=== MOBILE STAT BLOCK ===");
                        out.println();
                        out.println("  Name:          " + matchedMob.getName());
                        out.println("  Short Desc:    " + (matchedMob.getShortDesc() != null ? matchedMob.getShortDesc() : "-"));
                        out.println("  Instance ID:   " + matchedMob.getInstanceId());
                        out.println("  Template ID:   " + matchedMob.getTemplateId());
                        out.println("  Level:         " + matchedMob.getLevel());
                        out.println("  Room:          " + matchedMob.getCurrentRoom());
                        out.println("  Spawn Room:    " + (matchedMob.getSpawnRoomId() != null ? matchedMob.getSpawnRoomId() : "-"));
                        out.println();
                        
                        // Template info
                        if (mobTmpl != null) {
                            out.println("--- TEMPLATE INFO ---");
                            out.println("  Key:           " + (mobTmpl.getKey() != null ? mobTmpl.getKey() : "-"));
                            out.println("  Long Desc:     " + (mobTmpl.getLongDesc() != null ? mobTmpl.getLongDesc() : "-"));
                            out.println("  Keywords:      " + (mobTmpl.getKeywords() != null && !mobTmpl.getKeywords().isEmpty() ? String.join(", ", mobTmpl.getKeywords()) : "-"));
                            out.println();
                        }
                        
                        // Vitals
                        out.println("--- VITALS ---");
                        out.println("  HP:            " + matchedMob.getHpCur() + " / " + matchedMob.getHpMax());
                        out.println("  MP:            " + matchedMob.getMpCur() + " / " + matchedMob.getMpMax());
                        out.println("  MV:            " + matchedMob.getMvCur() + " / " + matchedMob.getMvMax());
                        out.println();
                        
                        // Ability scores
                        out.println("--- ABILITIES ---");
                        out.println("  STR: " + String.format("%2d", matchedMob.getStr()) + 
                                   " (" + (matchedMob.getStr() >= 10 ? "+" : "") + ((matchedMob.getStr() - 10) / 2) + ")" +
                                   "   DEX: " + String.format("%2d", matchedMob.getDex()) + 
                                   " (" + (matchedMob.getDex() >= 10 ? "+" : "") + ((matchedMob.getDex() - 10) / 2) + ")" +
                                   "   CON: " + String.format("%2d", matchedMob.getCon()) + 
                                   " (" + (matchedMob.getCon() >= 10 ? "+" : "") + ((matchedMob.getCon() - 10) / 2) + ")");
                        out.println("  INT: " + String.format("%2d", matchedMob.getIntel()) + 
                                   " (" + (matchedMob.getIntel() >= 10 ? "+" : "") + ((matchedMob.getIntel() - 10) / 2) + ")" +
                                   "   WIS: " + String.format("%2d", matchedMob.getWis()) + 
                                   " (" + (matchedMob.getWis() >= 10 ? "+" : "") + ((matchedMob.getWis() - 10) / 2) + ")" +
                                   "   CHA: " + String.format("%2d", matchedMob.getCha()) + 
                                   " (" + (matchedMob.getCha() >= 10 ? "+" : "") + ((matchedMob.getCha() - 10) / 2) + ")");
                        out.println();
                        
                        // Defenses
                        out.println("--- DEFENSES ---");
                        out.println("  Armor:         " + matchedMob.getArmor());
                        break;
                    }
                    case "combat":
                        rec = handleCombatCommand(cmd, name, rec, dao);
                        break;
                    
                    case "flee":
                        rec = handleCombatCommand(cmd, name, rec, dao);
                        break;
                    case "kick":
                        rec = handleCombatCommand(cmd, name, rec, dao);
                        break;
                    case "bash":
                        rec = handleCombatCommand(cmd, name, rec, dao);
                        break;
                    case "heroic":
                        rec = handleCombatCommand(cmd, name, rec, dao);
                        break;
                    case "infuse":
                        rec = handleCombatCommand(cmd, name, rec, dao);
                        break;
                    case "hide": {
                        // HIDE - Skill-based invisibility (requires hide skill, has cooldown)
                        if (rec == null) {
                            out.println("You must be logged in to hide.");
                            break;
                        }
                        Integer charId = this.characterId;
                        if (charId == null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        
                        // Look up the hide skill (id=305)
                        Skill hideSkill = dao.getSkillById(305);
                        if (hideSkill == null) {
                            out.println("Hide skill not found in database.");
                            break;
                        }
                        
                        // Check if character knows the hide skill
                        CharacterSkill charHide = dao.getCharacterSkill(charId, 305);
                        if (charHide == null) {
                            out.println("You don't know how to hide.");
                            break;
                        }
                        
                        // Check cooldown using unified check
                        com.example.tassmud.util.AbilityCheck.CheckResult hideCheck = 
                            com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, hideSkill);
                        if (hideCheck.isFailure()) {
                            out.println(hideCheck.getFailureMessage());
                            break;
                        }
                        
                        // Check if already invisible
                        if (com.example.tassmud.effect.EffectRegistry.isInvisible(charId)) {
                            out.println("You are already invisible.");
                            break;
                        }
                        
                        // Perform proficiency roll to determine success
                        // Success chance = proficiency%
                        int roll = (int)(Math.random() * 100) + 1;
                        int proficiency = charHide.getProficiency();
                        boolean hideSucceeded = roll <= proficiency;
                        
                        if (hideSucceeded) {
                            // Apply invisibility effect (id 110)
                            com.example.tassmud.effect.EffectInstance inst = 
                                com.example.tassmud.effect.EffectRegistry.apply("110", charId, charId, null);
                            
                            if (inst != null) {
                                out.println("You fade from view, becoming invisible.");
                                // Announce to room only if others can see invisible
                                roomAnnounceFromActor(currentRoomId, name + " fades from view.", this.characterId);
                            } else {
                                out.println("You try to hide but fail.");
                            }
                        } else {
                            out.println("You attempt to hide but fail to conceal yourself.");
                        }
                        
                        // Record skill use (applies cooldown and checks proficiency growth)
                        com.example.tassmud.util.SkillExecution.Result hideResult = 
                            com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                                name, charId, hideSkill, charHide, dao, hideSucceeded);
                        
                        // Debug channel output for proficiency check
                        sendDebug("Hide proficiency check:");
                        sendDebug("  Skill progression: " + hideSkill.getProgression());
                        sendDebug("  Current proficiency: " + proficiency + "%");
                        sendDebug("  Roll: " + roll + " (needed <= " + proficiency + ")");
                        sendDebug("  Skill succeeded: " + hideSucceeded);
                        sendDebug("  Proficiency improved: " + hideResult.didProficiencyImprove());
                        
                        if (hideResult.didProficiencyImprove()) {
                            out.println(hideResult.getProficiencyMessage());
                        }
                        break;
                    }
                    case "visible":
                    case "unhide": {
                        // VISIBLE/UNHIDE - Drop invisibility
                        if (rec == null) {
                            out.println("You must be logged in to become visible.");
                            break;
                        }
                        Integer charId = this.characterId;
                        if (charId == null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        
                        // Check if currently invisible
                        if (!com.example.tassmud.effect.EffectRegistry.isInvisible(charId)) {
                            out.println("You are already visible.");
                            break;
                        }
                        
                        // Remove invisibility effect
                        com.example.tassmud.effect.EffectRegistry.removeInvisibility(charId);
                        
                        out.println("You become visible again.");
                        // Announce to room
                        roomAnnounce(currentRoomId, name + " fades into view.", this.characterId, true);
                        break;
                    }
                    case "quit":
                        out.println("Goodbye!");
                        socket.close();
                        return;
                    case "train": {
                        // TRAIN [ability|skill|spell] <name> - Spend talent points
                        if (rec == null) {
                            out.println("You must be logged in to train.");
                            break;
                        }
                        Integer trainCharId = dao.getCharacterIdByName(name);
                        if (trainCharId == null) {
                            out.println("Failed to find your character.");
                            break;
                        }
                        
                        String trainArgs = cmd.getArgs();
                        int talentPoints = dao.getTalentPoints(trainCharId);
                        
                        // No args: show status
                        if (trainArgs == null || trainArgs.trim().isEmpty()) {
                            // Display talent points, ability scores, and trainable skills/spells
                            StringBuilder trainInfo = new StringBuilder();
                            trainInfo.append("\n===================================================================\n");
                            trainInfo.append("  TRAINING STATUS\n");
                            trainInfo.append("===================================================================\n");
                            trainInfo.append(String.format("  Talent Points Available: %d\n", talentPoints));
                            trainInfo.append("-------------------------------------------------------------------\n");
                            trainInfo.append("  [ ABILITY SCORES ] (base + trained = total)\n");
                            
                            // Calculate modifiers for display
                            int strTotal = rec.getStrTotal();
                            int dexTotal = rec.getDexTotal();
                            int conTotal = rec.getConTotal();
                            int intTotal = rec.getIntTotal();
                            int wisTotal = rec.getWisTotal();
                            int chaTotal = rec.getChaTotal();
                            
                            trainInfo.append(String.format("  STR: %2d + %d = %2d (%+d)    Cost: %s\n",
                                rec.str, rec.trainedStr, strTotal, (strTotal - 10) / 2, formatTrainCost(strTotal)));
                            trainInfo.append(String.format("  DEX: %2d + %d = %2d (%+d)    Cost: %s\n",
                                rec.dex, rec.trainedDex, dexTotal, (dexTotal - 10) / 2, formatTrainCost(dexTotal)));
                            trainInfo.append(String.format("  CON: %2d + %d = %2d (%+d)    Cost: %s\n",
                                rec.con, rec.trainedCon, conTotal, (conTotal - 10) / 2, formatTrainCost(conTotal)));
                            trainInfo.append(String.format("  INT: %2d + %d = %2d (%+d)    Cost: %s\n",
                                rec.intel, rec.trainedInt, intTotal, (intTotal - 10) / 2, formatTrainCost(intTotal)));
                            trainInfo.append(String.format("  WIS: %2d + %d = %2d (%+d)    Cost: %s\n",
                                rec.wis, rec.trainedWis, wisTotal, (wisTotal - 10) / 2, formatTrainCost(wisTotal)));
                            trainInfo.append(String.format("  CHA: %2d + %d = %2d (%+d)    Cost: %s\n",
                                rec.cha, rec.trainedCha, chaTotal, (chaTotal - 10) / 2, formatTrainCost(chaTotal)));
                            
                            // Show trainable skills (under 80%)
                            java.util.List<CharacterSkill> trainSkills = dao.getAllCharacterSkills(trainCharId);
                            java.util.List<String> trainableSkills = new java.util.ArrayList<>();
                            for (CharacterSkill cs : trainSkills) {
                                if (cs.getProficiency() < 80) {
                                    Skill skillDef = dao.getSkillById(cs.getSkillId());
                                    String skillName = skillDef != null ? skillDef.getName() : "Skill #" + cs.getSkillId();
                                    trainableSkills.add(String.format("%-22s %3d%%", skillName, cs.getProficiency()));
                                }
                            }
                            if (!trainableSkills.isEmpty()) {
                                trainInfo.append("-------------------------------------------------------------------\n");
                                trainInfo.append("  [ TRAINABLE SKILLS ] (< 80%: costs 1 point for +5%)\n");
                                java.util.Collections.sort(trainableSkills, String.CASE_INSENSITIVE_ORDER);
                                for (String s : trainableSkills) {
                                    trainInfo.append("  ").append(s).append("\n");
                                }
                            }
                            
                            // Show trainable spells (under 80%)
                            java.util.List<CharacterSpell> trainSpells = dao.getAllCharacterSpells(trainCharId);
                            java.util.List<String> trainableSpells = new java.util.ArrayList<>();
                            for (CharacterSpell cs : trainSpells) {
                                if (cs.getProficiency() < 80) {
                                    Spell spellDef = dao.getSpellById(cs.getSpellId());
                                    String spellName = spellDef != null ? spellDef.getName() : "Spell #" + cs.getSpellId();
                                    trainableSpells.add(String.format("%-22s %3d%%", spellName, cs.getProficiency()));
                                }
                            }
                            if (!trainableSpells.isEmpty()) {
                                trainInfo.append("-------------------------------------------------------------------\n");
                                trainInfo.append("  [ TRAINABLE SPELLS ] (< 80%: costs 1 point for +5%)\n");
                                java.util.Collections.sort(trainableSpells, String.CASE_INSENSITIVE_ORDER);
                                for (String s : trainableSpells) {
                                    trainInfo.append("  ").append(s).append("\n");
                                }
                            }
                            
                            trainInfo.append("===================================================================\n");
                            trainInfo.append("  Usage: TRAIN ABILITY <str|dex|con|int|wis|cha>\n");
                            trainInfo.append("         TRAIN SKILL <skill_name>\n");
                            trainInfo.append("         TRAIN SPELL <spell_name>\n");
                            trainInfo.append("===================================================================\n");
                            out.print(trainInfo.toString());
                            break;
                        }
                        
                        // Parse args: train ability|skill|spell <name>
                        String[] trainParts = trainArgs.trim().split("\\s+", 2);
                        if (trainParts.length < 2) {
                            out.println("Usage: TRAIN ABILITY <str|dex|con|int|wis|cha>");
                            out.println("       TRAIN SKILL <skill_name>");
                            out.println("       TRAIN SPELL <spell_name>");
                            break;
                        }
                        
                        String trainType = trainParts[0].toLowerCase();
                        String trainTarget = trainParts[1].trim();
                        
                        if (talentPoints < 1) {
                            out.println("You have no talent points to spend.");
                            break;
                        }
                        
                        switch (trainType) {
                            case "ability":
                            case "stat":
                            case "attr":
                            case "attribute": {
                                // Train an ability score
                                String abilityName = trainTarget.toLowerCase();
                                int currentTotal = 0;
                                String displayName = null;
                                switch (abilityName) {
                                    case "str": case "strength": 
                                        currentTotal = rec.getStrTotal(); displayName = "Strength"; break;
                                    case "dex": case "dexterity": 
                                        currentTotal = rec.getDexTotal(); displayName = "Dexterity"; break;
                                    case "con": case "constitution": 
                                        currentTotal = rec.getConTotal(); displayName = "Constitution"; break;
                                    case "int": case "intel": case "intelligence": 
                                        currentTotal = rec.getIntTotal(); displayName = "Intelligence"; break;
                                    case "wis": case "wisdom": 
                                        currentTotal = rec.getWisTotal(); displayName = "Wisdom"; break;
                                    case "cha": case "charisma": 
                                        currentTotal = rec.getChaTotal(); displayName = "Charisma"; break;
                                }
                                
                                if (displayName == null) {
                                    out.println("Unknown ability: " + trainTarget);
                                    out.println("Valid abilities: STR, DEX, CON, INT, WIS, CHA");
                                    break;
                                }
                                
                                int cost = CharacterDAO.getAbilityTrainingCost(currentTotal);
                                if (cost < 0) {
                                    out.println("Your " + displayName + " is already at maximum (20). You cannot train it further with talent points.");
                                    break;
                                }
                                if (talentPoints < cost) {
                                    out.println("Training " + displayName + " from " + currentTotal + " to " + (currentTotal + 1) + " costs " + cost + " talent points.");
                                    out.println("You only have " + talentPoints + " talent point" + (talentPoints == 1 ? "" : "s") + ".");
                                    break;
                                }
                                
                                // Deduct points and increment ability
                                dao.setTalentPoints(trainCharId, talentPoints - cost);
                                dao.incrementTrainedAbility(trainCharId, abilityName);
                                
                                int newTotal = currentTotal + 1;
                                int newMod = (newTotal - 10) / 2;
                                out.println("You train your " + displayName + "!");
                                out.println(displayName + " increased from " + currentTotal + " to " + newTotal + " (" + (newMod >= 0 ? "+" : "") + newMod + " modifier).");
                                out.println("Spent " + cost + " talent point" + (cost == 1 ? "" : "s") + ". Remaining: " + (talentPoints - cost));
                                break;
                            }
                            case "skill": {
                                // Train a skill
                                Skill targetSkill = null;
                                CharacterSkill charSkill = null;
                                java.util.List<CharacterSkill> allSkills = dao.getAllCharacterSkills(trainCharId);
                                for (CharacterSkill cs : allSkills) {
                                    Skill def = dao.getSkillById(cs.getSkillId());
                                    if (def != null && def.getName().equalsIgnoreCase(trainTarget)) {
                                        targetSkill = def;
                                        charSkill = cs;
                                        break;
                                    }
                                }
                                
                                // Try partial match if exact match failed
                                if (targetSkill == null) {
                                    String targetLower = trainTarget.toLowerCase();
                                    for (CharacterSkill cs : allSkills) {
                                        Skill def = dao.getSkillById(cs.getSkillId());
                                        if (def != null && def.getName().toLowerCase().startsWith(targetLower)) {
                                            targetSkill = def;
                                            charSkill = cs;
                                            break;
                                        }
                                    }
                                }
                                
                                if (targetSkill == null || charSkill == null) {
                                    out.println("You don't know a skill called '" + trainTarget + "'.");
                                    break;
                                }
                                
                                int currentProf = charSkill.getProficiency();
                                if (currentProf >= 80) {
                                    out.println(targetSkill.getName() + " is already at " + currentProf + "%. Skills cannot be trained above 80% - you must practice them in use.");
                                    break;
                                }
                                
                                // Training costs 1 point for +5% proficiency
                                int newProf = Math.min(80, currentProf + 5);
                                dao.setCharacterSkillLevel(trainCharId, charSkill.getSkillId(), newProf);
                                dao.setTalentPoints(trainCharId, talentPoints - 1);
                                
                                out.println("You train " + targetSkill.getName() + "!");
                                out.println("Proficiency increased from " + currentProf + "% to " + newProf + "%.");
                                out.println("Spent 1 talent point. Remaining: " + (talentPoints - 1));
                                break;
                            }
                            case "spell": {
                                // Train a spell
                                Spell targetSpell = null;
                                CharacterSpell charSpell = null;
                                java.util.List<CharacterSpell> allSpells = dao.getAllCharacterSpells(trainCharId);
                                for (CharacterSpell cs : allSpells) {
                                    Spell def = dao.getSpellById(cs.getSpellId());
                                    if (def != null && def.getName().equalsIgnoreCase(trainTarget)) {
                                        targetSpell = def;
                                        charSpell = cs;
                                        break;
                                    }
                                }
                                
                                // Try partial match if exact match failed
                                if (targetSpell == null) {
                                    String targetLower = trainTarget.toLowerCase();
                                    for (CharacterSpell cs : allSpells) {
                                        Spell def = dao.getSpellById(cs.getSpellId());
                                        if (def != null && def.getName().toLowerCase().startsWith(targetLower)) {
                                            targetSpell = def;
                                            charSpell = cs;
                                            break;
                                        }
                                    }
                                }
                                
                                if (targetSpell == null || charSpell == null) {
                                    out.println("You don't know a spell called '" + trainTarget + "'.");
                                    break;
                                }
                                
                                int currentProf = charSpell.getProficiency();
                                if (currentProf >= 80) {
                                    out.println(targetSpell.getName() + " is already at " + currentProf + "%. Spells cannot be trained above 80% - you must practice them in use.");
                                    break;
                                }
                                
                                // Training costs 1 point for +5% proficiency
                                int newProf = Math.min(80, currentProf + 5);
                                dao.setCharacterSpellLevel(trainCharId, charSpell.getSpellId(), newProf);
                                dao.setTalentPoints(trainCharId, talentPoints - 1);
                                
                                out.println("You train " + targetSpell.getName() + "!");
                                out.println("Proficiency increased from " + currentProf + "% to " + newProf + "%.");
                                out.println("Spent 1 talent point. Remaining: " + (talentPoints - 1));
                                break;
                            }
                            default:
                                out.println("Unknown training type: " + trainType);
                                out.println("Usage: TRAIN ABILITY <str|dex|con|int|wis|cha>");
                                out.println("       TRAIN SKILL <skill_name>");
                                out.println("       TRAIN SPELL <spell_name>");
                        }
                        break;
                    }
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
                        
                        // Check if I'm a GM (for seeing GM-invisible players)
                        boolean iAmGm = dao.isCharacterFlagTrueByName(name, "is_gm");
                        
                        int count = 0;
                        for (ClientHandler session : sessions) {
                            String pName = session.playerName;
                            if (pName == null) continue; // Not yet logged in
                            
                            // Look up character info
                            CharacterRecord pRec = dao.findByName(pName);
                            if (pRec == null) continue;
                            
                            Integer pCharId = dao.getCharacterIdByName(pName);
                            
                            // Check GM invisibility - only other GMs can see GM-invisible players
                            if (session.gmInvisible && session != this && !iAmGm) {
                                continue; // Can't see GM-invisible players in who list
                            }
                            
                            // Check normal invisibility - skip if we can't see them (unless it's ourselves)
                            boolean isInvis = com.example.tassmud.effect.EffectRegistry.isInvisible(pCharId);
                            boolean canSee = session == this || com.example.tassmud.effect.EffectRegistry.canSee(this.characterId, pCharId);
                            if (isInvis && !canSee) {
                                continue; // Can't see them in who list
                            }
                            
                            Integer pClassId = pRec.currentClassId;
                            CharacterClass pClass = pClassId != null ? classDao.getClassById(pClassId) : null;
                            int pLevel = (pClassId != null && pCharId != null) 
                                ? classDao.getCharacterClassLevel(pCharId, pClassId) : 0;
                            
                            String className = pClass != null ? pClass.name : "Adventurer";
                            String desc = pRec.description != null && !pRec.description.isEmpty() 
                                ? pRec.description : "";
                            
                            // Add invisibility tag if invisible but we can see them
                            String invisTag = "";
                            if (session.gmInvisible && session != this) {
                                invisTag = " (GM-INVIS)";
                            } else if (isInvis && canSee && session != this) {
                                invisTag = " (INVIS)";
                            }
                            
                            // Format: [Lv ##] ClassName     PlayerName - Description
                            out.println(String.format("  [Lv %2d] %-12s  %-15s%s %s",
                                pLevel, className, pName, invisTag,
                                desc.isEmpty() ? "" : "- " + truncate(desc, 30)));
                            count++;
                        }
                        
                        out.println("-------------------------------------------------------------------");
                        out.println(String.format("  %d player%s online", count, count == 1 ? "" : "s"));
                        out.println("===================================================================");
                        out.println();
                        break;
                    }
                    case "chat":
                        handleCommunication(cmd, name, rec, dao);
                        break;
                    case "yell":
                        handleCommunication(cmd, name, rec, dao);
                        break;
                    case "whisper":
                        handleCommunication(cmd, name, rec, dao);
                        break;
                    case "gmchat":
                        handleCommunication(cmd, name, rec, dao);
                        break;
                    case "debug": {
                        // GM-only: toggle debug channel output
                        if (!ensureGm(name, rec, dao)) break;
                        debugChannelEnabled = !debugChannelEnabled;
                        if (debugChannelEnabled) {
                            out.println("Debug channel is now ON. You will see [DEBUG] messages.");
                        } else {
                            out.println("Debug channel is now OFF.");
                        }
                        break;
                    }
                    case "gminvis": {
                        // GM-only: toggle perfect invisibility
                        if (!ensureGm(name, rec, dao)) break;
                        gmInvisible = !gmInvisible;
                        if (gmInvisible) {
                            out.println("You fade into the shadows, becoming invisible to mortals.");
                            out.println("GM Invisibility is now ON. Only other GMs can see you.");
                            out.println("You will not appear in the who list, room descriptions, or be visible to mobs.");
                            // Notify other GMs
                            for (ClientHandler s : sessions) {
                                if (s != this && s.playerName != null && dao.isCharacterFlagTrueByName(s.playerName, "is_gm")) {
                                    s.sendRaw("[GM] " + this.playerName + " has gone GM-invisible.");
                                }
                            }
                        } else {
                            out.println("You step out of the shadows, becoming visible once more.");
                            out.println("GM Invisibility is now OFF.");
                            // Notify other GMs
                            for (ClientHandler s : sessions) {
                                if (s != this && s.playerName != null && dao.isCharacterFlagTrueByName(s.playerName, "is_gm")) {
                                    s.sendRaw("[GM] " + this.playerName + " is no longer GM-invisible.");
                                }
                            }
                        }
                        break;
                    }
                    case "dbinfo":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
                    case "genmap":
                        rec = handleGmCommand(cmd, name, rec, dao);
                        break;
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
                        // GM-only: SPAWN ITEM <template_id> [level]   or   SPAWN MOB <mob_id> [room_id]   or   SPAWN GOLD <amount>
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use spawn.");
                            break;
                        }
                        String spawnArgs = cmd.getArgs();
                        if (spawnArgs == null || spawnArgs.trim().isEmpty()) {
                            out.println("Usage: SPAWN ITEM <template_id> [level]  - Spawn item with random stats scaled to level");
                            out.println("       SPAWN MOB <template_id> [room_id]");
                            out.println("       SPAWN GOLD <amount>");
                            break;
                        }
                        String[] sp = spawnArgs.trim().split("\\s+");
                        if (sp.length < 2) {
                            out.println("Usage: SPAWN ITEM <template_id> [level]  - Spawn item with random stats scaled to level");
                            out.println("       SPAWN MOB <template_id> [room_id]");
                            out.println("       SPAWN GOLD <amount>");
                            break;
                        }
                        String spawnType = sp[0].toUpperCase();
                        if (spawnType.equals("ITEM")) {
                            // SPAWN ITEM <template_id> [level] - Spawn item with loot-generated stats
                            int templateId;
                            try {
                                templateId = Integer.parseInt(sp[1]);
                            } catch (NumberFormatException e) {
                                out.println("Invalid template ID: " + sp[1]);
                                break;
                            }
                            
                            Integer targetRoomId = rec != null ? rec.currentRoom : null;
                            if (targetRoomId == null) {
                                out.println("You must be in a room to spawn items.");
                                break;
                            }
                            
                            // Validate template exists
                            ItemDAO itemDao = new ItemDAO();
                            ItemTemplate tmpl = itemDao.getTemplateById(templateId);
                            if (tmpl == null) {
                                out.println("No item template found with ID " + templateId);
                                break;
                            }
                            
                            // Determine level: use optional arg, otherwise use character's class level
                            CharacterClassDAO spawnClassDao = new CharacterClassDAO();
                            int spawnLevel = (rec != null && rec.currentClassId != null) 
                                ? spawnClassDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
                            if (sp.length >= 3) {
                                try {
                                    spawnLevel = Integer.parseInt(sp[2]);
                                    if (spawnLevel < 1) spawnLevel = 1;
                                    if (spawnLevel > 55) spawnLevel = 55;
                                } catch (NumberFormatException e) {
                                    out.println("Invalid level: " + sp[2] + ". Using your level (" + spawnLevel + ").");
                                }
                            }
                            
                            // Generate item with level-scaled stats using LootGenerator
                            long instanceId = LootGenerator.generateItemFromTemplateInRoom(templateId, spawnLevel, targetRoomId, itemDao);
                            if (instanceId < 0) {
                                out.println("Failed to create item instance.");
                                break;
                            }
                            
                            String itemName = tmpl.name != null ? tmpl.name : "item #" + templateId;
                            boolean isWeapon = tmpl.hasType("weapon");
                            boolean isArmor = tmpl.hasType("armor") || tmpl.hasType("shield");
                            String typeNote = isWeapon ? "weapon" : (isArmor ? "armor" : "item");
                            out.println("Spawned " + itemName + " (instance #" + instanceId + ", level " + spawnLevel + " " + typeNote + ") in current room.");
                            if (isWeapon || isArmor) {
                                out.println("Stats scaled to level " + spawnLevel + ". Use ISTAT to inspect.");
                            }
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
                        } else if (spawnType.equals("GOLD")) {
                            // GM gold spawn - gives gold directly to the GM
                            long amount;
                            try {
                                amount = Long.parseLong(sp[1]);
                            } catch (NumberFormatException e) {
                                out.println("Invalid gold amount: " + sp[1]);
                                break;
                            }
                            if (amount <= 0) {
                                out.println("Amount must be a positive number.");
                                break;
                            }
                            // Cap at Long.MAX_VALUE to prevent overflow
                            long currentGold = dao.getGold(characterId);
                            long maxAddable = Long.MAX_VALUE - currentGold;
                            if (amount > maxAddable) {
                                amount = maxAddable;
                                out.println("Amount capped to prevent overflow.");
                            }
                            if (amount == 0) {
                                out.println("You already have maximum gold.");
                                break;
                            }
                            boolean success = dao.addGold(characterId, amount);
                            if (success) {
                                long newTotal = dao.getGold(characterId);
                                out.println("Spawned " + amount + " gold. You now have " + newTotal + " gp.");
                            } else {
                                out.println("Failed to add gold.");
                            }
                        } else {
                            out.println("Unknown spawn type: " + spawnType);
                            out.println("Usage: SPAWN ITEM <template_id> [room_id]");
                            out.println("       SPAWN MOB <template_id> [room_id]");
                            out.println("       SPAWN GOLD <amount>");
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
                    case "slay": {
                        // GM-only: SLAY <target> - instantly kill a mob
                        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
                            out.println("You do not have permission to use slay.");
                            break;
                        }
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You must be in a room to use slay.");
                            break;
                        }
                        
                        Integer charId = this.characterId;
                        if (charId == null) {
                            charId = dao.getCharacterIdByName(name);
                        }
                        
                        CombatManager combatMgr = CombatManager.getInstance();
                        Combat combat = combatMgr.getCombatForCharacter(charId);
                        
                        Mobile targetMob = null;
                        String slayArgs = cmd.getArgs();
                        
                        if (combat != null && combatMgr.isInCombat(charId)) {
                            // In combat - if no args, target current enemy
                            if (slayArgs == null || slayArgs.trim().isEmpty()) {
                                // Find first enemy combatant
                                Combatant self = combat.findByCharacterId(charId);
                                if (self != null) {
                                    for (Combatant c : combat.getActiveCombatants()) {
                                        if (c.getAlliance() != self.getAlliance() && c.isMobile()) {
                                            targetMob = c.getMobile();
                                            break;
                                        }
                                    }
                                }
                                if (targetMob == null) {
                                    out.println("You have no enemy to slay.");
                                    break;
                                }
                            } else {
                                // Search for target by name in combat
                                String targetSearch = slayArgs.trim().toLowerCase();
                                for (Combatant c : combat.getActiveCombatants()) {
                                    if (c.isMobile() && c.getMobile() != null) {
                                        Mobile mob = c.getMobile();
                                        if (mob.getName().toLowerCase().contains(targetSearch)) {
                                            targetMob = mob;
                                            break;
                                        }
                                    }
                                }
                                if (targetMob == null) {
                                    out.println("No enemy matching '" + slayArgs + "' in combat.");
                                    break;
                                }
                            }
                            
                            // Kill the mob instantly
                            String mobName = targetMob.getName();
                            out.println("You raise your hand and " + mobName + " is struck down by divine power!");
                            broadcastRoomMessage(rec.currentRoom, name + " raises their hand and " + mobName + " is struck down by divine power!");
                            
                            // Find combatant and set HP to 0 - combat system will handle death
                            Combatant victimCombatant = null;
                            for (Combatant c : combat.getActiveCombatants()) {
                                if (c.getMobile() == targetMob) {
                                    victimCombatant = c;
                                    break;
                                }
                            }
                            if (victimCombatant != null) {
                                Character victimChar = victimCombatant.getAsCharacter();
                                if (victimChar != null) {
                                    victimChar.setHpCur(0);
                                }
                            }
                        } else {
                            // Not in combat - find target in room and kill immediately
                            if (slayArgs == null || slayArgs.trim().isEmpty()) {
                                out.println("Usage: slay <target>");
                                out.println("  Instantly kill a mob in the room.");
                                break;
                            }
                            
                            MobileDAO mobileDao = new MobileDAO();
                            List<Mobile> mobilesInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
                            if (mobilesInRoom.isEmpty()) {
                                out.println("There is nothing here to slay.");
                                break;
                            }
                            
                            String targetSearch = slayArgs.trim().toLowerCase();
                            for (Mobile mob : mobilesInRoom) {
                                if (mob.getName().toLowerCase().contains(targetSearch)) {
                                    targetMob = mob;
                                    break;
                                }
                            }
                            
                            if (targetMob == null) {
                                out.println("You don't see '" + slayArgs + "' here.");
                                break;
                            }
                            
                            if (targetMob.isDead()) {
                                out.println(targetMob.getName() + " is already dead.");
                                break;
                            }
                            
                            String mobName = targetMob.getName();
                            out.println("You raise your hand and " + mobName + " is struck down by divine power!");
                            broadcastRoomMessage(rec.currentRoom, name + " raises their hand and " + mobName + " is struck down by divine power!");
                            
                            // Create corpse
                            try {
                                ItemDAO itemDAO = new ItemDAO();
                                itemDAO.createCorpse(rec.currentRoom, mobName);
                            } catch (Exception e) {
                                System.err.println("[slay] Failed to create corpse: " + e.getMessage());
                            }
                            
                            // Kill and remove the mob
                            targetMob.die();
                            mobileDao.deleteInstance(targetMob.getInstanceId());
                        }
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
                            long containerGold = itemDao.getGoldContents(matchedContainer.instance.instanceId);
                            
                            // Handle "get gold <container>" specifically
                            if (itemSearchPart.equalsIgnoreCase("gold")) {
                                if (containerGold <= 0) {
                                    out.println(getItemDisplayName(matchedContainer) + " contains no gold.");
                                } else {
                                    long goldTaken = itemDao.takeGoldContents(matchedContainer.instance.instanceId);
                                    dao.addGold(charId, goldTaken);
                                    out.println("You get " + goldTaken + " gold from " + getItemDisplayName(matchedContainer) + ".");
                                }
                                break;
                            }
                            
                            if (itemSearchPart.equalsIgnoreCase("all")) {
                                // Get all from container (including gold)
                                if (containerContents.isEmpty() && containerGold <= 0) {
                                    out.println(getItemDisplayName(matchedContainer) + " is empty.");
                                    break;
                                }
                                int count = 0;
                                // Take gold first
                                if (containerGold > 0) {
                                    long goldTaken = itemDao.takeGoldContents(matchedContainer.instance.instanceId);
                                    dao.addGold(charId, goldTaken);
                                    out.println("You get " + goldTaken + " gold from " + getItemDisplayName(matchedContainer) + ".");
                                }
                                for (ItemDAO.RoomItem ci : containerContents) {
                                    itemDao.moveInstanceToCharacter(ci.instance.instanceId, charId);
                                    out.println("You get " + getItemDisplayName(ci) + " from " + getItemDisplayName(matchedContainer) + ".");
                                    count++;
                                }
                                if (count > 1) out.println("Got " + count + " items from " + getItemDisplayName(matchedContainer) + ".");
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
                                    out.println("You don't see '" + itemSearchPart + "' in " + getItemDisplayName(matchedContainer) + ".");
                                    break;
                                }
                                
                                itemDao.moveInstanceToCharacter(matchedItem.instance.instanceId, charId);
                                out.println("You get " + getItemDisplayName(matchedItem) + " from " + getItemDisplayName(matchedContainer) + ".");
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
                                out.println("You pick up " + getItemDisplayName(ri) + ".");
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
                    case "sacrifice":
                    case "sac": {
                        // SACRIFICE <item> - sacrifice an item on the ground for 1 XP
                        // Corpses must be empty to be sacrificed
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You are nowhere.");
                            break;
                        }
                        String sacArgs = cmd.getArgs();
                        if (sacArgs == null || sacArgs.trim().isEmpty()) {
                            out.println("Usage: sacrifice <item>");
                            out.println("Sacrifice an item on the ground to gain 1 experience point.");
                            out.println("Corpses must be empty (looted) before they can be sacrificed.");
                            break;
                        }
                        
                        String sacArg = sacArgs.trim().toLowerCase();
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        // Get items in the room
                        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
                        if (roomItems.isEmpty()) {
                            out.println("There is nothing here to sacrifice.");
                            break;
                        }
                        
                        // Find matching item
                        ItemDAO.RoomItem matched = null;
                        
                        // Priority 1: Exact name match
                        for (ItemDAO.RoomItem ri : roomItems) {
                            String itemName = ri.instance.customName != null ? ri.instance.customName : ri.template.name;
                            if (itemName != null && itemName.toLowerCase().equals(sacArg)) {
                                matched = ri;
                                break;
                            }
                        }
                        
                        // Priority 2: Name starts with search term
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                String itemName = ri.instance.customName != null ? ri.instance.customName : ri.template.name;
                                if (itemName != null && itemName.toLowerCase().startsWith(sacArg)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }
                        
                        // Priority 3: Name contains search term
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                String itemName = ri.instance.customName != null ? ri.instance.customName : ri.template.name;
                                if (itemName != null && itemName.toLowerCase().contains(sacArg)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }
                        
                        // Priority 4: Keyword match
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : roomItems) {
                                if (ri.template.keywords != null) {
                                    for (String kw : ri.template.keywords) {
                                        if (kw.toLowerCase().startsWith(sacArg)) {
                                            matched = ri;
                                            break;
                                        }
                                    }
                                    if (matched != null) break;
                                }
                            }
                        }
                        
                        if (matched == null) {
                            out.println("You don't see '" + sacArgs.trim() + "' here to sacrifice.");
                            break;
                        }
                        
                        String itemDisplayName = matched.instance.customName != null ? matched.instance.customName : 
                                                 (matched.template.name != null ? matched.template.name : "an item");
                        
                        // Check if it's a corpse - if so, it must be empty
                        if (matched.template.isContainer()) {
                            // Check for items inside
                            java.util.List<ItemDAO.RoomItem> contents = itemDao.getItemsInContainer(matched.instance.instanceId);
                            if (!contents.isEmpty()) {
                                out.println(itemDisplayName + " is not empty. You must loot it first before sacrificing.");
                                break;
                            }
                            // Check for gold inside
                            long gold = itemDao.getGoldContents(matched.instance.instanceId);
                            if (gold > 0) {
                                out.println(itemDisplayName + " still contains " + gold + " gold. Loot it first.");
                                break;
                            }
                        }
                        
                        // Delete the item
                        boolean deleted = itemDao.deleteInstance(matched.instance.instanceId);
                        if (!deleted) {
                            out.println("Failed to sacrifice " + itemDisplayName + ".");
                            break;
                        }
                        
                        // Grant 1 XP using CharacterClassDAO
                        CharacterClassDAO classDao = new CharacterClassDAO();
                        boolean leveledUp = classDao.addXpToCurrentClass(charId, 1);
                        
                        // Announce to room
                        out.println("You sacrifice " + itemDisplayName + " to the gods.");
                        out.println("The gods grant you 1 experience point.");
                        roomAnnounce(rec.currentRoom, name + " sacrifices " + itemDisplayName + " to the gods.");
                        
                        // Handle level-up if it occurred
                        if (leveledUp) {
                            Integer classId = classDao.getCharacterCurrentClassId(charId);
                            if (classId != null) {
                                int newLevel = classDao.getCharacterClassLevel(charId, classId);
                                out.println("You have reached level " + newLevel + "!");
                                final int charIdFinal = charId;
                                classDao.processLevelUp(charId, newLevel, msg -> sendToCharacter(charIdFinal, msg));
                            }
                        }
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
                                        itemName = getItemDisplayName(inst, tmpl);
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

                        // Priority 1: Exact name match (check both customName and template name)
                        for (ItemDAO.RoomItem ri : unequippedItems) {
                            String displayName = getItemDisplayName(ri);
                            if (displayName.equalsIgnoreCase(equipArg)) {
                                matched = ri;
                                break;
                            }
                        }

                        // Priority 2: Word match
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : unequippedItems) {
                                String displayName = getItemDisplayName(ri);
                                String[] nameWords = displayName.toLowerCase().split("\\s+");
                                for (String w : nameWords) {
                                    if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                        matched = ri;
                                        break;
                                    }
                                }
                                if (matched != null) break;
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
                                String displayName = getItemDisplayName(ri);
                                if (displayName.toLowerCase().startsWith(searchLower)) {
                                    matched = ri;
                                    break;
                                }
                            }
                        }

                        // Priority 5: Name contains search
                        if (matched == null) {
                            for (ItemDAO.RoomItem ri : unequippedItems) {
                                String displayName = getItemDisplayName(ri);
                                if (displayName.toLowerCase().contains(searchLower)) {
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
                            out.println(getItemDisplayName(matched) + " cannot be equipped.");
                            break;
                        }

                        // Check armor proficiency requirement
                        if (matched.template.isArmor()) {
                            ArmorCategory armorCat = matched.template.getArmorCategory();
                            if (armorCat != null) {
                                String skillKey = armorCat.getSkillKey();
                                Skill armorSkill = dao.getSkillByKey(skillKey);
                                if (armorSkill == null || !dao.hasSkill(charId, armorSkill.getId())) {
                                    out.println("You lack proficiency in " + armorCat.getDisplayName() + " armor to equip " + getItemDisplayName(matched) + ".");
                                    break;
                                }
                            }
                        }

                        // Check shield proficiency requirement
                        if (matched.template.isShield()) {
                            Skill shieldSkill = dao.getSkillByKey("shields");
                            if (shieldSkill == null || !dao.hasSkill(charId, shieldSkill.getId())) {
                                out.println("You lack proficiency with shields to equip " + getItemDisplayName(matched) + ".");
                                break;
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
                                    removedItems.add(getItemDisplayName(inst, tmpl));
                                }
                                dao.setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
                            }
                            // Clear off hand if occupied
                            Long offHandItem = equippedMap.get(EquipmentSlot.OFF_HAND.id);
                            if (offHandItem != null) {
                                ItemInstance inst = itemDao.getInstance(offHandItem);
                                if (inst != null) {
                                    ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                    removedItems.add(getItemDisplayName(inst, tmpl));
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
                                        removedItems.add(getItemDisplayName(mainInst, mainTmpl));
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
                                    removedItems.add(getItemDisplayName(curInst, curTmpl));
                                }
                                dao.setCharacterEquipment(charId, slot.id, null);
                            }
                        }

                        // Equip the new item to main hand
                        boolean equipped = dao.setCharacterEquipment(charId, slot.id, matched.instance.instanceId);
                        if (!equipped) {
                            out.println("Failed to equip " + getItemDisplayName(matched) + ".");
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
                            out.println("You remove " + removedStr + " and equip " + getItemDisplayName(matched) + " (" + slotDisplay + ").");
                        } else {
                            out.println("You equip " + getItemDisplayName(matched) + " (" + slotDisplay + ").");
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
                        ItemInstance matchedInstance = null;
                        ItemTemplate matchedTemplate = null;

                        // Build list of equipped items with their instances and templates
                        java.util.List<Object[]> equippedItems = new java.util.ArrayList<>();
                        for (java.util.Map.Entry<Integer, Long> entry : equippedMap.entrySet()) {
                            if (entry.getValue() == null) continue;
                            ItemInstance inst = itemDao.getInstance(entry.getValue());
                            if (inst == null) continue;
                            ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                            if (tmpl == null) continue;
                            equippedItems.add(new Object[] { entry.getKey(), entry.getValue(), inst, tmpl });
                        }

                        // Priority 1: Exact display name match
                        for (Object[] arr : equippedItems) {
                            ItemInstance inst = (ItemInstance) arr[2];
                            ItemTemplate tmpl = (ItemTemplate) arr[3];
                            String displayName = getItemDisplayName(inst, tmpl);
                            if (displayName.equalsIgnoreCase(removeArg)) {
                                matchedSlotId = (Integer) arr[0];
                                matchedInstanceId = (Long) arr[1];
                                matchedInstance = inst;
                                matchedTemplate = tmpl;
                                break;
                            }
                        }

                        // Priority 2: Word match on display name
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemInstance inst = (ItemInstance) arr[2];
                                ItemTemplate tmpl = (ItemTemplate) arr[3];
                                String displayName = getItemDisplayName(inst, tmpl);
                                String[] words = displayName.toLowerCase().split("\\s+");
                                for (String w : words) {
                                    if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                        matchedSlotId = (Integer) arr[0];
                                        matchedInstanceId = (Long) arr[1];
                                        matchedInstance = inst;
                                        matchedTemplate = tmpl;
                                        break;
                                    }
                                }
                                if (matchedSlotId != null) break;
                            }
                        }

                        // Priority 3: Keyword match
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemInstance inst = (ItemInstance) arr[2];
                                ItemTemplate tmpl = (ItemTemplate) arr[3];
                                if (tmpl.keywords != null) {
                                    for (String kw : tmpl.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matchedSlotId = (Integer) arr[0];
                                            matchedInstanceId = (Long) arr[1];
                                            matchedInstance = inst;
                                            matchedTemplate = tmpl;
                                            break;
                                        }
                                    }
                                    if (matchedSlotId != null) break;
                                }
                            }
                        }

                        // Priority 4: Display name starts with
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemInstance inst = (ItemInstance) arr[2];
                                ItemTemplate tmpl = (ItemTemplate) arr[3];
                                String displayName = getItemDisplayName(inst, tmpl);
                                if (displayName.toLowerCase().startsWith(searchLower)) {
                                    matchedSlotId = (Integer) arr[0];
                                    matchedInstanceId = (Long) arr[1];
                                    matchedInstance = inst;
                                    matchedTemplate = tmpl;
                                    break;
                                }
                            }
                        }

                        // Priority 5: Display name contains
                        if (matchedSlotId == null) {
                            for (Object[] arr : equippedItems) {
                                ItemInstance inst = (ItemInstance) arr[2];
                                ItemTemplate tmpl = (ItemTemplate) arr[3];
                                String displayName = getItemDisplayName(inst, tmpl);
                                if (displayName.toLowerCase().contains(searchLower)) {
                                    matchedSlotId = (Integer) arr[0];
                                    matchedInstanceId = (Long) arr[1];
                                    matchedInstance = inst;
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

                        out.println("You remove " + getItemDisplayName(matchedInstance, matchedTemplate) + " (" + slotName + ").");
                        if (matchedTemplate.armorSaveBonus != 0 || matchedTemplate.fortSaveBonus != 0 || 
                            matchedTemplate.refSaveBonus != 0 || matchedTemplate.willSaveBonus != 0) {
                            out.println("  Saves: Armor " + rec.getArmorTotal() + ", Fort " + rec.getFortitudeTotal() + ", Ref " + rec.getReflexTotal() + ", Will " + rec.getWillTotal());
                        }
                        break;
                    }
                    // Note: quaff/drink commands are handled by ItemCommandHandler via CommandDispatcher
                    case "list": {
                        // LIST - show items for sale from shopkeepers in the room
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You must be in a room to see what's for sale.");
                            break;
                        }
                        
                        // Find all shopkeeper mobs in the room
                        MobileDAO mobileDao = new MobileDAO();
                        ShopDAO shopDao = new ShopDAO();
                        ItemDAO itemDao = new ItemDAO();
                        
                        java.util.List<Mobile> mobsInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
                        java.util.List<Integer> shopkeeperTemplateIds = new java.util.ArrayList<>();
                        
                        for (Mobile mob : mobsInRoom) {
                            if (mob.hasBehavior(MobileBehavior.SHOPKEEPER)) {
                                shopkeeperTemplateIds.add(mob.getTemplateId());
                            }
                        }
                        
                        if (shopkeeperTemplateIds.isEmpty()) {
                            out.println("There are no shopkeepers here.");
                            break;
                        }
                        
                        // Get all shops for these shopkeepers
                        java.util.List<Shop> shops = shopDao.getShopsForMobTemplateIds(shopkeeperTemplateIds);
                        if (shops.isEmpty()) {
                            out.println("The shopkeeper has nothing for sale.");
                            break;
                        }
                        
                        // Get all item IDs available for sale
                        java.util.Set<Integer> itemIds = shopDao.getAllItemIds(shops);
                        if (itemIds.isEmpty()) {
                            out.println("The shopkeeper has nothing for sale.");
                            break;
                        }
                        
                        // Build list of items with prices
                        java.util.List<ItemTemplate> itemsForSale = new java.util.ArrayList<>();
                        for (Integer itemId : itemIds) {
                            ItemTemplate tmpl = itemDao.getTemplateById(itemId);
                            if (tmpl != null) {
                                itemsForSale.add(tmpl);
                            }
                        }
                        
                        if (itemsForSale.isEmpty()) {
                            out.println("The shopkeeper has nothing for sale.");
                            break;
                        }
                        
                        // Sort by price ascending
                        itemsForSale.sort((item1, item2) -> Integer.compare(item1.value, item2.value));
                        
                        out.println("Items for sale:");
                        for (ItemTemplate item : itemsForSale) {
                            String itemName = item.name != null ? item.name : "(unnamed)";
                            out.println(String.format("  %-40s %,d gp", itemName, item.value));
                        }
                        break;
                    }
                    case "buy": {
                        // BUY <item> [quantity] - purchase an item from a shopkeeper
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You must be in a room to buy items.");
                            break;
                        }
                        String buyArgs = cmd.getArgs();
                        if (buyArgs == null || buyArgs.trim().isEmpty()) {
                            out.println("Usage: buy <item> [quantity]");
                            break;
                        }
                        
                        // Parse args: item name and optional quantity
                        String buyArg = buyArgs.trim();
                        int quantity = 1;
                        String itemSearchStr;
                        
                        // Check if last word is a number (quantity)
                        String[] parts = buyArg.split("\\s+");
                        if (parts.length > 1) {
                            String lastPart = parts[parts.length - 1];
                            try {
                                quantity = Integer.parseInt(lastPart);
                                if (quantity < 1) quantity = 1;
                                if (quantity > 100) {
                                    out.println("You can only buy up to 100 items at once.");
                                    break;
                                }
                                // Reconstruct item name without quantity
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < parts.length - 1; i++) {
                                    if (i > 0) sb.append(" ");
                                    sb.append(parts[i]);
                                }
                                itemSearchStr = sb.toString();
                            } catch (NumberFormatException e) {
                                itemSearchStr = buyArg;
                            }
                        } else {
                            itemSearchStr = buyArg;
                        }
                        
                        // Find shopkeepers
                        MobileDAO mobileDao = new MobileDAO();
                        ShopDAO shopDao = new ShopDAO();
                        ItemDAO itemDao = new ItemDAO();
                        
                        java.util.List<Mobile> mobsInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
                        java.util.List<Integer> shopkeeperTemplateIds = new java.util.ArrayList<>();
                        
                        for (Mobile mob : mobsInRoom) {
                            if (mob.hasBehavior(MobileBehavior.SHOPKEEPER)) {
                                shopkeeperTemplateIds.add(mob.getTemplateId());
                            }
                        }
                        
                        if (shopkeeperTemplateIds.isEmpty()) {
                            out.println("There are no shopkeepers here.");
                            break;
                        }
                        
                        java.util.List<Shop> shops = shopDao.getShopsForMobTemplateIds(shopkeeperTemplateIds);
                        java.util.Set<Integer> availableItemIds = shopDao.getAllItemIds(shops);
                        
                        if (availableItemIds.isEmpty()) {
                            out.println("There is nothing for sale here.");
                            break;
                        }
                        
                        // Build list of available items for matching
                        java.util.List<ItemTemplate> availableItems = new java.util.ArrayList<>();
                        for (Integer itemId : availableItemIds) {
                            ItemTemplate tmpl = itemDao.getTemplateById(itemId);
                            if (tmpl != null) {
                                availableItems.add(tmpl);
                            }
                        }
                        
                        // Smart match the item by name/keywords
                        String searchLower = itemSearchStr.toLowerCase();
                        ItemTemplate matchedItem = null;
                        
                        // Priority 1: Exact name match
                        for (ItemTemplate tmpl : availableItems) {
                            if (tmpl.name != null && tmpl.name.equalsIgnoreCase(itemSearchStr)) {
                                matchedItem = tmpl;
                                break;
                            }
                        }
                        
                        // Priority 2: Name word match
                        if (matchedItem == null) {
                            for (ItemTemplate tmpl : availableItems) {
                                if (tmpl.name != null) {
                                    String[] nameWords = tmpl.name.toLowerCase().split("\\s+");
                                    for (String w : nameWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            matchedItem = tmpl;
                                            break;
                                        }
                                    }
                                    if (matchedItem != null) break;
                                }
                            }
                        }
                        
                        // Priority 3: Keyword match
                        if (matchedItem == null) {
                            for (ItemTemplate tmpl : availableItems) {
                                if (tmpl.keywords != null) {
                                    for (String kw : tmpl.keywords) {
                                        if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                            matchedItem = tmpl;
                                            break;
                                        }
                                    }
                                    if (matchedItem != null) break;
                                }
                            }
                        }
                        
                        // Priority 4: Name starts with
                        if (matchedItem == null) {
                            for (ItemTemplate tmpl : availableItems) {
                                if (tmpl.name != null && tmpl.name.toLowerCase().startsWith(searchLower)) {
                                    matchedItem = tmpl;
                                    break;
                                }
                            }
                        }
                        
                        // Priority 5: Name contains
                        if (matchedItem == null) {
                            for (ItemTemplate tmpl : availableItems) {
                                if (tmpl.name != null && tmpl.name.toLowerCase().contains(searchLower)) {
                                    matchedItem = tmpl;
                                    break;
                                }
                            }
                        }
                        
                        if (matchedItem == null) {
                            out.println("'" + itemSearchStr + "' is not for sale here.");
                            break;
                        }
                        
                        // Calculate total cost
                        long totalCost = (long) matchedItem.value * quantity;
                        
                        // Check if player has enough gold
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        long playerGold = dao.getGold(charId);
                        if (playerGold < totalCost) {
                            out.println("You need " + String.format("%,d", totalCost) + " gp to buy " + 
                                (quantity > 1 ? quantity + " " + matchedItem.name : matchedItem.name) + 
                                ", but you only have " + String.format("%,d", playerGold) + " gp.");
                            break;
                        }
                        
                        // Subtract gold and add items
                        dao.addGold(charId, -totalCost);
                        for (int i = 0; i < quantity; i++) {
                            itemDao.createInstance(matchedItem.id, null, charId);
                        }
                        
                        String itemName = matchedItem.name != null ? matchedItem.name : "an item";
                        if (quantity == 1) {
                            out.println("You buy " + itemName + " for " + String.format("%,d", totalCost) + " gp.");
                        } else {
                            out.println("You buy " + quantity + " " + itemName + " for " + String.format("%,d", totalCost) + " gp.");
                        }
                        break;
                    }
                    case "sell": {
                        // SELL <item> [quantity] - sell an item to a shopkeeper (half value)
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You must be in a room to sell items.");
                            break;
                        }
                        String sellArgs = cmd.getArgs();
                        if (sellArgs == null || sellArgs.trim().isEmpty()) {
                            out.println("Usage: sell <item> [quantity]");
                            break;
                        }
                        
                        // Parse args: item name and optional quantity
                        String sellArg = sellArgs.trim();
                        int quantity = 1;
                        String itemSearchStr;
                        
                        // Check if last word is a number (quantity)
                        String[] parts = sellArg.split("\\s+");
                        if (parts.length > 1) {
                            String lastPart = parts[parts.length - 1];
                            try {
                                quantity = Integer.parseInt(lastPart);
                                if (quantity < 1) quantity = 1;
                                if (quantity > 100) {
                                    out.println("You can only sell up to 100 items at once.");
                                    break;
                                }
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < parts.length - 1; i++) {
                                    if (i > 0) sb.append(" ");
                                    sb.append(parts[i]);
                                }
                                itemSearchStr = sb.toString();
                            } catch (NumberFormatException e) {
                                itemSearchStr = sellArg;
                            }
                        } else {
                            itemSearchStr = sellArg;
                        }
                        
                        // Find shopkeepers (must be one present to sell)
                        MobileDAO mobileDao = new MobileDAO();
                        java.util.List<Mobile> mobsInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
                        boolean hasShopkeeper = false;
                        
                        for (Mobile mob : mobsInRoom) {
                            if (mob.hasBehavior(MobileBehavior.SHOPKEEPER)) {
                                hasShopkeeper = true;
                                break;
                            }
                        }
                        
                        if (!hasShopkeeper) {
                            out.println("There are no shopkeepers here.");
                            break;
                        }
                        
                        // Get player's inventory
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        // Get equipped items to exclude
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
                        for (Long iid : equippedMap.values()) {
                            if (iid != null) equippedInstanceIds.add(iid);
                        }
                        
                        // Get all items in inventory (not equipped, not in containers)
                        java.util.List<ItemDAO.RoomItem> allItems = itemDao.getItemsByCharacter(charId);
                        java.util.List<ItemDAO.RoomItem> inventoryItems = new java.util.ArrayList<>();
                        for (ItemDAO.RoomItem ri : allItems) {
                            if (equippedInstanceIds.contains(ri.instance.instanceId)) continue;
                            if (ri.instance.containerInstanceId != null) continue;
                            inventoryItems.add(ri);
                        }
                        
                        if (inventoryItems.isEmpty()) {
                            out.println("You have nothing to sell.");
                            break;
                        }
                        
                        // Smart match the item by name/keywords
                        String searchLower = itemSearchStr.toLowerCase();
                        java.util.List<ItemDAO.RoomItem> matchingItems = new java.util.ArrayList<>();
                        
                        // Find all items matching the search term
                        for (ItemDAO.RoomItem ri : inventoryItems) {
                            boolean match = false;
                            
                            // Check customName first (for generated/renamed items)
                            if (ri.instance.customName != null && !ri.instance.customName.isEmpty()) {
                                String customLower = ri.instance.customName.toLowerCase();
                                if (customLower.equalsIgnoreCase(itemSearchStr) ||
                                    customLower.startsWith(searchLower) ||
                                    customLower.contains(searchLower)) {
                                    match = true;
                                } else {
                                    // Check customName words
                                    String[] customWords = customLower.split("\\s+");
                                    for (String w : customWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            match = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            // Check template name
                            if (!match && ri.template.name != null) {
                                String nameLower = ri.template.name.toLowerCase();
                                if (nameLower.equalsIgnoreCase(itemSearchStr) ||
                                    nameLower.startsWith(searchLower) ||
                                    nameLower.contains(searchLower)) {
                                    match = true;
                                } else {
                                    // Check name words
                                    String[] nameWords = nameLower.split("\\s+");
                                    for (String w : nameWords) {
                                        if (w.equals(searchLower) || w.startsWith(searchLower)) {
                                            match = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            // Check keywords
                            if (!match && ri.template.keywords != null) {
                                for (String kw : ri.template.keywords) {
                                    if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                                        match = true;
                                        break;
                                    }
                                }
                            }
                            
                            if (match) {
                                matchingItems.add(ri);
                            }
                        }
                        
                        if (matchingItems.isEmpty()) {
                            out.println("You don't have '" + itemSearchStr + "' to sell.");
                            break;
                        }
                        
                        // Limit to requested quantity
                        int actualQuantity = Math.min(quantity, matchingItems.size());
                        java.util.List<ItemDAO.RoomItem> toSell = matchingItems.subList(0, actualQuantity);
                        
                        // Calculate sell value (half of item value, minimum 1)
                        ItemDAO.RoomItem soldItem = toSell.get(0);
                        int sellPrice = Math.max(1, soldItem.template.value / 2);
                        long totalGold = (long) sellPrice * actualQuantity;
                        
                        // Delete items and add gold
                        for (ItemDAO.RoomItem ri : toSell) {
                            itemDao.deleteInstance(ri.instance.instanceId);
                        }
                        dao.addGold(charId, totalGold);
                        
                        String itemName = getItemDisplayName(soldItem);
                        if (actualQuantity == 1) {
                            out.println("You sell " + itemName + " for " + String.format("%,d", totalGold) + " gp.");
                        } else {
                            out.println("You sell " + actualQuantity + " " + itemName + " for " + String.format("%,d", totalGold) + " gp.");
                        }
                        
                        if (actualQuantity < quantity) {
                            out.println("(You only had " + actualQuantity + " to sell.)");
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
                            // Add name (prefer customName for generated items)
                            itemNames.add(getItemDisplayName(ri));
                        }

                        // Get gold amount
                        long gold = dao.getGold(charId);

                        if (itemNames.isEmpty()) {
                            out.println("You are not carrying anything.");
                        } else {
                            // Sort alphabetically
                            java.util.Collections.sort(itemNames, String.CASE_INSENSITIVE_ORDER);

                            out.println("You are carrying:");
                            for (String n : itemNames) {
                                out.println("  " + n);
                            }
                        }

                        // Always show gold
                        out.println("");
                        out.println("Gold: " + gold + " gp");
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
                        // classXp is progress within current level (0 to XP_PER_LEVEL-1), so xpToNext is simply the remainder
                        int xpToNext = classLevel < CharacterClass.MAX_HERO_LEVEL ? CharacterClass.XP_PER_LEVEL - classXp : 0;
                        
                        // Get all class progress for multiclass display
                        java.util.List<CharacterClassDAO.ClassProgress> allProgress = classDao.getCharacterClassProgress(charId);
                        
                        // Get equipment for AC and weapon damage
                        ItemDAO itemDao = new ItemDAO();
                        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
                        
                        // Calculate ability modifiers using totals (base + trained)
                        int strTotal = rec.getStrTotal();
                        int dexTotal = rec.getDexTotal();
                        int conTotal = rec.getConTotal();
                        int intTotal = rec.getIntTotal();
                        int wisTotal = rec.getWisTotal();
                        int chaTotal = rec.getChaTotal();
                        int strMod = (strTotal - 10) / 2;
                        int dexMod = (dexTotal - 10) / 2;
                        int conMod = (conTotal - 10) / 2;
                        int intMod = (intTotal - 10) / 2;
                        int wisMod = (wisTotal - 10) / 2;
                        int chaMod = (chaTotal - 10) / 2;
                        
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
                        
                        // Stance info
                        Stance playerStance = RegenerationService.getInstance().getPlayerStance(charId);
                        String stanceLabel = playerStance.getDisplayName().substring(0, 1).toUpperCase() + playerStance.getDisplayName().substring(1);
                        int regenPct = playerStance.getRegenPercent();
                        sheet.append(String.format("  Stance: %-10s (%d%% regen out of combat)\n", stanceLabel, regenPct));
                        
                        // XP bar - shows progress within current level (0 to XP_PER_LEVEL)
                        if (currentClass != null && classLevel < CharacterClass.MAX_HERO_LEVEL) {
                            // classXp is already the progress within the current level (resets to 0 on level-up)
                            int xpNeeded = CharacterClass.XP_PER_LEVEL;
                            int pct = (classXp * 100) / xpNeeded;
                            sheet.append(String.format("  XP: %d / %d  [%s%s] %d%%  (%d TNL)\n",
                                classXp, xpNeeded,
                                repeat("#", pct / 5), repeat(".", 20 - pct / 5),
                                pct, xpToNext));
                        } else if (currentClass != null) {
                            sheet.append(String.format("  XP: %d  [MAX LEVEL]\n", classXp));
                        }
                        
                        // ═══ ABILITY SCORES ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ ABILITY SCORES ]");
                        if (rec.talentPoints > 0) {
                            sheet.append("  (").append(rec.talentPoints).append(" Talent Point").append(rec.talentPoints == 1 ? "" : "s").append(")");
                        }
                        sheet.append("\n");
                        sheet.append(String.format("  STR: %2d (%+d)    DEX: %2d (%+d)    CON: %2d (%+d)\n",
                            strTotal, strMod, dexTotal, dexMod, conTotal, conMod));
                        sheet.append(String.format("  INT: %2d (%+d)    WIS: %2d (%+d)    CHA: %2d (%+d)\n",
                            intTotal, intMod, wisTotal, wisMod, chaTotal, chaMod));
                        
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
                                // Use effective stats (instance overrides if present, otherwise template)
                                int effectiveBaseDie = mainInst.getEffectiveBaseDie(mainTmpl);
                                int effectiveMultiplier = mainInst.getEffectiveMultiplier(mainTmpl);
                                double effectiveAbilityMult = mainInst.getEffectiveAbilityMultiplier(mainTmpl);
                                String abilityScore = mainTmpl != null ? mainTmpl.abilityScore : "STR";
                                if (effectiveBaseDie > 0) {
                                    String dmgStr = formatDamage(effectiveMultiplier, effectiveBaseDie, 
                                        getAbilityBonus(abilityScore, effectiveAbilityMult, rec));
                                    String handLabel = isTwoHanded ? "Both Hands:" : "Main Hand: ";
                                    String weaponName = getItemDisplayName(mainInst, mainTmpl);
                                    sheet.append(String.format("  %s %-20s  Damage: %s\n", 
                                        handLabel, truncate(weaponName, 20), dmgStr));
                                    hasWeapon = true;
                                }
                            }
                        }
                        if (offHandId != null && !isTwoHanded) {
                            ItemInstance offInst = itemDao.getInstance(offHandId);
                            if (offInst != null) {
                                ItemTemplate offTmpl = itemDao.getTemplateById(offInst.templateId);
                                // Use effective stats (instance overrides if present, otherwise template)
                                int effectiveBaseDie = offInst.getEffectiveBaseDie(offTmpl);
                                int effectiveMultiplier = offInst.getEffectiveMultiplier(offTmpl);
                                double effectiveAbilityMult = offInst.getEffectiveAbilityMultiplier(offTmpl);
                                String abilityScore = offTmpl != null ? offTmpl.abilityScore : "STR";
                                if (effectiveBaseDie > 0) {
                                    String dmgStr = formatDamage(effectiveMultiplier, effectiveBaseDie,
                                        getAbilityBonus(abilityScore, effectiveAbilityMult, rec));
                                    String weaponName = getItemDisplayName(offInst, offTmpl);
                                    sheet.append(String.format("  Off Hand:  %-20s  Damage: %s\n",
                                        truncate(weaponName, 20), dmgStr));
                                } else {
                                    // Shield or non-weapon (show armor bonus)
                                    String itemName = getItemDisplayName(offInst, offTmpl);
                                    sheet.append(String.format("  Off Hand:  %-20s  (Shield)\n",
                                        truncate(itemName, 20)));
                                }
                                hasWeapon = true;
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
                        
                        // ═══ AUTO SETTINGS ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ AUTO SETTINGS ]\n");
                        int autoflee = rec.autoflee;
                        if (autoflee > 0) {
                            sheet.append(String.format("  Autoflee: %d%% HP\n", autoflee));
                        } else {
                            sheet.append("  Autoflee: disabled\n");
                        }
                        sheet.append(String.format("  Autoloot: %s    Autogold: %s    Autosac: %s\n",
                            rec.autoloot ? "ON" : "OFF", rec.autogold ? "ON" : "OFF", rec.autosac ? "ON" : "OFF"));
                        
                        // ═══ ACTIVE EFFECTS ═══
                        sheet.append("\n  ").append(thinDiv.substring(0, 40)).append("\n");
                        sheet.append("  [ ACTIVE EFFECTS ]\n");
                        // Attempt to get live modifiers (combat) else load from DB
                        java.util.List<com.example.tassmud.model.Modifier> activeMods = new java.util.ArrayList<>();
                        com.example.tassmud.combat.Combatant combatant = com.example.tassmud.combat.CombatManager.getInstance().getCombatantForCharacter(charId);
                        if (combatant != null && combatant.getAsCharacter() != null) {
                            activeMods = combatant.getAsCharacter().getAllModifiers();
                        } else {
                            activeMods = dao.getModifiersForCharacter(charId);
                        }
                        // Filter expired
                        long nowMs = System.currentTimeMillis();
                        activeMods.removeIf(com.example.tassmud.model.Modifier::isExpired);

                        if (activeMods.isEmpty()) {
                            sheet.append("  (No active spell effects)\n");
                        } else {
                            for (com.example.tassmud.model.Modifier m : activeMods) {
                                long remaining = Math.max(0, m.expiresAtMillis() - nowMs) / 1000L;
                                String timeStr = formatDuration(remaining);
                                String sign = m.op() == com.example.tassmud.model.Modifier.Op.ADD && m.value() > 0 ? "+" : "";
                                sheet.append(String.format("  - %s : %s%1.0f %s  (%s)\n",
                                    m.source(), sign, m.value(), m.stat().name(), timeStr));
                            }
                        }
                        
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
                    case "consider":
                    case "con": {
                        // CONSIDER <target> - evaluate how dangerous a target would be
                        if (rec == null) {
                            out.println("You must be logged in to consider targets.");
                            break;
                        }
                        String conArgs = cmd.getArgs();
                        if (conArgs == null || conArgs.trim().isEmpty()) {
                            out.println("Consider whom?");
                            break;
                        }
                        
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }
                        
                        // Get player's level
                        CharacterClassDAO classDao = new CharacterClassDAO();
                        Integer currentClassId = rec.currentClassId;
                        int playerLevel = currentClassId != null ? classDao.getCharacterClassLevel(charId, currentClassId) : 1;
                        
                        // Search for target mob in the room
                        String targetName = conArgs.trim().toLowerCase();
                        MobileDAO mobileDao = new MobileDAO();
                        java.util.List<Mobile> mobsInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
                        
                        Mobile targetMob = null;
                        for (Mobile mob : mobsInRoom) {
                            String mobNameLower = mob.getName().toLowerCase();
                            if (mobNameLower.equals(targetName) || 
                                mobNameLower.startsWith(targetName) || 
                                mobNameLower.contains(targetName)) {
                                targetMob = mob;
                                break;
                            }
                        }
                        
                        if (targetMob == null) {
                            out.println("You don't see '" + conArgs.trim() + "' here.");
                            break;
                        }
                        
                        // Calculate level difference: player - target
                        int targetLevel = targetMob.getLevel();
                        int levelDiff = playerLevel - targetLevel;
                        
                        // Clamp to -5 to +5 range
                        levelDiff = Math.max(-5, Math.min(5, levelDiff));
                        
                        String opponentName = targetMob.getName();
                        String message;
                        switch (levelDiff) {
                            case 5:
                                message = "Don't bother, " + opponentName + " is not worth your time.";
                                break;
                            case 4:
                                message = "You would wipe the floor with " + opponentName + ".";
                                break;
                            case 3:
                                message = "It should be an easy fight.";
                                break;
                            case 2:
                                message = opponentName + " wouldn't pose much trouble.";
                                break;
                            case 1:
                                message = "You're stronger than " + opponentName + ", but not by much.";
                                break;
                            case 0:
                                message = "You and " + opponentName + " are well matched.";
                                break;
                            case -1:
                                message = "You're at a disadvantage, but you can probably swing it.";
                                break;
                            case -2:
                                message = opponentName + " would be a tough fight.";
                                break;
                            case -3:
                                message = "You should pass on this one if you know what's good for you.";
                                break;
                            case -4:
                                message = "Not a chance in hell, which is where you'd end up.";
                                break;
                            case -5:
                            default:
                                message = "Death appreciates your sacrifice.";
                                break;
                        }
                        out.println(message);
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
                        
                        // Check cooldown and combat traits before allowing cast
                        com.example.tassmud.util.AbilityCheck.CheckResult spellCheck = 
                            com.example.tassmud.util.AbilityCheck.canPlayerCastSpell(name, charId, matchedSpell);
                        if (spellCheck.isFailure()) {
                            out.println(spellCheck.getFailureMessage());
                            break;
                        }
                        
                        // Calculate MP cost: 2^spellLevel
                        int spellLevel = matchedSpell.getLevel();
                        int mpCost = (int) Math.pow(2, spellLevel);
                        
                        // Check if player has enough MP
                        if (rec.mpCur < mpCost) {
                            out.println("You don't have enough mana to cast " + matchedSpell.getName() + ". (Need " + mpCost + " MP, have " + rec.mpCur + ")");
                            break;
                        }
                        
                        // Deduct MP cost
                        if (!dao.deductManaPoints(name, mpCost)) {
                            out.println("Failed to expend mana for the spell.");
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
                        castMsg.append("You cast ").append(matchedSpell.getName()).append(" (-").append(mpCost).append(" MP)");
                        
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

                        // Dispatch spell effects via the EffectRegistry
                        java.util.List<Integer> targets = new java.util.ArrayList<>();
                        Spell.SpellTarget ttype = matchedSpell.getTarget();
                        if (ttype == Spell.SpellTarget.ALL_ALLIES) {
                            for (ClientHandler s : sessions) {
                                if (s == this) continue;
                                Integer otherCharId = s.characterId;
                                if (otherCharId == null) continue;
                                Integer otherRoom = s.currentRoomId;
                                if (otherRoom != null && otherRoom.equals(this.currentRoomId)) {
                                    targets.add(otherCharId);
                                }
                            }
                            targets.add(charId);
                        } else if (ttype == Spell.SpellTarget.SELF) {
                            targets.add(charId);
                        } else {
                            if (targetArg != null && !targetArg.trim().isEmpty()) {
                                Integer targetId = dao.getCharacterIdByName(targetArg);
                                if (targetId != null) targets.add(targetId);
                            }
                        }

                        if (targets.isEmpty()) {
                            out.println("No valid targets found for that spell.");
                        } else {
                            java.util.List<String> effectedNames = new java.util.ArrayList<>();
                            // Determine caster proficiency for this spell and send as extraParams
                            com.example.tassmud.model.CharacterSpell charSpell = dao.getCharacterSpell(charId, matchedSpell.getId());
                            java.util.Map<String,String> extraParams = new java.util.HashMap<>();
                            int proficiency = 1;
                            if (charSpell != null) {
                                proficiency = charSpell.getProficiency();
                                extraParams.put("proficiency", String.valueOf(proficiency));
                            }

                            // Compute scaled cooldown based on spell-level and effect-level cooldowns (use the largest)
                            int finalCooldown = (int) Math.round(matchedSpell.getCooldown());
                            for (String effId : matchedSpell.getEffectIds()) {
                                com.example.tassmud.effect.EffectDefinition def = com.example.tassmud.effect.EffectRegistry.getDefinition(effId);
                                if (def == null) continue;
                                double effectCd = def.getCooldownSeconds();
                                if (effectCd <= 0) continue;
                                int cdSecs;
                                if (def.getProficiencyImpact().contains(com.example.tassmud.effect.EffectDefinition.ProficiencyImpact.COOLDOWN)) {
                                    cdSecs = com.example.tassmud.util.AbilityCheck.computeScaledCooldown(effectCd, proficiency);
                                } else {
                                    cdSecs = (int) Math.round(effectCd);
                                }
                                if (cdSecs > finalCooldown) finalCooldown = cdSecs;
                            }

                            // Apply the computed cooldown for this spell
                            com.example.tassmud.util.AbilityCheck.applyPlayerSpellCooldown(name, matchedSpell, finalCooldown);

                            for (String effId : matchedSpell.getEffectIds()) {
                                com.example.tassmud.effect.EffectDefinition def = com.example.tassmud.effect.EffectRegistry.getDefinition(effId);
                                for (Integer tgt : targets) {
                                    com.example.tassmud.effect.EffectInstance inst = com.example.tassmud.effect.EffectRegistry.apply(effId, charId, tgt, extraParams);
                                    if (inst != null && def != null) {
                                        // Notify target if online
                                        ClientHandler targetSession = charIdToSession.get(tgt);
                                        if (targetSession != null) {
                                            targetSession.sendRaw(def.getName() + " from " + name + " takes effect.");
                                        }
                                        CharacterDAO.CharacterRecord recT = dao.findById(tgt);
                                        if (recT != null) effectedNames.add(recT.name + "(" + def.getName() + ")");
                                    }
                                }
                            }
                            if (!effectedNames.isEmpty()) out.println("Effects applied: " + String.join(", ", effectedNames));
                        }
                        
                        break;
                    }
                    default:
                        out.println("Unhandled command: " + cmd.getName());
                }
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
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
                        com.example.tassmud.model.Character ch = combatant.getAsCharacter();
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
     * Format the talent point cost to train an ability from its current total.
     */
    private static String formatTrainCost(int currentTotal) {
        int cost = CharacterDAO.getAbilityTrainingCost(currentTotal);
        if (cost < 0) return "MAX";
        return cost + " pt" + (cost == 1 ? "" : "s");
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
     * Format a duration (seconds) into H:MM:SS or M:SS depending on length.
     */
    private static String formatDuration(long seconds) {
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

    /**
     * Centralized handler for combat-related commands.
     */
    public CharacterRecord handleCombatCommand(Command cmd, String name, CharacterRecord rec, CharacterDAO dao) {
        if (cmd == null) return rec;
        String cmdName = cmd.getName().toLowerCase();
        try {
            switch (cmdName) {
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
                    // FLEE - innate combat skill to escape from combat
                    Integer charId = this.characterId;
                    if (charId == null && name != null) {
                        charId = dao.getCharacterIdByName(name);
                    }
                    CombatManager combatMgr = CombatManager.getInstance();
                    Combat activeCombat = combatMgr.getCombatForCharacter(charId);
                    if (activeCombat == null) {
                        out.println("You are not in combat.");
                        break;
                    }

                    Combatant userCombatant = activeCombat.findByCharacterId(charId);
                    if (userCombatant == null) {
                        out.println("Combat error: could not find your combatant.");
                        break;
                    }

                    // Get current room and available exits
                    Room curRoom = dao.getRoomById(currentRoomId);
                    if (curRoom == null) {
                        out.println("You can't flee - you don't know where you are!");
                        break;
                    }

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
                        out.println("There's nowhere to flee to!");
                        break;
                    }

                    // Get user's level for opposed check
                    CharacterClassDAO fleeClassDao = new CharacterClassDAO();
                    int userLevel = rec.currentClassId != null 
                        ? fleeClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;

                    // Find the highest level opponent for opposed check
                    int opponentLevel = 1;
                    for (Combatant c : activeCombat.getCombatants()) {
                        if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                            int cLevel;
                            if (c.isPlayer()) {
                                Integer cCharId = c.getCharacterId();
                                CharacterRecord cRec = dao.getCharacterById(cCharId);
                                cLevel = cRec != null && cRec.currentClassId != null 
                                    ? fleeClassDao.getCharacterClassLevel(cCharId, cRec.currentClassId) : 1;
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
                        out.println("You try to flee but your opponents block your escape!");
                        if (!this.gmInvisible) {
                            roomAnnounce(currentRoomId, name + " tries to flee but fails!", this.characterId, true);
                        }
                        break;
                    }

                    // Flee succeeded - pick a random exit
                    String fleeDirection = availableExits.get((int)(Math.random() * availableExits.size()));
                    Integer destRoomId = exitRooms.get(fleeDirection);

                    // Check movement cost
                    int moveCost = dao.getMoveCostForRoom(destRoomId);

                    if (rec.mvCur < moveCost) {
                        // Insufficient MV - fall prone instead of escaping
                        userCombatant.setProne();
                        out.println("You break free but stumble and fall prone from exhaustion!");
                        if (!this.gmInvisible) {
                            roomAnnounce(currentRoomId, name + " tries to flee but collapses from exhaustion!", this.characterId, true);
                        }
                        break;
                    }

                    // Deduct movement points
                    if (!dao.deductMovementPoints(name, moveCost)) {
                        // Shouldn't happen, but handle it
                        userCombatant.setProne();
                        out.println("You break free but stumble and fall prone from exhaustion!");
                        if (!this.gmInvisible) {
                            roomAnnounce(currentRoomId, name + " tries to flee but collapses from exhaustion!", this.characterId, true);
                        }
                        break;
                    }

                    // Remove from combat
                    activeCombat.removeCombatant(userCombatant);

                    // Announce departure
                    out.println("You flee " + fleeDirection + "!");
                    if (!this.gmInvisible) {
                        roomAnnounce(currentRoomId, name + " flees " + fleeDirection + "!", this.characterId, true);
                    }

                    // Move to new room
                    boolean moved = dao.updateCharacterRoom(name, destRoomId);
                    if (!moved) {
                        out.println("Something strange happened during your escape.");
                        break;
                    }

                    // Update cached room and show new location
                    rec = dao.findByName(name);
                    this.currentRoomId = rec != null ? rec.currentRoom : null;
                    Room newRoom = dao.getRoomById(destRoomId);

                    // Announce arrival
                    if (!this.gmInvisible) {
                        roomAnnounce(destRoomId, makeArrivalMessage(name, fleeDirection), this.characterId, true);
                    }

                    if (newRoom != null) {
                        showRoom(newRoom, destRoomId);
                    }

                    // Combat will check shouldEnd() on next tick and end if no opponents remain
                    break;
                }
                case "kick": {
                    // KICK - combat skill that interrupts opponent's next attack
                    if (rec == null) {
                        out.println("You must be logged in to use kick.");
                        break;
                    }
                    Integer charId = this.characterId;
                    if (charId == null) {
                        charId = dao.getCharacterIdByName(name);
                    }

                    // Look up the kick skill (id=10)
                    Skill kickSkill = dao.getSkillById(10);
                    if (kickSkill == null) {
                        out.println("Kick skill not found in database.");
                        break;
                    }

                    // Check if character knows the kick skill
                    CharacterSkill charKick = dao.getCharacterSkill(charId, 10);
                    if (charKick == null) {
                        out.println("You don't know how to kick.");
                        break;
                    }

                    // Check cooldown and combat traits using unified check
                    com.example.tassmud.util.AbilityCheck.CheckResult kickCheck = 
                        com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, kickSkill);
                    if (kickCheck.isFailure()) {
                        out.println(kickCheck.getFailureMessage());
                        break;
                    }

                    // Get the active combat and target
                    CombatManager combatMgr = CombatManager.getInstance();
                    Combat activeCombat = combatMgr.getCombatForCharacter(charId);
                    if (activeCombat == null) {
                        out.println("You must be in combat to use kick.");
                        break;
                    }

                    // Get the user's combatant and find the opponent
                    Combatant userCombatant = activeCombat.findByCharacterId(charId);
                    if (userCombatant == null) {
                        out.println("Combat error: could not find your combatant.");
                        break;
                    }

                    // Find opponent (first combatant on different alliance)
                    Combatant targetCombatant = null;
                    for (Combatant c : activeCombat.getCombatants()) {
                        if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                            targetCombatant = c;
                            break;
                        }
                    }

                    if (targetCombatant == null) {
                        out.println("You have no opponent to kick.");
                        break;
                    }

                    // Get levels for opposed check
                    CharacterClassDAO kickClassDao = new CharacterClassDAO();
                    int userLevel = rec.currentClassId != null ? kickClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
                    int targetLevel;
                    if (targetCombatant.isPlayer()) {
                        Integer targetCharId = targetCombatant.getCharacterId();
                        CharacterRecord targetRec = dao.getCharacterById(targetCharId);
                        targetLevel = targetRec != null && targetRec.currentClassId != null 
                            ? kickClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
                    } else {
                        // For mobiles, use a level based on their HP (TODO: add proper level to Mobile)
                        targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
                    }

                    // Perform opposed check with proficiency (1d100 vs success chance)
                    int roll = (int)(Math.random() * 100) + 1;
                    int proficiency = charKick.getProficiency();
                    int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

                    String targetName = targetCombatant.getName();
                    boolean kickSucceeded = roll <= successChance;

                    if (kickSucceeded) {
                        // Success! Interrupt the opponent's next attack
                        targetCombatant.addStatusFlag(Combatant.StatusFlag.INTERRUPTED);
                        out.println("Your kick connects! " + targetName + "'s next attack is interrupted.");

                        // Notify the opponent if they're a player
                        if (targetCombatant.isPlayer()) {
                            Integer targetCharId = targetCombatant.getCharacterId();
                            ClientHandler targetHandler = charIdToSession.get(targetCharId);
                            if (targetHandler != null) {
                                targetHandler.out.println(name + " kicks you, interrupting your next attack!");
                            }
                        }
                    } else {
                        // Miss
                        out.println("Your kick misses " + targetName + ".");

                        // Notify the opponent if they're a player
                        if (targetCombatant.isPlayer()) {
                            Integer targetCharId = targetCombatant.getCharacterId();
                            ClientHandler targetHandler = charIdToSession.get(targetCharId);
                            if (targetHandler != null) {
                                targetHandler.out.println(name + " tries to kick you but misses.");
                            }
                        }
                    }

                    // Use unified skill execution to apply cooldown and check proficiency growth
                    com.example.tassmud.util.SkillExecution.Result skillResult = 
                        com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                            name, charId, kickSkill, charKick, dao, kickSucceeded);

                    // Debug channel output for proficiency check (only shown if debug enabled)
                    sendDebug("Kick proficiency check:");
                    sendDebug("  Skill progression: " + kickSkill.getProgression());
                    sendDebug("  Current proficiency: " + charKick.getProficiency() + "%");
                    sendDebug("  Gain chance at this level: " + kickSkill.getProgression().getGainChance(charKick.getProficiency()) + "%");
                    sendDebug("  Skill succeeded: " + kickSucceeded);
                    sendDebug("  Proficiency improved: " + skillResult.didProficiencyImprove());
                    if (skillResult.getProficiencyResult() != null) {
                        sendDebug("  Old prof: " + skillResult.getProficiencyResult().getOldProficiency() + 
                                  " -> New prof: " + skillResult.getProficiencyResult().getNewProficiency());
                    }

                    if (skillResult.didProficiencyImprove()) {
                        out.println(skillResult.getProficiencyMessage());
                    }
                    break;
                }
                case "bash": {
                    // BASH - combat skill that stuns and slows opponent (requires shield)
                    if (rec == null) {
                        out.println("You must be logged in to use bash.");
                        break;
                    }
                    Integer charId = this.characterId;
                    if (charId == null) {
                        charId = dao.getCharacterIdByName(name);
                    }

                    // Look up the bash skill (id=11)
                    Skill bashSkill = dao.getSkillById(11);
                    if (bashSkill == null) {
                        out.println("Bash skill not found in database.");
                        break;
                    }

                    // Check if character knows the bash skill
                    CharacterSkill charBash = dao.getCharacterSkill(charId, 11);
                    if (charBash == null) {
                        out.println("You don't know how to bash.");
                        break;
                    }

                    // Check cooldown, combat traits, and SHIELD requirement using unified check
                    com.example.tassmud.util.AbilityCheck.CheckResult bashCheck = 
                        com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, bashSkill);
                    if (bashCheck.isFailure()) {
                        out.println(bashCheck.getFailureMessage());
                        break;
                    }

                    // Get the active combat and target
                    CombatManager combatMgr = CombatManager.getInstance();
                    Combat activeCombat = combatMgr.getCombatForCharacter(charId);
                    if (activeCombat == null) {
                        out.println("You must be in combat to use bash.");
                        break;
                    }

                    // Get the user's combatant and find the opponent
                    Combatant userCombatant = activeCombat.findByCharacterId(charId);
                    if (userCombatant == null) {
                        out.println("Combat error: could not find your combatant.");
                        break;
                    }

                    // Bash sacrifices the user's next attack
                    userCombatant.addStatusFlag(Combatant.StatusFlag.INTERRUPTED);

                    // Find opponent (first combatant on different alliance)
                    Combatant targetCombatant = null;
                    for (Combatant c : activeCombat.getCombatants()) {
                        if (c.getAlliance() != userCombatant.getAlliance() && c.isActive() && c.isAlive()) {
                            targetCombatant = c;
                            break;
                        }
                    }

                    if (targetCombatant == null) {
                        out.println("You have no opponent to bash.");
                        break;
                    }

                    // Get levels for opposed check
                    CharacterClassDAO bashClassDao = new CharacterClassDAO();
                    int userLevel = rec.currentClassId != null ? bashClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
                    int targetLevel;
                    if (targetCombatant.isPlayer()) {
                        Integer targetCharId = targetCombatant.getCharacterId();
                        CharacterRecord targetRec = dao.getCharacterById(targetCharId);
                        targetLevel = targetRec != null && targetRec.currentClassId != null 
                            ? bashClassDao.getCharacterClassLevel(targetCharId, targetRec.currentClassId) : 1;
                    } else {
                        // For mobiles, use a level based on their HP
                        targetLevel = Math.max(1, targetCombatant.getHpMax() / 10);
                    }

                    // Perform opposed check with proficiency (1d100 vs success chance)
                    int roll = (int)(Math.random() * 100) + 1;
                    int proficiency = charBash.getProficiency();
                    int successChance = com.example.tassmud.util.OpposedCheck.getSuccessPercentWithProficiency(userLevel, targetLevel, proficiency);

                    String targetName = targetCombatant.getName();
                    boolean bashSucceeded = roll <= successChance;

                    if (bashSucceeded) {
                        // Success! Apply STUNNED and SLOWED for 1d6 rounds
                        int stunDuration = (int)(Math.random() * 6) + 1;
                        targetCombatant.addStatusFlag(Combatant.StatusFlag.STUNNED);
                        targetCombatant.addStatusFlag(Combatant.StatusFlag.SLOWED);

                        out.println("You slam your shield into " + targetName + ", stunning them for " + stunDuration + " rounds!");

                        // Notify the opponent if they're a player
                        if (targetCombatant.isPlayer()) {
                            Integer targetCharId = targetCombatant.getCharacterId();
                            ClientHandler targetHandler = charIdToSession.get(targetCharId);
                            if (targetHandler != null) {
                                targetHandler.out.println(name + " bashes you with their shield! You are stunned and slowed!");
                            }
                        }

                        // Debug output
                        sendDebug("Bash success! Applied STUNNED and SLOWED for " + stunDuration + " rounds.");
                        sendDebug("  (Note: Current implementation removes status on first trigger. Multi-round tracking TODO.)");
                    } else {
                        // Miss
                        out.println("Your shield bash misses " + targetName + ".");

                        // Notify the opponent if they're a player
                        if (targetCombatant.isPlayer()) {
                            Integer targetCharId = targetCombatant.getCharacterId();
                            ClientHandler targetHandler = charIdToSession.get(targetCharId);
                            if (targetHandler != null) {
                                targetHandler.out.println(name + " tries to bash you with their shield but misses.");
                            }
                        }
                    }

                    // Use unified skill execution to apply cooldown and check proficiency growth
                    com.example.tassmud.util.SkillExecution.Result bashResult = 
                        com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                            name, charId, bashSkill, charBash, dao, bashSucceeded);

                    if (bashResult.didProficiencyImprove()) {
                        out.println(bashResult.getProficiencyMessage());
                    }
                    break;
                }
                case "heroic": {
                    // HEROIC STRIKE - applies Heroism effect (guaranteed crits) to self
                    if (rec == null) {
                        out.println("You must be logged in to use Heroic Strike.");
                        break;
                    }
                    Integer charId = this.characterId;
                    if (charId == null) {
                        charId = dao.getCharacterIdByName(name);
                    }
                    
                    // Look up the heroic strike skill (id=18)
                    final int HEROIC_STRIKE_SKILL_ID = 18;
                    Skill heroicSkill = dao.getSkillById(HEROIC_STRIKE_SKILL_ID);
                    if (heroicSkill == null) {
                        out.println("Heroic Strike skill not found in database.");
                        break;
                    }
                    
                    // Check if character knows the heroic strike skill
                    CharacterSkill charHeroic = dao.getCharacterSkill(charId, HEROIC_STRIKE_SKILL_ID);
                    if (charHeroic == null) {
                        out.println("You don't know how to use Heroic Strike.");
                        break;
                    }
                    
                    // Check cooldown and combat traits using unified check
                    com.example.tassmud.util.AbilityCheck.CheckResult heroicCheck = 
                        com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, heroicSkill);
                    if (heroicCheck.isFailure()) {
                        out.println(heroicCheck.getFailureMessage());
                        break;
                    }
                    
                    // Get the active combat (skill requires combat)
                    CombatManager combatMgr = CombatManager.getInstance();
                    Combat activeCombat = combatMgr.getCombatForCharacter(charId);
                    if (activeCombat == null) {
                        out.println("You must be in combat to use Heroic Strike.");
                        break;
                    }
                    
                    // Apply the skill's effects to self
                    int proficiency = charHeroic.getProficiency();
                    com.example.tassmud.util.SkillExecution.EffectResult effectResult = 
                        com.example.tassmud.util.SkillExecution.applySkillEffectsToSelf(heroicSkill, charId, proficiency);
                    
                    if (effectResult.hasAppliedEffects()) {
                        out.println("You channel your heroic spirit! " + effectResult.getSummary() + " takes effect!");
                        // Broadcast to room (excluding self)
                        for (ClientHandler ch : charIdToSession.values()) {
                            if (ch != this && ch.currentRoomId != null && ch.currentRoomId.equals(currentRoomId)) {
                                ch.out.println(name + " is filled with heroic determination!");
                            }
                        }
                    } else {
                        out.println("You attempt to summon your heroic spirit, but nothing happens.");
                    }
                    
                    // Apply cooldown and check proficiency growth (skill always "succeeds" if effects apply)
                    boolean heroicSucceeded = effectResult.hasAppliedEffects();
                    com.example.tassmud.util.SkillExecution.Result heroicResult = 
                        com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                            name, charId, heroicSkill, charHeroic, dao, heroicSucceeded);
                    
                    if (heroicResult.didProficiencyImprove()) {
                        out.println(heroicResult.getProficiencyMessage());
                    }
                    break;
                }
                case "infuse": {
                    // INFUSE - Infuse your staff with arcane energy
                    if (rec == null) {
                        out.println("You must be logged in to use infuse.");
                        break;
                    }
                    Integer charId = this.characterId;
                    if (charId == null) {
                        charId = dao.getCharacterIdByName(name);
                    }
                    
                    // Look up the lesser arcane infusion skill (id=100)
                    final int ARCANE_INFUSION_SKILL_ID = 100;
                    Skill infuseSkill = dao.getSkillById(ARCANE_INFUSION_SKILL_ID);
                    if (infuseSkill == null) {
                        out.println("Arcane Infusion skill not found in database.");
                        break;
                    }
                    
                    // Check if character knows the arcane infusion skill
                    CharacterSkill charInfuse = dao.getCharacterSkill(charId, ARCANE_INFUSION_SKILL_ID);
                    if (charInfuse == null) {
                        out.println("You don't know how to infuse weapons with arcane energy.");
                        break;
                    }
                    
                    // Check cooldown using unified check (no combat requirement for infusion)
                    com.example.tassmud.util.AbilityCheck.CheckResult infuseCheck = 
                        com.example.tassmud.util.SkillExecution.checkPlayerCanUseSkill(name, charId, infuseSkill);
                    if (infuseCheck.isFailure()) {
                        out.println(infuseCheck.getFailureMessage());
                        break;
                    }
                    
                    // Check if player has a staff equipped
                    ItemDAO itemDao = new ItemDAO();
                    Long mainHandInstanceId = dao.getCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.getId());
                    if (mainHandInstanceId == null) {
                        out.println("You need to have a weapon equipped to infuse it.");
                        break;
                    }
                    
                    ItemInstance weaponInstance = itemDao.getInstance(mainHandInstanceId);
                    if (weaponInstance == null) {
                        out.println("You need to have a weapon equipped to infuse it.");
                        break;
                    }
                    
                    ItemTemplate weaponTemplate = itemDao.getTemplateById(weaponInstance.templateId);
                    if (weaponTemplate == null) {
                        out.println("You need to have a weapon equipped to infuse it.");
                        break;
                    }
                    
                    // Check if the weapon is a staff
                    com.example.tassmud.model.WeaponFamily weaponFamily = weaponTemplate.getWeaponFamily();
                    if (weaponFamily != com.example.tassmud.model.WeaponFamily.STAVES) {
                        out.println("You can only infuse staves with arcane energy.");
                        break;
                    }
                    
                    // Apply the skill's effects to self
                    int proficiency = charInfuse.getProficiency();
                    com.example.tassmud.util.SkillExecution.EffectResult effectResult = 
                        com.example.tassmud.util.SkillExecution.applySkillEffectsToSelf(infuseSkill, charId, proficiency);
                    
                    if (effectResult.hasAppliedEffects()) {
                        String weaponName = weaponTemplate.name != null ? weaponTemplate.name : "staff";
                        out.println("You channel arcane energy into your " + weaponName + "! It crackles with magical power.");
                        out.println("Your attacks now fire arcane bolts, targeting reflex and using Intelligence for damage.");
                        // Broadcast to room (excluding self)
                        for (ClientHandler ch : charIdToSession.values()) {
                            if (ch != this && ch.currentRoomId != null && ch.currentRoomId.equals(currentRoomId)) {
                                ch.out.println(name + "'s " + weaponName + " begins to glow with arcane energy!");
                            }
                        }
                    } else {
                        out.println("You attempt to channel arcane energy, but nothing happens.");
                    }
                    
                    // Apply cooldown and check proficiency growth
                    boolean infuseSucceeded = effectResult.hasAppliedEffects();
                    com.example.tassmud.util.SkillExecution.Result infuseResult = 
                        com.example.tassmud.util.SkillExecution.recordPlayerSkillUse(
                            name, charId, infuseSkill, charInfuse, dao, infuseSucceeded);
                    
                    if (infuseResult.didProficiencyImprove()) {
                        out.println(infuseResult.getProficiencyMessage());
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception ignored) {}
        return rec;
    }
}
