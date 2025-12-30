package com.example.tassmud.combat;

import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.util.LootGenerator;
import com.example.tassmud.util.TickService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages all active combat instances in the game.
 * Handles combat ticks, round processing, and combat state.
 */
public class CombatManager {
    
    // Singleton instance
    private static CombatManager instance;
    
    /** All active combats, keyed by combat ID */
    private final Map<Long, Combat> activeCombats = new ConcurrentHashMap<>();
    
    /** Map from room ID to combat - at most one combat per room */
    private final Map<Integer, Combat> combatsByRoom = new ConcurrentHashMap<>();
    
    /** Map from character ID to their active combat */
    private final Map<Integer, Combat> combatsByCharacter = new ConcurrentHashMap<>();
    
    /** Map from mobile instance ID to their active combat */
    private final Map<Long, Combat> combatsByMobile = new ConcurrentHashMap<>();
    
    /** Combat ID generator */
    private final AtomicLong combatIdGenerator = new AtomicLong(1);
    private static final Logger logger = LoggerFactory.getLogger(CombatManager.class);
    
    /** Callback for sending messages to players */
    private BiConsumer<Integer, String> playerMessageCallback;
    
    /** Callback for sending messages to a room */
    private BiConsumer<Integer, String> roomMessageCallback;
    
    /** Callback for sending prompts to players (triggers prompt display) */
    private Consumer<Integer> playerPromptCallback;
    
    /** Callback for handling autoflee - takes (characterId, combat) and returns true if flee succeeded */
    private java.util.function.BiFunction<Integer, Combat, Boolean> playerAutofleeCallback;
    
    /** The basic attack command available to all combatants */
    private final BasicAttackCommand basicAttack = new BasicAttackCommand();
    
    /** Handler for multiple attacks (second_attack, third_attack, fourth_attack) */
    private final MultiAttackHandler multiAttackHandler = new MultiAttackHandler();
    
    private CombatManager() {}
    
    /**
     * Get the singleton instance.
     */
    public static synchronized CombatManager getInstance() {
        if (instance == null) {
            instance = new CombatManager();
        }
        return instance;
    }
    
    /**
     * Initialize the combat manager with a tick service.
     */
    public void initialize(TickService tickService) {
        // Schedule combat tick every 500ms for responsive combat
        tickService.scheduleAtFixedRate("combat-tick", this::tick, 500, 500);
        // Set up multi-attack handler message callback
        multiAttackHandler.setPlayerMessageCallback(this::sendToPlayer);
        logger.info("[CombatManager] Initialized with tick service");
    }
    
    /**
     * Set callback for sending messages to players.
     * @param callback (characterId, message) -> void
     */
    public void setPlayerMessageCallback(BiConsumer<Integer, String> callback) {
        this.playerMessageCallback = callback;
    }
    
    /**
     * Set callback for sending messages to a room.
     * @param callback (roomId, message) -> void
     */
    public void setRoomMessageCallback(BiConsumer<Integer, String> callback) {
        this.roomMessageCallback = callback;
    }
    
    /**
     * Set callback for sending prompts to players after combat rounds.
     * @param callback (characterId) -> void
     */
    public void setPlayerPromptCallback(Consumer<Integer> callback) {
        this.playerPromptCallback = callback;
    }
    
    /**
     * Set callback for handling player autoflee.
     * The callback takes (characterId, combat) and should return true if flee succeeded.
     * @param callback (characterId, combat) -> success
     */
    public void setPlayerAutofleeCallback(java.util.function.BiFunction<Integer, Combat, Boolean> callback) {
        this.playerAutofleeCallback = callback;
    }
    
    /**
     * Main combat tick - called periodically to process all active combats.
     */
    public void tick() {
        List<Combat> toRemove = new ArrayList<>();
        
        for (Combat combat : activeCombats.values()) {
            if (combat.hasEnded()) {
                toRemove.add(combat);
                continue;
            }
            
            if (!combat.isActive()) {
                continue;
            }
            
            processCombatTick(combat);
            
            // Check if combat should end after processing
            if (combat.shouldEnd()) {
                endCombat(combat);
                toRemove.add(combat);
            }
        }
        
        // Clean up ended combats
        for (Combat combat : toRemove) {
            cleanupCombat(combat);
        }
    }
    
    /**
     * Process a single tick for a combat instance.
     */
    private void processCombatTick(Combat combat) {
        // If round is complete and it's time for a new round, start one
        if (combat.isRoundComplete() && combat.isTimeForNextRound()) {
            combat.startNewRound();
            // Send blank line to separate rounds visually
            broadcastToRoom(combat.getRoomId(), "");
        }
        
        // Process turns until we hit someone who hasn't acted or round is complete
        while (!combat.isRoundComplete()) {
            Combatant current = combat.getCurrentTurnCombatant();
            if (current == null) {
                combat.advanceTurn();
                continue;
            }
            
            // Skip if already acted, dead, or inactive
            if (current.hasActedThisRound() || !current.isAlive() || !current.isActive()) {
                combat.advanceTurn();
                continue;
            }
            
            // Process this combatant's turn
            processTurn(combat, current);
            current.setHasActedThisRound(true);
            
            // Check if combat ended
            if (combat.shouldEnd()) {
                return;
            }
            
            // Move to next combatant
            if (!combat.advanceTurn()) {
                break; // Round complete
            }
        }
        
        // After all turns are processed, if round just completed, send prompts to players (once)
        if (combat.isRoundComplete() && !combat.isPromptsSentForRound()) {
            // Process autoflee checks before prompts
            processAutoflee(combat);
            
            // Check if combat ended due to autoflee
            if (combat.shouldEnd()) {
                return;
            }
            
            sendPromptsToPlayers(combat);
            combat.setPromptsSentForRound(true);
        }
    }
    
    /**
     * Process autoflee for all combatants in a combat.
     * Called at the end of each round.
     */
    private void processAutoflee(Combat combat) {
        CharacterDAO charDao = new CharacterDAO();
        
        // Create a copy of combatants to avoid concurrent modification
        List<Combatant> combatants = new ArrayList<>(combat.getCombatants());
        
        for (Combatant c : combatants) {
            if (!c.isActive() || !c.isAlive()) {
                continue;
            }
            
            int autoflee;
            if (c.isPlayer() && c.getCharacterId() != null) {
                // For players, look up their autoflee setting
                autoflee = charDao.getAutoflee(c.getCharacterId());
            } else if (c.isMobile()) {
                // For mobs, get from the mobile
                autoflee = c.getAutoflee();
            } else {
                continue;
            }
            
            // Check if should autoflee
            if (!c.shouldAutoflee(autoflee)) {
                continue;
            }
            
            // Trigger autoflee
            if (c.isPlayer() && c.getCharacterId() != null) {
                // Use callback for players
                if (playerAutofleeCallback != null) {
                    boolean success = playerAutofleeCallback.apply(c.getCharacterId(), combat);
                    if (success) {
                        sendToPlayer(c.getCharacterId(), "Panic overwhelms you and you flee!");
                    }
                }
            } else if (c.isMobile()) {
                // Handle mob autoflee directly
                processMobileAutoflee(combat, c);
            }
        }
    }
    
