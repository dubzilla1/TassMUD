package com.example.tassmud.util;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.Room;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.MobileDAO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles mobile (NPC/monster) roaming behavior.
 * 
 * By default, mobiles without the SENTINEL behavior will wander within their area.
 * Each mob has a "stay timer" that determines how long they remain in a room
 * before potentially moving. When spawning or entering a room, roll 1d100+10
 * to determine the number of seconds before the next movement attempt.
 * 
 * Roaming rules:
 * - SENTINEL mobs never move
 * - Mobs in combat don't move
 * - Dead mobs don't move
 * - Mobs must have sufficient movement points (uses same costs as players)
 * - Mobs typically stay within their spawn area
 * - Announcements are made when mobs enter/leave rooms
 */
public class MobileRoamingService {
    
    private static final long TICK_INTERVAL_MS = 1000; // Check every second
    
    private static MobileRoamingService instance;
    
    private final CharacterDAO dao;
    private final MobileDAO mobileDao;
    private final CombatManager combatManager;
    
    /** Maps mobile instance ID -> timestamp when they should next attempt to move */
    private final Map<Long, Long> nextMoveTime = new ConcurrentHashMap<>();
    
    private MobileRoamingService() {
        this.dao = new CharacterDAO();
        this.mobileDao = new MobileDAO();
        this.combatManager = CombatManager.getInstance();
    }
    
    public static synchronized MobileRoamingService getInstance() {
        if (instance == null) {
            instance = new MobileRoamingService();
        }
        return instance;
    }
    
    /**
     * Initialize the roaming tick.
     */
    public void initialize(TickService tickService) {
        tickService.scheduleAtFixedRate("mobile-roaming", this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS);
        System.out.println("[MobileRoamingService] Initialized with " + TICK_INTERVAL_MS + "ms interval");
    }
    
    /**
     * Register a mobile for roaming (call when mob spawns or enters a room).
     * Sets the next move time based on 1d100+10 seconds from now.
     */
    public void registerMobile(long instanceId) {
        long delaySeconds = rollStayDuration();
        long nextMove = System.currentTimeMillis() + (delaySeconds * 1000);
        nextMoveTime.put(instanceId, nextMove);
    }
    
    /**
     * Unregister a mobile from roaming (call when mob dies or is removed).
     */
    public void unregisterMobile(long instanceId) {
        nextMoveTime.remove(instanceId);
    }
    
    /**
     * Roll how long a mob stays in a room: 1d100+10 seconds.
     */
    private long rollStayDuration() {
        return (long) (Math.random() * 100) + 1 + 10; // 11-110 seconds
    }
    
