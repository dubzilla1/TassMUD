package com.example.tassmud.combat;

import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.TickService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

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
    
    /** Callback for sending messages to players */
    private BiConsumer<Integer, String> playerMessageCallback;
    
    /** Callback for sending messages to a room */
    private BiConsumer<Integer, String> roomMessageCallback;
    
    /** The basic attack command available to all combatants */
    private final BasicAttackCommand basicAttack = new BasicAttackCommand();
    
    /** Tick service for scheduling combat updates */
    private TickService tickService;
    
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
        this.tickService = tickService;
        // Schedule combat tick every 500ms for responsive combat
        tickService.scheduleAtFixedRate("combat-tick", this::tick, 500, 500);
        System.out.println("[CombatManager] Initialized with tick service");
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
            broadcastToRoom(combat.getRoomId(), "--- Round " + combat.getCurrentRound() + " ---");
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
        
        // Execute the command
        CombatResult result = command.execute(combatant, target, combat);
        combat.addRoundResult(result);
        
        // Send messages based on result
        broadcastCombatResult(combat, result);
        
        // Check for death
        if (result.isDeath() || !target.isAlive()) {
            handleCombatantDeath(combat, target, combatant);
        }
        
        // Apply end-of-turn effects (damage over time, healing, etc.)
        applyEndOfTurnEffects(combat, combatant);
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
        
        // For now, we set them to 1 HP instead of actually killing
        // This is per the spec - "for now just set them to 1hp and out of combat"
        Character victimChar = victim.getAsCharacter();
        if (victimChar != null) {
            victimChar.setHpCur(1);
        }
        
        // Send death message to the victim
        if (victim.isPlayer() && victim.getCharacterId() != null) {
            sendToPlayer(victim.getCharacterId(), 
                "You have been defeated! You narrowly escape death with 1 HP remaining.");
        }
        
        // Award weapon skill proficiency to the killer if they are a player
        awardWeaponSkillOnKill(killer);
    }
    
    /**
     * Award weapon family skill proficiency to a player when they get a kill.
     * Awards 1 point of proficiency (1%) for the weapon family of the equipped main-hand weapon.
     * Skill gain is capped at (class level * 10)% proficiency.
     */
    private void awardWeaponSkillOnKill(Combatant killer) {
        if (!killer.isPlayer() || killer.getCharacterId() == null) {
            return; // Only players gain skills
        }
        
        Character killerChar = killer.getAsCharacter();
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
    public Combat initiateCombat(Character attacker, Integer attackerId, Mobile target, int roomId) {
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
    public Combat mobileInitiateCombat(Mobile attacker, Character target, Integer targetId, int roomId) {
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
     * End a combat instance.
     */
    public void endCombat(Combat combat) {
        if (!combat.hasEnded()) {
            combat.end();
            broadcastToRoom(combat.getRoomId(), "=== Combat has ended ===");
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
                message = String.format("%s hits %s for %d damage!", 
                    attackerName, targetName, result.getDamage());
                break;
            case CRITICAL_HIT:
                message = String.format("CRITICAL! %s hits %s for %d damage!", 
                    attackerName, targetName, result.getDamage());
                break;
            case MISS:
                message = String.format("%s swings at %s but misses!", 
                    attackerName, targetName);
                break;
            case DODGED:
                message = String.format("%s attacks %s, but they dodge!", 
                    attackerName, targetName);
                break;
            case BLOCKED:
                message = String.format("%s's attack is blocked by %s!", 
                    attackerName, targetName);
                break;
            case HEAL:
                message = String.format("%s heals %s for %d HP!", 
                    attackerName, targetName, result.getHealing());
                break;
            case DEATH:
                // Handled separately in handleCombatantDeath
                return;
            default:
                message = result.getRoomMessage();
                if (message == null) return;
        }
        
        broadcastToRoom(combat.getRoomId(), message);
        combat.logEvent(message);
        
        // Show target's HP to attacker if they're a player
        if (result.getAttacker() != null && result.getAttacker().isPlayer() && 
            result.getAttacker().getCharacterId() != null && result.getTarget() != null) {
            Combatant target = result.getTarget();
            String hpMsg = String.format("  %s: %d/%d HP", 
                target.getName(), target.getHpCurrent(), target.getHpMax());
            sendToPlayer(result.getAttacker().getCharacterId(), hpMsg);
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
}
