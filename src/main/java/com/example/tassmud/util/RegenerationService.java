package com.example.tassmud.util;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Stance;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.MobileDAO;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles natural regeneration for all characters (players and mobiles).
 * 
 * Regeneration happens every 10 seconds for characters out of combat:
 * - STANDING: 1% of max HP/MP/MV
 * - SITTING:  5% of max HP/MP/MV
 * - SLEEPING: 10% of max HP/MP/MV
 * - SWIMMING/FLYING: 1% (same as standing)
 */
public class RegenerationService {
    
    private static final long REGEN_INTERVAL_MS = 10_000; // 10 seconds
    
    private static RegenerationService instance;
    
    private final CharacterDAO dao;
    private final MobileDAO mobileDao;
    private final CombatManager combatManager;
    
    // Track player stances (character ID -> stance)
    // Players default to STANDING when they log in
    private final ConcurrentHashMap<Integer, Stance> playerStances = new ConcurrentHashMap<>();
    
    private RegenerationService() {
        this.dao = new CharacterDAO();
        this.mobileDao = new MobileDAO();
        this.combatManager = CombatManager.getInstance();
    }
    
    public static synchronized RegenerationService getInstance() {
        if (instance == null) {
            instance = new RegenerationService();
        }
        return instance;
    }
    
    /**
     * Initialize the regeneration tick.
     */
    public void initialize(TickService tickService) {
        tickService.scheduleAtFixedRate("regeneration", this::tick, REGEN_INTERVAL_MS, REGEN_INTERVAL_MS);
        System.out.println("[RegenerationService] Initialized with " + REGEN_INTERVAL_MS + "ms interval");
    }
    
    /**
     * Register a player as logged in (defaults to STANDING stance).
     */
    public void registerPlayer(int characterId) {
        playerStances.put(characterId, Stance.STANDING);
    }
    
    /**
     * Unregister a player when they log out.
     */
    public void unregisterPlayer(int characterId) {
        playerStances.remove(characterId);
    }
    
    /**
     * Get a player's current stance.
     */
    public Stance getPlayerStance(int characterId) {
        return playerStances.getOrDefault(characterId, Stance.STANDING);
    }
    
    /**
     * Set a player's stance.
     */
    public void setPlayerStance(int characterId, Stance stance) {
        playerStances.put(characterId, stance != null ? stance : Stance.STANDING);
    }
    
    /**
     * Get all registered player character IDs.
     */
    public Set<Integer> getRegisteredPlayers() {
        return new HashSet<>(playerStances.keySet());
    }
    
    /**
     * Main regeneration tick - called every 10 seconds.
     */
    private void tick() {
        try {
            regeneratePlayers();
            regenerateMobiles();
        } catch (Throwable t) {
            System.err.println("[RegenerationService] Error during regen tick: " + t.getMessage());
        }
    }
    
    /**
     * Regenerate all online players who are not in combat.
     */
    private void regeneratePlayers() {
        for (Integer charId : playerStances.keySet()) {
            try {
                // Skip if in combat
                if (combatManager.isInCombat(charId)) {
                    continue;
                }
                
                // Get current stats from DB
                CharacterRecord rec = dao.findById(charId);
                if (rec == null) continue;
                
                // Check if needs regen
                if (rec.hpCur >= rec.hpMax && rec.mpCur >= rec.mpMax && rec.mvCur >= rec.mvMax) {
                    continue; // Already at full
                }
                
                // Calculate regen based on stance
                Stance stance = playerStances.getOrDefault(charId, Stance.STANDING);
                int percent = stance.getRegenPercent();
                
                int hpRegen = Math.max(1, (rec.hpMax * percent) / 100);
                int mpRegen = Math.max(1, (rec.mpMax * percent) / 100);
                int mvRegen = Math.max(1, (rec.mvMax * percent) / 100);
                
                int newHp = Math.min(rec.hpMax, rec.hpCur + hpRegen);
                int newMp = Math.min(rec.mpMax, rec.mpCur + mpRegen);
                int newMv = Math.min(rec.mvMax, rec.mvCur + mvRegen);
                
                // Only update if something changed
                if (newHp != rec.hpCur || newMp != rec.mpCur || newMv != rec.mvCur) {
                    dao.saveCharacterStateByName(rec.name, newHp, newMp, newMv, rec.currentRoom);
                }
            } catch (Exception e) {
                System.err.println("[RegenerationService] Error regenerating player " + charId + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Regenerate all mobiles who are not in combat and not dead.
     */
    private void regenerateMobiles() {
        List<Mobile> mobiles = mobileDao.getAllInstances();
        for (Mobile mobile : mobiles) {
            try {
                // Skip if dead or in combat
                if (mobile.isDead()) continue;
                if (combatManager.isInCombat(mobile)) continue;
                
                // Check if needs regen
                if (!mobile.needsRegen()) continue;
                
                // Mobiles use their stance (usually standing = 1% regen)
                Stance stance = mobile.getStance();
                int percent = stance.getRegenPercent();
                
                int hpRegen = Math.max(1, (mobile.getHpMax() * percent) / 100);
                int mpRegen = Math.max(1, (mobile.getMpMax() * percent) / 100);
                int mvRegen = Math.max(1, (mobile.getMvMax() * percent) / 100);
                
                mobile.setHpCur(mobile.getHpCur() + hpRegen);
                mobile.setMpCur(mobile.getMpCur() + mpRegen);
                mobile.setMvCur(mobile.getMvCur() + mvRegen);
                
                // Persist mobile state to DB
                mobileDao.updateInstance(mobile);
            } catch (Exception e) {
                System.err.println("[RegenerationService] Error regenerating mobile " + mobile.getInstanceId() + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Shutdown the regeneration service.
     */
    public void shutdown() {
        playerStances.clear();
    }
}