    /**
     * Main tick - check all mobiles for potential movement.
     */
    private void tick() {
        long now = System.currentTimeMillis();
        
        // Get all alive mobiles
        List<Mobile> allMobiles;
        try {
            allMobiles = mobileDao.getAllInstances();
        } catch (Exception e) {
            System.err.println("[MobileRoamingService] Error getting mobiles: " + e.getMessage());
            return;
        }
        
        for (Mobile mobile : allMobiles) {
            try {
                processMobile(mobile, now);
            } catch (Exception e) {
                System.err.println("[MobileRoamingService] Error processing mobile " + 
                    mobile.getInstanceId() + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Process a single mobile for potential movement.
     */
    private void processMobile(Mobile mobile, long now) {
        long instanceId = mobile.getInstanceId();
        
        // Skip dead mobs
        if (mobile.isDead()) {
            nextMoveTime.remove(instanceId);
            return;
        }
        
        // Skip SENTINEL mobs - they never move
        if (mobile.hasBehavior(MobileBehavior.SENTINEL)) {
            nextMoveTime.remove(instanceId);
            return;
        }
        
        // Skip mobs in combat
        if (combatManager.isInCombat(mobile)) {
            // Reset their timer - they'll get a new one after combat
            nextMoveTime.remove(instanceId);
            return;
        }
        
        // If mob isn't registered yet, register it now
        Long scheduledMove = nextMoveTime.get(instanceId);
        if (scheduledMove == null) {
            registerMobile(instanceId);
            return;
        }
        
        // Check if it's time to move
        if (now < scheduledMove) {
            return; // Not time yet
        }
        
        // Attempt to move
        attemptMove(mobile);
        
        // Re-register for next move (whether successful or not)
        registerMobile(instanceId);
    }
    
    /**
     * Attempt to move a mobile to an adjacent room.
     */
    private void attemptMove(Mobile mobile) {
        Integer currentRoomId = mobile.getCurrentRoom();
        if (currentRoomId == null) {
            return;
        }
        
        Room currentRoom = dao.getRoomById(currentRoomId);
        if (currentRoom == null) {
            return;
        }
        
        // Get all valid exits
        List<ExitChoice> validExits = getValidExits(mobile, currentRoom);
        if (validExits.isEmpty()) {
            return; // No valid exits
        }
        
        // Pick a random exit
        ExitChoice chosen = validExits.get((int) (Math.random() * validExits.size()));
        
        // Check movement cost
        int moveCost = dao.getMoveCostForRoom(chosen.destinationRoomId);
        if (mobile.getMvCur() < moveCost) {
            return; // Not enough movement points
        }
        
        // Deduct movement points
        mobile.setMvCur(mobile.getMvCur() - moveCost);
        
        // Announce departure from old room
        // Use the mob's template name for movement messages and strip any
        // short-description action phrases (e.g. "hisses menacingly") so that
        // arrival/departure lines remain concise: "A Giant Rat arrives..."
        String departureName = mobile.getName();
        if (departureName == null || departureName.trim().isEmpty()) {
            departureName = mobile.getShortDesc();
        }
        // Trim trailing period and remove verbs/adverbial phrases from short desc
        departureName = cleanArrivalName(departureName);

        String departureMsg = makeDepartureMessage(departureName, chosen.direction);
        ClientHandler.roomAnnounce(currentRoomId, departureMsg);
        
        // Move the mob
        mobile.setCurrentRoom(chosen.destinationRoomId);
        
        // Announce arrival in new room (use the same cleaned name)
        String arrivalMsg = makeArrivalMessage(departureName, chosen.direction);
        ClientHandler.roomAnnounce(chosen.destinationRoomId, arrivalMsg);
        
        // Persist the change
        mobileDao.updateInstance(mobile);
        
        // Check if this aggressive mob should attack players in the new room
        checkAggroOnMobEntry(mobile, chosen.destinationRoomId);
    }
    
    /**
     * Clean up a mob's name/short_desc for use in movement messages.
     * Removes common trailing phrases like "is here", "stands here", etc.
     */
    @SuppressWarnings("unused") // Utility method for future roaming message improvements
    private String cleanMobName(String name) {
        if (name == null) return "something";
        String lower = name.toLowerCase();
        
        // Remove common trailing phrases
        String[] suffixes = {
            " is here",
            " stands here",
            " lurks here",
            " waits here",
            " sits here",
            " lies here"
        };
        
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        
        return name;
    }

    /**
     * Clean up a name used in arrival/departure lines. Prefer the simple template
     * name and strip any trailing action phrases commonly found in short descriptions
     * (e.g. "hisses menacingly", "lurks in the shadows"). This keeps arrival
     * messages concise: "A Giant Rat arrives from the north." rather than
     * "A giant rat hisses menacingly arrives from the north."
     */
    private String cleanArrivalName(String name) {
        if (name == null) return "something";
        // Trim and remove trailing period
        name = name.trim();
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);

        // If name contains common short-desc verbs, cut at the verb start
        String lower = name.toLowerCase();
        String[] verbs = new String[] {" hisses ", " lurks ", " snarls ", " brandishes ", " mutters ",
            " eyes ", " waits ", " sits ", " lies ", " stares ", " glares ", " growls ",
            " howls ", " screeches ", " barks ", " chirps ", " hums ", " grunts ", " roars ",
            " shuffles ", " is here", " is standing", " is standing here"};

        for (String v : verbs) {
            int idx = lower.indexOf(v);
            if (idx > 0) {
                // Keep the portion before the verb
                return name.substring(0, idx).trim();
            }
        }

        // Also remove phrases like ", which" or ", that" after the noun
        int commaIdx = name.indexOf(",");
        if (commaIdx > 0) {
            return name.substring(0, commaIdx).trim();
        }

        return name;
    }
    
    /**
     * Get all valid exits for a mobile from its current room.
     * Applies restrictions (area bounds, locked doors, etc.).
     */
    private List<ExitChoice> getValidExits(Mobile mobile, Room currentRoom) {
        List<ExitChoice> exits = new ArrayList<>();
        
        int currentAreaId = currentRoom.getAreaId();
        Integer spawnAreaId = getSpawnAreaId(mobile);
        
        // Check each direction
        addExitIfValid(exits, mobile, currentRoom.getExitN(), "north", currentAreaId, spawnAreaId);
        addExitIfValid(exits, mobile, currentRoom.getExitS(), "south", currentAreaId, spawnAreaId);
        addExitIfValid(exits, mobile, currentRoom.getExitE(), "east", currentAreaId, spawnAreaId);
        addExitIfValid(exits, mobile, currentRoom.getExitW(), "west", currentAreaId, spawnAreaId);
        addExitIfValid(exits, mobile, currentRoom.getExitU(), "up", currentAreaId, spawnAreaId);
        addExitIfValid(exits, mobile, currentRoom.getExitD(), "down", currentAreaId, spawnAreaId);
        
        return exits;
    }
    
    /**
     * Add an exit to the list if it passes all validity checks.
     */
    private void addExitIfValid(List<ExitChoice> exits, Mobile mobile, Integer destRoomId, 
                                 String direction, int currentAreaId, Integer spawnAreaId) {
        if (destRoomId == null) {
            return; // No exit in this direction
        }
        
        Room destRoom = dao.getRoomById(destRoomId);
        if (destRoom == null) {
            return; // Invalid destination
        }
        
        // Check area restriction - mobs typically stay in their spawn area
        if (!canEnterArea(mobile, destRoom.getAreaId(), spawnAreaId)) {
            return;
        }
        
        // Check if exit is locked (placeholder for future implementation)
        if (!canPassDoor(mobile, destRoomId, direction)) {
            return;
        }
        
        // Check for any other restrictions (placeholder for future implementation)
        if (!canEnterRoom(mobile, destRoomId)) {
            return;
        }
        
        exits.add(new ExitChoice(destRoomId, direction));
    }
    
    /**
     * Get the area ID of the mobile's spawn room.
     */
    private Integer getSpawnAreaId(Mobile mobile) {
        Integer spawnRoomId = mobile.getSpawnRoomId();
        if (spawnRoomId == null) {
            return null;
        }
        Room spawnRoom = dao.getRoomById(spawnRoomId);
        return spawnRoom != null ? spawnRoom.getAreaId() : null;
    }
    
    // ==================== RESTRICTION CHECKS (Placeholders for future) ====================
    
    /**
     * Check if a mobile can enter a specific area.
     * By default, mobs stay within their spawn area.
     * 
     * @param mobile the mobile trying to move
     * @param destAreaId the area of the destination room
     * @param spawnAreaId the area where the mob spawned (null if unknown)
     * @return true if the mobile can enter the area
     */
    private boolean canEnterArea(Mobile mobile, int destAreaId, Integer spawnAreaId) {
        // If we don't know the spawn area, allow movement
        if (spawnAreaId == null) {
            return true;
        }
        
        // WANDERER behavior allows crossing area boundaries
        if (mobile.hasBehavior(MobileBehavior.WANDERER)) {
            return true;
        }
        
        // By default, stay within spawn area
        return destAreaId == spawnAreaId;
    }
    
    /**
     * Check if a mobile can pass through a door/exit.
     * Placeholder for future door/lock system.
     * 
     * @param mobile the mobile trying to pass
     * @param destRoomId the destination room
     * @param direction the direction of travel
     * @return true if the mobile can pass
     */
    private boolean canPassDoor(Mobile mobile, int destRoomId, String direction) {
        // TODO: Check for locked doors, secret doors, mob-only passages, etc.
        return true;
    }
    
    /**
     * Check if a mobile can enter a specific room.
     * Placeholder for future room restrictions.
     * 
     * @param mobile the mobile trying to enter
     * @param destRoomId the destination room
     * @return true if the mobile can enter
     */
    private boolean canEnterRoom(Mobile mobile, int destRoomId) {
        // TODO: Check for:
        // - No-mob rooms
        // - Level restrictions
        // - Faction restrictions
        // - Water rooms (swimming ability)
        // - Flying rooms (flying ability)
        return true;
    }
    
    // ==================== MESSAGE GENERATION ====================
    
    /**
     * Generate a departure message for a mobile leaving a room.
     */
    private String makeDepartureMessage(String name, String direction) {
        if (direction.equalsIgnoreCase("up") || direction.equalsIgnoreCase("down")) {
            return name + " leaves " + direction.toLowerCase() + ".";
        }
        return name + " leaves to the " + direction.toLowerCase() + ".";
    }
    
    /**
     * Generate an arrival message for a mobile entering a room.
     */
    private String makeArrivalMessage(String name, String fromDirection) {
        String opposite = getOppositeDirection(fromDirection);
        if (opposite == null) {
            return name + " arrives.";
        }
        if (opposite.equals("above") || opposite.equals("below")) {
            return name + " arrives from " + opposite + ".";
        }
        return name + " arrives from the " + opposite + ".";
    }
    
    /**
     * Get the opposite direction for arrival messages.
     */
    private String getOppositeDirection(String direction) {
        if (direction == null) return null;
        switch (direction.toLowerCase()) {
            case "north": return "south";
            case "south": return "north";
            case "east": return "west";
            case "west": return "east";
            case "up": return "below";
            case "down": return "above";
            default: return null;
        }
    }
    
    /**
     * Shutdown the roaming service.
     */
    public void shutdown() {
        nextMoveTime.clear();
    }
    
    // ========== AGGRESSIVE MOB BEHAVIOR ==========
    
    /**
     * Check if any aggressive mobs in a room should attack a player who just entered.
     * Called after a player moves into a new room.
     * 
     * For each AGGRESSIVE mob in the room:
     * - If there's combat already, the mob auto-joins (no roll)
     * - Otherwise, roll an opposed check (mob level vs player level)
     * - If mob wins, it attacks the player
     * 
     * @param roomId The room the player entered
     * @param characterId The player's character ID
     * @param playerLevel The player's class level
     */
    public void checkAggroOnPlayerEntry(int roomId, int characterId, int playerLevel) {
        // Get all mobs in the room
        List<Mobile> mobsInRoom = mobileDao.getMobilesInRoom(roomId);
        if (mobsInRoom.isEmpty()) {
            return;
        }
        
        // Check if there's already combat in this room
        Combat existingCombat = combatManager.getCombatInRoom(roomId);
        
        for (Mobile mob : mobsInRoom) {
            // Skip non-aggressive mobs
            if (!mob.hasBehavior(MobileBehavior.AGGRESSIVE)) {
                continue;
            }
            
            // Skip dead mobs
            if (mob.isDead()) {
                continue;
            }
            
            // Skip mobs already in combat
            if (combatManager.isInCombat(mob)) {
                continue;
            }
            
            // If there's already combat in the room, aggro mob auto-joins
            if (existingCombat != null && existingCombat.isActive()) {
                combatManager.aggroMobJoinCombat(mob, roomId);
                continue;
            }
            
            // Roll opposed check: mob level vs player level
            int mobLevel = Math.max(1, mob.getLevel());
            if (OpposedCheck.check(mobLevel, playerLevel)) {
                // Mob wins - initiate combat!
                triggerAggroAttack(mob, characterId, roomId);
            }
        }
    }
    
    /**
     * Check aggro when a mob enters a room.
     * Called after a mobile moves into a new room.
     * 
     * - If there's combat in the room, AGGRESSIVE mob auto-joins
     * - Otherwise, for each player in the room, roll opposed check
     * 
     * @param mob The mob that just entered
     * @param roomId The room the mob entered
     */
    public void checkAggroOnMobEntry(Mobile mob, int roomId) {
        if (mob == null || !mob.hasBehavior(MobileBehavior.AGGRESSIVE)) {
            return;
        }
        
        if (mob.isDead() || combatManager.isInCombat(mob)) {
            return;
        }
        
        // Check if there's already combat in this room
        Combat existingCombat = combatManager.getCombatInRoom(roomId);
        if (existingCombat != null && existingCombat.isActive()) {
            // Auto-join the fight
            combatManager.aggroMobJoinCombat(mob, roomId);
            return;
        }
        
        // Get all players in the room and try to attack one
        List<PlayerInRoom> playersInRoom = getPlayersInRoom(roomId);
        if (playersInRoom.isEmpty()) {
            return;
        }
        
        int mobLevel = Math.max(1, mob.getLevel());
        
        // Try to attack each player (in random order) until one succeeds
        java.util.Collections.shuffle(playersInRoom);
        for (PlayerInRoom player : playersInRoom) {
            if (OpposedCheck.check(mobLevel, player.level)) {
                // Mob wins - initiate combat!
                triggerAggroAttack(mob, player.characterId, roomId);
                return; // Only attack one player
            }
        }
    }
    
    /**
     * Trigger an aggressive mob attacking a player.
     */
    private void triggerAggroAttack(Mobile mob, int characterId, int roomId) {
        // Get the player's Character object
        CharacterDAO.CharacterRecord rec = dao.getCharacterById(characterId);
        if (rec == null) {
            return;
        }
        
        // Build a Character object from the record for combat
        com.example.tassmud.model.Character playerChar = ClientHandler.buildCharacterForCombat(rec, characterId);
        if (playerChar == null) {
            return;
        }
        
        // Announce the attack
        String attackMsg = mob.getName() + " snarls and attacks " + rec.name + "!";
        ClientHandler.roomAnnounce(roomId, attackMsg);
        
        // Send message to the victim
        ClientHandler.sendToCharacter(characterId, "\r\n\u001B[1;31m" + mob.getName() + " attacks you!\u001B[0m");
        
        // Initiate combat
        combatManager.mobileInitiateCombat(mob, playerChar, characterId, roomId);
        
        // Send prompt to the player
        ClientHandler.sendPromptToCharacter(characterId);
    }
    
    /**
     * Get all players currently in a room with their levels.
     */
    private List<PlayerInRoom> getPlayersInRoom(int roomId) {
        List<PlayerInRoom> result = new ArrayList<>();
        CharacterClassDAO classDao = new CharacterClassDAO();
        
        for (Integer charId : ClientHandler.getConnectedCharacterIds()) {
            ClientHandler handler = ClientHandler.getHandlerByCharacterId(charId);
            if (handler != null && handler.getCurrentRoomId() != null && handler.getCurrentRoomId() == roomId) {
                // Get player's level
                CharacterDAO.CharacterRecord rec = dao.getCharacterById(charId);
                int level = 1;
                if (rec != null && rec.currentClassId != null) {
                    level = classDao.getCharacterClassLevel(charId, rec.currentClassId);
                }
                result.add(new PlayerInRoom(charId, level));
            }
        }
        
        return result;
    }
    
    /**
     * Simple container for a player in a room.
     */
    private static class PlayerInRoom {
        final int characterId;
        final int level;
        
        PlayerInRoom(int characterId, int level) {
            this.characterId = characterId;
            this.level = level;
        }
    }
    
    /**
     * Simple container for an exit choice.
     */
    private static class ExitChoice {
        final int destinationRoomId;
        final String direction;
        
        ExitChoice(int destinationRoomId, String direction) {
            this.destinationRoomId = destinationRoomId;
            this.direction = direction;
        }
    }
}
