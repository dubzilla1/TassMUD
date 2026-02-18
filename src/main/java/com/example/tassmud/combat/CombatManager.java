package com.example.tassmud.combat;


import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Stance;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.persistence.SkillDAO;
import com.example.tassmud.effect.FlurryEffect;
import com.example.tassmud.effect.HasteEffect;
import com.example.tassmud.util.TickService;
import com.example.tassmud.util.GroupManager;
import com.example.tassmud.util.RegenerationService;
import com.example.tassmud.model.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
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
    
    /** Ki Pool skill ID (from skills.yaml) */
    private static final int KI_POOL_SKILL_ID = 710;
    
    /** Flurry of Blows skill ID (from skills.yaml) */
    private static final int FLURRY_SKILL_ID = 701;
    
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

    /** Extracted services */
    private CombatRewardService rewardService;
    private DeathHandler deathHandler;
    private CombatMessagingService messagingService;
    
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
        // Build extracted services using the already-set callbacks
        messagingService = new CombatMessagingService(playerMessageCallback, roomMessageCallback, playerPromptCallback);
        rewardService = new CombatRewardService(playerMessageCallback);
        deathHandler = new DeathHandler(playerMessageCallback, roomMessageCallback, rewardService);

        // Schedule combat tick every 500ms for responsive combat
        tickService.scheduleAtFixedRate("combat-tick", this::tick, 500, 500);
        // Set up multi-attack handler message callback
        multiAttackHandler.setPlayerMessageCallback(messagingService::sendToPlayer);
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
            
            messagingService.sendPromptsToPlayers(combat);
            combat.setPromptsSentForRound(true);
        }
    }
    
    /**
     * Process autoflee for all combatants in a combat.
     * Called at the end of each round.
     */
    private void processAutoflee(Combat combat) {
        CharacterDAO charDao = DaoProvider.characters();
        
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
        
        CharacterDAO charDao = DaoProvider.characters();
        Integer roomId = mob.getCurrentRoom();
        if (roomId == null) return;
        
        com.example.tassmud.model.Room room = DaoProvider.rooms().getRoomById(roomId);
        if (room == null) return;
        
        // Build list of available exits
        List<Integer> availableRooms = new ArrayList<>(room.getExits().values());
        
        if (availableRooms.isEmpty()) {
            // Nowhere to flee - mob stays and fights
            broadcastToRoom(roomId, mob.getName() + " panics but has nowhere to run!");
            return;
        }
        
        // Pick a random exit
        Integer destRoom = availableRooms.get(ThreadLocalRandom.current().nextInt(availableRooms.size()));
        
        // Announce and move
        broadcastToRoom(roomId, mob.getName() + " flees in terror!");
        
        // Remove from combat
        combat.removeCombatant(mobCombatant);
        
        // Move mob
        mob.setCurrentRoom(destRoom);
        try {
            MobileDAO mobileDao = DaoProvider.mobiles();
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
        
        // Check for paralysis - completely skip turn if paralyzed
        Integer combatantId = combatant.getCharacterId();
        if (combatantId != null && com.example.tassmud.effect.ParalyzedEffect.isParalyzed(combatantId)) {
            // Notify player/room they're paralyzed
            String name = combatant.getName();
            broadcastToRoom(combat.getRoomId(), name + " struggles against the paralysis but cannot move!");
            return;
        }

        // Check for stun - completely skip turn if stunned, decrement duration
        if (combatant.isStunned()) {
            String name = combatant.getName();
            boolean stillStunned = combatant.tickStun();
            if (stillStunned) {
                broadcastToRoom(combat.getRoomId(), name + " is stunned and unable to act!");
            } else {
                broadcastToRoom(combat.getRoomId(), name + " shakes off the stun and recovers!");
            }
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
        
        // Check for confusion - redirect attacks to random targets in room
        if (combatantId != null && com.example.tassmud.effect.ConfusedEffect.isConfused(combatantId)) {
            Combatant confusedTarget = selectConfusedTarget(combat, combatant);
            if (confusedTarget != null) {
                target = confusedTarget;
                String attackerName = combatant.getName();
                String targetName = target.getName();
                broadcastToRoom(combat.getRoomId(), attackerName + " swings wildly in confusion at " + targetName + "!");
            }
        }
        
        // Check for AoE weapon infusion (e.g., Greater Arcane Infusion)
        // If the combatant has an AoE infusion active and is using basic attack,
        // execute an AoE attack instead of single target
        if (command == basicAttack && basicAttack.hasAoEInfusion(combatant)) {
            // Execute AoE attack against all valid targets
            List<CombatResult> aoeResults = basicAttack.executeAoE(combatant, combat, 0);
            int totalAoeDamage = 0;
            for (CombatResult result : aoeResults) {
                combat.addRoundResult(result);
                messagingService.broadcastCombatResult(combat, result);
                tryGenerateKi(combatant, result);
                totalAoeDamage += Math.max(0, result.getDamage());
                
                Combatant aoeTarget = result.getTarget();
                if (result.getDamage() > 0 && aoeTarget != null && aoeTarget.isPlayer()) {
                    rewardService.trackArmorDamage(aoeTarget, result.getDamage());
                    messagingService.syncPlayerHpToDatabase(aoeTarget);
                }
                
                if ((result.isDeath() || (aoeTarget != null && !aoeTarget.isAlive())) && aoeTarget != null) {
                    deathHandler.handleCombatantDeath(combat, aoeTarget, combatant);
                }
            }
            
            // Track aggro for AoE attacks (10 base + total damage across all targets)
            if (combatant.isPlayer() && combatant.getCharacterId() != null) {
                combat.addAttackAggro(combatant.getCharacterId(), totalAoeDamage);
            }
            
            // Apply end-of-turn effects (damage over time, healing, etc.)
            applyEndOfTurnEffects(combat, combatant);
            return; // AoE attack complete, skip normal single-target flow
        }
        
        // Execute the primary attack/command (single target)
        CombatResult result = command.execute(combatant, target, combat);
        combat.addRoundResult(result);
        
        // Send messages based on result
        messagingService.broadcastCombatResult(combat, result);
        
        // Ki generation for monks on successful damage
        tryGenerateKi(combatant, result);
        
        // Track aggro for player attackers (attacks generate 10 base + damage)
        if (combatant.isPlayer() && combatant.getCharacterId() != null) {
            combat.addAttackAggro(combatant.getCharacterId(), result.getDamage());
        }
        
        // Track armor damage for proficiency training (only for player targets)
        if (result.getDamage() > 0 && target.isPlayer()) {
            rewardService.trackArmorDamage(target, result.getDamage());
            // Sync player HP to database so prompt reflects actual HP
            messagingService.syncPlayerHpToDatabase(target);
        }
        
        // Check for death
        if (result.isDeath() || !target.isAlive()) {
            deathHandler.handleCombatantDeath(combat, target, combatant);
        }
        
        // Process multiple attacks (only for basic attacks)
        // Multi-attack only applies when using basic attack, not special abilities
        if (command == basicAttack && combatant.isAlive() && !combat.shouldEnd()) {
            processMultiAttacks(combat, combatant, target);
        }
        
        // Flurry of Blows: chance of an additional attack each round
        if (command == basicAttack && combatant.isAlive() && !combat.shouldEnd()) {
            processFlurryAttack(combat, combatant, target);
        }
        
        // Haste: one guaranteed extra attack per round
        if (command == basicAttack && combatant.isAlive() && !combat.shouldEnd()) {
            processHasteAttack(combat, combatant, target);
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
            messagingService.broadcastCombatResult(combat, result);
            
            // Ki generation for monks on extra attacks
            tryGenerateKi(combatant, result);
            
            // Track aggro for multi-attacks (same as primary: 10 base + damage)
            if (combatant.isPlayer() && combatant.getCharacterId() != null) {
                combat.addAttackAggro(combatant.getCharacterId(), result.getDamage());
            }
            
            // Track armor damage
            if (result.getDamage() > 0 && target.isPlayer()) {
                rewardService.trackArmorDamage(target, result.getDamage());
                messagingService.syncPlayerHpToDatabase(target);
            }
            
            // Check for death
            if (result.isDeath() || !target.isAlive()) {
                deathHandler.handleCombatantDeath(combat, target, combatant);
            }
        }
    }
    
    /**
     * Process a possible bonus attack from Flurry of Blows.
     * Only triggers for player combatants who currently have the flurry effect.
     * Chance = 25% + (flurry proficiency / 2).
     */
    private void processFlurryAttack(Combat combat, Combatant combatant, Combatant initialTarget) {
        if (!combatant.isPlayer() || combatant.getCharacterId() == null) return;
        Integer charId = combatant.getCharacterId();
        if (!FlurryEffect.hasFlurry(charId)) return;
        
        // Look up flurry skill proficiency
        SkillDAO skillDao = DaoProvider.skills();
        CharacterSkill flurrySkill = skillDao.getCharacterSkill(charId, FLURRY_SKILL_ID);
        if (flurrySkill == null) return;
        
        int proficiency = flurrySkill.getProficiency();
        int chance = 25 + proficiency / 2;   // 25% at 1 prof → 75% at 100 prof
        
        if (ThreadLocalRandom.current().nextInt(100) >= chance) return; // didn't trigger
        
        // Pick a valid target (may have changed if initial died)
        Combatant target = initialTarget;
        if (!target.isAlive() || !target.isActive()) {
            target = combat.getRandomTarget(combatant);
        }
        if (target == null || !combatant.isAlive() || combat.shouldEnd()) return;
        
        // Execute the bonus attack (no level penalty)
        CombatResult result = basicAttack.executeWithPenalty(combatant, target, combat, 0);
        combat.addRoundResult(result);
        
        // Flurry hit message
        sendToPlayer(charId, "\u001b[1;33mYour flurry lands an extra strike!\u001b[0m");
        messagingService.broadcastCombatResult(combat, result);
        
        // Ki generation (tryGenerateKi handles flurry suppression internally)
        tryGenerateKi(combatant, result);
        
        // Track aggro
        if (combatant.isPlayer() && combatant.getCharacterId() != null) {
            combat.addAttackAggro(combatant.getCharacterId(), result.getDamage());
        }
        
        // Track armor damage
        if (result.getDamage() > 0 && target.isPlayer()) {
            rewardService.trackArmorDamage(target, result.getDamage());
            messagingService.syncPlayerHpToDatabase(target);
        }
        
        // Check for death
        if (result.isDeath() || !target.isAlive()) {
            deathHandler.handleCombatantDeath(combat, target, combatant);
        }
        
        // Try to improve flurry proficiency on successful extra attack
        Skill flurryDef = skillDao.getSkillById(FLURRY_SKILL_ID);
        if (flurryDef != null && skillDao.tryImproveSkill(charId, FLURRY_SKILL_ID, flurryDef)) {
            sendToPlayer(charId, "\u001b[1;36mYour Flurry of Blows skill has improved!\u001b[0m");
        }
    }

    /**
     * Process a guaranteed bonus attack from the Haste effect.
     * Unlike Flurry (probabilistic), Haste always grants one extra basic attack
     * per round for the duration of the effect.  Works for both players and mobs.
     */
    private void processHasteAttack(Combat combat, Combatant combatant, Combatant initialTarget) {
        Integer charId = combatant.getCharacterId();
        if (charId == null) return;                       // Only players for now
        if (!HasteEffect.isHasted(charId)) return;

        // Pick a valid target (may have changed if initial died)
        Combatant target = initialTarget;
        if (!target.isAlive() || !target.isActive()) {
            target = combat.getRandomTarget(combatant);
        }
        if (target == null || !combatant.isAlive() || combat.shouldEnd()) return;

        // Execute the bonus attack (no level penalty)
        CombatResult result = basicAttack.executeWithPenalty(combatant, target, combat, 0);
        combat.addRoundResult(result);

        // Haste hit message
        if (combatant.isPlayer() && combatant.getCharacterId() != null) {
            sendToPlayer(combatant.getCharacterId(),
                    "\u001b[1;36mYour magically quickened reflexes grant an extra strike!\u001b[0m");
        }
        messagingService.broadcastCombatResult(combat, result);

        // Ki generation for monks
        tryGenerateKi(combatant, result);

        // Track aggro
        if (combatant.isPlayer() && combatant.getCharacterId() != null) {
            combat.addAttackAggro(combatant.getCharacterId(), result.getDamage());
        }

        // Track armor damage
        if (result.getDamage() > 0 && target.isPlayer()) {
            rewardService.trackArmorDamage(target, result.getDamage());
            messagingService.syncPlayerHpToDatabase(target);
        }

        // Check for death
        if (result.isDeath() || !target.isAlive()) {
            deathHandler.handleCombatantDeath(combat, target, combatant);
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
     * Uses aggro system: mobs target the player with highest threat.
     */
    private Combatant selectMobileTarget(Combat combat, Combatant mobile) {
        List<Combatant> targets = combat.getValidTargets(mobile);
        if (targets.isEmpty()) return null;
        
        // Use aggro-based targeting
        return combat.getHighestAggroTarget(targets);
    }
    
    /**
     * Select a random target for a confused combatant.
     * Can target allies, self, or even non-combatants in the room.
     * May pull non-combatants into combat.
     */
    private Combatant selectConfusedTarget(Combat combat, Combatant confusedAttacker) {
        // For confused attacks, pick randomly from ALL combatants in the fight
        // including allies, enemies, and potentially self
        List<Combatant> potentialTargets = new ArrayList<>();
        
        for (Combatant c : combat.getCombatants()) {
            if (c.isAlive() && c.isActive()) {
                potentialTargets.add(c);
            }
        }
        
        if (potentialTargets.isEmpty()) {
            return combat.getRandomTarget(confusedAttacker);
        }
        
        // Pick random target (could be self, ally, or enemy)
        java.util.Random rng = new java.util.Random();
        return potentialTargets.get(rng.nextInt(potentialTargets.size()));
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
        
        // Atomically get or create combat for this room to avoid TOCTOU race
        Combat combat = combatsByRoom.compute(roomId, (rid, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing; // Keep existing active combat
            }
            // Create new combat
            long combatId = combatIdGenerator.getAndIncrement();
            Combat newCombat = new Combat(combatId, rid);
            newCombat.addPlayerCombatant(attacker, attackerId);
            int mobAlliance = 1; // Enemies of players (player alliance is 0)
            newCombat.addMobileCombatant(target, mobAlliance);
            activeCombats.put(combatId, newCombat);
            return newCombat;
        });
        
        // If we joined an existing combat, add combatants that aren't already in
        if (combat.getState() == CombatState.ACTIVE) {
            if (!combat.containsCharacter(attackerId)) {
                combat.addPlayerCombatant(attacker, attackerId);
            }
            if (combat.findByMobileInstanceId(target.getInstanceId()) == null) {
                combat.addMobileCombatant(target);
            }
            combatsByCharacter.put(attackerId, combat);
            combatsByMobile.put(target.getInstanceId(), combat);
            return combat;
        }
        
        // Register new combat
        combatsByCharacter.put(attackerId, combat);
        combatsByMobile.put(target.getInstanceId(), combat);
        
        // Start combat
        combat.start();
        
        // Notify room
        String startMsg = attacker.getName() + " attacks " + target.getName() + "!";
        broadcastToRoom(roomId, startMsg);
        
        // Handle autoassist for group members
        processAutoassist(attackerId, roomId, combat);
        
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
        
        // Atomically get or create combat for this room to avoid TOCTOU race
        Combat combat = combatsByRoom.compute(roomId, (rid, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing; // Keep existing active combat
            }
            // Create new combat
            long combatId = combatIdGenerator.getAndIncrement();
            Combat newCombat = new Combat(combatId, rid);
            newCombat.addMobileCombatant(attacker);
            newCombat.addPlayerCombatant(target, targetId);
            activeCombats.put(combatId, newCombat);
            return newCombat;
        });
        
        // If we joined an existing combat, add combatants that aren't already in
        if (combat.getState() == CombatState.ACTIVE) {
            if (combat.findByMobileInstanceId(attacker.getInstanceId()) == null) {
                combat.addMobileCombatant(attacker);
            }
            if (!combat.containsCharacter(targetId)) {
                combat.addPlayerCombatant(target, targetId);
            }
            combatsByMobile.put(attacker.getInstanceId(), combat);
            combatsByCharacter.put(targetId, combat);
            return combat;
        }
        
        // Register new combat
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
     * Process autoassist for group members when a player initiates combat.
     * Group members in the same room with autoassist enabled will automatically join.
     * 
     * @param initiatorId The character ID of the player who started combat
     * @param roomId The room where combat is happening
     * @param combat The combat instance to join
     */
    private void processAutoassist(Integer initiatorId, int roomId, Combat combat) {
        if (initiatorId == null) return;
        
        GroupManager gm = GroupManager.getInstance();
        java.util.Optional<Group> groupOpt = gm.getGroupForCharacter(initiatorId);
        
        if (!groupOpt.isPresent()) {
            // Not in a group, nothing to do
            return;
        }
        
        Group group = groupOpt.get();
        CharacterDAO dao = DaoProvider.characters();
        
        // Check each group member
        for (Integer memberId : group.getMemberIds()) {
            // Skip the initiator (they're already in combat)
            if (memberId.equals(initiatorId)) {
                continue;
            }
            
            // Skip if member is already in combat
            if (combatsByCharacter.containsKey(memberId)) {
                continue;
            }
            
            // Get member's record to check room and autoassist setting
            CharacterRecord memberRec = dao.findById(memberId);
            if (memberRec == null) {
                continue;
            }
            
            // Check if in same room
            if (memberRec.currentRoom == null || memberRec.currentRoom != roomId) {
                continue;
            }
            
            // Check autoassist flag
            if (!memberRec.autoassist) {
                continue;
            }
            
            // Check stance - must be standing to join combat
            Stance stance = RegenerationService.getInstance().getPlayerStance(memberId);
            if (!stance.canInitiateCombat()) {
                // Notify them they couldn't assist due to stance
                ClientHandler.sendToCharacter(memberId, 
                    "You cannot assist - you must be standing to join combat.");
                continue;
            }
            
            // Remove invisibility when joining combat
            com.example.tassmud.effect.EffectRegistry.removeInvisibility(memberId);
            
            // Build GameCharacter and add to combat
            GameCharacter memberChar = ClientHandler.buildCharacterForCombat(memberRec, memberId);
            if (memberChar == null) {
                continue;
            }
            
            // Add to combat on player alliance (0)
            combat.addPlayerCombatant(memberChar, memberId);
            combatsByCharacter.put(memberId, combat);
            
            // Get initiator name for the message
            String initiatorName = dao.getCharacterNameById(initiatorId);
            if (initiatorName == null) initiatorName = "your ally";
            
            // Notify the assisting player
            ClientHandler.sendToCharacter(memberId, 
                "You rush to assist " + initiatorName + " in combat!");
            
            // Announce to room
            broadcastToRoom(roomId, memberRec.name + " rushes to assist!");
            
            logger.debug("Autoassist: {} joined combat to assist {}", memberRec.name, initiatorName);
        }
    }
    
    /**
     * End a combat instance.
     */
    public void endCombat(Combat combat) {
        if (!combat.hasEnded()) {
            // Check for armor proficiency improvements before ending
            for (Combatant c : combat.getCombatants()) {
                if (c.isPlayer() && c.getCharacterId() != null) {
                    rewardService.checkArmorProficiencyGain(c.getCharacterId(), c);
                }
            }
            
            // Sync all player HP to database before ending combat
            for (Combatant c : combat.getPlayerCombatants()) {
                messagingService.syncPlayerHpToDatabase(c);
                // Persist any active modifiers the player had during combat
                if (c.isPlayer() && c.getCharacterId() != null) {
                    GameCharacter ch = c.getAsCharacter();
                    if (ch != null) {
                        CharacterDAO dao = DaoProvider.characters();
                        dao.saveModifiersForCharacter(c.getCharacterId(), ch);
                    }
                }
            }
            
            combat.end();
            broadcastToRoom(combat.getRoomId(), "=== Combat has ended ===");
            // Send prompt to surviving players after combat ends
            messagingService.sendPromptsToSurvivingPlayers(combat);
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
    
    // === Ki Generation ===
    
    /**
     * Attempt to generate ki for a player combatant after dealing damage.
     * Only triggers for monks with the Ki Pool skill.
     *
     * Chance = 25% + (proficiency / 2).  Critical hits guarantee ki gain.
     * On successful generation the skill can improve (LEGENDARY curve).
     */
    private void tryGenerateKi(Combatant attacker, CombatResult result) {
        if (!attacker.isPlayer() || attacker.getCharacterId() == null) return;
        if (!result.isHit() || result.getDamage() <= 0) return;
        
        Integer charId = attacker.getCharacterId();
        SkillDAO skillDao = DaoProvider.skills();
        CharacterSkill kiSkill = skillDao.getCharacterSkill(charId, KI_POOL_SKILL_ID);
        if (kiSkill == null) return; // not a ki user
        
        GameCharacter gc = attacker.getCharacter();
        if (gc == null) return;
        
        // Already at max ki?
        if (gc.getKiCur() >= gc.getKiMax()) return;
        
        boolean isCrit = result.getType() == CombatResult.ResultType.CRITICAL_HIT;
        
        // Flurry of Blows suppresses ki generation on normal hits — only crits generate ki
        if (FlurryEffect.hasFlurry(charId) && !isCrit) return;
        
        boolean gained;
        
        if (isCrit) {
            gained = true;
        } else {
            int chance = 25 + kiSkill.getProficiency() / 2; // 25% at 1 prof, 75% at 100 prof
            gained = ThreadLocalRandom.current().nextInt(100) < chance;
        }
        
        if (gained) {
            int actual = gc.gainKi(1);
            if (actual > 0) {
                sendToPlayer(charId, "\u001b[1;33mYou channel your inner energy! (Ki: "
                    + gc.getKiCur() + "/" + gc.getKiMax() + ")\u001b[0m");
                
                // Persist ki to DB
                DaoProvider.characters().saveKiByName(attacker.getName(), gc.getKiMax(), gc.getKiCur());
                
                // Try to improve Ki Pool proficiency (LEGENDARY curve)
                Skill kiDef = skillDao.getSkillById(KI_POOL_SKILL_ID);
                if (kiDef != null && skillDao.tryImproveSkill(charId, KI_POOL_SKILL_ID, kiDef)) {
                    sendToPlayer(charId, "\u001b[1;36mYour Ki Pool skill has improved!\u001b[0m");
                }
            }
        }
    }
    
    // === Messaging (delegates to CombatMessagingService) ===
    
    private void sendToPlayer(Integer characterId, String message) {
        messagingService.sendToPlayer(characterId, message);
    }
    
    private void broadcastToRoom(int roomId, String message) {
        messagingService.broadcastToRoom(roomId, message);
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

    public static String getDamageVerb(int damage, boolean singular) {
        return CombatMessagingService.getDamageVerb(damage, singular);
    }

    public static boolean triggerAutoflee(Integer characterId, Combat combat) {
        if (characterId == null || combat == null) return false;
        
        ClientHandler handler = ClientHandler.charIdToSession.get(characterId);
        if (handler == null) return false;
        
        return handler.executeAutoflee(combat);
    }
}
