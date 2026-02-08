package com.example.tassmud.net.commands;

import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Skill;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;

/**
 * Delegate handler for equipment-related commands (equip, remove).
 * Extracted from ItemCommandHandler to reduce file size.
 * This is NOT a standalone CommandHandler — ItemCommandHandler delegates to these methods.
 */
class EquipmentCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(EquipmentCommandHandler.class);

    boolean handleRemoveCommand(CommandContext ctx) {
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
        ItemDAO itemDao = DaoProvider.items();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get currently equipped items
        java.util.Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);
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
            DaoProvider.equipment().setCharacterEquipment(charId, slotMatch.id, null);
            // For two-handed weapons, clear both slots
            if (isTwoHanded) {
                DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
                DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
            }
            DaoProvider.equipment().recalculateEquipmentBonuses(charId, itemDao);
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
        
        DaoProvider.equipment().setCharacterEquipment(charId, matchedSlotId, null);
        // For two-handed weapons, clear both slots
        if (isTwoHanded) {
            DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
            DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
            slotName = "Both Hands";
        }
        DaoProvider.equipment().recalculateEquipmentBonuses(charId, itemDao);
        rec = dao.findByName(name);

        out.println("You remove " + ClientHandler.getItemDisplayName(matchedInstance, matchedTemplate) + " (" + slotName + ").");
        if (matchedTemplate.armorSaveBonus != 0 || matchedTemplate.fortSaveBonus != 0 || 
            matchedTemplate.refSaveBonus != 0 || matchedTemplate.willSaveBonus != 0) {
            out.println("  Saves: Armor " + rec.getArmorTotal() + ", Fort " + rec.getFortitudeTotal() + ", Ref " + rec.getReflexTotal() + ", Will " + rec.getWillTotal());
        }
        return true;
    }

    boolean handleEquipCommand(CommandContext ctx) {
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
        ItemDAO itemDao = DaoProvider.items();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) {
            out.println("Failed to locate your character record.");
            return true;
        }

        // Get currently equipped items
        java.util.Map<Integer, Long> equippedMap = DaoProvider.equipment().getEquipmentMapByCharacterId(charId);

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
                Skill armorSkill = DaoProvider.skills().getSkillByKey(skillKey);
                if (armorSkill == null || !DaoProvider.skills().hasSkill(charId, armorSkill.getId())) {
                    out.println("You lack proficiency in " + armorCat.getDisplayName() + " armor to equip " + ClientHandler.getItemDisplayName(matched) + ".");
                    return true;
                }
            }
        }

        // Check shield proficiency requirement
        if (matched.template.isShield()) {
            Skill shieldSkill = DaoProvider.skills().getSkillByKey("shields");
            if (shieldSkill == null || !DaoProvider.skills().hasSkill(charId, shieldSkill.getId())) {
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
                DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
            }
            // Clear off hand if occupied
            Long offHandItem = equippedMap.get(EquipmentSlot.OFF_HAND.id);
            if (offHandItem != null) {
                ItemInstance inst = itemDao.getInstance(offHandItem);
                if (inst != null) {
                    ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
                    removedItems.add(ClientHandler.getItemDisplayName(inst, tmpl));
                }
                DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
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
                        DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.MAIN_HAND.id, null);
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
                        DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, null);
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
                DaoProvider.equipment().setCharacterEquipment(charId, slot.id, null);
            }
        }

        // Equip the new item to main hand
        boolean equipped = DaoProvider.equipment().setCharacterEquipment(charId, slot.id, matched.instance.instanceId);
        if (!equipped) {
            out.println("Failed to equip " + ClientHandler.getItemDisplayName(matched) + ".");
            return true;
        }
        
        // For two-handed weapons, also mark off-hand as occupied (same instance)
        if (isTwoHanded) {
            DaoProvider.equipment().setCharacterEquipment(charId, EquipmentSlot.OFF_HAND.id, matched.instance.instanceId);
        }

        // Recalculate and persist equipment bonuses
        DaoProvider.equipment().recalculateEquipmentBonuses(charId, itemDao);

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
}
