package com.example.tassmud.net.commands;


import com.example.tassmud.persistence.DaoProvider;
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
import com.example.tassmud.util.GroupManager;
import com.example.tassmud.util.AllyManager;
import com.example.tassmud.util.MobileRegistry;
import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.Group;

import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
            case "open":
            case "close":
            case "lock":
            case "unlock":
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

            // Cannot stand while stunned
            if (userCombatant.isStunned()) {
                out.println("You are too stunned to stand up!");
                return true;
            }
            
            if (!userCombatant.isProne()) {
                out.println("You are already standing.");
                return true;
            }
            
            // Combat stand from prone - this is an innate skill
            // Get user's level for opposed check
            CharacterClassDAO standClassDao = DaoProvider.classes();
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
            int roll = ThreadLocalRandom.current().nextInt(1, 101);
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
        
        // Check room flags - PRISON and NO_RECALL prevent recall
        boolean isGm = dao.isCharacterFlagTrueByName(name, "is_gm");
        Integer currentRoomId = rec.currentRoom;
        if (currentRoomId != null && !isGm) {
            if (DaoProvider.rooms().isRoomPrison(currentRoomId)) {
                out.println("You cannot leave this room.");
                return true;
            }
            if (DaoProvider.rooms().isRoomNoRecall(currentRoomId)) {
                out.println("Something prevents you from recalling.");
                return true;
            }
        }
        
        final int TEMPLE_OF_MIDGAARD = 3001;
        
        // Already at the inn?
        if (rec.currentRoom != null && rec.currentRoom == TEMPLE_OF_MIDGAARD) {
            out.println("You are already at the Mead-Gaard Inn.");
            return true;
        }
        
        // Verify the destination exists
        Room innRoom = DaoProvider.rooms().getRoomById(TEMPLE_OF_MIDGAARD);
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

        // Aura room-change: update recipient sets for sanctuary-like auras
        com.example.tassmud.effect.AuraManager.getInstance()
                .onPlayerRoomChange(charId, recallOldRoom, TEMPLE_OF_MIDGAARD);
        
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
                Room room = DaoProvider.rooms().getRoomById(lookRoomId);
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

                    ItemDAO itemDao = DaoProvider.items();

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

                // Mob lookup moved later to prefer items; handled after inventory search

                if (!found) {
                    ItemDAO itemDao = DaoProvider.items();
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
                        ItemDAO itemDao = DaoProvider.items();
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
                        // Before checking doors/extras, try mobiles (search after items so items win ties)
                        MobileDAO mobDao = DaoProvider.mobiles();
                        java.util.List<Mobile> roomMobs = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(lookRoomId);
                        for (Mobile mob : roomMobs) {
                            String mobNameLower = mob.getName() != null ? mob.getName().toLowerCase() : "";
                            boolean mobMatch = false;
                            if (mobNameLower.startsWith(searchTerm)) mobMatch = true;
                            if (!mobMatch) {
                                String[] words = mobNameLower.split("\\s+");
                                for (String w : words) {
                                    if (w.startsWith(searchTerm)) { mobMatch = true; break; }
                                }
                            }
                            if (!mobMatch && mob.getKeywords() != null) {
                                for (String kw : mob.getKeywords()) {
                                    if (kw != null && kw.toLowerCase().startsWith(searchTerm)) { mobMatch = true; break; }
                                }
                            }
                            if (!mobMatch && mobNameLower.contains(searchTerm)) mobMatch = true;
                            if (mobMatch) {
                                out.println(mob.getName());
                                String longDesc = mob.getDescription();
                                if (longDesc != null && !longDesc.isEmpty()) {
                                    out.println(longDesc);
                                } else {
                                    out.println("You see nothing special about " + mob.getName() + ".");
                                }

                                // Show equipment for explicit mob look
                                ItemDAO itemDao = DaoProvider.items();
                                java.util.List<com.example.tassmud.model.Modifier> mods = mob.getAllModifiers();
                                java.util.Map<Integer, Long> equippedMap = new java.util.HashMap<>();
                                for (com.example.tassmud.model.Modifier m : mods) {
                                    String src = m.source();
                                    if (src == null) continue;
                                    if (src.startsWith("equip#")) {
                                        try {
                                            long iid = Long.parseLong(src.substring(src.indexOf('#') + 1));
                                            com.example.tassmud.model.ItemInstance inst = itemDao.getInstanceById(iid);
                                            if (inst == null) continue;
                                            com.example.tassmud.model.EquipmentSlot slot = itemDao.getTemplateEquipmentSlot(inst.templateId);
                                            if (slot != null) equippedMap.put(slot.getId(), iid);
                                        } catch (Exception ignored) { }
                                    }
                                }

                                if (!equippedMap.isEmpty()) {
                                    out.println("  Equipment:");
                                    com.example.tassmud.model.EquipmentSlot[] slots = com.example.tassmud.model.EquipmentSlot.values();
                                    java.util.Arrays.sort(slots, (a, b) -> Integer.compare(a.id, b.id));
                                    int maxLen = 0;
                                    for (com.example.tassmud.model.EquipmentSlot s : slots) {
                                        if (equippedMap.containsKey(s.id)) {
                                            if (s.displayName.length() > maxLen) maxLen = s.displayName.length();
                                        }
                                    }
                                    for (com.example.tassmud.model.EquipmentSlot slot : slots) {
                                        Long instanceId = equippedMap.get(slot.id);
                                        if (instanceId == null) continue;
                                        String itemName = "(unknown)";
                                        com.example.tassmud.model.ItemInstance inst = itemDao.getInstanceById(instanceId);
                                        if (inst != null) {
                                            com.example.tassmud.model.ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                                            itemName = com.example.tassmud.net.ClientHandler.getItemDisplayName(inst, tmpl);
                                        }
                                        String paddedSlot = ("%-" + maxLen + "s").formatted(slot.displayName);
                                        out.println("    " + paddedSlot + ": " + itemName);
                                    }
                                }

                                found = true;
                                break;
                            }
                        }

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
                        com.example.tassmud.model.Door door = DaoProvider.rooms().getDoor(lookRoomId, dirToken);
                        if (door != null && door.description != null && !door.description.isEmpty()) {
                            out.println(door.description);
                            found = true;
                        }
                    }

                    if (!found) {
                        // Check room extras (plaques, signs, etc.)
                        java.util.Map<String,String> extras = DaoProvider.rooms().getRoomExtras(lookRoomId);
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

            // Handle open/close commands for doors
            if ("open".equals(cmdName) || "close".equals(cmdName)) {
                String argsStr = ctx.getArgs();
                if (argsStr == null || argsStr.trim().isEmpty()) {
                    out.println(("open".equals(cmdName) ? "Open" : "Close") + " what?");
                    return true;
                }
                String target = argsStr.trim().toLowerCase();
                Integer curRoomId = rec.currentRoom;
                if (curRoomId == null) {
                    out.println("You are nowhere.");
                    return true;
                }

                // Resolve direction token first
                String dirToken = null;
                switch (target) {
                    case "n": case "north": dirToken = "north"; break;
                    case "e": case "east": dirToken = "east"; break;
                    case "s": case "south": dirToken = "south"; break;
                    case "w": case "west": dirToken = "west"; break;
                    case "u": case "up": dirToken = "up"; break;
                    case "d": case "down": dirToken = "down"; break;
                }

                com.example.tassmud.model.Door door = null;
                if (dirToken != null) {
                    door = DaoProvider.rooms().getDoor(curRoomId, dirToken);
                } else {
                    // try to match by door description or keywords
                    java.util.List<com.example.tassmud.model.Door> doors = DaoProvider.rooms().getDoorsForRoom(curRoomId);
                    for (com.example.tassmud.model.Door d : doors) {
                        if (d.description != null && d.description.toLowerCase().contains(target)) {
                            door = d; break;
                        }
                        if (d.direction != null && d.direction.startsWith(target)) {
                            door = d; break;
                        }
                    }
                }

                if (door == null) {
                    out.println("You don't see a door like that here.");
                    return true;
                }

                if (door.hidden) {
                    out.println("You don't see a way that way.");
                    return true;
                }
                if (door.blocked) {
                    out.println("Something blocks your way.");
                    return true;
                }

                if ("open".equals(cmdName)) {
                    if (door.isOpen()) {
                        out.println("It's already open.");
                        return true;
                    }
                    if (door.isLocked()) {
                        out.println("The " + door.direction + " door is locked.");
                        return true;
                    }
                    // open it
                    DaoProvider.rooms().upsertDoor(curRoomId, door.direction, door.toRoomId, "OPEN", false, door.hidden, door.blocked, door.keyItemId, door.description);
                    out.println("You open the " + door.direction + " door.");
                    ClientHandler.roomAnnounceFromActor(curRoomId, name + " opens the " + door.direction + " door.", charId);
                    return true;
                } else {
                    // close
                    if (door.isClosed()) {
                        out.println("It's already closed.");
                        return true;
                    }
                    DaoProvider.rooms().upsertDoor(curRoomId, door.direction, door.toRoomId, "CLOSED", door.locked, door.hidden, door.blocked, door.keyItemId, door.description);
                    out.println("You close the " + door.direction + " door.");
                    ClientHandler.roomAnnounceFromActor(curRoomId, name + " closes the " + door.direction + " door.", charId);
                    return true;
                }
            }

            // Handle lock/unlock commands for doors
            if ("lock".equals(cmdName) || "unlock".equals(cmdName)) {
                String argsStr = ctx.getArgs();
                if (argsStr == null || argsStr.trim().isEmpty()) {
                    out.println(("lock".equals(cmdName) ? "Lock" : "Unlock") + " what?");
                    return true;
                }
                String target = argsStr.trim().toLowerCase();
                Integer curRoomId = rec.currentRoom;
                if (curRoomId == null) {
                    out.println("You are nowhere.");
                    return true;
                }

                // Resolve direction token first
                String dirToken = null;
                switch (target) {
                    case "n": case "north": dirToken = "north"; break;
                    case "e": case "east": dirToken = "east"; break;
                    case "s": case "south": dirToken = "south"; break;
                    case "w": case "west": dirToken = "west"; break;
                    case "u": case "up": dirToken = "up"; break;
                    case "d": case "down": dirToken = "down"; break;
                }

                com.example.tassmud.model.Door door = null;
                if (dirToken != null) {
                    door = DaoProvider.rooms().getDoor(curRoomId, dirToken);
                } else {
                    java.util.List<com.example.tassmud.model.Door> doors = DaoProvider.rooms().getDoorsForRoom(curRoomId);
                    for (com.example.tassmud.model.Door d : doors) {
                        if (d.description != null && d.description.toLowerCase().contains(target)) {
                            door = d; break;
                        }
                        if (d.direction != null && d.direction.startsWith(target)) {
                            door = d; break;
                        }
                    }
                }

                if (door == null) {
                    out.println("You don't see a door like that here.");
                    return true;
                }
                if (door.hidden) {
                    out.println("You don't see a way that way.");
                    return true;
                }
                if (door.blocked) {
                    out.println("Something blocks your way.");
                    return true;
                }
                if (door.keyItemId == null) {
                    out.println("That door doesn't have a lock.");
                    return true;
                }

                // Check player inventory for a matching key
                Integer resolvedCharId = charId != null ? charId : ctx.resolveCharacterId();
                boolean hasKey = false;
                String keyName = "key";
                if (resolvedCharId != null) {
                    java.util.List<com.example.tassmud.persistence.ItemDAO.RoomItem> inv =
                        DaoProvider.items().getItemsByCharacter(resolvedCharId);
                    for (com.example.tassmud.persistence.ItemDAO.RoomItem ri : inv) {
                        if (ri.instance.templateId == door.keyItemId) {
                            hasKey = true;
                            keyName = ri.template.name;
                            break;
                        }
                    }
                }

                if (!hasKey) {
                    out.println("You don't have the right key.");
                    return true;
                }

                if ("unlock".equals(cmdName)) {
                    if (!door.isLocked()) {
                        out.println("It's not locked.");
                        return true;
                    }
                    DaoProvider.rooms().upsertDoor(curRoomId, door.direction, door.toRoomId, "CLOSED", false, door.hidden, door.blocked, door.keyItemId, door.description);
                    out.println("You unlock the " + door.direction + " door with " + keyName + ".");
                    ClientHandler.roomAnnounceFromActor(curRoomId, name + " unlocks the " + door.direction + " door.", charId);
                    return true;
                } else {
                    // lock
                    if (door.isLocked()) {
                        out.println("It's already locked.");
                        return true;
                    }
                    if (door.isOpen()) {
                        out.println("Close the door first.");
                        return true;
                    }
                    DaoProvider.rooms().upsertDoor(curRoomId, door.direction, door.toRoomId, "LOCKED", true, door.hidden, door.blocked, door.keyItemId, door.description);
                    out.println("You lock the " + door.direction + " door with " + keyName + ".");
                    ClientHandler.roomAnnounceFromActor(curRoomId, name + " locks the " + door.direction + " door.", charId);
                    return true;
                }
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
                Room curRoom = DaoProvider.rooms().getRoomById(curRoomId);
                if (curRoom == null) {
                    out.println("You seem to be in an unknown place.");
                    return true;
                }

                Integer destId = null;
                String directionName = null;
                com.example.tassmud.model.Direction moveDir = com.example.tassmud.model.Direction.fromString(cmdName);
                if (moveDir != null) {
                    destId = curRoom.getExit(moveDir);
                    directionName = moveDir.fullName();
                }

                if (destId == null) {
                    out.println("You can't go that way.");
                    return true;
                }

                // If the destination room id refers to a missing room (broken world), redirect to The Void (id 0)
                Room maybeDest = (destId == null) ? null : DaoProvider.rooms().getRoomById(destId);
                if (destId != null && maybeDest == null) {
                    destId = 0;
                }

                // Check door state for player movement (block if closed/locked/blocked/hidden)
                com.example.tassmud.model.Door door = DaoProvider.rooms().getDoor(curRoomId, directionName);
                if (door != null) {
                    if (door.blocked) {
                        out.println("Something blocks your way.");
                        return true;
                    }
                    if (door.hidden) {
                        out.println("You can't go that way.");
                        return true;
                    }
                    if (door.isLocked()) {
                        out.println("The " + directionName + " door is locked.");
                        return true;
                    }
                    if (door.isClosed()) {
                        out.println("The " + directionName + " door is closed.");
                        return true;
                    }
                }

                // Check room flags
                boolean isGm = dao.isCharacterFlagTrueByName(name, "is_gm");
                
                // PRISON check - cannot leave by normal movement (only GM teleport)
                if (DaoProvider.rooms().isRoomPrison(curRoomId) && !isGm) {
                    out.println("You cannot leave this room.");
                    return true;
                }
                
                // PRIVATE check - destination room can only have 1 non-GM player
                if (DaoProvider.rooms().isRoomPrivate(destId) && !isGm) {
                    int nonGmCount = countNonGmPlayersInRoom(destId, dao);
                    if (nonGmCount >= 1) {
                        out.println("That room is currently occupied.");
                        return true;
                    }
                }

                // Check and deduct movement points based on destination room/area
                int moveCost = DaoProvider.rooms().getMoveCostForRoom(destId);
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
                Room newRoom = DaoProvider.rooms().getRoomById(destId);
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
                    CharacterClassDAO moveClassDao = DaoProvider.classes();
                    int playerLevel = rec.currentClassId != null
                        ? moveClassDao.getCharacterClassLevel(charId, rec.currentClassId) : 1;
                    MobileRoamingService.getInstance().checkAggroOnPlayerEntry(destId, charId, playerLevel);
                }

                // Group follow mechanic - if this player is a group leader, move followers with them
                final String leaderDirection = directionName;
                final Integer leaderDestId = destId;
                final Integer leaderCharId = charId;
                final Integer leaderOldRoomId = oldRoomId;
                moveGroupFollowers(dao, leaderCharId, leaderOldRoomId, leaderDestId, leaderDirection);

                // Ally follow mechanic - move any following allies with the player
                moveAllyFollowers(leaderCharId, leaderOldRoomId, leaderDestId, leaderDirection);

                // Aura room-change: update recipient sets for sanctuary-like auras
                com.example.tassmud.effect.AuraManager.getInstance()
                        .onPlayerRoomChange(leaderCharId, leaderOldRoomId, leaderDestId);

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
        
        // Check if character is blind - they can't see room details
        if (charId != null && com.example.tassmud.effect.EffectRegistry.isBlind(charId)) {
            out.println("You can't see anything! You are blind!");
            out.println();
            return;
        }
        
        // Room name
        out.println(com.example.tassmud.util.Colors.room(room.getName()));
        // Room description (indented with tab)
        out.println("\t" + room.getLongDesc());
        // Exits - only show available exits in order: north east south west up down
        StringBuilder exits = new StringBuilder();
        exits.append("[Exits:");
        for (com.example.tassmud.model.Direction dir : room.getExits().keySet()) {
            com.example.tassmud.model.Door d = DaoProvider.rooms().getDoor(roomId, dir.fullName());
            if (d == null || (d.isOpen() && !d.blocked && !d.hidden && !d.isLocked())) {
                exits.append(" ").append(dir.fullName());
            }
        }
        exits.append("]");
        out.println(com.example.tassmud.util.Colors.exit(exits.toString()));
        // Blank line
        out.println();
        // Other players in this room (before mobs and items)
        CharacterDAO visDao = DaoProvider.characters();
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
        MobileDAO mobDao = DaoProvider.mobiles();
        // Shared ItemDAO for equipment lookups (used for mob equipment and room items)
        ItemDAO itemDao = DaoProvider.items();
        java.util.List<Mobile> roomMobs = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(roomId);
        for (Mobile mob : roomMobs) {
            // Check if mob is invisible (mobiles use negative IDs for effect tracking)
            // For now, mobs don't have invisibility effects - that could be added later
            String desc = mob.getShortDesc();
            if (desc != null && !desc.isEmpty()) {
                out.println(com.example.tassmud.util.Colors.mob(desc));
            } else {
                out.println(com.example.tassmud.util.Colors.mob(mob.getName() + " is here."));
            }

            // (Equipment display for mobs is shown only on explicit "look <mob>" commands.)
        }
        // Items on the floor in this room
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
    
    /**
     * Count the number of non-GM players currently in a room.
     * Used for PRIVATE room checks.
     * @param roomId the room ID
     * @param dao the CharacterDAO for GM flag lookups
     * @return count of non-GM players in the room
     */
    private int countNonGmPlayersInRoom(int roomId, CharacterDAO dao) {
        int count = 0;
        for (ClientHandler session : ClientHandler.sessions) {
            Integer sessionRoom = session.currentRoomId;
            if (sessionRoom != null && sessionRoom == roomId) {
                String playerName = session.playerName;
                if (playerName != null && !dao.isCharacterFlagTrueByName(playerName, "is_gm")) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Move group followers when the leader moves.
     * Followers automatically follow the leader to the new room.
     */
    private void moveGroupFollowers(CharacterDAO dao, Integer leaderCharId, Integer fromRoomId, Integer toRoomId, String direction) {
        if (leaderCharId == null || fromRoomId == null || toRoomId == null) {
            return;
        }

        GroupManager gm = GroupManager.getInstance();
        java.util.Optional<Group> groupOpt = gm.getGroupForCharacter(leaderCharId);
        
        if (groupOpt.isEmpty()) {
            return; // Not in a group
        }

        Group group = groupOpt.get();
        
        // Only process if this player is the leader
        if (!group.isLeader(leaderCharId)) {
            return;
        }

        // Get followers who are in the same room as the leader was
        Set<Integer> followers = group.getFollowers();
        if (followers.isEmpty()) {
            return;
        }

        CharacterRecord leaderRec = dao.getCharacterById(leaderCharId);
        String leaderName = leaderRec != null ? leaderRec.name : "Your leader";

        for (int followerId : followers) {
            // Check if follower is in the same room as the leader was
            ClientHandler followerHandler = ClientHandler.charIdToSession.get(followerId);
            if (followerHandler == null) {
                continue; // Not online
            }

            if (followerHandler.currentRoomId == null || !followerHandler.currentRoomId.equals(fromRoomId)) {
                continue; // Not in the same room
            }

            CharacterRecord followerRec = dao.findByName(followerHandler.playerName);
            if (followerRec == null) {
                continue;
            }

            // Check if follower has enough movement points
            int moveCost = DaoProvider.rooms().getMoveCostForRoom(toRoomId);
            if (followerRec.mvCur < moveCost) {
                followerHandler.out.println("You are too exhausted to follow " + leaderName + ".");
                ClientHandler.sendPromptToCharacter(followerId);
                continue;
            }

            // Deduct movement points
            if (!dao.deductMovementPoints(followerHandler.playerName, moveCost)) {
                followerHandler.out.println("You are too exhausted to follow " + leaderName + ".");
                ClientHandler.sendPromptToCharacter(followerId);
                continue;
            }

            // Move the follower
            boolean moved = dao.updateCharacterRoom(followerHandler.playerName, toRoomId);
            if (!moved) {
                continue;
            }

            // Announce departure (respecting invisibility)
            String followerName = followerRec.name;
            if (!followerHandler.gmInvisible) {
                ClientHandler.roomAnnounceFromActor(fromRoomId, 
                    followerName + " follows " + leaderName + " " + direction + ".", followerId);
            }

            // Update handler state
            followerHandler.currentRoomId = toRoomId;

            // Aura room-change: update recipient sets for sanctuary-like auras
            com.example.tassmud.effect.AuraManager.getInstance()
                    .onPlayerRoomChange(followerId, fromRoomId, toRoomId);

            // Announce arrival
            if (!followerHandler.gmInvisible) {
                ClientHandler.roomAnnounceFromActor(toRoomId, 
                    followerName + " arrives following " + leaderName + ".", followerId);
            }

            // Show the new room to the follower
            followerHandler.out.println("You follow " + leaderName + " " + direction + ".");
            Room newRoom = DaoProvider.rooms().getRoomById(toRoomId);
            if (newRoom != null) {
                // Create a temporary context for showing the room
                CommandContext followerCtx = new CommandContext(
                    null, // cmd not needed for showRoom
                    followerHandler.playerName,
                    followerId,
                    toRoomId,
                    followerRec,
                    dao,
                    followerHandler.out,
                    false, // isGm
                    false, // inCombat
                    followerHandler
                );
                showRoom(newRoom, toRoomId, followerCtx);
            }

            // Check for aggressive mobs
            CharacterClassDAO classDao = DaoProvider.classes();
            int followerLevel = followerRec.currentClassId != null
                ? classDao.getCharacterClassLevel(followerId, followerRec.currentClassId) : 1;
            MobileRoamingService.getInstance().checkAggroOnPlayerEntry(toRoomId, followerId, followerLevel);
        }
    }

    /**
     * Move ally mobs that are following the owner when the owner changes rooms.
     * Only moves allies that are in {@code fromRoomId} and have {@code followsOwner} set.
     *
     * @param ownerCharId the character ID of the player who just moved
     * @param fromRoomId  the room the player left
     * @param toRoomId    the room the player entered
     * @param direction   direction name (for messaging)
     */
    private void moveAllyFollowers(int ownerCharId, int fromRoomId, int toRoomId, String direction) {
        java.util.List<AllyBinding> following =
                AllyManager.getInstance().getFollowingAlliesInRoom(ownerCharId, fromRoomId);
        if (following.isEmpty()) return;

        String ownerName = null; // resolve lazily

        for (AllyBinding binding : following) {
            Mobile mob = MobileRegistry.getInstance().getById(binding.getMobInstanceId());
            if (mob == null) continue;

            if (ownerName == null) {
                CharacterRecord ownerRec = DaoProvider.characters().getCharacterById(ownerCharId);
                ownerName = ownerRec != null ? ownerRec.name : "their master";
            }

            // Announce departure
            ClientHandler.broadcastRoomMessage(fromRoomId,
                    mob.getName() + " follows " + ownerName + " " + direction + ".");

            // Move the ally
            mob.setCurrentRoom(toRoomId);
            try {
                DaoProvider.mobiles().updateInstance(mob);
                MobileRegistry.getInstance().moveToRoom(mob.getInstanceId(), fromRoomId, toRoomId);
            } catch (Exception e) {
                // Non-fatal: log and continue
            }

            // Announce arrival
            ClientHandler.broadcastRoomMessage(toRoomId,
                    mob.getName() + " arrives following " + ownerName + ".");
        }
    }
}
