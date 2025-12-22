package com.example.tassmud.net.commands;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.MobileRoamingService;
import com.example.tassmud.util.RegenerationService;

import java.io.PrintWriter;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles movement-related commands by delegating to ClientHandler.
 * NOTE: Only list commands that are actually implemented in handleLookAndMovement().
 * Commands like recall, sit, sleep, rest, stand, wake, prompt are still in the main switch.
 */
public class MovementCommandHandler implements CommandHandler {

    private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.MOVEMENT).stream()
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
            case "look":
                return handleLookAndMovementCommand(cmdName,ctx);
            case "recall":
                return handleRecallCommand(ctx);
            case "sit":
                return handleSitCommand(ctx); 
            case "sleep":
            case "rest":
                return handleSleepCommand(ctx);
            case "stand":
            case "wake":
                return handleWakeCommand(ctx);
            default:
                return false;
        }
    }

    private boolean handleWakeCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer characterId = ctx.characterId;
        String name = ctx.playerName;
        Integer currentRoomId = rec != null ? rec.currentRoom : null;

         // Check if in combat - combat stand from prone is a special skill
        CombatManager combatMgr = CombatManager.getInstance();
        Combat activeCombat = combatMgr.getCombatForCharacter(characterId);
        
        if (activeCombat != null) {
            // In combat - check if prone
            Combatant userCombatant = activeCombat.findByCharacterId(characterId);
            if (userCombatant == null) {
                out.println("Combat error: could not find your combatant.");
                return true;
            }
            
            if (!userCombatant.isProne()) {
                out.println("You are already standing.");
                return true;
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
                ClientHandler.roomAnnounce(currentRoomId, name + " scrambles to their feet.", characterId, true);
            } else {
                // Failure - remain prone
                out.println("You struggle to stand but fail to get up.");
                ClientHandler.roomAnnounce(currentRoomId, name + " struggles to stand but remains on the ground.", characterId, true);
            }
            return true;
        }
        
        // Not in combat - normal stand behavior
        Stance currentStance = RegenerationService.getInstance().getPlayerStance(characterId);
        if (currentStance == Stance.STANDING) {
            out.println("You are already standing.");
            return true;
        }
        if (currentStance == Stance.SLEEPING) {
            out.println("You wake up and stand up.");
            ClientHandler.roomAnnounce(currentRoomId, name + " wakes up and stands up.", characterId, true);
        } else {
            out.println("You stand up.");
            ClientHandler.roomAnnounce(currentRoomId, name + " stands up.", characterId, true);
        }
        RegenerationService.getInstance().setPlayerStance(characterId, Stance.STANDING);
        return true;
    }

    private boolean handleSitCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        Integer characterId = ctx.characterId;
        String name = ctx.playerName;
        Integer currentRoomId = rec != null ? rec.currentRoom : null;

        Stance currentStance = RegenerationService.getInstance().getPlayerStance(characterId);
        if (currentStance == Stance.SITTING) {
            out.println("You are already sitting.");
            return true;
        }
        if (currentStance == Stance.SLEEPING) {
            out.println("You wake up and sit up.");
        } else {
            out.println("You sit down.");
        }
        RegenerationService.getInstance().setPlayerStance(characterId, Stance.SITTING);
        ClientHandler.roomAnnounceFromActor(currentRoomId, name + " sits down.", characterId);
        return true;
    }

    private boolean handleSleepCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        Integer characterId = ctx.characterId;
        String name = ctx.playerName;
        Integer currentRoomId = rec != null ? rec.currentRoom : null;

        Stance currentStance = RegenerationService.getInstance().getPlayerStance(characterId);
        if (currentStance == Stance.SLEEPING) {
            out.println("You are already sleeping.");
            return true;
        }
        if (currentStance == Stance.STANDING) {
            out.println("You lie down and go to sleep.");
        } else {
            out.println("You close your eyes and drift off to sleep.");
        }
        RegenerationService.getInstance().setPlayerStance(characterId, Stance.SLEEPING);
        ClientHandler.roomAnnounceFromActor(currentRoomId, name + " lies down and goes to sleep.", characterId);
        return true;
    }

    private boolean handleRecallCommand(CommandContext ctx) {
        // Teleport back to the Mead-Gaard Inn (home base)
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String name = ctx.playerName;

        if (rec == null) {
            out.println("No character record found.");
            return true;
        }
        
        // Check stance - can't recall while sleeping
        Stance recallStance = RegenerationService.getInstance().getPlayerStance(charId);
        if (!recallStance.canMove()) {
            if (recallStance == Stance.SLEEPING) {
                out.println("You are asleep! Type 'wake' to wake up first.");
            } else {
                out.println("You must stand up first. Type 'stand'.");
            }
            return true;
        }
        
        final int TEMPLE_OF_MIDGAARD = 3001;
        
        // Already at the inn?
        if (rec.currentRoom != null && rec.currentRoom == TEMPLE_OF_MIDGAARD) {
            out.println("You are already at the Mead-Gaard Inn.");
            return true;
        }
        
        // Verify the destination exists
        Room innRoom = dao.getRoomById(TEMPLE_OF_MIDGAARD);
        if (innRoom == null) {
            out.println("The Mead-Gaard Inn seems to have vanished from reality. Something is very wrong.");
            return true;
        }
        
        // Announce magical departure from old room (respecting invisibility)
        Integer recallOldRoom = rec.currentRoom;
        ClientHandler.roomAnnounceFromActor(recallOldRoom, name + " closes their eyes and vanishes in a shimmer of light.", charId);
        
        // Teleport the character
        dao.updateCharacterRoom(name, TEMPLE_OF_MIDGAARD);
        ctx.handler.currentRoomId = TEMPLE_OF_MIDGAARD;
        rec = dao.findByName(name);
        
        // Announce magical arrival in new room (respecting invisibility)
        ClientHandler.roomAnnounceFromActor(TEMPLE_OF_MIDGAARD, name + " appears in a shimmer of light.", charId);
        
        out.println();
        out.println("You close your eyes and think of home...");
        out.println("A warm sensation envelops you, and when you open your eyes, you find yourself at the Mead-Gaard Inn.");
        out.println();
        showRoom(innRoom, TEMPLE_OF_MIDGAARD, ctx);
        return true;
    }
    

    public boolean handleLookAndMovementCommand(String cmd, CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String name = ctx.playerName;

        if (rec == null) {
            out.println("You seem to be nowhere. (no character record found)");
            return true;
        }

        String cmdName = cmd.toLowerCase();
        try {
            if ("look".equals(cmdName)) {
                Integer lookRoomId = rec.currentRoom;
                if (lookRoomId == null) {
                    out.println("You are not located in any room.");
                    return true;
                }
                Room room = dao.getRoomById(lookRoomId);
                if (room == null) {
                    out.println("You are in an unknown place (room id " + lookRoomId + ").");
                    return true;
                }

                String lookArgs = ctx.getArgs();
                if (lookArgs == null || lookArgs.trim().isEmpty()) {
                    showRoom(room, lookRoomId, ctx);
                    return true;
                }

                if (lookArgs.trim().toLowerCase().startsWith("in ")) {
                    String containerSearch = lookArgs.trim().substring(3).trim().toLowerCase();
                    if (containerSearch.isEmpty()) {
                        out.println("Look in what?");
                        return true;
                    }

                    ItemDAO itemDao = new ItemDAO();

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
                        return true;
                    }

                    java.util.List<ItemDAO.RoomItem> contents = itemDao.getItemsInContainer(matchedContainer.instance.instanceId);
                    out.println(ClientHandler.getItemDisplayName(matchedContainer) + " contains:");
                    if (contents.isEmpty()) {
                        out.println("  Nothing.");
                    } else {
                        java.util.Map<String, Integer> itemCounts = new java.util.LinkedHashMap<>();
                        for (ItemDAO.RoomItem ci : contents) {
                            String itemName = ClientHandler.getItemDisplayName(ci);
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

                    return true;
                }

                String searchTerm = lookArgs.trim().toLowerCase();
                boolean found = false;
                for (ClientHandler s : ClientHandler.sessions) {
                    if (s == ctx.handler) continue;
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
                    // Check for door/exits (direction look) and room extras (plaques, signs)
                    String st = lookArgs.trim().toLowerCase();
                    String dirToken = null;
                    switch (st) {
                        case "n": case "north": dirToken = "north"; break;
                        case "e": case "east": dirToken = "east"; break;
                        case "s": case "south": dirToken = "south"; break;
                        case "w": case "west": dirToken = "west"; break;
                        case "u": case "up": dirToken = "up"; break;
                        case "d": case "down": dirToken = "down"; break;
                    }
                    if (dirToken != null) {
                        // Try to fetch door metadata and show its description if present
                        com.example.tassmud.persistence.CharacterDAO dao2 = new com.example.tassmud.persistence.CharacterDAO();
                        com.example.tassmud.model.Door door = dao2.getDoor(lookRoomId, dirToken);
                        if (door != null && door.description != null && !door.description.isEmpty()) {
                            out.println(door.description);
                            found = true;
                        }
                    }

                    if (!found) {
                        // Check room extras (plaques, signs, etc.)
                        com.example.tassmud.persistence.CharacterDAO dao2 = new com.example.tassmud.persistence.CharacterDAO();
                        java.util.Map<String,String> extras = dao2.getRoomExtras(lookRoomId);
                        if (extras != null && !extras.isEmpty()) {
                            // direct key match or prefix match
                            String matchKey = null;
                            for (String k : extras.keySet()) {
                                if (k.equalsIgnoreCase(st) || k.toLowerCase().startsWith(st)) { matchKey = k; break; }
                            }
                            if (matchKey != null) {
                                String val = extras.get(matchKey);
                                if (val != null && !val.isEmpty()) {
                                    out.println(val);
                                    found = true;
                                }
                            }
                        }
                    }

                    if (!found) {
                        out.println("You don't see '" + lookArgs.trim() + "' here.");
                    }
                }

                return true;
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
                Stance moveStance = RegenerationService.getInstance().getPlayerStance(charId);
                if (!moveStance.canMove()) {
                    if (moveStance == Stance.SLEEPING) {
                        out.println("You are asleep! Type 'wake' to wake up first.");
                    } else {
                        out.println("You must stand up first. Type 'stand'.");
                    }
                    return true;
                }

                Integer curRoomId = rec.currentRoom;
                if (curRoomId == null) {
                    out.println("You are not located in any room.");
                    return true;
                }
                Room curRoom = dao.getRoomById(curRoomId);
                if (curRoom == null) {
                    out.println("You seem to be in an unknown place.");
                    return true;
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
                    return true;
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
                    return true;
                }

                if (!dao.deductMovementPoints(name, moveCost)) {
                    out.println("You are too exhausted to move.");
                    return true;
                }

                boolean moved = dao.updateCharacterRoom(name, destId);
                if (!moved) {
                    out.println("You try to move but something prevents you.");
                    return true;
                }

                // Announce departure to the old room (respecting invisibility)
                Integer oldRoomId = curRoomId;
                if (!ctx.handler.gmInvisible) {
                    ClientHandler.roomAnnounceFromActor(oldRoomId, ClientHandler.makeDepartureMessage(name, directionName), charId);
                }

                // Refresh character record and show new room
                rec = dao.findByName(name);
                ctx.handler.currentRoomId = rec != null ? rec.currentRoom : null;
                Room newRoom = dao.getRoomById(destId);
                if (newRoom == null) {
                    out.println("You arrive at an unknown place.");
                    return true;
                }

                if (!ctx.handler.gmInvisible) {
                    ClientHandler.roomAnnounceFromActor(destId, ClientHandler.makeArrivalMessage(name, directionName), charId);
                }

                out.println("You move " + directionName + ".");
                showRoom(newRoom, destId, ctx);

                // Check for aggressive mobs in the new room
                {
                    CharacterClassDAO moveClassDao = new CharacterClassDAO();
                    int playerLevel = rec.currentClassId != null
                        ? moveClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
                    MobileRoamingService.getInstance().checkAggroOnPlayerEntry(destId, charId, playerLevel);
                }

                return true;
            }

        } catch (Exception ex) {
            out.println("An error occurred while processing the command.");
        }
        return true;
    }

    /** Display a room to this client (used by look and movement) */
    public static void showRoom(Room room, int roomId, CommandContext ctx) {
        PrintWriter out = ctx.out;
        Integer charId = ctx.characterId;
        String name = ctx.playerName;
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
        boolean iAmGm = name != null && visDao.isCharacterFlagTrueByName(name, "is_gm");
        for (ClientHandler s : ClientHandler.sessions) {
            // Skip self, skip sessions without a character, skip sessions not in this room
            if (s == ctx.handler) continue;
            String otherName = s.playerName;
            if (otherName == null) continue;
            Integer otherRoomId = s.currentRoomId;
            if (otherRoomId == null || !otherRoomId.equals(roomId)) continue;
            
            // Check GM invisibility - only other GMs can see GM-invisible players
            if (s.gmInvisible && !iAmGm) {
                continue; // Can't see GM-invisible players
            }
            
            // Check normal invisibility - skip if we can't see them
            Integer otherCharId = ClientHandler.getCharacterIdByName(otherName);
            boolean otherInvis = com.example.tassmud.effect.EffectRegistry.isInvisible(otherCharId);
            if (otherInvis) {
                // Check if we can see invisible
                if (!com.example.tassmud.effect.EffectRegistry.canSee(charId, otherCharId)) {
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
                out.println("A " + ClientHandler.getItemDisplayName(ri) + " lies here.");
            }
        }
    }
}
