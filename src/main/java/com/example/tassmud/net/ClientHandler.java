package com.example.tassmud.net;

import com.example.tassmud.model.Area;
import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.CommandParser.Command;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.GameClock;
import com.example.tassmud.util.HelpManager;
import com.example.tassmud.util.HelpPage;
import com.example.tassmud.util.PasswordUtil;
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
    private final Socket socket;
    private final GameClock gameClock;
    // Registry of active sessions
    private static final Set<ClientHandler> sessions = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, ClientHandler> nameToSession = new ConcurrentHashMap<>();

    // Per-session state used for routing messages
    private volatile PrintWriter out = null;
    private volatile String playerName = null;
    private volatile Integer currentRoomId = null;
    public ClientHandler(Socket socket, GameClock gameClock) {
        this.socket = socket;
        this.gameClock = gameClock;
    }

    private void registerSession() {
        sessions.add(this);
        if (playerName != null) nameToSession.put(playerName.toLowerCase(), this);
    }

    private void unregisterSession() {
        sessions.remove(this);
        if (playerName != null) nameToSession.remove(playerName.toLowerCase());
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
        // MOBs in this room (TODO: implement MOB system)
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

                // session prompt format (modifiable per session)
                String promptFormat = "<%h/%Hhp %m/%Mmp %v/%Vmv> ";

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
                // creation flow
                out.println("Character '" + name + "' not found. Creating new character.");
                boolean created = false;
                for (int attempt = 0; attempt < 3 && !created; attempt++) {
                    out.print("Create password: "); out.flush();
                    String p1 = in.readLine();
                    if (p1 == null) return;
                    out.print("Re-type password: "); out.flush();
                    String p2 = in.readLine();
                    if (p2 == null) return;
                        if (!p1.equals(p2)) {
                            out.println("Passwords do not match. Try again.");
                            continue;
                        }
                        // Collect basic character fields
                        out.print("Enter age (number): "); out.flush();
                        String ageLine = in.readLine();
                        int age = 0;
                        try {
                            age = Integer.parseInt(ageLine.trim());
                        } catch (Exception ignored) {
                            age = 0;
                        }
                        out.print("Enter a short description: "); out.flush();
                        String desc = in.readLine();
                        if (desc == null) desc = "";

                        String salt = PasswordUtil.generateSaltBase64();
                        String hash = PasswordUtil.hashPasswordBase64(p1.toCharArray(), java.util.Base64.getDecoder().decode(salt));

                        // Default point values for a new character
                        int hpMax = 100, mpMax = 50, mvMax = 100;
                        int hpCur = hpMax, mpCur = mpMax, mvCur = mvMax;
                        // Default ability scores
                        int str = 10, dex = 10, con = 10, intel = 10, wis = 10, cha = 10;
                        // Default saves
                        int armor = 10, fortitude = 10, reflex = 10, will = 10;

                        // Default starting room: 3001 if it exists, otherwise fall back
                        int startRoomId = 3001;
                        Room startRoom = dao.getRoomById(startRoomId);
                        if (startRoom == null) {
                            int anyRoomId = dao.getAnyRoomId();
                            startRoomId = anyRoomId > 0 ? anyRoomId : 0;
                        }
                        Integer currentRoom = startRoomId > 0 ? startRoomId : null;

                        Character ch = new Character(name, age, desc, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur,
                                currentRoom,
                                str, dex, con, intel, wis, cha, armor, fortitude, reflex, will);
                        boolean ok = dao.createCharacter(ch, hash, salt);
                            if (ok) {
                            out.println("Character created. Welcome, " + name + "!");
                            created = true;
                            break;
                        } else {
                            out.println("Failed to create character (name may be taken). Try a different name.");
                            socket.close();
                            return;
                        }
                }
                    if (!created) {
                    out.println("Failed to create character after several attempts. Goodbye.");
                    socket.close();
                    return;
                }
                        // reload the character record after successful creation
                        rec = dao.findByName(name);
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

            // register player name and room for routing
            this.playerName = name;
            this.currentRoomId = rec != null ? rec.currentRoom : null;
            nameToSession.put(name.toLowerCase(), this);

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
                // print formatted prompt
                try {
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
                switch (cmdName) {
                    case "help":
                        String a = cmd.getArgs();
                        boolean isGm = dao.isCharacterFlagTrueByName(name, "is_gm");
                        if (a == null || a.trim().isEmpty()) {
                            out.println("Available commands:");
                            for (String k : HelpManager.getAllPagesFor(isGm).keySet()) {
                                out.println("  " + k);
                            }
                            out.println("Type 'help <command>' for details.");
                        } else {
                            String arg0 = a.trim().split("\\s+",2)[0];
                            if (arg0.equalsIgnoreCase("commands")) {
                                out.println("Available commands:");
                                for (String k : HelpManager.getAllPagesFor(isGm).keySet()) {
                                    out.println("  " + k);
                                }
                                break;
                            }
                            if (arg0.equalsIgnoreCase("reload")) {
                                if (!isGm) {
                                    out.println("You do not have permission to reload help files.");
                                } else {
                                    HelpManager.reloadPages();
                                    out.println("Help pages reloaded.");
                                }
                            } else if (arg0.equalsIgnoreCase("commands")) {
                                // fallback: if HelpManager has nothing, build from canonical commands
                                java.util.Map<String,String> pages = HelpManager.getAllPagesFor(isGm);
                                if (pages != null && !pages.isEmpty()) {
                                    out.println("Available commands:");
                                    for (String k : pages.keySet()) out.println("  " + k);
                                } else {
                                    out.println("Available commands:");
                                    for (String k : CommandParser.getCanonicalCommands()) {
                                        // hide GM-only commands unless isGm
                                        if (!isGm && (k.equalsIgnoreCase("cflag") || k.equalsIgnoreCase("gmchat"))) continue;
                                        out.println("  " + k);
                                    }
                                }
                                break;
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
                        showRoom(room, lookRoomId);
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
                    case "quit":
                        out.println("Goodbye!");
                        socket.close();
                        return;
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
                            out.println("       SPAWN MOB <mob_id> [room_id]  (not yet implemented)");
                            break;
                        }
                        String[] sp = spawnArgs.trim().split("\\s+");
                        if (sp.length < 2) {
                            out.println("Usage: SPAWN ITEM <template_id> [room_id]");
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
                            out.println("MOB spawning is not yet implemented.");
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
                    case "groupchat": {
                        out.println("Group chat is not implemented yet.");
                        break;
                    }
                    case "get":
                    case "pickup": {
                        // GET <item_name> [from <container>]  or  GET ALL
                        if (rec == null || rec.currentRoom == null) {
                            out.println("You are nowhere to pick anything up.");
                            break;
                        }
                        String getArgs = cmd.getArgs();
                        if (getArgs == null || getArgs.trim().isEmpty()) {
                            out.println("Usage: get <item_name>  or  get all");
                            break;
                        }
                        String itemArg = getArgs.trim();
                        ItemDAO itemDao = new ItemDAO();
                        Integer charId = dao.getCharacterIdByName(name);
                        if (charId == null) {
                            out.println("Failed to locate your character record.");
                            break;
                        }

                        // Handle "get all"
                        if (itemArg.equalsIgnoreCase("all")) {
                            java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
                            if (roomItems.isEmpty()) {
                                out.println("There is nothing here to pick up.");
                                break;
                            }
                            int count = 0;
                            for (ItemDAO.RoomItem ri : roomItems) {
                                itemDao.moveInstanceToCharacter(ri.instance.instanceId, charId);
                                out.println("You pick up " + (ri.template.name != null ? ri.template.name : "an item") + ".");
                                count++;
                            }
                            if (count > 1) out.println("Picked up " + count + " items.");
                            break;
                        }

                        // Smart matching: find items in the room whose name or keywords match
                        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
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

                        // Check if slot is already occupied - if so, auto-remove the old item
                        Long currentInSlot = equippedMap.get(slot.id);
                        String removedItemName = null;
                        if (currentInSlot != null) {
                            // Get the name of the currently equipped item for the message
                            ItemInstance curInst = itemDao.getInstance(currentInSlot);
                            if (curInst != null) {
                                ItemTemplate curTmpl = itemDao.getTemplateById(curInst.templateId);
                                if (curTmpl != null && curTmpl.name != null) removedItemName = curTmpl.name;
                            }
                            if (removedItemName == null) removedItemName = "something";
                            // Clear the slot (item stays in inventory)
                            dao.setCharacterEquipment(charId, slot.id, null);
                        }

                        // Equip the new item
                        boolean equipped = dao.setCharacterEquipment(charId, slot.id, matched.instance.instanceId);
                        if (!equipped) {
                            out.println("Failed to equip " + matched.template.name + ".");
                            break;
                        }

                        // Recalculate and persist equipment bonuses
                        dao.recalculateEquipmentBonuses(charId, itemDao);

                        // Refresh character record
                        rec = dao.findByName(name);

                        // Show message - mention if we auto-removed something
                        if (removedItemName != null) {
                            out.println("You remove " + removedItemName + " and equip " + matched.template.name + " (" + slot.displayName + ").");
                        } else {
                            out.println("You equip " + matched.template.name + " (" + slot.displayName + ").");
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
                            if (inst != null) {
                                ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                if (tmpl != null) {
                                    if (tmpl.name != null) itemName = tmpl.name;
                                    armorBonus = tmpl.armorSaveBonus;
                                    fortBonus = tmpl.fortSaveBonus;
                                    refBonus = tmpl.refSaveBonus;
                                    willBonus = tmpl.willSaveBonus;
                                }
                            }
                            // Clear the slot
                            dao.setCharacterEquipment(charId, slotMatch.id, null);
                            dao.recalculateEquipmentBonuses(charId, itemDao);
                            rec = dao.findByName(name);
                            out.println("You remove " + itemName + " (" + slotMatch.displayName + ").");
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
                        dao.setCharacterEquipment(charId, matchedSlotId, null);
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
}
