package com.example.tassmud.net.commands;

import java.io.PrintWriter;

import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegate for GM informational/lookup commands extracted from GmCommandHandler.
 * Contains: mstat, istat, ilist, mlist, mfind, ifind, gmchat, genmap, debug, dbinfo.
 */
class GmInfoHandler {

    private static final Logger logger = LoggerFactory.getLogger(GmInfoHandler.class);

    boolean handleMstatCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterRecord rec = ctx.character;
        // GM-only: MSTAT <mobile> - Show detailed stats of a mobile in the room
        if (!ensureGm(ctx)) return true;
        String mstatArgs = ctx.getArgs();
        if (mstatArgs == null || mstatArgs.trim().isEmpty()) {
            out.println("Usage: MSTAT <mobile>");
            out.println("  Shows detailed stat block for a mobile in your current room.");
            return true;
        }
        String mstatSearch = mstatArgs.trim().toLowerCase();

        Mobile matchedMob = com.example.tassmud.util.MobileMatchingService.findInRoomFuzzy(rec.currentRoom, mstatSearch);
        if (matchedMob == null) { out.println("No mobile '" + mstatSearch + "' found in this room."); return true; }

        MobileDAO mstatMobDao = DaoProvider.mobiles();
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
        return true;
    }

    boolean handleIstatCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;
        // GM-only: ISTAT <item> - Show detailed stats of an inventory item
        if (!ensureGm(ctx)) return true;
        String istatArgs = ctx.getArgs();
        if (istatArgs == null || istatArgs.trim().isEmpty()) {
            out.println("Usage: ISTAT <item>");
            out.println("  Shows detailed stat block for an item in your inventory.");
            return true;
        }
        String istatSearch = istatArgs.trim().toLowerCase();
        Integer charId = dao.getCharacterIdByName(name);
        if (charId == null) { out.println("Failed to locate your character record."); return true; }
        ItemDAO istatItemDao = DaoProvider.items();
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

        if (matchedItem == null) { out.println("You don't have '" + istatSearch + "' in your inventory."); return true; }

        ItemInstance inst = matchedItem.instance;
        ItemTemplate tmpl = matchedItem.template;
        String displayName = ClientHandler.getItemDisplayName(matchedItem);

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

        java.util.List<String> spellEffects = inst.getEffectiveSpellEffects(tmpl);
        if (!spellEffects.isEmpty()) {
            out.println("--- SPELL EFFECTS ---");
            boolean hasOverrides = !inst.spellEffectOverrides.isEmpty();
            for (int i = 0; i < spellEffects.size(); i++) {
                out.println("  Effect " + (i + 1) + ": " + spellEffects.get(i) + (hasOverrides ? " (override)" : ""));
            }
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
        return true;
    }

    boolean handleIlistCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        // GM-only: ILIST <search_string>
        // Finds all item templates matching the given string
        if (!ensureGm(ctx)) return true;
        String ilistArgs = ctx.getArgs();
        if (ilistArgs == null || ilistArgs.trim().isEmpty()) {
            out.println("Usage: ILIST <search_string>");
            out.println("  Searches item templates by name.");
            return true;
        }
        String searchStr = ilistArgs.trim();
        ItemDAO ilistItemDao = DaoProvider.items();
        java.util.List<ItemTemplate> matches = ilistItemDao.searchItemTemplates(searchStr);
        if (matches.isEmpty()) {
            out.println("No item templates found matching '" + searchStr + "'.");
            return true;
        }
        out.println();
        out.println(String.format("%-6s %-25s %-12s %s", "ID", "Name", "Type", "Description"));
        out.println(ClientHandler.repeat("-", 75));
        for (ItemTemplate t : matches) {
            String typeName = t.types != null && !t.types.isEmpty() ? String.join(",", t.types) : "";
            String desc = t.description != null ? ClientHandler.truncate(t.description, 28) : "";
            out.println(String.format("%-6d %-25s %-12s %s",
                t.id,
                ClientHandler.truncate(t.name, 25),
                ClientHandler.truncate(typeName, 12),
                desc));
        }
        out.println(ClientHandler.repeat("-", 75));
        out.println(matches.size() + " item(s) found.");
        out.println();
        return true;
    }

    boolean handleMlistCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        // GM-only: MLIST <search_string>
        // Finds all mobile templates matching the given string
        if (!ensureGm(ctx)) return true;
        String mlistArgs = ctx.getArgs();
        if (mlistArgs == null || mlistArgs.trim().isEmpty()) {
            out.println("Usage: MLIST <search_string>");
            out.println("  Searches mobile templates by name.");
            return true;
        }
        String searchStr = mlistArgs.trim();
        MobileDAO mlistMobDao = DaoProvider.mobiles();
        java.util.List<MobileTemplate> matches = mlistMobDao.searchTemplates(searchStr);
        if (matches.isEmpty()) {
            out.println("No mobile templates found matching '" + searchStr + "'.");
            return true;
        }
        out.println();
        out.println(String.format("%-6s %-25s %-6s %s", "ID", "Name", "Lvl", "ShortDesc"));
        out.println(ClientHandler.repeat("-", 80));
        for (MobileTemplate t : matches) {
            String shortDesc = t.getShortDesc() != null ? ClientHandler.truncate(t.getShortDesc(), 40) : "";
            out.println(String.format("%-6d %-25s %-6d %s",
                t.getId(),
                ClientHandler.truncate(t.getName(), 25),
                t.getLevel(),
                shortDesc));
        }
        out.println(ClientHandler.repeat("-", 80));
        out.println(matches.size() + " mobile(s) found.");
        out.println();
        return true;
    }

    boolean handleMfindCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        // GM-only: MFIND <template_id>
        if (!ensureGm(ctx)) return true;
        String mfindArgs = ctx.getArgs();
        if (mfindArgs == null || mfindArgs.trim().isEmpty()) {
            out.println("Usage: MFIND <template_id>");
            out.println("  Finds mobile instances by template ID and shows the room they're in.");
            return true;
        }
        int tplId;
        try {
            tplId = Integer.parseInt(mfindArgs.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            out.println("Invalid template ID. Must be a number.");
            return true;
        }

        MobileDAO mfindDao = DaoProvider.mobiles();
        MobileTemplate tpl = mfindDao.getTemplateById(tplId);
        String tplName = tpl != null ? tpl.getName() : ("Template#" + tplId);

        java.util.List<Mobile> instances = mfindDao.findInstancesByTemplateId(tplId);
        if (instances.isEmpty()) {
            out.println("No mobile instances of template #" + tplId + " (" + tplName + ") found.");
            return true;
        }

        out.println();
        out.println("=== Instances of #" + tplId + " (" + tplName + ") ===");
        out.println();
        out.println(String.format("  %-10s %-6s %-20s %-6s %s", "InstID", "TplID", "Name", "RoomID", "Room Name"));
        for (Mobile m : instances) {
            Integer roomId = m.getCurrentRoom();
            String roomName = "(no room)";
            if (roomId != null) {
                Room r = DaoProvider.rooms().getRoomById(roomId);
                roomName = r != null ? r.getName() : "Unknown";
            }
            out.println(String.format("  %-10d %-6d %-20s %-6s %s",
                m.getInstanceId(), m.getTemplateId(), ClientHandler.truncate(m.getName(), 20),
                roomId != null ? String.valueOf(roomId) : "-", ClientHandler.truncate(roomName, 30)));
        }
        out.println();
        out.println("Total: " + instances.size() + " instance(s) found.");
        out.println();
        return true;
    }

    boolean handleIfindCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;

        // GM-only: IFIND <template_id> [world|char|bags|all]
        // Finds all instances of an item template in the game
        if (!ensureGm(ctx)) return true;
        String ifindArgs = ctx.getArgs();
        if (ifindArgs == null || ifindArgs.trim().isEmpty()) {
            out.println("Usage: IFIND <template_id> [world|char|bags|all]");
            out.println("  Finds all instances of a given item template.");
            out.println("  world = items in rooms, char = in character inventories,");
            out.println("  bags = in containers, all = all locations (default)");
            return true;
        }
        String[] ifindParts = ifindArgs.trim().split("\\s+");
        int ifindTemplateId;
        try {
            ifindTemplateId = Integer.parseInt(ifindParts[0]);
        } catch (NumberFormatException e) {
            out.println("Invalid template ID. Must be a number.");
            return true;
        }
        String scope = ifindParts.length >= 2 ? ifindParts[1].toLowerCase() : "all";
        if (!scope.equals("world") && !scope.equals("char") && !scope.equals("bags") && !scope.equals("all")) {
            out.println("Invalid scope. Use: world, char, bags, or all");
            return true;
        }
        
        ItemDAO ifindItemDao = DaoProvider.items();
        ItemTemplate ifindTemplate = ifindItemDao.getTemplateById(ifindTemplateId);
        String templateName = ifindTemplate != null ? ifindTemplate.name : "Unknown";
        
        java.util.List<ItemInstance> allInstances = ifindItemDao.findInstancesByTemplateId(ifindTemplateId);
        if (allInstances.isEmpty()) {
            out.println("No instances of template #" + ifindTemplateId + " (" + templateName + ") found.");
            return true;
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
                    Room rm = DaoProvider.rooms().getRoomById(inst.locationRoomId);
                    String roomName = rm != null ? rm.getName() : "Unknown";
                    out.println(String.format("  %-10d %-6d %-20s %-6d %s",
                        inst.instanceId, inst.templateId, ClientHandler.truncate(templateName, 20),
                        inst.locationRoomId, ClientHandler.truncate(roomName, 25)));
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
                    Room charRoom = charRoomId != null ? DaoProvider.rooms().getRoomById(charRoomId) : null;
                    String charRoomName = charRoom != null ? charRoom.getName() : "Unknown";
                    out.println(String.format("  %-10d %-6d %-20s %-12s %-6s %s",
                        inst.instanceId, inst.templateId, ClientHandler.truncate(templateName, 20),
                        ClientHandler.truncate(charName, 12),
                        charRoomId != null ? String.valueOf(charRoomId) : "?",
                        ClientHandler.truncate(charRoomName, 20)));
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
                        inst.instanceId, inst.templateId, ClientHandler.truncate(templateName, 20),
                        inst.containerInstanceId, containerId, ClientHandler.truncate(containerName, 20)));
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
        return true;
    }

    boolean handleGmchatCommand(CommandContext ctx) {
        if (!ensureGm(ctx)) return true;
        return CommunicationCommandHandler.handleCommunication(ctx);
    }

    boolean handleGenmapCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterDAO.CharacterRecord rec = ctx.character;
        // GM-only: generate ASCII map for an area
        if (!ensureGm(ctx)) return true;
        String mapArgs = ctx.getArgs();
        int targetAreaId;
        if (mapArgs == null || mapArgs.trim().isEmpty()) {
            // Use current room's area
            if (rec == null || rec.currentRoom == null) {
                out.println("Usage: genmap [areaId]");
                return true;
            }
            com.example.tassmud.model.Room currentRoom = DaoProvider.rooms().getRoomById(rec.currentRoom);
            if (currentRoom == null) {
                out.println("Could not determine your current area.");
                return true;
            }
            targetAreaId = currentRoom.getAreaId();
        } else {
            try {
                targetAreaId = Integer.parseInt(mapArgs.trim());
            } catch (NumberFormatException e) {
                out.println("Invalid area ID: " + mapArgs);
                return true;
            }
        }

        // Generate the map
        String mapResult = com.example.tassmud.tools.MapGenerator.generateMapForAreaInGame(targetAreaId);
        if (mapResult != null) {
            out.println(mapResult);
        } else {
            out.println("Failed to generate map for area " + targetAreaId);
        }
        return true;
    }

    boolean handleDebugCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        // GM-only: toggle debug channel output
        if (!ensureGm(ctx)) return true;
        ctx.handler.debugChannelEnabled = !ctx.handler.debugChannelEnabled;
        if (ctx.handler.debugChannelEnabled) {
            out.println("Debug channel is now ON. You will see [DEBUG] messages.");
        } else {
            out.println("Debug channel is now OFF.");
        }
        return true;
}

    boolean handleDbinfoCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        // GM-only: prints table schema information
        if (!ensureGm(ctx)) return true;
        // Ensure item tables/migrations are applied by constructing ItemDAO
        try { DaoProvider.items(); } catch (Exception ignored) {}

        String targs = ctx.getArgs();
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
        return true;
    }

    // --- Utility ---

    static boolean ensureGm(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

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
}