    /**
     * Process autoflee for a mobile combatant.
     * Mobs don't need opposed checks - they just flee to a random adjacent room if possible.
     */
    private void processMobileAutoflee(Combat combat, Combatant mobCombatant) {
        Mobile mob = mobCombatant.getMobile();
        if (mob == null) return;
        
        CharacterDAO charDao = new CharacterDAO();
        Integer roomId = mob.getCurrentRoom();
        if (roomId == null) return;
        
        com.example.tassmud.model.Room room = charDao.getRoomById(roomId);
        if (room == null) return;
        
        // Build list of available exits
        List<Integer> availableRooms = new ArrayList<>();
        if (room.getExitN() != null) availableRooms.add(room.getExitN());
        if (room.getExitE() != null) availableRooms.add(room.getExitE());
        if (room.getExitS() != null) availableRooms.add(room.getExitS());
        if (room.getExitW() != null) availableRooms.add(room.getExitW());
        if (room.getExitU() != null) availableRooms.add(room.getExitU());
        if (room.getExitD() != null) availableRooms.add(room.getExitD());
        
        if (availableRooms.isEmpty()) {
            // Nowhere to flee - mob stays and fights
            broadcastToRoom(roomId, mob.getName() + " panics but has nowhere to run!");
            return;
        }
        
        // Pick a random exit
        Integer destRoom = availableRooms.get((int)(Math.random() * availableRooms.size()));
        
        // Announce and move
        broadcastToRoom(roomId, mob.getName() + " flees in terror!");
        
        // Remove from combat
        combat.removeCombatant(mobCombatant);
        
        // Move mob
        mob.setCurrentRoom(destRoom);
        try {
            MobileDAO mobileDao = new MobileDAO();
            mobileDao.updateInstance(mob);
        } catch (Exception e) {
            logger.warn("[CombatManager] Failed to update fleeing mob: {}", e.getMessage(), e);
        }
        
        // Announce arrival at destination
        broadcastToRoom(destRoom, mob.getName() + " arrives, looking panicked.");
    }
    
    /**
     * Process a single combatant's turn.
     */
    private void processTurn(Combat combat, Combatant combatant) {
        // First check if still alive
        if (!combatant.isAlive()) {
            return;
        }
        
        // Check if there are valid targets
        List<Combatant> targets = combat.getValidTargets(combatant);
        if (targets.isEmpty()) {
            return;
        }
        
        // Get the command to execute
        CombatCommand command = null;
        Combatant target = null;
        
        if (combatant.isMobile()) {
            // AI: select a command and target
            command = selectMobileCommand(combat, combatant);
            target = selectMobileTarget(combat, combatant);
        } else {
            // Player: use queued command or default to basic attack
            command = combatant.pollNextCommand();
            if (command == null) {
                command = basicAttack;
            }
            // For now, auto-target a random enemy (players can specify later)
            target = combat.getRandomTarget(combatant);
        }
        
        if (command == null) {
            command = basicAttack;
        }
        
        if (target == null) {
            target = combat.getRandomTarget(combatant);
        }
        
        if (target == null) {
            // No valid target
            return;
        }
        
        // Check for AoE weapon infusion (e.g., Greater Arcane Infusion)
        // If the combatant has an AoE infusion active and is using basic attack,
        // execute an AoE attack instead of single target
        if (command == basicAttack && basicAttack.hasAoEInfusion(combatant)) {
            // Execute AoE attack against all valid targets
            List<CombatResult> aoeResults = basicAttack.executeAoE(combatant, combat, 0);
            for (CombatResult result : aoeResults) {
                combat.addRoundResult(result);
                broadcastCombatResult(combat, result);
                
                Combatant aoeTarget = result.getTarget();
                if (result.getDamage() > 0 && aoeTarget != null && aoeTarget.isPlayer()) {
                    trackArmorDamage(aoeTarget, result.getDamage());
                    syncPlayerHpToDatabase(aoeTarget);
                }
                
                if ((result.isDeath() || (aoeTarget != null && !aoeTarget.isAlive())) && aoeTarget != null) {
                    handleCombatantDeath(combat, aoeTarget, combatant);
                }
            }
            
            // Apply end-of-turn effects (damage over time, healing, etc.)
            applyEndOfTurnEffects(combat, combatant);
            return; // AoE attack complete, skip normal single-target flow
        }
        
        // Execute the primary attack/command (single target)
        CombatResult result = command.execute(combatant, target, combat);
        combat.addRoundResult(result);
        
        // Send messages based on result
        broadcastCombatResult(combat, result);
        
        // Track armor damage for proficiency training (only for player targets)
        if (result.getDamage() > 0 && target.isPlayer()) {
            trackArmorDamage(target, result.getDamage());
            // Sync player HP to database so prompt reflects actual HP
            syncPlayerHpToDatabase(target);
        }
        
        // Check for death
        if (result.isDeath() || !target.isAlive()) {
            handleCombatantDeath(combat, target, combatant);
        }
        
        // Process multiple attacks (only for basic attacks)
        // Multi-attack only applies when using basic attack, not special abilities
        if (command == basicAttack && combatant.isAlive() && !combat.shouldEnd()) {
            processMultiAttacks(combat, combatant, target);
        }
        
        // Apply end-of-turn effects (damage over time, healing, etc.)
        applyEndOfTurnEffects(combat, combatant);
    }
    
    /**
     * Process additional attacks for a combatant with multi-attack skills.
     * Checks for second_attack, third_attack, fourth_attack and executes
     * any that trigger based on proficiency.
     */
    private void processMultiAttacks(Combat combat, Combatant combatant, Combatant initialTarget) {
        // Get additional attack opportunities
        List<MultiAttackHandler.AttackOpportunity> attacks = multiAttackHandler.getAdditionalAttacks(combatant);
        
        for (MultiAttackHandler.AttackOpportunity attack : attacks) {
            // Skip if attack didn't trigger
            if (!attack.isTriggered()) {
                continue;
            }
            
            // Check if combatant or combat ended
            if (!combatant.isAlive() || combat.shouldEnd()) {
                break;
            }
            
            // Get a valid target (may have changed if initial target died)
            Combatant target = initialTarget;
            if (!target.isAlive() || !target.isActive()) {
                target = combat.getRandomTarget(combatant);
            }
            
            if (target == null) {
                break; // No valid targets remaining
            }
            
            // Execute the attack with level penalty
            CombatResult result = basicAttack.executeWithPenalty(
                combatant, target, combat, attack.getLevelPenalty());
            combat.addRoundResult(result);
            
            // Send messages
            broadcastCombatResult(combat, result);
            
            // Track armor damage
            if (result.getDamage() > 0 && target.isPlayer()) {
                trackArmorDamage(target, result.getDamage());
                syncPlayerHpToDatabase(target);
            }
            
            // Check for death
            if (result.isDeath() || !target.isAlive()) {
                handleCombatantDeath(combat, target, combatant);
            }
        }
    }
    
