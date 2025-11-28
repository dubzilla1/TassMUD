package com.example.tassmud.combat;

import com.example.tassmud.model.Character;
import com.example.tassmud.model.Mobile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Represents an active combat encounter in a room.
 * Manages combatants, rounds, initiative order, and combat resolution.
 */
public class Combat {
    
    /** Unique identifier for this combat instance */
    private final long combatId;
    
    /** Room where this combat is taking place */
    private final int roomId;
    
    /** Current state of the combat */
    private CombatState state = CombatState.INITIALIZING;
    
    /** All combatants in this combat, keyed by combatant ID */
    private final Map<Long, Combatant> combatants = new ConcurrentHashMap<>();
    
    /** ID generator for combatants */
    private final AtomicLong combatantIdGenerator = new AtomicLong(1);
    
    /** Current round number (starts at 1) */
    private int currentRound = 0;
    
    /** Combatants in initiative order for the current round */
    private List<Combatant> initiativeOrder = new ArrayList<>();
    
    /** Index into initiative order for whose turn it is */
    private int currentTurnIndex = 0;
    
    /** Timestamp when combat started */
    private final long startedAt;
    
    /** Timestamp when the current round started */
    private long roundStartedAt;
    
    /** Timestamp when combat ended (0 if still active) */
    private long endedAt = 0;
    
    /** Results from this round (for display) */
    private final List<CombatResult> roundResults = new ArrayList<>();
    
    /** Log of all combat events (for recap) */
    private final List<String> combatLog = new ArrayList<>();
    
    /** Alliance counter for assigning unique alliances */
    private int nextAlliance = 1;
    
    /** Default alliance for player characters (they're allied with each other) */
    public static final int PLAYER_ALLIANCE = 0;
    
    /** Duration of a combat round in milliseconds (2-4 seconds, we'll use 3) */
    public static final long ROUND_DURATION_MS = 3000;
    
    /**
     * Create a new combat in the specified room.
     */
    public Combat(long combatId, int roomId) {
        this.combatId = combatId;
        this.roomId = roomId;
        this.startedAt = System.currentTimeMillis();
        this.roundStartedAt = startedAt;
    }
    
    // Identification
    
    public long getCombatId() { return combatId; }
    
    public int getRoomId() { return roomId; }
    
    public CombatState getState() { return state; }
    
    public void setState(CombatState state) { this.state = state; }
    
    public boolean isActive() { return state == CombatState.ACTIVE; }
    
    public boolean hasEnded() { return state == CombatState.ENDED; }
    
    // Timing
    
    public long getStartedAt() { return startedAt; }
    
    public long getEndedAt() { return endedAt; }
    
    public long getRoundStartedAt() { return roundStartedAt; }
    
    public int getCurrentRound() { return currentRound; }
    
    /**
     * Check if it's time for the next round.
     */
    public boolean isTimeForNextRound() {
        return System.currentTimeMillis() >= roundStartedAt + ROUND_DURATION_MS;
    }
    
    /**
     * Get milliseconds until the next round.
     */
    public long getTimeUntilNextRound() {
        return Math.max(0, (roundStartedAt + ROUND_DURATION_MS) - System.currentTimeMillis());
    }
    
    // Combatant Management
    
    /**
     * Add a player character to combat.
     * @return The Combatant wrapper
     */
    public Combatant addPlayerCombatant(Character character, Integer characterId) {
        long id = combatantIdGenerator.getAndIncrement();
        Combatant combatant = new Combatant(id, character, characterId, PLAYER_ALLIANCE);
        combatants.put(id, combatant);
        logEvent(character.getName() + " enters combat!");
        return combatant;
    }
    
    /**
     * Add a mobile to combat.
     * @return The Combatant wrapper
     */
    public Combatant addMobileCombatant(Mobile mobile) {
        long id = combatantIdGenerator.getAndIncrement();
        // Mobiles get a unique alliance (enemies of players)
        int alliance = nextAlliance++;
        Combatant combatant = new Combatant(id, mobile, alliance);
        combatants.put(id, combatant);
        logEvent(mobile.getName() + " enters combat!");
        return combatant;
    }
    
