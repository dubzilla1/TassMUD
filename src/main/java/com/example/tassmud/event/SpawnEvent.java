package com.example.tassmud.event;

import com.example.tassmud.model.*;
import com.example.tassmud.persistence.*;
import java.util.List;

/**
 * Event that spawns items or mobs in a room based on a SpawnConfig.
 * Handles spawning into containers by finding or creating the container first.
 */
public class SpawnEvent implements GameEvent {
    
    private final SpawnConfig config;
    private final ItemDAO itemDao;
    private final MobileDAO mobileDao;
    
    public SpawnEvent(SpawnConfig config) {
        this.config = config;
        this.itemDao = new ItemDAO();
        this.mobileDao = new MobileDAO();
    }
    
    @Override
    public void execute() {
        try {
            if (config.type == SpawnConfig.SpawnType.ITEM) {
                spawnItems();
            } else if (config.type == SpawnConfig.SpawnType.MOB) {
                spawnMobs();
            }
        } catch (Exception e) {
            System.err.println("[SpawnEvent] Error executing spawn " + config.getSpawnId() + ": " + e.getMessage());
        }
    }
    
    /**
     * Spawn items according to the config.
     */
    private void spawnItems() {
        // Check if spawning into a container
        Long containerInstanceId = null;
        if (config.hasContainer()) {
            containerInstanceId = findOrCreateContainer();
            if (containerInstanceId == null) {
                System.err.println("[SpawnEvent] Could not find/create container " + config.containerTemplateId + " in room " + config.roomId);
                return;
            }
        }
        
        // Count existing items of this template in the target location
        int existingCount = countExistingItems(containerInstanceId);
        int toSpawn = Math.max(0, config.quantity - existingCount);
        
        if (toSpawn <= 0) {
            // Already at or above capacity
            return;
        }
        
        // Spawn the items
        for (int i = 0; i < toSpawn; i++) {
            try {
                long instanceId;
                if (containerInstanceId != null) {
                    instanceId = itemDao.createInstance(config.templateId, null, null, containerInstanceId);
                } else {
                    instanceId = itemDao.createInstance(config.templateId, config.roomId, null);
                }
                
                if (instanceId > 0) {
                    ItemTemplate tmpl = itemDao.getTemplateById(config.templateId);
                    String name = tmpl != null ? tmpl.name : "item #" + config.templateId;
                    System.out.println("[SpawnEvent] Spawned " + name + " (instance #" + instanceId + ") in " + 
                        (containerInstanceId != null ? "container #" + containerInstanceId : "room " + config.roomId));
                }
            } catch (Exception e) {
                System.err.println("[SpawnEvent] Failed to spawn item: " + e.getMessage());
            }
        }
    }
    
    /**
     * Spawn mobs according to the config.
     */
    private void spawnMobs() {
        // Clean up empty corpses in the room before spawning
        cleanupEmptyCorpses();
        
        // Count existing mobs of this template in the room
        List<Mobile> roomMobs = mobileDao.getMobilesInRoom(config.roomId);
        int existingCount = 0;
        for (Mobile mob : roomMobs) {
            if (mob.getTemplateId() == config.templateId) {
                existingCount++;
            }
        }
        
        int toSpawn = Math.max(0, config.quantity - existingCount);
        
        if (toSpawn <= 0) {
            return;
        }
        
        // Spawn the mobs
        MobileTemplate template = mobileDao.getTemplateById(config.templateId);
        if (template == null) {
            System.err.println("[SpawnEvent] Mob template not found: " + config.templateId);
            return;
        }
        
        for (int i = 0; i < toSpawn; i++) {
            try {
                Mobile spawned = mobileDao.spawnMobile(template, config.roomId);
                if (spawned != null) {
                    System.out.println("[SpawnEvent] Spawned " + spawned.getName() + " (instance #" + spawned.getInstanceId() + ") in room " + config.roomId);
                }
            } catch (Exception e) {
                System.err.println("[SpawnEvent] Failed to spawn mob: " + e.getMessage());
            }
        }
    }
    
    /**
     * Clean up empty corpses in this room.
     * Called during mob respawn to remove corpses that have been looted.
     */
    private void cleanupEmptyCorpses() {
        try {
            int deleted = itemDao.deleteEmptyCorpsesInRoom(config.roomId);
            if (deleted > 0) {
                System.out.println("[SpawnEvent] Cleaned up " + deleted + " empty corpse(s) in room " + config.roomId);
            }
        } catch (Exception e) {
            System.err.println("[SpawnEvent] Failed to cleanup corpses in room " + config.roomId + ": " + e.getMessage());
        }
    }
    
    /**
     * Find an existing container instance in the room, or create one if needed.
     * @return The container instance ID, or null if not found/created
     */
    private Long findOrCreateContainer() {
        // First, look for an existing container of this template in the room
        List<ItemDAO.RoomItem> roomItems = itemDao.getItemsInRoom(config.roomId);
        for (ItemDAO.RoomItem ri : roomItems) {
            if (ri.template.id == config.containerTemplateId && ri.template.isContainer()) {
                return ri.instance.instanceId;
            }
        }
        
        // No existing container found - create one
        try {
            ItemTemplate containerTemplate = itemDao.getTemplateById(config.containerTemplateId);
            if (containerTemplate == null || !containerTemplate.isContainer()) {
                System.err.println("[SpawnEvent] Container template " + config.containerTemplateId + " not found or not a container");
                return null;
            }
            
            long containerInstanceId = itemDao.createInstance(config.containerTemplateId, config.roomId, null);
            System.out.println("[SpawnEvent] Created container " + containerTemplate.name + " (instance #" + containerInstanceId + ") in room " + config.roomId);
            return containerInstanceId;
        } catch (Exception e) {
            System.err.println("[SpawnEvent] Failed to create container: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Count how many items of this template exist in the target location.
     * @param containerInstanceId The container to check, or null for room
     */
    private int countExistingItems(Long containerInstanceId) {
        List<ItemDAO.RoomItem> items;
        if (containerInstanceId != null) {
            items = itemDao.getItemsInContainer(containerInstanceId);
        } else {
            items = itemDao.getItemsInRoom(config.roomId);
        }
        
        int count = 0;
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.id == config.templateId) {
                count++;
            }
        }
        return count;
    }
}