    /**
     * Select a combat command for a mobile based on AI.
     */
    private CombatCommand selectMobileCommand(Combat combat, Combatant mobile) {
        // TODO: Check mob's skill list and cooldowns
        // For now, just use basic attack
        return basicAttack;
    }
    
    /**
     * Select a target for a mobile based on AI.
     */
    private Combatant selectMobileTarget(Combat combat, Combatant mobile) {
        List<Combatant> targets = combat.getValidTargets(mobile);
        if (targets.isEmpty()) return null;
        
        // For now: random target
        // TODO: Priority targeting (lowest HP, healers, etc.)
        return targets.get((int)(Math.random() * targets.size()));
    }
    
    /**
     * Handle a combatant being killed.
     */
    private void handleCombatantDeath(Combat combat, Combatant victim, Combatant killer) {
        String deathMessage = victim.getName() + " has been slain by " + killer.getName() + "!";
        broadcastToRoom(combat.getRoomId(), deathMessage);
        combat.logEvent(deathMessage);
        
        // Mark as inactive
        victim.setActive(false);
        
        // Handle player death
        if (victim.isPlayer()) {
            handlePlayerDeath(combat, victim);
        }
        
        // Handle mob death - spawn corpse and despawn mob
        if (victim.isMobile()) {
            Mobile mob = victim.getMobile();
            if (mob != null) {
                int roomId = combat.getRoomId();
                String mobName = mob.getName();
                int mobLevel = Math.max(1, mob.getLevel());
                
                // Calculate gold to put in corpse
                java.util.Random rand = new java.util.Random();
                int baseGold = mobLevel * 2;
                int bonusGold = (mobLevel * mobLevel) / 5;
                int variance = rand.nextInt(mobLevel + 1);
                long goldAmount = baseGold + bonusGold + variance;
                
                // Spawn a corpse in the room with gold
                long corpseId = -1;
                try {
                    ItemDAO itemDAO = new ItemDAO();
                    corpseId = itemDAO.createCorpse(roomId, mobName, goldAmount);
                    if (corpseId > 0) {
                        broadcastToRoom(roomId, mobName + " falls to the ground, leaving behind a corpse.");
                        
                        // Generate random loot in the corpse
                        List<LootGenerator.GeneratedItem> loot = LootGenerator.generateLoot(mobLevel, corpseId, itemDAO);
                        if (loot.isEmpty()) {
                            logger.info("[Loot] No items generated for {} (level {})", mobName, mobLevel);
                        } else {
                            // Log and announce loot items
                            for (LootGenerator.GeneratedItem item : loot) {
                                String itemName = item.customName != null ? item.customName : "item#" + item.templateId;
                                logger.info("[Loot] Generated '{}' from {} (level {})", itemName, mobName, mobLevel);
                                if (item.customName != null) {
                                    broadcastToRoom(roomId, "  * " + item.customName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[CombatManager] Failed to create corpse for {}: {}", mobName, e.getMessage(), e);
                }

                // Move any item instances that were used to equip this mob into the corpse.
                // SpawnEvent applied modifiers with source="equip#<instanceId>", so inspect modifiers.
                try {
                    if (corpseId > 0) {
                        ItemDAO itemDAO = new ItemDAO();
                        java.util.List<com.example.tassmud.model.Modifier> mods = mob.getAllModifiers();
                        for (com.example.tassmud.model.Modifier mod : mods) {
                            String src = mod.source();
                            if (src == null) continue;
                            String idPart = null;
                            if (src.startsWith("equip#")) {
                                idPart = src.substring("equip#".length());
                            } else if (src.startsWith("inventory#")) {
                                idPart = src.substring("inventory#".length());
                            }
                            if (idPart == null) continue;
                            try {
                                long itemInstanceId = Long.parseLong(idPart);
                                // Move instance into corpse container
                                itemDAO.moveInstanceToContainer(itemInstanceId, corpseId);
                            } catch (NumberFormatException nfe) {
                                // ignore malformed source
                            } catch (Exception ex) {
                                logger.warn("[CombatManager] Failed to move item {} into corpse: {}", idPart, ex.getMessage(), ex);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[CombatManager] Error while moving equipped items to corpse: {}", e.getMessage(), e);
                }
                
                // Handle autogold for the killer
                if (killer.isPlayer() && killer.getCharacterId() != null && corpseId > 0) {
                    CharacterDAO charDao = new CharacterDAO();
                    CharacterDAO.CharacterRecord killerRec = charDao.getCharacterById(killer.getCharacterId());
                    if (killerRec != null && killerRec.autogold && goldAmount > 0) {
                        // Take the gold automatically
                        ItemDAO itemDAO = new ItemDAO();
                        long goldTaken = itemDAO.takeGoldContents(corpseId);
                        if (goldTaken > 0) {
                            charDao.addGold(killer.getCharacterId(), goldTaken);
                            sendToPlayer(killer.getCharacterId(), "You receive " + goldTaken + " gold.");
                        }
                    }
                    
                    // Handle autoloot for the killer
                    if (killerRec != null && killerRec.autoloot) {
                        ItemDAO itemDAO = new ItemDAO();
                        List<ItemDAO.RoomItem> corpseContents = itemDAO.getItemsInContainer(corpseId);
                        if (!corpseContents.isEmpty()) {
                            for (ItemDAO.RoomItem ri : corpseContents) {
                                itemDAO.moveInstanceToCharacter(ri.instance.instanceId, killer.getCharacterId());
                                String displayName = ri.instance.customName != null && !ri.instance.customName.isEmpty() 
                                    ? ri.instance.customName : ri.template.name;
                                sendToPlayer(killer.getCharacterId(), "You get " + displayName + " from the corpse.");
                            }
                        }
                    }
                    
                    // Handle autosac for the killer - sacrifice empty corpses for 1 XP
                    // Refresh record to check autosac setting
                    killerRec = charDao.getCharacterById(killer.getCharacterId());
                    if (killerRec != null && killerRec.autosac) {
                        ItemDAO itemDAO = new ItemDAO();
                        // Check if corpse is empty (no items and no gold)
                        List<ItemDAO.RoomItem> remainingContents = itemDAO.getItemsInContainer(corpseId);
                        long remainingGold = itemDAO.getGoldContents(corpseId);
                        if (remainingContents.isEmpty() && remainingGold <= 0) {
                            // Delete the corpse and award 1 XP
                            itemDAO.deleteInstance(corpseId);
                            CharacterClassDAO classDAO = new CharacterClassDAO();
                            classDAO.addXpToCurrentClass(killer.getCharacterId(), 1);
                            sendToPlayer(killer.getCharacterId(), "You sacrifice the corpse for 1 experience point.");
                        }
                    }
                }
                
                // Mark the mob as dead and remove from the world
                try {
                    MobileDAO mobileDAO = new MobileDAO();
                    mob.die();
                    mobileDAO.deleteInstance(mob.getInstanceId());
                } catch (Exception e) {
                    logger.warn("[CombatManager] Failed to despawn mob {}: {}", mobName, e.getMessage(), e);
                }
            }
            
            // Award XP to the killer if they are a player
            awardExperienceOnKill(killer, victim);
        }
        
        // Award weapon skill proficiency to the killer if they are a player
        awardWeaponSkillOnKill(killer);
    }
    
    /**
     * Handle a player's death - create corpse, move items, deduct XP, teleport to recall.
     * 
     * Death sequence:
     * 1. Create a corpse container in the death room with the player's name
     * 2. Move ALL equipped items into the corpse
     * 3. Move ALL inventory items into the corpse
     * 4. Move ALL gold into the corpse
     * 5. Deduct 250 XP (minimum 0, cannot lose a level)
     * 6. Teleport player to Mead-Gaard Inn (room 3041)
     * 7. Set HP to 1, MP to 0, MV to 0
     * 8. Set stance to SLEEPING
     */
    private void handlePlayerDeath(Combat combat, Combatant victim) {
        Integer characterId = victim.getCharacterId();
        if (characterId == null) return;
        
        int deathRoomId = combat.getRoomId();
        String playerName = victim.getName();
        
        CharacterDAO charDAO = new CharacterDAO();
        ItemDAO itemDAO = new ItemDAO();
        CharacterClassDAO classDAO = new CharacterClassDAO();
        
        // Get player's gold before we clear it
        long playerGold = charDAO.getGold(characterId);
        
        // 1. Create the corpse in the death room (uses template 999)
        long corpseId = -1;
        try {
            corpseId = itemDAO.createCorpse(deathRoomId, playerName, playerGold);
            if (corpseId > 0) {
                broadcastToRoom(deathRoomId, playerName + " has fallen! Their corpse lies on the ground.");
            }
        } catch (Exception e) {
            logger.warn("[CombatManager] Failed to create corpse for {}: {}", playerName, e.getMessage(), e);
        }
        
        // 2. Move ALL equipped items into the corpse
        if (corpseId > 0) {
            java.util.Map<Integer, Long> equipment = charDAO.getEquipmentMapByCharacterId(characterId);
            // Use a set to track moved items (two-handed weapons appear in both main and off hand)
            java.util.Set<Long> movedItemIds = new java.util.HashSet<>();
            for (java.util.Map.Entry<Integer, Long> entry : equipment.entrySet()) {
                Long itemInstanceId = entry.getValue();
                if (itemInstanceId != null && !movedItemIds.contains(itemInstanceId)) {
                    try {
                        itemDAO.moveInstanceToContainer(itemInstanceId, corpseId);
                        movedItemIds.add(itemInstanceId);
                    } catch (Exception e) {
                        logger.warn("[CombatManager] Failed to move equipment to corpse: {}", e.getMessage(), e);
                    }
                }
            }
            // Clear all equipment slots
            charDAO.clearAllEquipment(characterId);
            
            // 3. Move ALL inventory items into the corpse
            java.util.List<ItemDAO.RoomItem> inventory = itemDAO.getItemsByCharacter(characterId);
            for (ItemDAO.RoomItem item : inventory) {
                try {
                    itemDAO.moveInstanceToContainer(item.instance.instanceId, corpseId);
                } catch (Exception e) {
                    logger.warn("[CombatManager] Failed to move inventory to corpse: {}", e.getMessage(), e);
                }
            }
            
            // 4. Gold was already moved when corpse was created, just clear player's gold
            if (playerGold > 0) {
                charDAO.setGold(characterId, 0);
            }
        }
        
        // 5. Deduct 250 XP (minimum 0, cannot lose a level)
        int xpLost = classDAO.deductXpFromCurrentClass(characterId, 250);
        
        // 6. Teleport player to Mead-Gaard Inn (room 3041)
        int recallRoomId = 3041;
        charDAO.updateCharacterRoom(playerName, recallRoomId);
        
        // Announce departure from death room (if different from recall room)
        if (deathRoomId != recallRoomId) {
            broadcastToRoom(deathRoomId, playerName + "'s spirit departs...");
        }
        
        // Announce arrival at recall room
        broadcastToRoom(recallRoomId, "The spirit of " + playerName + " materializes, barely clinging to life.");
        
        // 7. Set HP to 1, MP to 0, MV to 0
        charDAO.setVitals(characterId, 1, 0, 0);
        
        // Update the character's in-memory state
        GameCharacter victimChar = victim.getAsCharacter();
        if (victimChar != null) {
            victimChar.setHpCur(1);
            victimChar.setMpCur(0);
            victimChar.setMvCur(0);
            victimChar.setCurrentRoom(recallRoomId);
        }
        
        // 8. Set stance to SLEEPING (handled by ClientHandler session update)
        // We need to notify ClientHandler to update the session state
        
        // Send death messages to the player
        sendToPlayer(characterId, "");
        sendToPlayer(characterId, "\u001B[1;31m*** YOU HAVE DIED ***\u001B[0m");
        sendToPlayer(characterId, "");
        sendToPlayer(characterId, "Your vision fades to black...");
        sendToPlayer(characterId, "");
        if (playerGold > 0) {
            sendToPlayer(characterId, "Your \u001B[33m" + playerGold + " gold\u001B[0m lies in your corpse.");
        }
        if (xpLost > 0) {
            sendToPlayer(characterId, "You have lost \u001B[1;31m" + xpLost + " experience\u001B[0m.");
        }
        sendToPlayer(characterId, "");
        sendToPlayer(characterId, "You awaken at the Mead-Gaard Inn, naked and penniless.");
        sendToPlayer(characterId, "Your possessions remain in your corpse at the place of your death.");
        sendToPlayer(characterId, "You fall into an exhausted sleep...");
        
        // Notify ClientHandler to set the player to SLEEPING stance
        // This is done via a special callback mechanism
        ClientHandler.handlePlayerDeathStance(characterId);
    }
    
    /**
     * Calculate and award experience points when a player kills a mob.
     * 
     * Formula: 100 / 2^(effective_level - target_level)
     * Where effective_level = char_class_level + floor(char_class_level / 10)
     * 
     * This means:
     * - Levels 1-9: 100 XP for same-level kills
     * - Levels 10-19: 50 XP for same-level kills
     * - Levels 20-29: 25 XP for same-level kills
     * - Each level the foe is weaker: half XP
     * - Each level the foe is stronger: double XP
     */
    private void awardExperienceOnKill(Combatant killer, Combatant victim) {
        if (!killer.isPlayer() || killer.getCharacterId() == null) {
            return; // Only players gain XP
        }
        if (!victim.isMobile() || victim.getMobile() == null) {
            return; // Only mob kills award XP
        }
        
        int characterId = killer.getCharacterId();
        int targetLevel = victim.getMobile().getLevel();
        
        CharacterClassDAO classDAO = new CharacterClassDAO();
        Integer classId = classDAO.getCharacterCurrentClassId(characterId);
        if (classId == null) {
            return; // No class, no XP
        }
        int charLevel = classDAO.getCharacterClassLevel(characterId, classId);
        
        // Calculate effective level: charLevel + floor(charLevel / 10)
        int effectiveLevel = charLevel + (charLevel / 10);
        
        // Calculate level difference (positive = foe is weaker)
        int levelDiff = effectiveLevel - targetLevel;
        
        // Calculate XP: 100 / 2^levelDiff
        // Clamp levelDiff to prevent extreme values
        levelDiff = Math.max(-10, Math.min(10, levelDiff));
        double xpDouble = 100.0 / Math.pow(2, levelDiff);
        int xpAwarded = (int) Math.round(xpDouble);
        
        // Minimum 1 XP
        if (xpAwarded < 1) xpAwarded = 1;
        
        // Award the XP
        boolean leveledUp = classDAO.addXpToCurrentClass(characterId, xpAwarded);
        
        // Notify the player about XP gain
        sendToPlayer(characterId, "You gain " + xpAwarded + " experience.");
        
        // Handle level-up if it occurred
        if (leveledUp) {
            int newLevel = classDAO.getCharacterClassLevel(characterId, classId);
            sendToPlayer(characterId, "You have reached level " + newLevel + "!");
            
            // Process level-up: grant skills, add vitals, restore
            final int charId = characterId;
            classDAO.processLevelUp(characterId, newLevel, msg -> sendToPlayer(charId, msg));
        }
    }

    /**
    /**
     * Award weapon family skill proficiency to a player when they get a kill.
     * Awards 1 point of proficiency (1%) for the weapon family of the equipped main-hand weapon.
     * Skill gain is capped at (class level * 10)% proficiency.
     */
    private void awardWeaponSkillOnKill(Combatant killer) {
        if (!killer.isPlayer() || killer.getCharacterId() == null) {
            return; // Only players gain skills
        }
        
        GameCharacter killerChar = killer.getAsCharacter();
        if (killerChar == null) {
            return;
        }
        
        int characterId = killer.getCharacterId();
        
        CharacterDAO characterDAO = new CharacterDAO();
        CharacterClassDAO classDAO = new CharacterClassDAO();
        ItemDAO itemDAO = new ItemDAO();
        
        // Get character's current class level for skill cap calculation
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        int classLevel = 1; // Default to level 1 if no class
        if (currentClassId != null) {
            classLevel = Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }
        int maxProficiencyForLevel = classLevel * 10; // Cap: 10% per class level
        
        // Get equipped main-hand weapon
        Long mainHandInstanceId = characterDAO.getCharacterEquipment(characterId, EquipmentSlot.MAIN_HAND.getId());
        if (mainHandInstanceId == null) {
            return; // No weapon equipped, no skill gain (unarmed combat could be added later)
        }
        
        // Get the item template to find weapon family
        ItemInstance weaponInstance = itemDAO.getInstance(mainHandInstanceId);
        if (weaponInstance == null) {
            return;
        }
        
        ItemTemplate weaponTemplate = itemDAO.getTemplateById(weaponInstance.templateId);
        if (weaponTemplate == null) {
            return;
        }
        
        WeaponFamily weaponFamily = weaponTemplate.getWeaponFamily();
        if (weaponFamily == null) {
            return; // Not a weapon or no family assigned
        }
        
        // Look up the skill for this weapon family
        String skillKey = weaponFamily.getSkillKey();
        Skill familySkill = characterDAO.getSkillByKey(skillKey);
        if (familySkill == null) {
            return; // Skill not found in database
        }
        
        // Check if character has this skill
        CharacterSkill charSkill = characterDAO.getCharacterSkill(characterId, familySkill.getId());
        if (charSkill == null) {
            // Character doesn't have this weapon skill - teach it at base proficiency
            characterDAO.learnSkill(characterId, familySkill.getId());
            charSkill = characterDAO.getCharacterSkill(characterId, familySkill.getId());
            if (charSkill != null) {
                sendToPlayer(characterId, "You have learned " + familySkill.getName() + "!");
            }
        }
        
        if (charSkill == null) {
            return; // Failed to learn/get skill
        }
        
        // Check if already at cap for current class level (or max proficiency)
        int currentProficiency = charSkill.getProficiency();
        int effectiveCap = Math.min(maxProficiencyForLevel, CharacterSkill.MAX_PROFICIENCY);
        if (currentProficiency >= effectiveCap) {
            return; // At cap for current level or already mastered
        }
        
        // Award 1 point of proficiency (but don't exceed level cap)
        int gainAmount = Math.min(1, effectiveCap - currentProficiency);
        if (gainAmount > 0) {
            int result = characterDAO.increaseSkillProficiency(characterId, familySkill.getId(), gainAmount);
            if (result > 0) {
                sendToPlayer(characterId, "Your " + familySkill.getName() + " skill improves! (" + result + "%)");
            }
        }
    }
    
    /**
     * Track damage taken for armor proficiency training.
     * Records damage against each equipped armor category.
     */
    private void trackArmorDamage(Combatant target, int damage) {
        if (!target.isPlayer() || target.getCharacterId() == null) {
            return;
        }
        
        CharacterDAO characterDAO = new CharacterDAO();
        ItemDAO itemDAO = new ItemDAO();
        Integer characterId = target.getCharacterId();
        
        // Get all equipped items and find armor categories
        java.util.Map<Integer, Long> equipped = characterDAO.getEquipmentMapByCharacterId(characterId);
        java.util.Set<ArmorCategory> wornCategories = new java.util.HashSet<>();
        
        for (Long instanceId : equipped.values()) {
            if (instanceId == null) continue;
            ItemInstance inst = itemDAO.getInstance(instanceId);
            if (inst == null) continue;
            ItemTemplate tmpl = itemDAO.getTemplateById(inst.templateId);
            if (tmpl == null || !tmpl.isArmor()) continue;
            
            ArmorCategory category = tmpl.getArmorCategory();
            if (category != null) {
                wornCategories.add(category);
            }
        }
        
        // Record damage for each worn armor category
        for (ArmorCategory category : wornCategories) {
            target.recordArmorDamage(category, damage);
        }
    }
    
    /**
     * Check if a player's armor proficiency should improve at end of combat.
     * 
     * For each armor category where damage taken >= max HP:
     *   Roll 1d100 and compare to success threshold.
     *   Success threshold = 1 / 2^max(0, skill% - level*2)
     *   
     * This creates diminishing returns: first 2% per level are guaranteed,
     * then 50% chance, 25%, 12.5%, etc.
     */
    private void checkArmorProficiencyGain(Integer characterId, Combatant combatant) {
        if (characterId == null) return;
        
        CharacterDAO characterDAO = new CharacterDAO();
        
        // Get character's max HP and level
        GameCharacter character = combatant.getAsCharacter();
        if (character == null) return;
        
        int maxHp = character.getHpMax();
        if (maxHp <= 0) return;
        
        // Get character's class level for the proficiency cap
        CharacterClassDAO classDAO = new CharacterClassDAO();
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        int classLevel = 1;
        if (currentClassId != null) {
            classLevel = Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }
        
        // Check each armor category with accumulated damage
        java.util.Map<ArmorCategory, Integer> damageCounters = combatant.getArmorDamageCounters();
        
        for (java.util.Map.Entry<ArmorCategory, Integer> entry : damageCounters.entrySet()) {
            ArmorCategory category = entry.getKey();
            int damageTaken = entry.getValue();
            
            // Must have taken at least max HP worth of damage
            if (damageTaken < maxHp) {
                continue;
            }
            
            // Look up the skill for this armor category
            String skillKey = category.getSkillKey();
            Skill armorSkill = characterDAO.getSkillByKey(skillKey);
            if (armorSkill == null) {
                continue;
            }
            
            // Check if character has this skill
            CharacterSkill charSkill = characterDAO.getCharacterSkill(characterId, armorSkill.getId());
            if (charSkill == null) {
                continue; // Must have the skill to improve it
            }
            
            int currentProficiency = charSkill.getProficiency();
            if (currentProficiency >= CharacterSkill.MAX_PROFICIENCY) {
                continue; // Already mastered
            }
            
            // Calculate success chance: 1 / 2^max(0, skill% - level*2)
            // Example: Level 4, skill 7% -> 1/2^max(0, 7-8) = 1/2^0 = 100%
            // Example: Level 4, skill 10% -> 1/2^max(0, 10-8) = 1/2^2 = 25%
            int exponent = Math.max(0, currentProficiency - (classLevel * 2));
            double successChance = 1.0 / Math.pow(2, exponent);
            int successThreshold = (int) Math.round(successChance * 100); // Convert to 1-100 scale
            
            // Roll 1d100
            int roll = (int)(Math.random() * 100) + 1;
            
            if (roll <= successThreshold) {
                // Success! Increase proficiency by 1%
                int newProficiency = characterDAO.increaseSkillProficiency(characterId, armorSkill.getId(), 1);
                if (newProficiency > 0) {
                    sendToPlayer(characterId, "Your " + armorSkill.getName() + " skill improves! (" + newProficiency + "%)");
                }
            }
        }
        
        // Reset counters after check
        combatant.resetArmorDamageCounters();
    }
    
    /**
     * Apply end-of-turn effects to a combatant.
     */
    private void applyEndOfTurnEffects(Combat combat, Combatant combatant) {
        // TODO: Process DoTs, HoTs, regeneration, etc.
        // For now, nothing
    }
    
    // === Combat Initiation ===
    
    /**
     * Start combat between a player and a mobile.
     * @return The created Combat, or null if combat couldn't start
     */
    public Combat initiateCombat(GameCharacter attacker, Integer attackerId, Mobile target, int roomId) {
        // Remove invisibility from the attacker when they enter combat
        if (attackerId != null) {
            com.example.tassmud.effect.EffectRegistry.removeInvisibility(attackerId);
        }
        
        // Check if there's already combat in this room
        Combat combat = combatsByRoom.get(roomId);
        
        if (combat != null && combat.isActive()) {
            // Join existing combat
            if (!combat.containsCharacter(attackerId)) {
                combat.addPlayerCombatant(attacker, attackerId);
                combatsByCharacter.put(attackerId, combat);
            }
            if (combat.findByMobileInstanceId(target.getInstanceId()) == null) {
                combat.addMobileCombatant(target);
                combatsByMobile.put(target.getInstanceId(), combat);
            }
            return combat;
        }
        
        // Create new combat
        long combatId = combatIdGenerator.getAndIncrement();
        combat = new Combat(combatId, roomId);
        
        // Add combatants
        combat.addPlayerCombatant(attacker, attackerId);
        
        // Determine mob alliance - mobs of same template group together
        int mobAlliance = 1; // Enemies of players (player alliance is 0)
        combat.addMobileCombatant(target, mobAlliance);
        
        // Register combat
        activeCombats.put(combatId, combat);
        combatsByRoom.put(roomId, combat);
        combatsByCharacter.put(attackerId, combat);
        combatsByMobile.put(target.getInstanceId(), combat);
        
        // Start combat
        combat.start();
        
        // Notify room
        String startMsg = attacker.getName() + " attacks " + target.getName() + "!";
        broadcastToRoom(roomId, startMsg);
        
        return combat;
    }
    
    /**
     * Have a mobile initiate combat against a player.
     */
    public Combat mobileInitiateCombat(Mobile attacker, GameCharacter target, Integer targetId, int roomId) {
        // Remove invisibility from the target (player being attacked) when they enter combat
        if (targetId != null) {
            com.example.tassmud.effect.EffectRegistry.removeInvisibility(targetId);
        }
        
        // Check if there's already combat in this room
        Combat combat = combatsByRoom.get(roomId);
        
        if (combat != null && combat.isActive()) {
            // Join existing combat
            if (combat.findByMobileInstanceId(attacker.getInstanceId()) == null) {
                combat.addMobileCombatant(attacker);
                combatsByMobile.put(attacker.getInstanceId(), combat);
            }
            if (!combat.containsCharacter(targetId)) {
                combat.addPlayerCombatant(target, targetId);
                combatsByCharacter.put(targetId, combat);
            }
            return combat;
        }
        
        // Create new combat
        long combatId = combatIdGenerator.getAndIncrement();
        combat = new Combat(combatId, roomId);
        
        // Add combatants
        combat.addMobileCombatant(attacker);
        combat.addPlayerCombatant(target, targetId);
        
        // Register combat
        activeCombats.put(combatId, combat);
        combatsByRoom.put(roomId, combat);
        combatsByCharacter.put(targetId, combat);
        combatsByMobile.put(attacker.getInstanceId(), combat);
        
        // Start combat
        combat.start();
        
        // Notify room
        String startMsg = attacker.getName() + " attacks " + target.getName() + "!";
        broadcastToRoom(roomId, startMsg);
        
        return combat;
    }
    
    /**
     * Have an aggressive mob join an existing combat in a room.
     * The mob joins the "enemy" alliance (opposing players).
     * No roll required - aggro mobs auto-join combats.
     * 
     * @param mob The aggressive mob to add
     * @param roomId The room where combat is happening
     * @return true if the mob joined combat, false otherwise
     */
    public boolean aggroMobJoinCombat(Mobile mob, int roomId) {
        Combat combat = combatsByRoom.get(roomId);
        if (combat == null || !combat.isActive()) {
            return false;
        }
        
        // Check if mob is already in combat
        if (combat.findByMobileInstanceId(mob.getInstanceId()) != null) {
            return false;
        }
        
        // Add mob to combat on the "enemy" alliance (opposing players)
        int mobAlliance = 1; // Mobs are alliance 1, players are alliance 0
        combat.addMobileCombatant(mob, mobAlliance);
        combatsByMobile.put(mob.getInstanceId(), combat);
        
        // Announce the mob joining
        broadcastToRoom(roomId, mob.getName() + " joins the fight!");
        
        return true;
    }
    
    /**
     * End a combat instance.
     */
    public void endCombat(Combat combat) {
        if (!combat.hasEnded()) {
            // Check for armor proficiency improvements before ending
            for (Combatant c : combat.getCombatants()) {
                if (c.isPlayer() && c.getCharacterId() != null) {
                    checkArmorProficiencyGain(c.getCharacterId(), c);
                }
            }
            
            // Sync all player HP to database before ending combat
            for (Combatant c : combat.getPlayerCombatants()) {
                syncPlayerHpToDatabase(c);
                // Persist any active modifiers the player had during combat
                if (c.isPlayer() && c.getCharacterId() != null) {
                    GameCharacter ch = c.getAsCharacter();
                    if (ch != null) {
                        CharacterDAO dao = new CharacterDAO();
                        dao.saveModifiersForCharacter(c.getCharacterId(), ch);
                    }
                }
            }
            
            combat.end();
            broadcastToRoom(combat.getRoomId(), "=== Combat has ended ===");
            // Send prompt to surviving players after combat ends
            sendPromptsToSurvivingPlayers(combat);
        }
    }
    
    /**
     * Clean up references to a combat.
     */
    private void cleanupCombat(Combat combat) {
        activeCombats.remove(combat.getCombatId());
        combatsByRoom.remove(combat.getRoomId());
        
        for (Combatant c : combat.getCombatants()) {
            if (c.isPlayer() && c.getCharacterId() != null) {
                combatsByCharacter.remove(c.getCharacterId());
            } else if (c.isMobile() && c.getMobile() != null) {
                combatsByMobile.remove(c.getMobile().getInstanceId());
            }
        }
    }
    
    // === Query Methods ===
    
    /**
     * Check if a character is in combat.
     */
    public boolean isInCombat(Integer characterId) {
        if (characterId == null) return false;
        Combat combat = combatsByCharacter.get(characterId);
        return combat != null && combat.isActive();
    }
    
    /**
     * Get the combat a character is in.
     */
    public Combat getCombatForCharacter(Integer characterId) {
        if (characterId == null) return null;
        Combat combat = combatsByCharacter.get(characterId);
        if (combat != null && combat.isActive()) {
            return combat;
        }
        return null;
    }
    
    /**
     * Get the combatant wrapper for a character.
     */
    public Combatant getCombatantForCharacter(Integer characterId) {
        Combat combat = getCombatForCharacter(characterId);
        if (combat == null) return null;
        return combat.findByCharacterId(characterId);
    }
    
    /**
     * Check if a mobile is in combat.
     */
    public boolean isInCombat(Mobile mobile) {
        if (mobile == null) return false;
        Combat combat = combatsByMobile.get(mobile.getInstanceId());
        return combat != null && combat.isActive();
    }
    
    /**
     * Get combat in a room.
     */
    public Combat getCombatInRoom(int roomId) {
        Combat combat = combatsByRoom.get(roomId);
        if (combat != null && combat.isActive()) {
            return combat;
        }
        return null;
    }
    
    /**
     * Get all active combats.
     */
    public Collection<Combat> getAllActiveCombats() {
        return activeCombats.values();
    }
    
    // === Player Commands ===
    
    /**
     * Queue a combat command for a player.
     * @return true if command was queued, false if not in combat
     */
    public boolean queuePlayerCommand(Integer characterId, CombatCommand command) {
        Combatant combatant = getCombatantForCharacter(characterId);
        if (combatant == null) return false;
        
        // Only allow one queued command (discard extras per spec)
        combatant.clearCommandQueue();
        combatant.queueCommand(command);
        return true;
    }
    
    // === Messaging ===
    
    private void sendToPlayer(Integer characterId, String message) {
        if (playerMessageCallback != null && characterId != null) {
            playerMessageCallback.accept(characterId, message);
        }
    }
    
    private void sendPromptToPlayer(Integer characterId) {
        if (playerPromptCallback != null && characterId != null) {
            playerPromptCallback.accept(characterId);
        }
    }
    
    private void sendPromptsToPlayers(Combat combat) {
        for (Combatant combatant : combat.getPlayerCombatants()) {
            if (combatant.isActive() && combatant.isAlive()) {
                sendPromptToPlayer(combatant.getCharacterId());
            }
        }
    }
    
    /** Send prompts to all surviving players (used at end of combat when isActive is false) */
    private void sendPromptsToSurvivingPlayers(Combat combat) {
        for (Combatant combatant : combat.getPlayerCombatants()) {
            if (combatant.isAlive()) {
                sendPromptToPlayer(combatant.getCharacterId());
            }
        }
    }
    
    /**
     * Sync a player combatant's HP/MP/MV to the database.
     * This ensures the prompt and game state reflect combat damage.
     */
    private void syncPlayerHpToDatabase(Combatant player) {
        if (!player.isPlayer()) return;
        GameCharacter c = player.getAsCharacter();
        if (c == null) return;
        
        CharacterDAO dao = new CharacterDAO();
        dao.saveCharacterStateByName(
            c.getName(),
            c.getHpCur(),
            c.getMpCur(),
            c.getMvCur(),
            c.getCurrentRoom()
        );
    }
    
    private void broadcastToRoom(int roomId, String message) {
        if (roomMessageCallback != null) {
            roomMessageCallback.accept(roomId, message);
        }
    }
    
    private void broadcastCombatResult(Combat combat, CombatResult result) {
        // Send appropriate messages based on result type
        String attackerName = result.getAttacker() != null ? result.getAttacker().getName() : "Someone";
        String targetName = result.getTarget() != null ? result.getTarget().getName() : "something";
        
        String message;
        switch (result.getType()) {
            case HIT:
                message = String.format("%s's attack %s %s!", 
                    attackerName, getDamageVerb(result.getDamage(), false), targetName);
                break;
            case CRITICAL_HIT:
                message = String.format("CRITICAL! %s's attack %s %s!", 
                    attackerName, getDamageVerb(result.getDamage(), false), targetName);
                break;
            case MISS:
                message = String.format("%s's attack %s %s!", 
                    attackerName, getDamageVerb(0, false), targetName);
                break;
            case SHRUGGED_OFF:
                message = String.format("%s's melee attack is shrugged off by %s!", 
                    attackerName, targetName);
                break;
            case DODGED:
                message = String.format("%s's ranged attack is dodged by %s!", 
                    attackerName, targetName);
                break;
            case RESISTED:
                message = String.format("%s's magical attack is resisted by %s!", 
                    attackerName, targetName);
                break;
            case BLOCKED:
                message = String.format("%s's attack is blocked by %s!", 
                    attackerName, targetName);
                break;
            case PARRIED:
                message = String.format("%s's attack is parried by %s!", 
                    attackerName, targetName);
                break;
            case HEAL:
                message = String.format("%s heals %s for %d HP!", 
                    attackerName, targetName, result.getHealing());
                break;
            case DEATH:
                // Show the killing blow damage before the death message
                message = String.format("%s's attack %s %s!", 
                    attackerName, getDamageVerb(result.getDamage(), false), targetName);
                broadcastToRoom(combat.getRoomId(), message);
                combat.logEvent(message);
                
                // Send debug info for the killing blow
                String deathDebugInfo = result.getDebugInfo();
                if (deathDebugInfo != null && !deathDebugInfo.isEmpty()) {
                    ClientHandler.sendDebugToRoom(combat.getRoomId(), deathDebugInfo);
                }
                
                // Death itself is handled separately in handleCombatantDeath
                return;
            default:
                message = result.getRoomMessage();
                if (message == null) return;
        }
        
        broadcastToRoom(combat.getRoomId(), message);
        combat.logEvent(message);
        
        // Send debug info to players with debug channel enabled
        String debugInfo = result.getDebugInfo();
        if (debugInfo != null && !debugInfo.isEmpty()) {
            ClientHandler.sendDebugToRoom(combat.getRoomId(), debugInfo);
        }
        
        // Show target's HP to attacker if they're a player with Insight effect
        if (result.getAttacker() != null && result.getAttacker().isPlayer() && 
            result.getAttacker().getCharacterId() != null && result.getTarget() != null) {
            Integer attackerId = result.getAttacker().getCharacterId();
            if (com.example.tassmud.effect.EffectRegistry.hasInsight(attackerId)) {
                Combatant target = result.getTarget();
                String hpMsg = String.format("  %s: %d/%d HP", 
                    target.getName(), target.getHpCurrent(), target.getHpMax());
                sendToPlayer(attackerId, hpMsg);
            }
        }
    }
    
    // === Statistics ===
    
    public int getActiveCombatCount() {
        return (int) activeCombats.values().stream()
            .filter(Combat::isActive)
            .count();
    }
    
    public void shutdown() {
        // End all active combats
        for (Combat combat : new ArrayList<>(activeCombats.values())) {
            endCombat(combat);
            cleanupCombat(combat);
        }
    }
    
    // === Damage Verb System ===
    
    /**
     * Damage verb thresholds and their corresponding verbs.
     * Each entry is: [maxDamage, singularVerb, pluralVerb]
     * Sorted by damage threshold ascending.
     */
    private static final Object[][] DAMAGE_VERBS = {
        {0,   "miss",           "misses"},
        {1,   "scratch",        "scratches"},
        {2,   "graze",          "grazes"},
        {3,   "hit",            "hits"},
        {5,   "injure",         "injures"},
        {8,   "wound",          "wounds"},
        {13,  "maul",           "mauls"},
        {20,  "maim",           "maims"},
        {30,  "DEVASTATE",      "DEVASTATES"},
        {40,  "DECIMATE",       "DECIMATES"},
        {50,  "*MUTILATE*",     "*MUTILATES*"},
        {65,  "*DESTROY*",      "*DESTROYS*"},
        {80,  "**EVISCERATE**", "**EVISCERATES**"},
        {100, "**DISEMBOWEL**", "**DISEMBOWELS**"},
        {125, "***MASSACRE***", "***MASSACRES***"},
        {150, "***ANNIHILATE***", "***ANNIHILATES***"},
        {175, "==**DEMOLISH**==", "==**DEMOLISHES**=="},
        {200, "==**ERADICATE**==", "==**ERADICATES**=="},
    };
    
    /** Default verb for damage over max threshold */
    private static final String DAMAGE_VERB_MAX_SINGULAR = "--==**ATOMIZE**==--";
    private static final String DAMAGE_VERB_MAX_PLURAL = "--==**ATOMIZES**==--";
    
    /**
     * Get the damage verb for a given damage amount.
     * 
     * @param damage The damage dealt
     * @param singular True for singular form ("I hit"), false for plural ("attack hits")
     * @return The appropriate damage verb
     */
    public static String getDamageVerb(int damage, boolean singular) {
        for (Object[] entry : DAMAGE_VERBS) {
            int threshold = (Integer) entry[0];
            if (damage <= threshold) {
                return singular ? (String) entry[1] : (String) entry[2];
            }
        }
        // Damage exceeds all thresholds
        return singular ? DAMAGE_VERB_MAX_SINGULAR : DAMAGE_VERB_MAX_PLURAL;
    }

    

    public static boolean triggerAutoflee(Integer characterId, Combat combat) {
        if (characterId == null || combat == null) return false;
        
        ClientHandler handler = ClientHandler.charIdToSession.get(characterId);
        if (handler == null) return false;
        
        return handler.executeAutoflee(combat);
    }
}
