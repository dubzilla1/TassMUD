package com.example.tassmud.net.commands;


import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.TransactionManager;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.Shop;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.persistence.ShopDAO;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles ITEMS category commands: get, drop, put, sacrifice, equip, remove, quaff, use, list, buy, sell
 * This class extracts item-related command logic from ClientHandler to reduce
 * method size and improve maintainability.
 */
public class ItemCommandHandler implements CommandHandler {
    
    private final ShopCommandHandler shopCommands = new ShopCommandHandler();
    private final EquipmentCommandHandler equipCommands = new EquipmentCommandHandler();

    private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.ITEMS).stream()
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
            case "get":
            case "pickup":
                return handleGetCommand(ctx);
            case "drop":
                return handleDropCommand(ctx);
            case "put":
                return handlePutCommand(ctx);
            case "sac":
            case "sacrifice":
                return handleSacrificeCommand(ctx);
            case "wear":
            case "equip":
                return equipCommands.handleEquipCommand(ctx); 
            case "dequip":
            case "remove":
                return equipCommands.handleRemoveCommand(ctx);
            case "quaff":
            case "drink":
                return handleQuaffCommand(ctx);
            case "use":
                return handleUseCommand(ctx);
            case "list":
                return shopCommands.handleListCommand(ctx);
            case "buy":
                return shopCommands.handleBuyCommand(ctx);
            case "sell":
                return shopCommands.handleSellCommand(ctx);
            default:
                return false;
        }
    }
    
    private boolean handleSacrificeCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // SACRIFICE <item> - sacrifice an item on the ground for 1 XP
        // Corpses must be empty to be sacrificed
        if (rec == null || rec.currentRoom == null) {
            out.println("You are nowhere.");
            return true;
        }
        String sacArgs = ctx.getArgs();
        if (sacArgs == null || sacArgs.trim().isEmpty()) {
            out.println("Usage: sacrifice <item>");
            out.println("Sacrifice an item on the ground to gain 1 experience point.");
            out.println("Corpses must be empty (looted) before they can be sacrificed.");
            return true;
        }
        
        String sacArg = sacArgs.trim().toLowerCase();
        ItemDAO itemDao = DaoProvider.items();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get items in the room
        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
        if (roomItems.isEmpty()) {
            out.println("There is nothing here to sacrifice.");
            return true;
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
            return true;
        }
        
        String itemDisplayName = matched.instance.customName != null ? matched.instance.customName : 
                                    (matched.template.name != null ? matched.template.name : "an item");
        
        // Check if it's a corpse - if so, it must be empty
        if (matched.template.isContainer()) {
            // Check for items inside
            java.util.List<ItemDAO.RoomItem> contents = itemDao.getItemsInContainer(matched.instance.instanceId);
            if (!contents.isEmpty()) {
                out.println(itemDisplayName + " is not empty. You must loot it first before sacrificing.");
                return true;
            }
            // Check for gold inside
            long gold = itemDao.getGoldContents(matched.instance.instanceId);
            if (gold > 0) {
                out.println(itemDisplayName + " still contains " + gold + " gold. Loot it first.");
                return true;
            }
        }
        
        // Delete the item and grant XP in a single transaction
        final ItemDAO.RoomItem sacItem = matched;
        try {
            TransactionManager.runInTransaction(() -> {
                boolean del = itemDao.deleteInstance(sacItem.instance.instanceId);
                if (!del) throw new RuntimeException("SACRIFICE_FAILED");
            });
        } catch (RuntimeException e) {
            if ("SACRIFICE_FAILED".equals(e.getMessage())) {
                out.println("Failed to sacrifice " + itemDisplayName + ".");
                return true;
            }
            throw e;
        }
        
        // Announce to room
        out.println("You sacrifice " + itemDisplayName + " to the gods.");
        ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " sacrifices " + itemDisplayName + " to the gods.");
        
        // Award 1 XP (ExperienceService handles the transaction for XP + level-up)
        com.example.tassmud.util.ExperienceService.awardFlatXp(charId, 1, msg -> {
            out.println(msg);
        });
        return true;
    }

    private boolean handlePutCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // PUT <item> <container> - put an item from inventory into a container
        if (rec == null || rec.currentRoom == null) {
            out.println("You are nowhere.");
            return true;
        }
        String putArgs = ctx.getArgs();
        if (putArgs == null || putArgs.trim().isEmpty()) {
            out.println("Usage: put <item> <container>");
            return true;
        }
        
        ItemDAO itemDao = DaoProvider.items();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get inventory items and find containers (room + inventory)
        java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
        java.util.List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(rec.currentRoom);
        
        if (invItems.isEmpty()) {
            out.println("You are not carrying anything to put in a container.");
            return true;
        }
        
        // Get equipped items to prevent putting equipped items
        java.util.Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);
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
            return true;
        }
        
        // Parse args to find container (from end) and item (from start)
        String[] words = putArgs.trim().split("\\s+");
        if (words.length < 2) {
            out.println("Usage: put <item> <container>");
            return true;
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
            return true;
        }
        
        if (itemSearchPart == null || itemSearchPart.isEmpty()) {
            out.println("Put what in " + matchedContainer.template.name + "?");
            return true;
        }
        
        // Smart match item in inventory
        // Search non-equipped inventory items FIRST, then equipped items
        String searchLower = itemSearchPart.toLowerCase();
        ItemDAO.RoomItem matchedItem = null;
        
        // Separate inventory items from equipped items (inventory items searched first)
        java.util.List<ItemDAO.RoomItem> inventoryOnly = new java.util.ArrayList<>();
        java.util.List<ItemDAO.RoomItem> equippedOnly = new java.util.ArrayList<>();
        for (ItemDAO.RoomItem ri : invItems) {
            if (equippedInstanceIds.contains(ri.instance.instanceId)) {
                equippedOnly.add(ri);
            } else {
                inventoryOnly.add(ri);
            }
        }
        
        // Search inventory items first, then equipped items
        java.util.List<java.util.List<ItemDAO.RoomItem>> searchOrder = java.util.List.of(inventoryOnly, equippedOnly);
        
        // Priority 1: Exact name match
        for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
            for (ItemDAO.RoomItem ri : itemList) {
                if (ri.template.name != null && ri.template.name.equalsIgnoreCase(itemSearchPart)) {
                    matchedItem = ri;
                    break;
                }
            }
            if (matchedItem != null) break;
        }
        // Priority 2: Name word starts with search
        if (matchedItem == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
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
                if (matchedItem != null) break;
            }
        }
        // Priority 3: Keyword match
        if (matchedItem == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
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
                if (matchedItem != null) break;
            }
        }
        // Priority 4: Name starts with search
        if (matchedItem == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
                    if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                        matchedItem = ri;
                        break;
                    }
                }
                if (matchedItem != null) break;
            }
        }
        // Priority 5: Name contains search
        if (matchedItem == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
                    if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                        matchedItem = ri;
                        break;
                    }
                }
                if (matchedItem != null) break;
            }
        }
        
        if (matchedItem == null) {
            out.println("You don't have '" + itemSearchPart + "' in your inventory.");
            return true;
        }
        
        // Check if trying to put container into itself
        if (matchedItem.instance.instanceId == matchedContainer.instance.instanceId) {
            out.println("You can't put something inside itself.");
            return true;
        }
        
        // Check if item is equipped
        if (equippedInstanceIds.contains(matchedItem.instance.instanceId)) {
            out.println("You cannot put " + (matchedItem.template.name != null ? matchedItem.template.name : "that item") + " in a container because it is currently equipped. Unequip it first.");
            return true;
        }
        
        // Move item to container
        itemDao.moveInstanceToContainer(matchedItem.instance.instanceId, matchedContainer.instance.instanceId);
        out.println("You put " + (matchedItem.template.name != null ? matchedItem.template.name : "an item") + " in " + matchedContainer.template.name + ".");
        return true;
    }

    private boolean handleDropCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // DROP <item_name>  or  DROP ALL
        if (rec == null || rec.currentRoom == null) {
            out.println("You are nowhere to drop anything.");
            return true;
        }
        String dropArgs = ctx.getArgs();
        if (dropArgs == null || dropArgs.trim().isEmpty()) {
            out.println("Usage: drop <item_name>  or  drop all");
            return true;
        }
        String dropArg = dropArgs.trim();
        ItemDAO itemDao = DaoProvider.items();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get equipped items to check if item is equipped
        java.util.Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);
        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
        for (Long iid : equippedMap.values()) {
            if (iid != null) equippedInstanceIds.add(iid);
        }

        // Get inventory items
        java.util.List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
        if (invItems.isEmpty()) {
            out.println("You are not carrying anything.");
            return true;
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
            return true;
        }

        // Smart matching: find items in inventory whose name or keywords match
        // Search non-equipped inventory items FIRST, then equipped items
        ItemDAO.RoomItem matched = null;
        String searchLower = dropArg.toLowerCase();
        
        // Separate inventory items from equipped items (inventory items searched first)
        java.util.List<ItemDAO.RoomItem> inventoryOnly = new java.util.ArrayList<>();
        java.util.List<ItemDAO.RoomItem> equippedOnly = new java.util.ArrayList<>();
        for (ItemDAO.RoomItem ri : invItems) {
            if (equippedInstanceIds.contains(ri.instance.instanceId)) {
                equippedOnly.add(ri);
            } else {
                inventoryOnly.add(ri);
            }
        }
        
        // Search inventory items first, then equipped items
        java.util.List<java.util.List<ItemDAO.RoomItem>> searchOrder = java.util.List.of(inventoryOnly, equippedOnly);

        // Priority 1: Exact name match (case-insensitive)
        for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
            for (ItemDAO.RoomItem ri : itemList) {
                if (ri.template.name != null && ri.template.name.equalsIgnoreCase(dropArg)) {
                    matched = ri;
                    break;
                }
            }
            if (matched != null) break;
        }

        // Priority 2: Name contains the search term as a word
        if (matched == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
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
                if (matched != null) break;
            }
        }

        // Priority 3: Keyword match
        if (matched == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
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
                if (matched != null) break;
            }
        }

        // Priority 4: Partial name match (name starts with search term)
        if (matched == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
                    if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                        matched = ri;
                        break;
                    }
                }
                if (matched != null) break;
            }
        }

        // Priority 5: Name contains search term anywhere
        if (matched == null) {
            for (java.util.List<ItemDAO.RoomItem> itemList : searchOrder) {
                for (ItemDAO.RoomItem ri : itemList) {
                    if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                        matched = ri;
                        break;
                    }
                }
                if (matched != null) break;
            }
        }

        if (matched == null) {
            out.println("You don't have '" + dropArg + "' in your inventory.");
            return true;
        }

        // Check if item is equipped
        if (equippedInstanceIds.contains(matched.instance.instanceId)) {
            out.println("You cannot drop " + (matched.template.name != null ? matched.template.name : "that item") + " because it is currently equipped. Unequip it first.");
            return true;
        }

        // Move item to the room
        itemDao.moveInstanceToRoom(matched.instance.instanceId, rec.currentRoom);
        out.println("You drop " + (matched.template.name != null ? matched.template.name : "an item") + ".");
        return true;
    }

    private boolean handleGetCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        
        // GET <item_name>  or  GET ALL  or  GET <item> <container>  or  GET ALL <container>
        if (rec == null || rec.currentRoom == null) {
            out.println("You are nowhere to pick anything up.");
            return true;
        }
        String getArgs = ctx.getArgs();
        if (getArgs == null || getArgs.trim().isEmpty()) {
            out.println("Usage: get <item_name>  or  get all  or  get <item> <container>");
            return true;
        }
        String itemArg = getArgs.trim();
        ItemDAO itemDao = DaoProvider.items();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
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
                    out.println(ClientHandler.getItemDisplayName(matchedContainer) + " contains no gold.");
                } else {
                    final ItemDAO.RoomItem goldContainer = matchedContainer;
                    long goldTaken = TransactionManager.runInTransaction(() -> {
                        long taken = itemDao.takeGoldContents(goldContainer.instance.instanceId);
                        dao.addGold(charId, taken);
                        return taken;
                    });
                    out.println("You get " + goldTaken + " gold from " + ClientHandler.getItemDisplayName(matchedContainer) + ".");
                }
                return true;
            }
            
            if (itemSearchPart.equalsIgnoreCase("all")) {
                // Get all from container (including gold)
                if (containerContents.isEmpty() && containerGold <= 0) {
                    out.println(ClientHandler.getItemDisplayName(matchedContainer) + " is empty.");
                    return true;
                }
                int count = 0;
                int skipped = 0;
                // Take gold first (in transaction to prevent gold loss)
                if (containerGold > 0) {
                    final ItemDAO.RoomItem allContainer = matchedContainer;
                    long goldTaken = TransactionManager.runInTransaction(() -> {
                        long taken = itemDao.takeGoldContents(allContainer.instance.instanceId);
                        dao.addGold(charId, taken);
                        return taken;
                    });
                    out.println("You get " + goldTaken + " gold from " + ClientHandler.getItemDisplayName(matchedContainer) + ".");
                }
                for (ItemDAO.RoomItem ci : containerContents) {
                    if (ci.template.isImmobile()) { skipped++; continue; }
                    itemDao.moveInstanceToCharacter(ci.instance.instanceId, charId);
                    out.println("You get " + ClientHandler.getItemDisplayName(ci) + " from " + ClientHandler.getItemDisplayName(matchedContainer) + ".");
                    count++;
                }
                if (count > 1) out.println("Got " + count + " items from " + ClientHandler.getItemDisplayName(matchedContainer) + ".");
                else if (count == 0 && skipped > 0) out.println("There is nothing in " + ClientHandler.getItemDisplayName(matchedContainer) + " you can pick up.");
                return true;
            } else {
                // Get specific item from container
                ItemDAO.RoomItem matchedItem = com.example.tassmud.util.ItemMatchingService.findMatchingItem(containerContents, itemSearchPart);
                
                if (matchedItem == null) {
                    out.println("You don't see '" + itemSearchPart + "' in " + ClientHandler.getItemDisplayName(matchedContainer) + ".");
                    return true;
                }
                if (matchedItem.template.isImmobile()) {
                    out.println("You can't pick that up.");
                    return true;
                }

                itemDao.moveInstanceToCharacter(matchedItem.instance.instanceId, charId);
                out.println("You get " + ClientHandler.getItemDisplayName(matchedItem) + " from " + ClientHandler.getItemDisplayName(matchedContainer) + ".");
                return true;
            }
        }

        // Handle "get all" (from room)
        if (itemArg.equalsIgnoreCase("all")) {
            if (roomItems.isEmpty()) {
                out.println("There is nothing here to pick up.");
                return true;
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
                out.println("You pick up " + ClientHandler.getItemDisplayName(ri) + ".");
                count++;
            }
            if (count > 1) out.println("Picked up " + count + " items.");
            else if (count == 0 && skipped > 0) out.println("There is nothing here you can pick up.");
            return true;
        }

        // Smart matching: find items in the room whose name or keywords match
        if (roomItems.isEmpty()) {
            out.println("There is nothing here to pick up.");
            return true;
        }

        // Try to find best match
        ItemDAO.RoomItem matched = com.example.tassmud.util.ItemMatchingService.findMatchingItem(roomItems, itemArg);

        if (matched == null) {
            out.println("You don't see '" + itemArg + "' here.");
            return true;
        }

        // Check if item is immobile
        if (matched.template.isImmobile()) {
            out.println("You can't pick that up.");
            return true;
        }

        // Move item to character's inventory
        itemDao.moveInstanceToCharacter(matched.instance.instanceId, charId);
        out.println("You pick up " + (matched.template.name != null ? matched.template.name : "an item") + ".");
        return true;
    }


    /**
     * Handle the quaff/drink command - consume a potion from inventory.
     */
    private boolean handleQuaffCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;
        
        if (rec == null) {
            out.println("No character record found.");
            return true;
        }
        
        String quaffArgs = ctx.getArgs();
        if (quaffArgs == null || quaffArgs.trim().isEmpty()) {
            out.println("Usage: quaff <potion_name>");
            return true;
        }
        
        String quaffArg = quaffArgs.trim();
        ItemDAO itemDao = DaoProvider.items();
        
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get equipped item IDs so we don't try to quaff equipped items
        Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);
        Set<Long> equippedInstanceIds = new HashSet<>();
        for (Long iid : equippedMap.values()) {
            if (iid != null) equippedInstanceIds.add(iid);
        }

        // Get inventory items (not equipped)
        List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
        List<ItemDAO.RoomItem> unequippedPotions = new ArrayList<>();
        for (ItemDAO.RoomItem ri : invItems) {
            if (!equippedInstanceIds.contains(ri.instance.instanceId) && ri.template.isPotion()) {
                unequippedPotions.add(ri);
            }
        }

        if (unequippedPotions.isEmpty()) {
            out.println("You have no potions in your inventory.");
            return true;
        }

        // Smart matching to find the potion
        ItemDAO.RoomItem matched = findMatchingItem(unequippedPotions, quaffArg);

        if (matched == null) {
            out.println("You don't have a potion named '" + quaffArg + "' in your inventory.");
            return true;
        }

        // Get the potion's effect
        String effectId = matched.template.spellEffectIds.isEmpty() ? null : matched.template.spellEffectIds.get(0);
        if (effectId == null || effectId.isEmpty()) {
            out.println("You quaff " + matched.template.name + " but it has no magical effect.");
            // Still consume the potion
            itemDao.deleteInstance(matched.instance.instanceId);
            return true;
        }

        // Get the effect definition
        EffectDefinition effectDef = EffectRegistry.getDefinition(effectId);
        if (effectDef == null) {
            out.println("You quaff " + matched.template.name + " but its magic fizzles.");
            itemDao.deleteInstance(matched.instance.instanceId);
            return true;
        }

        // Apply the effect with 100% proficiency and item level as caster level
        int itemLevel = matched.instance.itemLevel;
        int proficiency = 100; // Potions are always at full proficiency
        
        // Display quaffing message
        String potionName = matched.template.name != null ? matched.template.name : "a potion";
        out.println("You quaff " + potionName + ".");
        
        // Announce to room (excluding the quaffer)
        com.example.tassmud.net.ClientHandler.roomAnnounce(
            rec.currentRoom, 
            name + " quaffs " + potionName + ".", 
            charId, 
            true
        );
        
        // Build extra params with proficiency and caster_level (item level)
        Map<String, String> effectParams = new HashMap<>();
        effectParams.put("proficiency", String.valueOf(proficiency));
        effectParams.put("caster_level", String.valueOf(itemLevel));
        
        // Apply the effect using EffectRegistry - targetId is the quaffer, casterId is null (potion)
        EffectRegistry.apply(effectId, null, charId, effectParams);

        // Consume the potion (delete the instance)
        itemDao.deleteInstance(matched.instance.instanceId);
        
        return true;
    }
    
    /**
     * Handle the use command - use a magical item to cast its spell.
     * Priority: equipped items > inventory > room items
     * Spells are cast at 100% proficiency using the item's level.
     */
    private boolean handleUseCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;
        
        if (rec == null) {
            out.println("No character record found.");
            return true;
        }
        
        String useArgs = ctx.getArgs();
        if (useArgs == null || useArgs.trim().isEmpty()) {
            out.println("Usage: use <item_name>");
            return true;
        }
        
        String useArg = useArgs.trim();
        ItemDAO itemDao = DaoProvider.items();
        
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get equipped item IDs
        Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);
        Set<Long> equippedInstanceIds = new HashSet<>();
        for (Long iid : equippedMap.values()) {
            if (iid != null) equippedInstanceIds.add(iid);
        }
        
        // Get inventory items
        List<ItemDAO.RoomItem> invItems = itemDao.getItemsByCharacter(charId);
        
        // Get room items
        List<ItemDAO.RoomItem> roomItems = rec.currentRoom != null ? 
            itemDao.getItemsInRoom(rec.currentRoom) : new ArrayList<>();
        
        // Build separate lists: equipped, inventory (non-equipped), room
        List<ItemDAO.RoomItem> equippedItems = new ArrayList<>();
        List<ItemDAO.RoomItem> unequippedInvItems = new ArrayList<>();
        
        for (ItemDAO.RoomItem ri : invItems) {
            if (equippedInstanceIds.contains(ri.instance.instanceId)) {
                equippedItems.add(ri);
            } else {
                unequippedInvItems.add(ri);
            }
        }
        
        // Filter to only usable items (have on_use_spell_ids)
        List<ItemDAO.RoomItem> usableEquipped = new ArrayList<>();
        List<ItemDAO.RoomItem> usableInventory = new ArrayList<>();
        List<ItemDAO.RoomItem> usableRoom = new ArrayList<>();
        
        for (ItemDAO.RoomItem ri : equippedItems) {
            if (ri.template.isUsable()) usableEquipped.add(ri);
        }
        for (ItemDAO.RoomItem ri : unequippedInvItems) {
            if (ri.template.isUsable()) usableInventory.add(ri);
        }
        for (ItemDAO.RoomItem ri : roomItems) {
            if (ri.template.isUsable()) usableRoom.add(ri);
        }
        
        // Try to match item in priority order: equipped > inventory > room
        ItemDAO.RoomItem matched = null;
        String location = null;        
        matched = findMatchingItem(usableEquipped, useArg);
        if (matched != null) {
            location = "equipped";
        }
        
        if (matched == null) {
            matched = findMatchingItem(usableInventory, useArg);
            if (matched != null) {
                location = "inventory";
            }
        }
        
        if (matched == null) {
            matched = findMatchingItem(usableRoom, useArg);
            if (matched != null) {
                location = "room";
            }
        }
        
        if (matched == null) {
            out.println("You don't see a usable item named '" + useArg + "'.");
            return true;
        }
        
        ItemTemplate template = matched.template;
        ItemInstance instance = matched.instance;
        
        // Check if item has uses remaining
        int usesRemaining = instance.getEffectiveUsesRemaining(template);
        if (usesRemaining == 0) {
            out.println(template.name + " has no charges remaining.");
            return true;
        }
        
        // Get the spell(s) this item casts
        List<Integer> spellIds = template.onUseSpellIds;
        if (spellIds == null || spellIds.isEmpty()) {
            out.println(template.name + " has no magical properties to use.");
            return true;
        }
        
        // Item level determines spell power
        int itemLevel = instance.itemLevel;
        int proficiency = 100; // Items always cast at 100% proficiency
        
        // Display use message
        String itemName = template.name != null ? template.name : "the item";
        out.println("You use " + itemName + "...");
        
        // Announce to room
        com.example.tassmud.net.ClientHandler.roomAnnounce(
            rec.currentRoom, 
            name + " uses " + itemName + ".", 
            charId, 
            true
        );
        
        // Build extra params
        Map<String, String> effectParams = new HashMap<>();
        effectParams.put("proficiency", String.valueOf(proficiency));
        effectParams.put("caster_level", String.valueOf(itemLevel));
        
        // Cast each spell from the item
        for (Integer spellId : spellIds) {
            Spell spell = DaoProvider.spells().getSpellById(spellId);
            if (spell == null) {
                out.println("  (Spell #" + spellId + " not found)");
                continue;
            }
            
            // Determine targets based on spell's target type
            List<Integer> targets = new ArrayList<>();
            Spell.SpellTarget targetType = spell.getTarget();
            
            switch (targetType) {
                case SELF:
                    targets.add(charId);
                    break;
                case ALL_ALLIES:
                    // Add all players in room plus self
                    for (Integer connectedId : ClientHandler.getConnectedCharacterIds()) {
                        ClientHandler s = ClientHandler.getHandlerByCharacterId(connectedId);
                        if (s == null) continue;
                        Integer otherRoom = s.getCurrentRoomId();
                        if (otherRoom != null && otherRoom.equals(rec.currentRoom)) {
                            targets.add(connectedId);
                        }
                    }
                    if (!targets.contains(charId)) targets.add(charId);
                    break;
                default:
                    // For other target types (CURRENT_ENEMY, EXPLICIT_MOB_TARGET, etc.)
                    // default to self for now - items typically buff the user
                    targets.add(charId);
                    break;
            }
            
            // Apply all effects from this spell
            List<String> appliedEffects = new ArrayList<>();
            for (String effectId : spell.getEffectIds()) {
                EffectDefinition effectDef = EffectRegistry.getDefinition(effectId);
                if (effectDef == null) {
                    ClientHandler.sendDebugToCharacter(charId, "[USE DEBUG] Effect definition not found for ID: " + effectId);
                    continue;
                }
                
                for (Integer targetId : targets) {
                    EffectInstance result = EffectRegistry.apply(effectId, charId, targetId, effectParams);
                    if (result != null) {
                        ClientHandler.sendDebugToCharacter(charId, "[USE DEBUG] Applied effect " + effectDef.getName() + 
                            " (ID:" + effectId + ") to target " + targetId + ", expires at " + result.getExpiresAtMs());
                    } else {
                        ClientHandler.sendDebugToCharacter(charId, "[USE DEBUG] Failed to apply effect " + effectDef.getName() + 
                            " (ID:" + effectId + ") - apply() returned null");
                    }
                    
                    // Notify target if they're a different player
                    if (!targetId.equals(charId)) {
                        ClientHandler.sendToCharacter(targetId, effectDef.getName() + " from " + name + "'s " + itemName + " takes effect.");
                    }
                }
                appliedEffects.add(effectDef.getName());
            }
            
            if (!appliedEffects.isEmpty()) {
                out.println("  " + spell.getName() + ": " + String.join(", ", appliedEffects));
            } else {
                out.println("  " + spell.getName() + " takes effect.");
            }
        }
        
        // Decrement uses if not unlimited (-1)
        if (usesRemaining > 0) {
            int newUses = usesRemaining - 1;
            itemDao.updateUsesRemaining(instance.instanceId, newUses);
            if (newUses > 0) {
                out.println("(" + newUses + " charge" + (newUses == 1 ? "" : "s") + " remaining)");
            } else {
                out.println("(No charges remaining)");
            }
        }
        
        return true;
    }
    
    /**
     * Smart matching to find an item by name/keywords.
     * Delegates to {@link com.example.tassmud.util.ItemMatchingService}.
     * @deprecated Use {@link com.example.tassmud.util.ItemMatchingService#findMatchingItem} directly.
     */
    @Deprecated
    public static ItemDAO.RoomItem findMatchingItem(List<ItemDAO.RoomItem> items, String searchTerm) {
        return com.example.tassmud.util.ItemMatchingService.findMatchingItem(items, searchTerm);
    }
}
