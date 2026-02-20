package com.example.tassmud.net.commands;


import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.TransactionManager;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.Shop;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.persistence.ShopDAO;

import java.io.PrintWriter;
import java.util.logging.Logger;

/**
 * Delegate handler for shop-related commands: buy, list, sell.
 * Extracted from ItemCommandHandler to reduce file size.
 * This is NOT a standalone CommandHandler — ItemCommandHandler calls these methods directly.
 */
class ShopCommandHandler {

    private static final Logger LOG = Logger.getLogger(ShopCommandHandler.class.getName());

    boolean handleBuyCommand(CommandContext ctx) {
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
        MobileDAO mobileDao = DaoProvider.mobiles();
        ShopDAO shopDao = DaoProvider.shops();
        ItemDAO itemDao = DaoProvider.items();
        
        java.util.List<Mobile> mobsInRoom = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(rec.currentRoom);
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
        ItemTemplate matchedItem = com.example.tassmud.util.ItemMatchingService.findMatchingTemplate(availableItems, itemSearchStr);
        
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
            out.println("You need " + "%,d".formatted(totalCost) + " gp to buy " + 
                (quantity > 1 ? quantity + " " + matchedItem.name : matchedItem.name) + 
                ", but you only have " + "%,d".formatted(playerGold) + " gp.");
            return true;
        }
        
        // Subtract gold and add items (atomic transaction)
        final int qty = quantity;
        final int templateId = matchedItem.id;
        TransactionManager.runInTransaction(() -> {
            dao.addGold(charId, -totalCost);
            for (int i = 0; i < qty; i++) {
                itemDao.createInstance(templateId, null, charId);
            }
        });
        
        String itemName = matchedItem.name != null ? matchedItem.name : "an item";
        if (quantity == 1) {
            out.println("You buy " + itemName + " for " + "%,d".formatted(totalCost) + " gp.");
        } else {
            out.println("You buy " + quantity + " " + itemName + " for " + "%,d".formatted(totalCost) + " gp.");
        }
        return true;
    }

    boolean handleListCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        
        // LIST - show items for sale from shopkeepers in the room
        if (rec == null || rec.currentRoom == null) {
            out.println("You must be in a room to see what's for sale.");
            return true;
        }
        
        // Find all shopkeeper mobs in the room
        MobileDAO mobileDao = DaoProvider.mobiles();
        ShopDAO shopDao = DaoProvider.shops();
        ItemDAO itemDao = DaoProvider.items();
        
        java.util.List<Mobile> mobsInRoom = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(rec.currentRoom);
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
            out.println("  %-40s %,d gp".formatted(itemName, item.value));
        }
        return true;
    }

    boolean handleSellCommand(CommandContext ctx) {
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
        MobileDAO mobileDao = DaoProvider.mobiles();
        java.util.List<Mobile> mobsInRoom = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(rec.currentRoom);
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
        ItemDAO itemDao = DaoProvider.items();
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }
        
        // Get equipped items to exclude
        java.util.Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);
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
                // Delete items and add gold (atomic transaction)
                soldJunk = true;
                final int junkGold = sellGold;
                TransactionManager.runInTransaction(() -> {
                    for (ItemDAO.RoomItem ri : inventoryToJunkItems) {
                        itemDao.deleteInstance(ri.instance.instanceId);
                    }
                    dao.addGold(charId, junkGold);
                });
                for (ItemDAO.RoomItem ri : inventoryToJunkItems) {
                    out.println("Sold " + ri.instance.getEffectiveName(ri.template));
                }
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
        
        // Delete items and add gold (atomic transaction)
        TransactionManager.runInTransaction(() -> {
            for (ItemDAO.RoomItem ri : toSell) {
                itemDao.deleteInstance(ri.instance.instanceId);
            }
            dao.addGold(charId, totalGold);
        });
        
        String itemName = ClientHandler.getItemDisplayName(soldItem);
        if (actualQuantity == 1) {
            out.println("You sell " + itemName + " for " + "%,d".formatted(totalGold) + " gp.");
        } else {
            out.println("You sell " + actualQuantity + " " + itemName + " for " + "%,d".formatted(totalGold) + " gp.");
        }
        
        if (actualQuantity < quantity) {
            out.println("(You only had " + actualQuantity + " to sell.)");
        }
                return true;
    }
}