    /**
     * Add a mobile to combat with a specific alliance.
     * Useful for making groups of mobs work together.
     */
    public Combatant addMobileCombatant(Mobile mobile, int alliance) {
        long id = combatantIdGenerator.getAndIncrement();
        Combatant combatant = new Combatant(id, mobile, alliance);
        combatants.put(id, combatant);
        logEvent(mobile.getName() + " enters combat!");
        return combatant;
    }
    
    /**
     * Remove a combatant from combat.
     */
    public void removeCombatant(Combatant combatant) {
        combatant.setActive(false);
        logEvent(combatant.getName() + " is no longer in combat.");
    }
    
    /**
     * Get all combatants.
     */
    public Collection<Combatant> getCombatants() {
        return combatants.values();
    }
    
    /**
     * Get all active combatants (still alive and in combat).
     */
    public List<Combatant> getActiveCombatants() {
        return combatants.values().stream()
            .filter(Combatant::isActive)
            .filter(Combatant::isAlive)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all player combatants.
     */
    public List<Combatant> getPlayerCombatants() {
        return combatants.values().stream()
            .filter(Combatant::isPlayer)
            .filter(Combatant::isActive)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all mobile combatants.
     */
    public List<Combatant> getMobileCombatants() {
        return combatants.values().stream()
            .filter(Combatant::isMobile)
            .filter(Combatant::isActive)
            .collect(Collectors.toList());
    }
    
    /**
     * Find a combatant by character ID.
     */
    public Combatant findByCharacterId(Integer characterId) {
        if (characterId == null) return null;
        return combatants.values().stream()
            .filter(c -> characterId.equals(c.getCharacterId()))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find a combatant by mobile instance ID.
     */
    public Combatant findByMobileInstanceId(long mobileInstanceId) {
        return combatants.values().stream()
            .filter(c -> c.getMobile() != null && c.getMobile().getInstanceId() == mobileInstanceId)
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Find a combatant by name (case-insensitive prefix match).
     */
    public Combatant findByName(String name) {
        if (name == null || name.isEmpty()) return null;
        String lowerName = name.toLowerCase();
        return combatants.values().stream()
            .filter(c -> c.getName().toLowerCase().startsWith(lowerName))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Get valid hostile targets for a combatant.
     */
    public List<Combatant> getValidTargets(Combatant attacker) {
        return combatants.values().stream()
            .filter(Combatant::isActive)
            .filter(Combatant::isAlive)
            .filter(c -> c.isHostileTo(attacker))
            .collect(Collectors.toList());
    }
    
    /**
     * Get a random valid target for a combatant.
     */
    public Combatant getRandomTarget(Combatant attacker) {
        List<Combatant> targets = getValidTargets(attacker);
        if (targets.isEmpty()) return null;
        return targets.get((int)(Math.random() * targets.size()));
    }
    
    /**
     * Check if a combatant is in this combat.
     */
    public boolean containsCombatant(Combatant combatant) {
        return combatants.containsKey(combatant.getCombatantId());
    }
    
    /**
     * Check if a character is in this combat.
     */
    public boolean containsCharacter(Integer characterId) {
        return findByCharacterId(characterId) != null;
    }
    
    // Round Management
    
    /**
     * Start combat - call this after initial combatants are added.
     */
    public void start() {
        if (state != CombatState.INITIALIZING) return;
        
        state = CombatState.ACTIVE;
        currentRound = 0;
        logEvent("=== COMBAT BEGINS ===");
        
        // Start the first round
        startNewRound();
    }
    
    /**
     * Start a new round.
     */
    public void startNewRound() {
        currentRound++;
        roundStartedAt = System.currentTimeMillis();
        roundResults.clear();
        currentTurnIndex = 0;
        
        logEvent("--- Round " + currentRound + " ---");
        
        // Roll initiative for all active combatants
        List<Combatant> active = getActiveCombatants();
        for (Combatant c : active) {
            c.rollInitiative();
            c.resetForNewRound();
        }
        
        // Sort by initiative (highest first)
        initiativeOrder = new ArrayList<>(active);
        initiativeOrder.sort((a, b) -> Integer.compare(b.getInitiative(), a.getInitiative()));
    }
    
    /**
     * Get the current combatant whose turn it is.
     */
    public Combatant getCurrentTurnCombatant() {
        if (initiativeOrder.isEmpty() || currentTurnIndex >= initiativeOrder.size()) {
            return null;
        }
        return initiativeOrder.get(currentTurnIndex);
    }
    
    /**
     * Advance to the next combatant's turn.
     * @return true if there are more turns this round, false if round is complete
     */
    public boolean advanceTurn() {
        currentTurnIndex++;
        // Skip any combatants that are no longer active
        while (currentTurnIndex < initiativeOrder.size()) {
            Combatant next = initiativeOrder.get(currentTurnIndex);
            if (next.isActive() && next.isAlive()) {
                return true;
            }
            currentTurnIndex++;
        }
        return false; // Round complete
    }
    
    /**
     * Check if all turns this round are complete.
     */
    public boolean isRoundComplete() {
        return currentTurnIndex >= initiativeOrder.size();
    }
    
    /**
     * Get the initiative order for display.
     */
    public List<Combatant> getInitiativeOrder() {
        return Collections.unmodifiableList(initiativeOrder);
    }
    
    // Combat Resolution
    
    /**
     * Check if combat should end.
     * Combat ends when one side has no active combatants.
     */
    public boolean shouldEnd() {
        // Get unique alliances that have active combatants
        Set<Integer> activeAlliances = combatants.values().stream()
            .filter(Combatant::isActive)
            .filter(Combatant::isAlive)
            .map(Combatant::getAlliance)
            .collect(Collectors.toSet());
        
        // Combat ends if there's only one alliance left (or none)
        return activeAlliances.size() <= 1;
    }
    
    /**
     * End combat.
     */
    public void end() {
        state = CombatState.ENDED;
        endedAt = System.currentTimeMillis();
        
        // Determine winners
        List<Combatant> survivors = getActiveCombatants();
        if (survivors.isEmpty()) {
            logEvent("=== COMBAT ENDS - No survivors ===");
        } else {
            String survivorNames = survivors.stream()
                .map(Combatant::getName)
                .collect(Collectors.joining(", "));
            logEvent("=== COMBAT ENDS - Survivors: " + survivorNames + " ===");
        }
        
        // Clear combat state from all combatants
        for (Combatant c : combatants.values()) {
            c.clearCommandQueue();
            c.setActive(false);
        }
    }
    
    /**
     * Add a result from this round.
     */
    public void addRoundResult(CombatResult result) {
        roundResults.add(result);
    }
    
    /**
     * Get results from this round.
     */
    public List<CombatResult> getRoundResults() {
        return Collections.unmodifiableList(roundResults);
    }
    
    // Combat Log
    
    public void logEvent(String event) {
        String timestamp = String.format("[R%d %.1fs]", currentRound, 
            (System.currentTimeMillis() - startedAt) / 1000.0);
        combatLog.add(timestamp + " " + event);
    }
    
    public List<String> getCombatLog() {
        return Collections.unmodifiableList(combatLog);
    }
    
    public String getRecentLog(int lines) {
        int start = Math.max(0, combatLog.size() - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < combatLog.size(); i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(combatLog.get(i));
        }
        return sb.toString();
    }
    
    // Statistics
    
    /**
     * Get combat duration in milliseconds.
     */
    public long getDurationMs() {
        if (endedAt > 0) {
            return endedAt - startedAt;
        }
        return System.currentTimeMillis() - startedAt;
    }
    
    /**
     * Get a summary of the combat state.
     */
    public String getSummary() {
        List<Combatant> active = getActiveCombatants();
        int players = (int) active.stream().filter(Combatant::isPlayer).count();
        int mobs = (int) active.stream().filter(Combatant::isMobile).count();
        
        return String.format("Combat #%d [%s] Round %d - %d players, %d mobs - Room %d",
            combatId, state.getDisplayName(), currentRound, players, mobs, roomId);
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
}
