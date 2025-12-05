package com.example.tassmud.net.commands;

import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;

import java.io.PrintWriter;
import java.util.*;

/**
 * Handles ITEMS category commands: get, drop, put, equip, remove, quaff, list, buy, sell
 * 
 * This class extracts item-related command logic from ClientHandler to reduce
 * method size and improve maintainability.
 */
public class ItemCommandHandler implements CommandHandler {
    
    private static final Set<String> SUPPORTED_COMMANDS = Set.of(
        "quaff", "drink"
        // Future: "get", "drop", "put", "equip", "remove", "list", "buy", "sell"
    );
    
    @Override
    public boolean supports(String commandName) {
        return SUPPORTED_COMMANDS.contains(commandName);
    }
    
    @Override
    public boolean handle(CommandContext ctx) {
        String cmdName = ctx.getCommandName();
        
        switch (cmdName) {
            case "quaff":
            case "drink":
                return handleQuaff(ctx);
            default:
                return false;
        }
    }
    
    /**
     * Handle the quaff/drink command - consume a potion from inventory.
     */
    private boolean handleQuaff(CommandContext ctx) {
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
