package com.example.tassmud.net.commands;

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
                return handleEquipCommand(ctx); 
            case "dequip":
            case "remove":
                return handleRemoveCommand(ctx);
            case "quaff":
            case "drink":
                return handleQuaffCommand(ctx);
            case "use":
                return handleUseCommand(ctx);
            case "list":
                return handleListCommand(ctx);
            case "buy":
                return handleBuyCommand(ctx);
            case "sell":
                return handleSellCommand(ctx);
            default:
                return false;
        }
    }
    
    private boolean handleBuyCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // BUY <item> [quantity] - purchase an item from a shopkeeper
        if (rec == null || rec.currentRoom == null) {
            out.println("You must be in a room to buy items.");
            return true;
        }
        String buyArgs = ctx.getArgs();
        if (buyArgs == null || buyArgs.trim().isEmpty()) {
            out.println("Usage: buy <item> [quantity]");
            return true;
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
                    return true;
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
            return true;
        }
        
        java.util.List<Shop> shops = shopDao.getShopsForMobTemplateIds(shopkeeperTemplateIds);
        java.util.Set<Integer> availableItemIds = shopDao.getAllItemIds(shops);
        
        if (availableItemIds.isEmpty()) {
            out.println("There is nothing for sale here.");
            return true;
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
            return true;
        }
        
        // Calculate total cost
        long totalCost = (long) matchedItem.value * quantity;
        
        // Check if player has enough gold
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        long playerGold = dao.getGold(charId);
        if (playerGold < totalCost) {
            out.println("You need " + String.format("%,d", totalCost) + " gp to buy " + 
                (quantity > 1 ? quantity + " " + matchedItem.name : matchedItem.name) + 
                ", but you only have " + String.format("%,d", playerGold) + " gp.");
            return true;
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
        return true;
    }

    private boolean handleListCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        
        // LIST - show items for sale from shopkeepers in the room
        if (rec == null || rec.currentRoom == null) {
            out.println("You must be in a room to see what's for sale.");
            return true;
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
            return true;
        }
        
        // Get all shops for these shopkeepers
        java.util.List<Shop> shops = shopDao.getShopsForMobTemplateIds(shopkeeperTemplateIds);
        if (shops.isEmpty()) {
            out.println("The shopkeeper has nothing for sale.");
            return true;
        }
        
        // Get all item IDs available for sale
        java.util.Set<Integer> itemIds = shopDao.getAllItemIds(shops);
        if (itemIds.isEmpty()) {
            out.println("The shopkeeper has nothing for sale.");
            return true;
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
            return true;
        }
        
        // Sort by price ascending
        itemsForSale.sort((item1, item2) -> Integer.compare(item1.value, item2.value));
        
        out.println("Items for sale:");
        for (ItemTemplate item : itemsForSale) {
            String itemName = item.name != null ? item.name : "(unnamed)";
            out.println(String.format("  %-40s %,d gp", itemName, item.value));
        }
        return true;
    }

    private boolean handleRemoveCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // REMOVE <item_name> or REMOVE <slot_name> - unequip an item
        if (rec == null) {
            out.println("No character record found.");
            return true;
        }
        String removeArgs = ctx.getArgs();
        if (removeArgs == null || removeArgs.trim().isEmpty()) {
            out.println("Usage: remove <item_name>  or  remove <slot_name>");
            return true;
        }
        String removeArg = removeArgs.trim();
        ItemDAO itemDao = new ItemDAO();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get currently equipped items
        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
        if (equippedMap.isEmpty()) {
            out.println("You have nothing equipped.");
            return true;
        }

        // Check if no items are actually equipped (all slots null)
        boolean hasEquipped = false;
        for (Long iid : equippedMap.values()) {
            if (iid != null) { hasEquipped = true; break; }
        }
        if (!hasEquipped) {
            out.println("You have nothing equipped.");
            return true;
        }

        // Try to match by slot name first
        EquipmentSlot slotMatch = EquipmentSlot.fromKey(removeArg);
        if (slotMatch != null) {
            Long instanceInSlot = equippedMap.get(slotMatch.id);
            if (instanceInSlot == null) {
                out.println("You have nothing equipped in your " + slotMatch.displayName + " slot.");
                return true;
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
            return true;
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
            String displayName = ClientHandler.getItemDisplayName(inst, tmpl);
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
                String displayName = ClientHandler.getItemDisplayName(inst, tmpl);
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
                String displayName = ClientHandler.getItemDisplayName(inst, tmpl);
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
                String displayName = ClientHandler.getItemDisplayName(inst, tmpl);
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
            return true;
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

        out.println("You remove " + ClientHandler.getItemDisplayName(matchedInstance, matchedTemplate) + " (" + slotName + ").");
        if (matchedTemplate.armorSaveBonus != 0 || matchedTemplate.fortSaveBonus != 0 || 
            matchedTemplate.refSaveBonus != 0 || matchedTemplate.willSaveBonus != 0) {
            out.println("  Saves: Armor " + rec.getArmorTotal() + ", Fort " + rec.getFortitudeTotal() + ", Ref " + rec.getReflexTotal() + ", Will " + rec.getWillTotal());
        }
        return true;
    }

    private boolean handleEquipCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        
        // EQUIP [item_name] - with no args, show current equipment; with args, equip item
        if (rec == null) {
            out.println("No character record found.");
            return true;
        }
        String equipArgs = ctx.getArgs();
        ItemDAO itemDao = new ItemDAO();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
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
                        itemName = ClientHandler.getItemDisplayName(inst, tmpl);
                    }
                }
                // Pad slot name to maxLen
                String paddedSlot = String.format("%-" + maxLen + "s", slot.displayName);
                out.println("  " + paddedSlot + ": " + itemName);
            }
            return true;
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
            return true;
        }

        // Smart matching to find the item
        ItemDAO.RoomItem matched = null;
        String searchLower = equipArg.toLowerCase();

        // Priority 1: Exact name match (check both customName and template name)
        for (ItemDAO.RoomItem ri : unequippedItems) {
            String displayName = ClientHandler.getItemDisplayName(ri);
            if (displayName.equalsIgnoreCase(equipArg)) {
                matched = ri;
                break;
            }
        }

        // Priority 2: Word match
        if (matched == null) {
            for (ItemDAO.RoomItem ri : unequippedItems) {
                String displayName = ClientHandler.getItemDisplayName(ri);
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
                String displayName = ClientHandler.getItemDisplayName(ri);
                if (displayName.toLowerCase().startsWith(searchLower)) {
                    matched = ri;
                    break;
                }
            }
        }

        // Priority 5: Name contains search
        if (matched == null) {
            for (ItemDAO.RoomItem ri : unequippedItems) {
                String displayName = ClientHandler.getItemDisplayName(ri);
                if (displayName.toLowerCase().contains(searchLower)) {
                    matched = ri;
                    break;
                }
            }
        }

        if (matched == null) {
            out.println("You don't have '" + equipArg + "' in your inventory to equip.");
            return true;
        }

        // Check if item is equipable (has a slot)
        EquipmentSlot slot = itemDao.getTemplateEquipmentSlot(matched.template.id);
        if (slot == null) {
            out.println(ClientHandler.getItemDisplayName(matched) + " cannot be equipped.");
            return true;
        }

        // Check armor proficiency requirement
        if (matched.template.isArmor()) {
            ArmorCategory armorCat = matched.template.getArmorCategory();
            if (armorCat != null) {
                String skillKey = armorCat.getSkillKey();
                Skill armorSkill = dao.getSkillByKey(skillKey);
                if (armorSkill == null || !dao.hasSkill(charId, armorSkill.getId())) {
                    out.println("You lack proficiency in " + armorCat.getDisplayName() + " armor to equip " + ClientHandler.getItemDisplayName(matched) + ".");
                    return true;
                }
            }
        }

        // Check shield proficiency requirement
        if (matched.template.isShield()) {
            Skill shieldSkill = dao.getSkillByKey("shields");
            if (shieldSkill == null || !dao.hasSkill(charId, shieldSkill.getId())) {
                out.println("You lack proficiency with shields to equip " + ClientHandler.getItemDisplayName(matched) + ".");
                return true;
            }
        }

        // Check item level requirement
        if (matched.instance.itemLevel > dao.getPlayerLevel(charId) && dao.getCharacterFlag(charId,"s_gm") != "1") {
            out.println("You must be at least level " + matched.instance.itemLevel + " to equip " + ClientHandler.getItemDisplayName(matched) + ".");
            return true;
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
                    removedItems.add(ClientHandler.getItemDisplayName(inst, tmpl));
                }
                dao.setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
            }
            // Clear off hand if occupied
            Long offHandItem = equippedMap.get(EquipmentSlot.OFF_HAND.id);
            if (offHandItem != null) {
                ItemInstance inst = itemDao.getInstance(offHandItem);
                if (inst != null) {
                    ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                    removedItems.add(ClientHandler.getItemDisplayName(inst, tmpl));
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
                        removedItems.add(ClientHandler.getItemDisplayName(mainInst, mainTmpl));
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
                    removedItems.add(ClientHandler.getItemDisplayName(curInst, curTmpl));
                }
                dao.setCharacterEquipment(charId, slot.id, null);
            }
        }

        // Equip the new item to main hand
        boolean equipped = dao.setCharacterEquipment(charId, slot.id, matched.instance.instanceId);
        if (!equipped) {
            out.println("Failed to equip " + ClientHandler.getItemDisplayName(matched) + ".");
            return true;
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
            out.println("You remove " + removedStr + " and equip " + ClientHandler.getItemDisplayName(matched) + " (" + slotDisplay + ").");
        } else {
            out.println("You equip " + ClientHandler.getItemDisplayName(matched) + " (" + slotDisplay + ").");
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
        return true;
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
        ItemDAO itemDao = new ItemDAO();
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
        
        // Delete the item
        boolean deleted = itemDao.deleteInstance(matched.instance.instanceId);
        if (!deleted) {
            out.println("Failed to sacrifice " + itemDisplayName + ".");
            return true;
        }
        
        // Grant 1 XP using CharacterClassDAO
        CharacterClassDAO classDao = new CharacterClassDAO();
        boolean leveledUp = classDao.addXpToCurrentClass(charId, 1);
        
        // Announce to room
        out.println("You sacrifice " + itemDisplayName + " to the gods.");
        out.println("The gods grant you 1 experience point.");
        ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " sacrifices " + itemDisplayName + " to the gods.");
        
        // Handle level-up if it occurred
        if (leveledUp) {
            Integer classId = classDao.getCharacterCurrentClassId(charId);
            if (classId != null) {
                int newLevel = classDao.getCharacterClassLevel(charId, classId);
                out.println("You have reached level " + newLevel + "!");
                final int charIdFinal = charId;
                classDao.processLevelUp(charId, newLevel, msg -> ClientHandler.sendToCharacter(charIdFinal, msg));
            }
        }
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
        
        ItemDAO itemDao = new ItemDAO();
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
        ItemDAO itemDao = new ItemDAO();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
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
        ItemDAO itemDao = new ItemDAO();
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
                    long goldTaken = itemDao.takeGoldContents(matchedContainer.instance.instanceId);
                    dao.addGold(charId, goldTaken);
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
                // Take gold first
                if (containerGold > 0) {
                    long goldTaken = itemDao.takeGoldContents(matchedContainer.instance.instanceId);
                    dao.addGold(charId, goldTaken);
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

    private boolean handleSellCommand(CommandContext ctx) {
        // SELL <item> [quantity] - sell an item to a shopkeeper (half value)
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String sellArgs = ctx.getArgs();

        if (rec == null || rec.currentRoom == null) {
            out.println("You must be in a room to sell items.");
            return true;
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
            return true;
        }
        
        // Get player's inventory
        ItemDAO itemDao = new ItemDAO();
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get equipped items to exclude
        java.util.Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
        java.util.Set<Long> equippedInstanceIds = new java.util.HashSet<>();
        for (Long iid : equippedMap.values()) {
            if (iid != null) equippedInstanceIds.add(iid);
        }
        Boolean soldJunk = false;
        if (rec.autojunk || sellArgs.equalsIgnoreCase("junk") || sellArgs.equalsIgnoreCase("trash")) {
            
            // Get all items in inventory (not equipped, not in containers)
            java.util.List<ItemDAO.RoomItem> allItems = itemDao.getItemsByCharacter(charId);
            java.util.List<ItemDAO.RoomItem> inventoryToJunkItems = new java.util.ArrayList<>();
            Integer sellGold = 0;
            for (ItemDAO.RoomItem ri : allItems) {
                if (equippedInstanceIds.contains(ri.instance.instanceId)) continue;
                if (ri.template.types.contains("trash")) {
                    inventoryToJunkItems.add(ri);
                    sellGold += 1;
                }
            }
            
            if (!inventoryToJunkItems.isEmpty()) {
                // Delete items and add gold
                soldJunk = true;
                for (ItemDAO.RoomItem ri : inventoryToJunkItems) {
                    itemDao.deleteInstance(ri.instance.instanceId);
                    out.println("Sold " + ri.instance.getEffectiveName(ri.template));
                }
                dao.addGold(charId, sellGold);
                out.println("Sold all junk items for "+ sellGold + " gp");
            }
        }
       
        // stop here if we were just selling junk
        if (sellArgs.equalsIgnoreCase("junk") || sellArgs.equalsIgnoreCase("trash")) {
            if (!soldJunk) out.println("You have no more junk left to sell.");
            return true;
        }
        
        if (sellArgs == null || sellArgs.trim().isEmpty())  {
            if (!soldJunk) out.println("Usage: sell <item> [quantity]");
            return true;
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
                    return true;
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
            return true;
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
            return true;
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
        
        String itemName = ClientHandler.getItemDisplayName(soldItem);
        if (actualQuantity == 1) {
            out.println("You sell " + itemName + " for " + String.format("%,d", totalGold) + " gp.");
        } else {
            out.println("You sell " + actualQuantity + " " + itemName + " for " + String.format("%,d", totalGold) + " gp.");
        }
        
        if (actualQuantity < quantity) {
            out.println("(You only had " + actualQuantity + " to sell.)");
        }
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
        ItemDAO itemDao = new ItemDAO();
        
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get equipped item IDs so we don't try to quaff equipped items
        Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
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
        String effectId = matched.template.spellEffectId1;
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
        ItemDAO itemDao = new ItemDAO();
        
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get equipped item IDs
        Map<Integer, Long> equippedMap = dao.getEquipmentMapByCharacterId(charId);
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
            Spell spell = dao.getSpellById(spellId);
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
     * Uses priority: exact match > word match > keyword match > prefix > contains
     */
    public static ItemDAO.RoomItem findMatchingItem(List<ItemDAO.RoomItem> items, String searchTerm) {
        if (items == null || items.isEmpty() || searchTerm == null) return null;
        
        String searchLower = searchTerm.toLowerCase();
        
        // Priority 1: Exact name match
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.name != null && ri.template.name.equalsIgnoreCase(searchTerm)) {
                return ri;
            }
        }

        // Priority 2: Word match
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.name != null) {
                String[] nameWords = ri.template.name.toLowerCase().split("\\s+");
                for (String w : nameWords) {
                    if (w.equals(searchLower) || w.startsWith(searchLower)) {
                        return ri;
                    }
                }
            }
        }

        // Priority 3: Keyword match
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.keywords != null) {
                for (String kw : ri.template.keywords) {
                    if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) {
                        return ri;
                    }
                }
            }
        }

        // Priority 4: Name starts with search
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.name != null && ri.template.name.toLowerCase().startsWith(searchLower)) {
                return ri;
            }
        }

        // Priority 5: Name contains search
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.name != null && ri.template.name.toLowerCase().contains(searchLower)) {
                return ri;
            }
        }

        return null;
    }
}
