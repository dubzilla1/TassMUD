package com.example.tassmud.event;

import com.example.tassmud.persistence.CharacterDAO;
import java.util.*;

/**
 * Manages spawn registration and scheduling for all areas.
 * Spaces out area spawns to prevent all spawns from firing at once.
 */
public class SpawnManager {
    
    private static SpawnManager instance;
    
    /** Spawn configs grouped by area ID */
    private final Map<Integer, List<SpawnConfig>> spawnsByArea;
    
    /** Track registered spawn IDs to prevent duplicates */
    private final Set<String> registeredSpawns;
    
    /** Base stagger delay between areas (in milliseconds) */
    private static final long AREA_STAGGER_MS = 5000; // 5 seconds between areas
    
    /** Stagger delay within an area between different spawns */
    private static final long SPAWN_STAGGER_MS = 500; // 0.5 seconds between spawns in same area
    
    private SpawnManager() {
        this.spawnsByArea = new HashMap<>();
        this.registeredSpawns = new HashSet<>();
    }
    
    public static synchronized SpawnManager getInstance() {
        if (instance == null) {
            instance = new SpawnManager();
        }
        return instance;
    }
    
    /**
     * Register a spawn config. Does not schedule yet - call scheduleAllSpawns() after loading.
     * @param areaId The area this spawn belongs to
     * @param config The spawn configuration
     */
    public void registerSpawn(int areaId, SpawnConfig config) {
        String spawnId = config.getSpawnId();
        if (registeredSpawns.contains(spawnId)) {
            return; // Already registered
        }
        
        spawnsByArea.computeIfAbsent(areaId, k -> new ArrayList<>()).add(config);
        registeredSpawns.add(spawnId);
    }
    
    /**
     * Schedule all registered spawns with the event scheduler.
     * Staggers spawns by area to spread out the load.
     */
    public void scheduleAllSpawns() {
        EventScheduler scheduler = EventScheduler.getInstance();
        
        // Sort areas for consistent ordering
        List<Integer> areaIds = new ArrayList<>(spawnsByArea.keySet());
        Collections.sort(areaIds);
        
        int areaIndex = 0;
        int totalScheduled = 0;
        
        for (Integer areaId : areaIds) {
            List<SpawnConfig> areaSpawns = spawnsByArea.get(areaId);
            if (areaSpawns == null || areaSpawns.isEmpty()) continue;
            
            // Calculate base delay for this area
            long areaBaseDelay = areaIndex * AREA_STAGGER_MS;
            
            int spawnIndex = 0;
            for (SpawnConfig config : areaSpawns) {
                // Calculate initial delay for this spawn
                long initialDelay = areaBaseDelay + (spawnIndex * SPAWN_STAGGER_MS);
                
                // Get the recurring period
                long periodMs = config.getDelayMs();
                
                // Create and schedule the spawn event
                SpawnEvent event = new SpawnEvent(config);
                scheduler.scheduleRecurring(config.getSpawnId(), event, initialDelay, periodMs);
                
                spawnIndex++;
                totalScheduled++;
            }
            
            areaIndex++;
        }
        
        System.out.println("[SpawnManager] Scheduled " + totalScheduled + " spawns across " + areaIds.size() + " areas");
    }
    
    /**
     * Trigger an immediate spawn check for all registered spawns.
     * Useful for initial server startup to populate the world.
     */
    public void triggerInitialSpawns() {
        System.out.println("[SpawnManager] Triggering initial spawns...");
        
        int total = 0;
        for (List<SpawnConfig> areaSpawns : spawnsByArea.values()) {
            for (SpawnConfig config : areaSpawns) {
                try {
                    SpawnEvent event = new SpawnEvent(config);
                    event.execute();
                    total++;
                } catch (Exception e) {
                    System.err.println("[SpawnManager] Error in initial spawn: " + e.getMessage());
                }
            }
        }
        
        System.out.println("[SpawnManager] Completed " + total + " initial spawn checks");
    }
    
    /**
     * Clear all registered spawns.
     */
    public void clear() {
        spawnsByArea.clear();
        registeredSpawns.clear();
    }
    
    /**
     * Get the total number of registered spawn configs.
     */
    public int getSpawnCount() {
        return registeredSpawns.size();
    }
    
    /**
     * Get spawn configs for a specific area.
     */
    public List<SpawnConfig> getSpawnsForArea(int areaId) {
        return spawnsByArea.getOrDefault(areaId, Collections.emptyList());
    }
}
