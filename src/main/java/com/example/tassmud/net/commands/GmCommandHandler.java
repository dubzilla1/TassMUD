package com.example.tassmud.net.commands;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Spell;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.CommandDefinition.Category;
import com.example.tassmud.net.CommandRegistry;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.LootGenerator;

/**
 * Delegates GM commands to ClientHandler.handleGmCommand
 * NOTE: Only list commands that are actually implemented in handleGmCommand().
 * Handled commands include cflag, cset, cskill, cspell, dbinfo, debug, genmap, gmchat,
 * gminvis, goto, ifind, ilist, istat, mstat, peace, promote, restore, slay, spawn, system.
 */
public class GmCommandHandler implements CommandHandler {

private static final Set<String> SUPPORTED_COMMANDS = CommandRegistry.getCommandsByCategory(Category.GM).stream()
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
            case "cflag": return handleCflagCommand(ctx);
            case "cset": return handleCsetCommand(ctx);
            case "cskill": return handleCskillCommand(ctx);
            case "cspell": return handleCspellCommand(ctx);
            case "dbinfo": return handleDbinfoCommand(ctx);
            case "debug": return handleDebugCommand(ctx);
            case "genmap": return handleGenmapCommand(ctx);
            case "gmchat": return handleGmchatCommand(ctx);
            case "gminvis": return handleGminvisCommand(ctx);
            case "goto": return handleGotoCommand(ctx);
            case "ifind": return handleIfindCommand(ctx);
            case "ilist": return handleIlistCommand(ctx);
            case "istat": return handleIstatCommand(ctx);
            case "mstat": return handleMstatCommand(ctx);
            case "peace": return handlePeaceCommand(ctx);
            case "promote": return handlePromoteCommand(ctx);
            case "restore": return handleRestoreCommand(ctx);
            case "slay": return handleSlayCommand(ctx);
            case "spawn": return handleSpawnCommand(ctx);
            case "system": return handleSystemCommand(ctx);
            default:
                return false;
        }
    }

    private boolean handleGminvisCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;


        // GM-only: toggle perfect invisibility
        if (!ensureGm(ctx)) return true;
        ctx.handler.gmInvisible = !ctx.handler.gmInvisible;
        if (ctx.handler.gmInvisible) {
            out.println("You fade into the shadows, becoming invisible to mortals.");
            out.println("GM Invisibility is now ON. Only other GMs can see you.");
            out.println("You will not appear in the who list, room descriptions, or be visible to mobs.");
            // Notify other GMs
            for (ClientHandler s : ClientHandler.sessions) {
                if (s != ctx.handler && s.playerName != null && dao.isCharacterFlagTrueByName(s.playerName, "is_gm")) {
                    s.sendRaw("[GM] " + ctx.playerName + " has gone GM-invisible.");
                }
            }
        } else {
            out.println("You step out of the shadows, becoming visible once more.");
            out.println("GM Invisibility is now OFF.");
            // Notify other GMs
            for (ClientHandler s : ClientHandler.sessions) {
                if (s != ctx.handler && s.playerName != null && dao.isCharacterFlagTrueByName(s.playerName, "is_gm")) {
                    s.sendRaw("[GM] " + ctx.playerName + " is no longer GM-invisible.");
                }
            }
        }
        return true;
    }

    private boolean handleSystemCommand(CommandContext ctx) {
        String t = ctx.getArgs();
        if (t == null || t.trim().isEmpty()) { ctx.out.println("Usage: system <message>"); return true; }
        // system messages are broadcast to all
        ClientHandler.broadcastAll("[system] " + t);
        return true;
    }

    private boolean handleSpawnCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = ctx.character;
        String name = ctx.playerName;
        Integer characterId = ctx.characterId;
        // GM-only: SPAWN ITEM <template_id> [level]   or   SPAWN MOB <mob_id> [room_id]   or   SPAWN GOLD <amount>
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use spawn.");
            return true;
        }
        String spawnArgs = ctx.getArgs();
        if (spawnArgs == null || spawnArgs.trim().isEmpty()) {
            out.println("Usage: SPAWN ITEM <template_id> [level]  - Spawn item with random stats scaled to level");
            out.println("       SPAWN MOB <template_id> [room_id]");
            out.println("       SPAWN GOLD <amount>");
            return true;
        }
        String[] sp = spawnArgs.trim().split("\\s+");
        if (sp.length < 2) {
            out.println("Usage: SPAWN ITEM <template_id> [level]  - Spawn item with random stats scaled to level");
            out.println("       SPAWN MOB <template_id> [room_id]");
            out.println("       SPAWN GOLD <amount>");
            return true;
        }
        String spawnType = sp[0].toUpperCase();
        if (spawnType.equals("ITEM")) {
            // SPAWN ITEM <template_id> [level] - Spawn item with loot-generated stats
            int templateId;
            try {
                templateId = Integer.parseInt(sp[1]);
            } catch (NumberFormatException e) {
                out.println("Invalid template ID: " + sp[1]);
                return true;
            }
            
            Integer targetRoomId = rec != null ? rec.currentRoom : null;
            if (targetRoomId == null) {
                out.println("You must be in a room to spawn items.");
                return true;
            }
            
            // Validate template exists
            ItemDAO itemDao = new ItemDAO();
            ItemTemplate tmpl = itemDao.getTemplateById(templateId);
            if (tmpl == null) {
                out.println("No item template found with ID " + templateId);
                return true;
            }
            
            // Determine level: use optional arg, otherwise use character's class level
            CharacterClassDAO spawnClassDao = new CharacterClassDAO();
            int spawnLevel = (rec != null && rec.currentClassId != null) 
                ? spawnClassDao.getCharacterClassLevel(characterId, rec.currentClassId) : 1;
            if (sp.length >= 3) {
                try {
                    spawnLevel = Integer.parseInt(sp[2]);
                    if (spawnLevel < 1) spawnLevel = 1;
                    if (spawnLevel > 55) spawnLevel = 55;
                } catch (NumberFormatException e) {
                    out.println("Invalid level: " + sp[2] + ". Using your level (" + spawnLevel + ").");
                }
            }
            
            // Generate item with level-scaled stats using LootGenerator
            long instanceId = LootGenerator.generateItemFromTemplateInRoom(templateId, spawnLevel, targetRoomId, itemDao);
            if (instanceId < 0) {
                out.println("Failed to create item instance.");
                return true;
            }
            
            String itemName = tmpl.name != null ? tmpl.name : "item #" + templateId;
            boolean isWeapon = tmpl.hasType("weapon");
            boolean isArmor = tmpl.hasType("armor") || tmpl.hasType("shield");
            String typeNote = isWeapon ? "weapon" : (isArmor ? "armor" : "item");
            out.println("Spawned " + itemName + " (instance #" + instanceId + ", level " + spawnLevel + " " + typeNote + ") in current room.");
            if (isWeapon || isArmor) {
                out.println("Stats scaled to level " + spawnLevel + ". Use ISTAT to inspect.");
            }
        } else if (spawnType.equals("MOB")) {
            int mobTemplateId;
            try {
                mobTemplateId = Integer.parseInt(sp[1]);
            } catch (NumberFormatException e) {
                out.println("Invalid mob template ID: " + sp[1]);
                return true;
            }
            Integer targetRoomId = rec != null ? rec.currentRoom : null;
            if (sp.length >= 3) {
                try {
                    targetRoomId = Integer.parseInt(sp[2]);
                } catch (NumberFormatException e) {
                    out.println("Invalid room ID: " + sp[2]);
                    return true;
                }
            }
            if (targetRoomId == null) {
                out.println("No room specified and you are not in a room.");
                return true;
            }
            // Validate template exists
            MobileDAO mobDao = new MobileDAO();
            MobileTemplate mobTemplate = mobDao.getTemplateById(mobTemplateId);
            if (mobTemplate == null) {
                out.println("No mob template found with ID " + mobTemplateId);
                return true;
            }
            // Spawn the mobile instance in the target room
            Mobile spawnedMob = mobDao.spawnMobile(mobTemplate, targetRoomId);
            if (spawnedMob == null) {
                out.println("Failed to spawn mobile instance.");
                return true;
            }
            out.println("Spawned " + spawnedMob.getName() + " (instance #" + spawnedMob.getInstanceId() + ") in room " + targetRoomId + ".");
        } else if (spawnType.equals("GOLD")) {
            // GM gold spawn - gives gold directly to the GM
            long amount;
            try {
                amount = Long.parseLong(sp[1]);
            } catch (NumberFormatException e) {
                out.println("Invalid gold amount: " + sp[1]);
                return true;
            }
            if (amount <= 0) {
                out.println("Amount must be a positive number.");
                return true;
            }
            // Cap at Long.MAX_VALUE to prevent overflow
            long currentGold = dao.getGold(characterId);
            long maxAddable = Long.MAX_VALUE - currentGold;
            if (amount > maxAddable) {
                amount = maxAddable;
                out.println("Amount capped to prevent overflow.");
            }
            if (amount == 0) {
                out.println("You already have maximum gold.");
                return true;
            }
            boolean success = dao.addGold(characterId, amount);
            if (success) {
                long newTotal = dao.getGold(characterId);
                out.println("Spawned " + amount + " gold. You now have " + newTotal + " gp.");
            } else {
                out.println("Failed to add gold.");
            }
        } else {
            out.println("Unknown spawn type: " + spawnType);
            out.println("Usage: SPAWN ITEM <template_id> [room_id]");
            out.println("       SPAWN MOB <template_id> [room_id]");
            out.println("       SPAWN GOLD <amount>");
        }
        return true;
    }

    private boolean handleSlayCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = ctx.character;
        String name = ctx.playerName;
        Integer charId = ctx.characterId;

        // GM-only: SLAY <target> - instantly kill a mob
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use slay.");
            return true;
        }
        if (rec == null || rec.currentRoom == null) {
            out.println("You must be in a room to use slay.");
            return true;
        }
        
        if (charId == null) {
            charId = dao.getCharacterIdByName(name);
        }
        
        CombatManager combatMgr = CombatManager.getInstance();
        Combat combat = combatMgr.getCombatForCharacter(charId);
        
        Mobile targetMob = null;
        String slayArgs = ctx.getArgs();
        
        if (combat != null && combatMgr.isInCombat(charId)) {
            // In combat - if no args, target current enemy
            if (slayArgs == null || slayArgs.trim().isEmpty()) {
                // Find first enemy combatant
                Combatant self = combat.findByCharacterId(charId);
                if (self != null) {
                    for (Combatant c : combat.getActiveCombatants()) {
                        if (c.getAlliance() != self.getAlliance() && c.isMobile()) {
                            targetMob = c.getMobile();
                            break;
                        }
                    }
                }
                if (targetMob == null) {
                    out.println("You have no enemy to slay.");
                    return true;
                }
            } else {
                // Search for target by name in combat
                String targetSearch = slayArgs.trim().toLowerCase();
                for (Combatant c : combat.getActiveCombatants()) {
                    if (c.isMobile() && c.getMobile() != null) {
                        Mobile mob = c.getMobile();
                        if (mob.getName().toLowerCase().contains(targetSearch)) {
                            targetMob = mob;
                            break;
                        }
                    }
                }
                if (targetMob == null) {
                    out.println("No enemy matching '" + slayArgs + "' in combat.");
                    return true;
                }
            }
            
            // Kill the mob instantly
            String mobName = targetMob.getName();
            out.println("You raise your hand and " + mobName + " is struck down by divine power!");
            ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " raises their hand and " + mobName + " is struck down by divine power!");
            
            // Find combatant and set HP to 0 - combat system will handle death
            Combatant victimCombatant = null;
            for (Combatant c : combat.getActiveCombatants()) {
                if (c.getMobile() == targetMob) {
                    victimCombatant = c;
                    break;
                }
            }
            if (victimCombatant != null) {
                GameCharacter victimChar = victimCombatant.getAsCharacter();
                if (victimChar != null) {
                    victimChar.setHpCur(0);
                }
            }
        } else {
            // Not in combat - find target in room and kill immediately
            if (slayArgs == null || slayArgs.trim().isEmpty()) {
                out.println("Usage: slay <target>");
                out.println("  Instantly kill a mob in the room.");
                return true;
            }
            
            MobileDAO mobileDao = new MobileDAO();
            List<Mobile> mobilesInRoom = mobileDao.getMobilesInRoom(rec.currentRoom);
            if (mobilesInRoom.isEmpty()) {
                out.println("There is nothing here to slay.");
                return true;
            }
            
            String targetSearch = slayArgs.trim().toLowerCase();
            for (Mobile mob : mobilesInRoom) {
                if (mob.getName().toLowerCase().contains(targetSearch)) {
                    targetMob = mob;
                    break;
                }
            }
            
            if (targetMob == null) {
                out.println("You don't see '" + slayArgs + "' here.");
                return true;
            }
            
            if (targetMob.isDead()) {
                out.println(targetMob.getName() + " is already dead.");
                return true;
            }
            
            String mobName = targetMob.getName();
            out.println("You raise your hand and " + mobName + " is struck down by divine power!");
            ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " raises their hand and " + mobName + " is struck down by divine power!");
            
            // Create corpse
            try {
                ItemDAO itemDAO = new ItemDAO();
                itemDAO.createCorpse(rec.currentRoom, mobName);
            } catch (Exception e) {
                System.err.println("[slay] Failed to create corpse: " + e.getMessage());
            }
            
            // Kill and remove the mob
            targetMob.die();
            mobileDao.deleteInstance(targetMob.getInstanceId());
        }
        return true;
    }

    private boolean handleRestoreCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // GM-only: RESTORE [character] - restore HP/MP/MV to full
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use restore.");
            return true;
        }
        String restoreArgs = ctx.getArgs();
        String targetName = (restoreArgs == null || restoreArgs.trim().isEmpty()) 
            ? name : restoreArgs.trim();
        
        CharacterDAO.CharacterRecord targetRec = dao.findByName(targetName);
        if (targetRec == null) {
            out.println("Character '" + targetName + "' not found.");
            return true;
        }
        
        // Restore to full
        dao.saveCharacterStateByName(targetName, 
            targetRec.hpMax, targetRec.mpMax, targetRec.mvMax, 
            targetRec.currentRoom);
        
        // If target is in combat, also update their combatant
        Integer targetCharId = dao.getCharacterIdByName(targetName);
        if (targetCharId != null) {
            Combat combat = CombatManager.getInstance().getCombatForCharacter(targetCharId);
            if (combat != null) {
                Combatant combatant = combat.findByCharacterId(targetCharId);
                if (combatant != null) {
                    GameCharacter c = combatant.getAsCharacter();
                    if (c != null) {
                        c.setHpCur(c.getHpMax());
                        c.setMpCur(c.getMpMax());
                        c.setMvCur(c.getMvMax());
                    }
                }
            }
        }
        
        if (targetName.equalsIgnoreCase(name)) {
            out.println("You feel fully restored!");
        } else {
            out.println("You restore " + targetRec.name + " to full health.");
            // Notify target if online
            ClientHandler targetHandler = ClientHandler.nameToSession.get(targetName.toLowerCase());
            if (targetHandler != null) {
                targetHandler.sendRaw("You feel a surge of divine energy and are fully restored!");
            }
        }
        return true;
    }

    private boolean handlePromoteCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        String name = ctx.playerName;

        // GM-only: PROMOTE <char> [level_target]
        // Levels up a character. If level_target specified, levels them up to that level.
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use promote.");
            return true;
        }
        String promoteArgs = ctx.getArgs();
        if (promoteArgs == null || promoteArgs.trim().isEmpty()) {
            out.println("Usage: PROMOTE <character> [level_target]");
            out.println("  Levels up a character once, or to the specified level.");
            return true;
        }
        
        String[] promoteParts = promoteArgs.trim().split("\\s+");
        String promoteTargetName = promoteParts[0];
        Integer targetLevel = null;
        
        if (promoteParts.length > 1) {
            try {
                targetLevel = Integer.parseInt(promoteParts[1]);
            } catch (NumberFormatException e) {
                out.println("Invalid level target: " + promoteParts[1]);
                return true;
            }
        }
        
        // Find character
        Integer promoteCharId = dao.getCharacterIdByName(promoteTargetName);
        if (promoteCharId == null) {
            out.println("Character '" + promoteTargetName + "' not found.");
            return true;
        }
        
        CharacterClassDAO classDAO = new CharacterClassDAO();
        Integer promoteClassId = classDAO.getCharacterCurrentClassId(promoteCharId);
        if (promoteClassId == null) {
            out.println(promoteTargetName + " has no class assigned.");
            return true;
        }
        
        int currentLevel = classDAO.getCharacterClassLevel(promoteCharId, promoteClassId);
        
        // Validate target level if specified
        if (targetLevel != null) {
            if (targetLevel < 1 || targetLevel > 60) {
                out.println("Level target must be between 1 and 60.");
                return true;
            }
            if (targetLevel <= currentLevel) {
                out.println(promoteTargetName + " is already level " + currentLevel + 
                    ". Cannot promote to level " + targetLevel + " (demotion not implemented).");
                return true;
            }
        } else {
            // Default: promote by one level
            targetLevel = currentLevel + 1;
            if (targetLevel > 60) {
                out.println(promoteTargetName + " is already at maximum level.");
                return true;
            }
        }
        
        // Get target handler for messaging
        ClientHandler promoteTargetHandler = ClientHandler.nameToSession.get(promoteTargetName.toLowerCase());
        java.util.function.Consumer<String> msgCallback = null;
        if (promoteTargetHandler != null) {
            final ClientHandler handler = promoteTargetHandler;
            msgCallback = msg -> handler.sendRaw(msg);
        }
        
        // Loop: set XP to 1000 and trigger level-up until we reach target level
        int levelsGained = 0;
        while (currentLevel < targetLevel) {
            // Set XP to trigger level-up
            boolean leveledUp = classDAO.addXpToCurrentClass(promoteCharId, CharacterClass.XP_PER_LEVEL);
            if (!leveledUp) {
                out.println("Failed to level up " + promoteTargetName + " (unexpected error).");
                break;
            }
            
            currentLevel++;
            levelsGained++;
            
            // Notify target of level-up
            if (promoteTargetHandler != null) {
                promoteTargetHandler.sendRaw("You have reached level " + currentLevel + "!");
            }
            
            // Process level-up benefits
            classDAO.processLevelUp(promoteCharId, currentLevel, msgCallback);
        }
        
        if (levelsGained > 0) {
            out.println("Promoted " + promoteTargetName + " by " + levelsGained + 
                " level(s) to level " + currentLevel + ".");
        }
        return true;
    }

    private boolean handlePeaceCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        CharacterRecord rec = ctx.character;
        String name = ctx.playerName;
        // GM-only: End all combat in the current room
        if (!dao.isCharacterFlagTrueByName(name, "is_gm")) {
            out.println("You do not have permission to use peace.");
            return true;
        }
        if (rec == null || rec.currentRoom == null) {
            out.println("You are not in a room.");
            return true;
        }
        Combat roomCombat = CombatManager.getInstance().getCombatInRoom(rec.currentRoom);
        if (roomCombat == null || !roomCombat.isActive()) {
            out.println("There is no active combat in this room.");
            return true;
        }
        CombatManager.getInstance().endCombat(roomCombat);
        out.println("You wave your hand and combat ceases.");
        ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " waves their hand and all combat in the room ceases.");
        return true;
    }

    private boolean handleMstatCommand(CommandContext ctx) {
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

        MobileDAO mstatMobDao = new MobileDAO();
        java.util.List<Mobile> mobsInRoom = mstatMobDao.getMobilesInRoom(rec.currentRoom);

        Mobile matchedMob = null;
        for (Mobile mob : mobsInRoom) {
            if (mob.isDead()) continue;
            if (mob.getName() != null && mob.getName().toLowerCase().contains(mstatSearch)) { matchedMob = mob; break; }
            if (mob.getShortDesc() != null && mob.getShortDesc().toLowerCase().contains(mstatSearch)) { matchedMob = mob; break; }
        }

        if (matchedMob == null) {
            for (Mobile mob : mobsInRoom) {
                if (mob.isDead()) continue;
                MobileTemplate mobTmpl = mstatMobDao.getTemplateById(mob.getTemplateId());
                if (mobTmpl != null && mobTmpl.matchesKeyword(mstatSearch)) { matchedMob = mob; break; }
            }
        }

        if (matchedMob == null) { out.println("No mobile '" + mstatSearch + "' found in this room."); return true; }

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

    private boolean handleIstatCommand(CommandContext ctx) {
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
        ItemDAO istatItemDao = new ItemDAO();
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

        String eff1 = inst.getEffectiveSpellEffect1(tmpl);
        String eff2 = inst.getEffectiveSpellEffect2(tmpl);
        String eff3 = inst.getEffectiveSpellEffect3(tmpl);
        String eff4 = inst.getEffectiveSpellEffect4(tmpl);
        if (eff1 != null || eff2 != null || eff3 != null || eff4 != null) {
            out.println("--- SPELL EFFECTS ---");
            if (eff1 != null) out.println("  Effect 1: " + eff1 + (inst.spellEffect1Override != null ? " (override)" : ""));
            if (eff2 != null) out.println("  Effect 2: " + eff2 + (inst.spellEffect2Override != null ? " (override)" : ""));
            if (eff3 != null) out.println("  Effect 3: " + eff3 + (inst.spellEffect3Override != null ? " (override)" : ""));
            if (eff4 != null) out.println("  Effect 4: " + eff4 + (inst.spellEffect4Override != null ? " (override)" : ""));
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

    private boolean handleIlistCommand(CommandContext ctx) {
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
        ItemDAO ilistItemDao = new ItemDAO();
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

    private boolean handleIfindCommand(CommandContext ctx) {
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
        
        ItemDAO ifindItemDao = new ItemDAO();
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
                    Room rm = dao.getRoomById(inst.locationRoomId);
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
                    Room charRoom = charRoomId != null ? dao.getRoomById(charRoomId) : null;
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

    private boolean handleGmchatCommand(CommandContext ctx) {
        if (!ensureGm(ctx)) return true;
        return CommunicationCommandHandler.handleCommunication(ctx);
    }

    private boolean handleGenmapCommand(CommandContext ctx) {
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
            com.example.tassmud.model.Room currentRoom = dao.getRoomById(rec.currentRoom);
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

    private boolean handleDebugCommand(CommandContext ctx) {
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

    private boolean handleDbinfoCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        // GM-only: prints table schema information
        if (!ensureGm(ctx)) return true;
        // Ensure item tables/migrations are applied by constructing ItemDAO
        try { new ItemDAO(); } catch (Exception ignored) {}

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

    private boolean handleCspellCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao; 
        // GM-only: CSPELL <character> <spell_id> [amount]  OR  CSPELL LIST
        // Grants a spell to a character at a given proficiency (default 100%)
        if (!ensureGm(ctx)) return true;
        String cspellArgs = ctx.getArgs();
        if (cspellArgs == null || cspellArgs.trim().isEmpty()) {
            out.println("Usage: CSPELL <character> <spell_id> [amount]");
            out.println("       CSPELL LIST - List all available spells");
            out.println("  Grants a spell to a character at the specified proficiency.");
            out.println("  Amount defaults to 100 (mastered) if not specified.");
            return true;
        }
        String[] cspellParts = cspellArgs.trim().split("\\s+");
        
        // Handle CSPELL LIST
        if (cspellParts[0].equalsIgnoreCase("list")) {
            java.util.List<Spell> allSpells = dao.getAllSpells();
            if (allSpells.isEmpty()) {
                out.println("No spells found in the database.");
                return true;
            }
            
            // Sort alphabetically by name
            allSpells.sort((sp1, sp2) -> sp1.getName().compareToIgnoreCase(sp2.getName()));
            
            out.println();
            out.println("===================================================================");
            out.println("                       AVAILABLE SPELLS");
            out.println("===================================================================");
            out.println();
            
            // Format: "SpellName (ID)" in 3 columns
            java.util.List<String> spellDisplays = new java.util.ArrayList<>();
            for (Spell sp : allSpells) {
                spellDisplays.add(String.format("%s (%d)", sp.getName(), sp.getId()));
            }
            
            // Print in rows of 3
            for (int i = 0; i < spellDisplays.size(); i += 3) {
                String c1 = spellDisplays.get(i);
                String c2 = (i + 1 < spellDisplays.size()) ? spellDisplays.get(i + 1) : "";
                String c3 = (i + 2 < spellDisplays.size()) ? spellDisplays.get(i + 2) : "";
                out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
            }
            
            out.println();
            out.println("-------------------------------------------------------------------");
            out.println(String.format("  Total: %d spells", allSpells.size()));
            out.println("===================================================================");
            out.println();
            return true;
        }
        
        if (cspellParts.length < 2) {
            out.println("Usage: CSPELL <character> <spell_id> [amount]");
            out.println("       CSPELL LIST - List all available spells");
            return true;
        }
        String targetSpellCharName = cspellParts[0];
        int spellId;
        try {
            spellId = Integer.parseInt(cspellParts[1]);
        } catch (NumberFormatException e) {
            out.println("Invalid spell ID. Must be a number.");
            return true;
        }
        int spellAmount = 100; // default to mastered
        if (cspellParts.length >= 3) {
            try {
                spellAmount = Integer.parseInt(cspellParts[2]);
                if (spellAmount < 1) spellAmount = 1;
                if (spellAmount > 100) spellAmount = 100;
            } catch (NumberFormatException e) {
                out.println("Invalid amount. Must be a number between 1 and 100.");
                return true;
            }
        }
        // Look up target character
        Integer targetSpellCharId = dao.getCharacterIdByName(targetSpellCharName);
        if (targetSpellCharId == null) {
            out.println("Character '" + targetSpellCharName + "' not found.");
            return true;
        }
        // Check if spell exists
        Spell spellDef = dao.getSpellById(spellId);
        String spellName = spellDef != null ? spellDef.getName() : "Spell #" + spellId;
        // Set the spell level (will create if doesn't exist, or update if it does)
        boolean spellOk = dao.setCharacterSpellLevel(targetSpellCharId, spellId, spellAmount);
        if (spellOk) {
            String spellProfStr = spellAmount >= 100 ? "MASTERED" : spellAmount + "%";
            out.println("Granted " + targetSpellCharName + " the spell '" + spellName + "' at " + spellProfStr + ".");
        } else {
            out.println("Failed to grant spell to " + targetSpellCharName + ".");
        }
        return true;
    }

    private boolean handleCskillCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao; 
        // GM-only: CSKILL <character> <skill_id> [amount]  OR  CSKILL LIST
        // Grants a skill to a character at a given proficiency (default 100%)

        if (!ensureGm(ctx)) return true;
        String cskillArgs = ctx.getArgs();
        if (cskillArgs == null || cskillArgs.trim().isEmpty()) {
            out.println("Usage: CSKILL <character> <skill_id> [amount]");
            out.println("       CSKILL LIST - List all available skills");
            out.println("  Grants a skill to a character at the specified proficiency.");
            out.println("  Amount defaults to 100 (mastered) if not specified.");
            return true;
        }
        String[] cskillParts = cskillArgs.trim().split("\\s+");
        
        // Handle CSKILL LIST
        if (cskillParts[0].equalsIgnoreCase("list")) {
            java.util.List<Skill> allSkills = dao.getAllSkills();
            if (allSkills.isEmpty()) {
                out.println("No skills found in the database.");
                return true;
            }
            
            out.println();
            out.println("===================================================================");
            out.println("                       AVAILABLE SKILLS");
            out.println("===================================================================");
            out.println();
            
            // Format: "SkillName (ID)" in 3 columns
            java.util.List<String> skillDisplays = new java.util.ArrayList<>();
            for (Skill sk : allSkills) {
                skillDisplays.add(String.format("%s (%d)", sk.getName(), sk.getId()));
            }
            
            // Print in rows of 3
            for (int i = 0; i < skillDisplays.size(); i += 3) {
                String c1 = skillDisplays.get(i);
                String c2 = (i + 1 < skillDisplays.size()) ? skillDisplays.get(i + 1) : "";
                String c3 = (i + 2 < skillDisplays.size()) ? skillDisplays.get(i + 2) : "";
                out.println(String.format("  %-21s %-21s %-21s", c1, c2, c3));
            }
            
            out.println();
            out.println("-------------------------------------------------------------------");
            out.println(String.format("  Total: %d skills", allSkills.size()));
            out.println("===================================================================");
            out.println();
            return true;
        }
        
        if (cskillParts.length < 2) {
            out.println("Usage: CSKILL <character> <skill_id> [amount]");
            out.println("       CSKILL LIST - List all available skills");
            return true;
        }
        String targetCharName = cskillParts[0];
        int skillId;
        try {
            skillId = Integer.parseInt(cskillParts[1]);
        } catch (NumberFormatException e) {
            out.println("Invalid skill ID. Must be a number.");
            return true;
        }
        int amount = 100; // default to mastered
        if (cskillParts.length >= 3) {
            try {
                amount = Integer.parseInt(cskillParts[2]);
                if (amount < 1) amount = 1;
                if (amount > 100) amount = 100;
            } catch (NumberFormatException e) {
                out.println("Invalid amount. Must be a number between 1 and 100.");
                return true;
            }
        }
        // Look up target character
        Integer targetCharId = dao.getCharacterIdByName(targetCharName);
        if (targetCharId == null) {
            out.println("Character '" + targetCharName + "' not found.");
            return true;
        }
        // Check if skill exists
        Skill skillDef = dao.getSkillById(skillId);
        String skillName = skillDef != null ? skillDef.getName() : "Skill #" + skillId;
        // Set the skill level (will create if doesn't exist, or update if it does)
        boolean ok = dao.setCharacterSkillLevel(targetCharId, skillId, amount);
        if (ok) {
            String profStr = amount >= 100 ? "MASTERED" : amount + "%";
            out.println("Granted " + targetCharName + " the skill '" + skillName + "' at " + profStr + ".");
        } else {
            out.println("Failed to grant skill to " + targetCharName + ".");
        }
        return true;
    }

    private boolean handleCsetCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;       
        
        // GM-only: CSET <char> <attribute> <value>  OR  CSET LIST
        if (!ensureGm(ctx)) return true;
        String csetArgs = ctx.getArgs();
        if (csetArgs == null || csetArgs.trim().isEmpty()) {
            out.println("Usage: CSET <character> <attribute> <value>");
            out.println("       CSET LIST - Show all settable attributes");
            return true;
        }
        String[] csetParts = csetArgs.trim().split("\\s+", 3);
        
        // Handle CSET LIST
        if (csetParts[0].equalsIgnoreCase("list")) {
            out.println();
            out.println("═══════════════════════════════════════════════════════════════");
            out.println("                    SETTABLE ATTRIBUTES");
            out.println("═══════════════════════════════════════════════════════════════");
            out.println();
            out.println("  [ VITALS ]");
            out.println("    hp, hpmax, mp, mpmax, mv, mvmax");
            out.println();
            out.println("  [ BASE ABILITIES ]");
            out.println("    str, dex, con, int, wis, cha");
            out.println();
            out.println("  [ TRAINED ABILITIES ]");
            out.println("    trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha");
            out.println("    (aliases: tstr, tdex, tcon, tint, twis, tcha)");
            out.println();
            out.println("  [ SAVES ]");
            out.println("    armor (ac), fortitude (fort), reflex (ref), will");
            out.println();
            out.println("  [ EQUIPMENT BONUSES ]");
            out.println("    armor_equip, fort_equip, reflex_equip, will_equip");
            out.println();
            out.println("  [ CLASS & PROGRESSION ]");
            out.println("    class, level, xp");
            out.println();
            out.println("  [ OTHER ]");
            out.println("    age, room, autoflee, talents, gold, description");
            out.println();
            out.println("═══════════════════════════════════════════════════════════════");
            out.println();
            return true;
        }
        
        if (csetParts.length < 3) {
            out.println("Usage: CSET <character> <attribute> <value>");
            out.println("       CSET LIST - Show all settable attributes");
            return true;
        }
        
        String targetName = csetParts[0];
        String attrName = csetParts[1];
        String attrValue = csetParts[2];
        
        // Find the character
        Integer targetCharId = dao.getCharacterIdByName(targetName);
        if (targetCharId == null) {
            out.println("Character '" + targetName + "' not found.");
            return true;
        }
        
        // Set the attribute
        String result = dao.setCharacterAttribute(targetCharId, attrName, attrValue);
        out.println(targetName + ": " + result);
        
        // Notify the target if they're online and it's not self
        if (!targetName.equalsIgnoreCase(ctx.playerName)) {
            ClientHandler targetHandler = ClientHandler.nameToSession.get(targetName.toLowerCase());
            if (targetHandler != null) {
                targetHandler.sendRaw("[GM] Your " + attrName + " has been modified.");
            }
        }
        return true;
    }

    private boolean handleCflagCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        
        // GM-only: CFLAG SET <char> <flag> <value>  OR  CFLAG CHECK <char> <flag>
        if (!ensureGm(ctx)) return true;
        String cfArgs2b = ctx.getArgs();
        if (cfArgs2b == null || cfArgs2b.trim().isEmpty()) {
            out.println("Usage: CFLAG SET <char> <flag> <value>   |   CFLAG CHECK <char> <flag>");
            return true;
        }
        String[] parts = cfArgs2b.trim().split("\\s+");
        String verb = parts.length > 0 ? parts[0].toLowerCase() : "";
        if (verb.equals("set")) {
            if (parts.length < 4) { out.println("Usage: CFLAG SET <char> <flag> <value>"); return true; }
            String targetName2 = parts[1];
            String flagName2 = parts[2];
            // join remaining parts as the value to allow spaces
            StringBuilder sbv = new StringBuilder();
            for (int i = 3; i < parts.length; i++) { if (sbv.length() > 0) sbv.append(' '); sbv.append(parts[i]); }
            String flagVal2 = sbv.toString();
            boolean ok3 = dao.setCharacterFlagByName(targetName2, flagName2, flagVal2);
            if (ok3) out.println("Flag set for " + targetName2 + ": " + flagName2 + " = " + flagVal2);
            else out.println("Failed to set flag for " + targetName2 + ".");
            return true;
        } else if (verb.equals("check") || verb.equals("get")) {
            if (parts.length < 3) { out.println("Usage: CFLAG CHECK <char> <flag>"); return true; }
            String targetName3 = parts[1];
            String flagName3 = parts[2];
            String v = dao.getCharacterFlagByName(targetName3, flagName3);
            if (v == null || v.isEmpty()) {
                out.println("No flag set for " + targetName3 + " (" + flagName3 + ")");
            } else {
                out.println("Flag for " + targetName3 + ": " + flagName3 + " = " + v);
            }
            return true;
        } else {
            out.println("Usage: CFLAG SET <char> <flag> <value>   |   CFLAG CHECK <char> <flag>");
            return true;
        }
    }

    private boolean handleGotoCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO.CharacterRecord rec = ctx.character;
        CharacterDAO dao = ctx.dao;
        Integer charId = ctx.characterId;
        String name = ctx.playerName;
        // GM-only: GOTO <room_id> or GOTO <player_name>

        if (!ensureGm(ctx)) return true;
        String gotoArgs = ctx.getArgs();
        if (gotoArgs == null || gotoArgs.trim().isEmpty()) {
            out.println("Usage: GOTO <room_id> or GOTO <player_name>");
            out.println("  Teleports you directly to a room or to another player.");
            return true;
        }

        int gotoRoomId = -1;
        String gotoTarget = gotoArgs.trim();

        try {
            gotoRoomId = Integer.parseInt(gotoTarget);
        } catch (NumberFormatException e) {
            ClientHandler targetSession = null;
            for (ClientHandler s : ClientHandler.sessions) {
                String pName = s.playerName;
                if (pName != null && pName.toLowerCase().startsWith(gotoTarget.toLowerCase())) { targetSession = s; break; }
            }

            if (targetSession != null && targetSession.currentRoomId != null) {
                gotoRoomId = targetSession.currentRoomId;
                out.println("(Teleporting to " + targetSession.playerName + ")");
            } else {
                CharacterRecord targetRec = dao.findByName(gotoTarget);
                if (targetRec != null && targetRec.currentRoom != null) {
                    gotoRoomId = targetRec.currentRoom;
                    out.println("(Teleporting to " + targetRec.name + "'s last location)");
                } else {
                    out.println("No room or player found matching '" + gotoTarget + "'.");
                    return true;
                }
            }
        }

        Room gotoRoom = dao.getRoomById(gotoRoomId);
        if (gotoRoom == null) { out.println("Room #" + gotoRoomId + " does not exist."); return true; }

        Integer oldRoom = rec.currentRoom;
        if (!ctx.handler.gmInvisible) { ClientHandler.roomAnnounce(oldRoom, ClientHandler.makeDepartureMessage(name, null), charId, true); }

        dao.updateCharacterRoom(name, gotoRoomId);
        ctx.handler.currentRoomId = gotoRoomId;
        rec = dao.findByName(name);

        if (!ctx.handler.gmInvisible) { ClientHandler.roomAnnounce(gotoRoomId, ClientHandler.makeArrivalMessage(name, null), charId, true); }

        out.println();
        out.println("You vanish and reappear in " + gotoRoom.getName() + ".");
        out.println();
        MovementCommandHandler.showRoom(gotoRoom, gotoRoomId, ctx);
        return true;
    }

    private static boolean ensureGm(CommandContext ctx) {
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
