package com.example.tassmud.net.commands;

import com.example.tassmud.persistence.DaoProvider;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Stat;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.LootGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegate for GM world-manipulation commands, extracted from GmCommandHandler.
 * Not a standalone CommandHandler — methods are called by GmCommandHandler.
 */
class GmWorldHandler {

    private static final Logger logger = LoggerFactory.getLogger(GmWorldHandler.class);

    boolean handleCheckTemplateCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        if (!dao.isCharacterFlagTrueByName(ctx.playerName, "is_gm")) {
            out.println("You do not have permission to use checktemplate.");
            return true;
        }
        String args = ctx.getArgs();
        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: CHECKTEMPLATE <mobile_template_id>");
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args.trim());
        } catch (NumberFormatException e) {
            out.println("Invalid id: " + args);
            return true;
        }

        String url = System.getProperty("tassmud.db.url", "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name FROM mobile_template WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        out.println("Mobile template found: id=" + rs.getInt(1) + " name='" + rs.getString(2) + "'");
                    } else {
                        out.println("Mobile template not found: " + id);
                    }
                }
            }
        } catch (SQLException e) {
            out.println("DB error: " + e.getMessage());
        }
        return true;
    }

    boolean handleSeedTemplatesCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        CharacterDAO dao = ctx.dao;
        if (!dao.isCharacterFlagTrueByName(ctx.playerName, "is_gm")) {
            out.println("You do not have permission to use seedtemplates.");
            return true;
        }
        out.println("Seeding item and mobile templates into running server (this may take a moment)...");
        try {
            com.example.tassmud.persistence.DataLoader.loadTemplatesOnly();
            out.println("Template seeding complete. Triggered initial spawn checks.");
        } catch (Exception e) {
            out.println("Failed to seed templates: " + e.getMessage());
        }
        return true;
    }

    boolean handleGminvisCommand(CommandContext ctx) {
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

    boolean handleSystemCommand(CommandContext ctx) {
        String t = ctx.getArgs();
        if (t == null || t.trim().isEmpty()) { ctx.out.println("Usage: system <message>"); return true; }
        // system messages are broadcast to all
        ClientHandler.broadcastAll("[system] " + t);
        return true;
    }

    boolean handleSpawnCommand(CommandContext ctx) {
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
            ItemDAO itemDao = DaoProvider.items();
            ItemTemplate tmpl = itemDao.getTemplateById(templateId);
            if (tmpl == null) {
                out.println("No item template found with ID " + templateId);
                return true;
            }
            
            // Determine level: use optional arg, otherwise use character's class level
            CharacterClassDAO spawnClassDao = DaoProvider.classes();
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
            MobileDAO mobDao = DaoProvider.mobiles();
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
            int randStat;
            for (int i=1; i<=spawnedMob.getLevel(); i++) {
                randStat = ThreadLocalRandom.current().nextInt(3);
                switch (randStat) {
                    case 0: spawnedMob.addStat(Stat.FORTITUDE, 1); break;
                    case 1: spawnedMob.addStat(Stat.REFLEX, 1); break;
                    case 2: spawnedMob.addStat(Stat.WILL, 1); break;
                }
            }
            com.example.tassmud.util.MobileRegistry.getInstance().register(spawnedMob);
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

    boolean handleSlayCommand(CommandContext ctx) {
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
                // Extract mob list from combatants and use centralized matching
                java.util.List<Mobile> combatMobs = new java.util.ArrayList<>();
                for (Combatant c : combat.getActiveCombatants()) {
                    if (c.isMobile() && c.getMobile() != null) combatMobs.add(c.getMobile());
                }
                targetMob = com.example.tassmud.util.MobileMatchingService.findInList(combatMobs, slayArgs, true);
                if (targetMob == null) {
                    out.println("No enemy matching '" + slayArgs + "' in combat.");
                    return true;
                }
            }
            
            // Kill the mob instantly
            String mobName = targetMob.getName();
            out.println("You raise your hand and " + mobName + " is struck down by divine power!");
            ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " raises their hand and " + mobName + " is struck down by divine power!", charId);
            
            // Find combatant and set HP to 0 - then perform death handling immediately
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

                // Announce the divine strike (already announced above)
                // Create corpse in room
                try {
                    ItemDAO itemDAO = DaoProvider.items();
                    itemDAO.createCorpse(rec.currentRoom, mobName);
                } catch (Exception e) {
                    logger.warn("[slay] Failed to create corpse: {}", e.getMessage());
                }

                // Remove combatant from combat to avoid later double-processing
                try {
                    Combat roomCombat = CombatManager.getInstance().getCombatInRoom(rec.currentRoom);
                    if (roomCombat != null) {
                        roomCombat.removeCombatant(victimCombatant);
                    }
                } catch (Exception e) {
                    // non-fatal
                }

                // Kill and remove the mob instance
                try {
                    MobileDAO mobileDao = DaoProvider.mobiles();
                    targetMob.die();
                    com.example.tassmud.util.MobileRegistry.getInstance().unregister(targetMob.getInstanceId());
                    mobileDao.deleteInstance(targetMob.getInstanceId());
                } catch (Exception e) {
                    logger.warn("[slay] Failed to despawn mob: {}", e.getMessage());
                }
            }
        } else {
            // Not in combat - find target in room and kill immediately
            if (slayArgs == null || slayArgs.trim().isEmpty()) {
                out.println("Usage: slay <target>");
                out.println("  Instantly kill a mob in the room.");
                return true;
            }
            
            MobileDAO mobileDao = DaoProvider.mobiles();
            List<Mobile> mobilesInRoom = com.example.tassmud.util.MobileRegistry.getInstance().getByRoom(rec.currentRoom);
            if (mobilesInRoom.isEmpty()) {
                out.println("There is nothing here to slay.");
                return true;
            }
            
            targetMob = com.example.tassmud.util.MobileMatchingService.findInRoomFuzzy(rec.currentRoom, slayArgs);
            
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
            ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " raises their hand and " + mobName + " is struck down by divine power!", charId);
            
            // Create corpse
            try {
                ItemDAO itemDAO = DaoProvider.items();
                itemDAO.createCorpse(rec.currentRoom, mobName);
            } catch (Exception e) {
                logger.warn("[slay] Failed to create corpse: {}", e.getMessage());
            }
            
            // Kill and remove the mob
            targetMob.die();
            com.example.tassmud.util.MobileRegistry.getInstance().unregister(targetMob.getInstanceId());
            mobileDao.deleteInstance(targetMob.getInstanceId());
        }
        return true;
    }

    boolean handlePeaceCommand(CommandContext ctx) {
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
        ClientHandler.broadcastRoomMessage(rec.currentRoom, name + " waves their hand and all combat in the room ceases.", ctx.characterId);
        return true;
    }

    boolean handleGotoCommand(CommandContext ctx) {
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

        Room gotoRoom = DaoProvider.rooms().getRoomById(gotoRoomId);
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

    /**
     * Handle the setweather command - forcibly change the weather.
     * Usage: setweather <weather_type>
     * Valid types: clear, partly_cloudy, overcast, windy, rainy, stormy, snowy, hurricane, earthquake, volcanic_ash
     */
    boolean handleSetWeatherCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        if (!ensureGm(ctx)) return true;
        
        String args = ctx.getArgs();
        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: setweather <weather_type>");
            out.println("Valid types: clear, partly_cloudy, overcast, windy, rainy, stormy, snowy, hurricane, earthquake, volcanic_ash");
            return true;
        }
        
        String weatherKey = args.trim().toLowerCase();
        
        // Get weather service
        com.example.tassmud.util.WeatherService weatherService = com.example.tassmud.util.WeatherService.getInstance();
        if (weatherService == null) {
            out.println("Weather service is not available.");
            return true;
        }
        
        // Parse the weather type
        com.example.tassmud.model.Weather newWeather = com.example.tassmud.model.Weather.fromKey(weatherKey);
        
        // Check if it was a valid key (fromKey returns CLEAR as default)
        boolean validKey = false;
        for (com.example.tassmud.model.Weather w : com.example.tassmud.model.Weather.values()) {
            if (w.getKey().equalsIgnoreCase(weatherKey) || w.name().equalsIgnoreCase(weatherKey)) {
                validKey = true;
                newWeather = w;
                break;
            }
        }
        
        if (!validKey) {
            out.println("Invalid weather type: " + weatherKey);
            out.println("Valid types: clear, partly_cloudy, overcast, windy, rainy, stormy, snowy, hurricane, earthquake, volcanic_ash");
            return true;
        }
        
        com.example.tassmud.model.Weather oldWeather = weatherService.getCurrentWeather();
        weatherService.setWeather(newWeather);
        
        out.println("[GM] Weather changed from " + oldWeather.getDisplayName() + " to " + newWeather.getDisplayName() + ".");
        return true;
    }

    /**
     * ENSLAVE <mob> [minutes]
     * Binds a mob in the same room as a DEFENDER ally of the GM.
     * With no duration it is permanent (until death); with a duration it is
     * temporary and expires after the given number of minutes.
     */
    boolean handleEnslaveCommand(CommandContext ctx) {
        PrintWriter out = ctx.out;
        if (!ensureGm(ctx)) return true;

        CharacterRecord rec = ctx.character;
        if (rec == null || rec.currentRoom == null) {
            out.println("You must be in a room to use enslave.");
            return true;
        }

        Integer charId = ctx.characterId;
        if (charId == null) {
            charId = ctx.dao.getCharacterIdByName(ctx.playerName);
        }
        if (charId == null) {
            out.println("Could not resolve your character ID.");
            return true;
        }

        String args = ctx.getArgs();
        if (args == null || args.trim().isEmpty()) {
            out.println("Usage: enslave <mob> [minutes]");
            out.println("  <mob>     - name or keyword of a mob in the room");
            out.println("  [minutes] - duration; omit for permanent (until death)");
            return true;
        }

        // Parse optional trailing numeric argument as duration in minutes
        String[] parts = args.trim().split("\\s+");
        String mobArg;
        Integer durationMinutes = null;

        // Check if the last token is a number
        String lastToken = parts[parts.length - 1];
        try {
            durationMinutes = Integer.parseInt(lastToken);
            if (durationMinutes <= 0) {
                out.println("Duration must be a positive number of minutes.");
                return true;
            }
            // mob arg is everything before the last token
            if (parts.length == 1) {
                out.println("Usage: enslave <mob> [minutes]");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) sb.append(' ');
                sb.append(parts[i]);
            }
            mobArg = sb.toString();
        } catch (NumberFormatException e) {
            // No trailing number — entire arg is the mob name, permanent binding
            mobArg = args.trim();
        }

        // Find the mob in the room
        Mobile target = com.example.tassmud.util.MobileMatchingService.findInRoomFuzzy(rec.currentRoom, mobArg);
        if (target == null) {
            out.println("You don't see '" + mobArg + "' here.");
            return true;
        }
        if (target.isDead()) {
            out.println(target.getName() + " is already dead.");
            return true;
        }

        // Determine persistence and expiry
        long expiresAt;
        com.example.tassmud.model.AllyPersistence persistence;
        String durationDesc;
        if (durationMinutes != null) {
            expiresAt = System.currentTimeMillis() + (durationMinutes * 60_000L);
            persistence = com.example.tassmud.model.AllyPersistence.TEMPORARY;
            durationDesc = durationMinutes + " minute" + (durationMinutes == 1 ? "" : "s");
        } else {
            expiresAt = 0L;
            persistence = com.example.tassmud.model.AllyPersistence.PERMANENT;
            durationDesc = "permanent";
        }

        // Cancel any existing combat the mob is in against the player
        CombatManager combatMgr = CombatManager.getInstance();
        Combat existingCombat = combatMgr.getCombatInRoom(rec.currentRoom);
        if (existingCombat != null) {
            Combatant mobCombatant = existingCombat.findByMobileInstanceId(target.getInstanceId());
            if (mobCombatant != null) {
                existingCombat.removeCombatant(mobCombatant);
            }
        }

        // Build and register the binding
        com.example.tassmud.model.AllyBinding binding = new com.example.tassmud.model.AllyBinding(
                target.getInstanceId(),
                charId,
                (long) target.getTemplateId(),
                com.example.tassmud.model.AllyBehavior.DEFENDER,
                persistence,
                true,   // followsOwner
                true,   // obeys
                expiresAt
        );
        com.example.tassmud.util.AllyManager.getInstance().bindAlly(binding);

        String mobName = target.getName();
        out.println("You extend your will and " + mobName + " bows its head in submission. [" + durationDesc + ", DEFENDER]");
        ClientHandler.broadcastRoomMessage(rec.currentRoom,
                ctx.playerName + " reaches out and " + mobName + "'s eyes glaze over in thrall.", charId);
        logger.info("[enslave] {} enslaved mob {} (instance {}) for {}",
                ctx.playerName, mobName, target.getInstanceId(), durationDesc);
        return true;
    }

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
