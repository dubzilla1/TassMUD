package com.example.tassmud.net.commands;

import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.effect.EffectInstance;
import com.example.tassmud.effect.EffectRegistry;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;

import java.io.PrintWriter;
import java.util.*;

/**
 * Handles ITEMS category commands: get, drop, put, equip, remove, quaff, use, list, buy, sell
 * 
 * This class extracts item-related command logic from ClientHandler to reduce
 * method size and improve maintainability.
 */
public class ItemCommandHandler implements CommandHandler {
    
    private static final Set<String> SUPPORTED_COMMANDS = Set.of(
        "quaff", "drink", "use"
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
            case "use":
                return handleUse(ctx);
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
     * Handle the use command - use a magical item to cast its spell.
     * Priority: equipped items > inventory > room items
     * Spells are cast at 100% proficiency using the item's level.
     */
    private boolean handleUse(CommandContext ctx) {
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
