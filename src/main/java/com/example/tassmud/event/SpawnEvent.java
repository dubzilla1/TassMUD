package com.example.tassmud.event;

import com.example.tassmud.model.*;
import com.example.tassmud.persistence.*;
import com.example.tassmud.util.SpawnEventLogger;
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
            SpawnEventLogger.error("[SpawnEvent] Error executing spawn " + config.getSpawnId() + ": " + e.getMessage());
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
                SpawnEventLogger.error("[SpawnEvent] Could not find/create container " + config.containerTemplateId + " in room " + config.roomId);
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
                    SpawnEventLogger.info("[SpawnEvent] Spawned " + name + " (instance #" + instanceId + ") in " + 
                        (containerInstanceId != null ? "container #" + containerInstanceId : "room " + config.roomId));
                }
            } catch (Exception e) {
                SpawnEventLogger.error("[SpawnEvent] Failed to spawn item: " + e.getMessage());
            }
        }
    }
    
    /**
     * Spawn mobs according to the config.
     */
    private void spawnMobs() {
        // Clean up empty corpses in the room before spawning
        cleanupEmptyCorpses();
        // Diagnostic: log spawn config being executed
        SpawnEventLogger.info("[SpawnEvent] Executing mob spawn config: " + config.toString());
        // Get configured mapping UUIDs for this spawn (one mapping per intended mob)
        List<String> mappingUuids = mobileDao.getSpawnMappingUUIDs(config.roomId, config.templateId);
        SpawnEventLogger.info("[SpawnEvent] Found " + (mappingUuids == null ? 0 : mappingUuids.size()) + " spawn mapping UUID(s) for template " + config.templateId + " in room " + config.roomId);
        if (mappingUuids == null || mappingUuids.isEmpty()) {
            // No canonical spawn mappings for this room/template - nothing to manage
            SpawnEventLogger.info("[SpawnEvent] No spawn mappings for template " + config.templateId + " in room " + config.roomId + "; skipping.");
            return;
        }

        // Ensure the template exists
        MobileTemplate template = mobileDao.getTemplateById(config.templateId);
        if (template == null) {
            SpawnEventLogger.error("[SpawnEvent] Mob template not found: " + config.templateId);
            return;
        }

        // For each mapping UUID, check globally if a live instance exists for that UUID. If not, spawn it.
        for (String uuid : mappingUuids) {
            try {
                Mobile existing = mobileDao.getInstanceByOriginUuid(uuid);
                if (existing != null) continue; // already present somewhere

                Mobile spawned = mobileDao.spawnMobile(template, config.roomId, uuid);
                if (spawned != null) {
                    SpawnEventLogger.info("[SpawnEvent] Spawned " + spawned.getName() + " (instance #" + spawned.getInstanceId() + ") in room " + config.roomId + " [uuid=" + uuid + "]");
                    // If this spawn config includes equipment, create item instances and apply their effects to the mobile
                    try {
                        if (config.equipment != null && !config.equipment.isEmpty()) {
                            for (java.util.Map<String,Object> eq : config.equipment) {
                                Object iv = eq.get("item_vnum");
                                if (iv == null) continue;
                                int itemVnum = Integer.parseInt(iv.toString());
                                long instId = itemDao.createInstance(itemVnum, null, null);
                                if (instId > 0) {
                                    // Persist marker linking this mobile instance to the item instance
                                    try { mobileDao.addMobileItemMarker(spawned.getInstanceId(), instId, "equip"); } catch (Exception ignore) {}
                                    ItemTemplate itmpl = itemDao.getTemplateById(itemVnum);
                                    applyEquipmentToMobile(spawned, instId, itmpl);
                                    SpawnEventLogger.info("[SpawnEvent] Equipped mob " + spawned.getName() + " with item " + itemVnum + " (instance #" + instId + ")");
                                }
                            }
                        }
                    } catch (Exception e) {
                        SpawnEventLogger.error("[SpawnEvent] Failed to equip spawned mob: " + e.getMessage());
                    }

                    // If the spawn config includes inventory entries (items given to the mob), create instances.
                    // Support items that should be placed inside a container (inventory entry contains `container_vnum`).
                    try {
                        if (config.inventory != null && !config.inventory.isEmpty()) {
                            // Map container template vnum -> created container instance id (one per container type per mob)
                            java.util.Map<Integer, Long> createdContainers = new java.util.HashMap<>();

                            for (java.util.Map<String,Object> inv : config.inventory) {
                                Object iv = inv.get("item_vnum");
                                if (iv == null) continue;
                                int itemVnum = Integer.parseInt(iv.toString());

                                // Does this inventory entry specify a container template vnum?
                                Integer containerVnum = null;
                                Object cv = inv.get("container_vnum");
                                if (cv != null) {
                                    try { containerVnum = Integer.parseInt(cv.toString()); } catch (Exception ignored) {}
                                }

                                long containerInstanceId = -1L;
                                if (containerVnum != null) {
                                    // Create the container instance for this mob (one per container template)
                                    if (!createdContainers.containsKey(containerVnum)) {
                                        long cinst = itemDao.createInstance(containerVnum, null, null);
                                        if (cinst > 0) {
                                            // Attach container to mob as inventory marker so it'll move to corpse later
                                            try {
                                                // persist container marker
                                                try { mobileDao.addMobileItemMarker(spawned.getInstanceId(), cinst, "inventory"); } catch (Exception ignore) {}
                                                com.example.tassmud.model.Modifier cm = new com.example.tassmud.model.Modifier("inventory#" + cinst, com.example.tassmud.model.Stat.ATTACK_HIT_BONUS, com.example.tassmud.model.Modifier.Op.ADD, 0, 0L, 0);
                                                java.util.UUID cmid = spawned.addModifier(cm);
                                                spawned.addEquipModifier(cmid);
                                                SpawnEventLogger.info("[SpawnEvent] Gave mob " + spawned.getName() + " container " + containerVnum + " (instance #" + cinst + ")");
                                            } catch (Exception ex) {
                                                SpawnEventLogger.error("[SpawnEvent] Failed to attach container marker to mob: " + ex.getMessage());
                                            }
                                            createdContainers.put(containerVnum, cinst);
                                        } else {
                                            SpawnEventLogger.error("[SpawnEvent] Failed to create container instance for template " + containerVnum + " for mob " + spawned.getName());
                                        }
                                    }
                                    Long c = createdContainers.get(containerVnum);
                                    if (c != null) containerInstanceId = c.longValue();
                                }

                                long instId;
                                if (containerInstanceId > 0) {
                                    instId = itemDao.createInstance(itemVnum, null, null, containerInstanceId);
                                } else {
                                    instId = itemDao.createInstance(itemVnum, null, null);
                                }

                                if (instId > 0) {
                                    // Track this instance on the mob by adding a harmless modifier with source inventory#<id>
                                    try {
                                        // persist inventory marker
                                        try { mobileDao.addMobileItemMarker(spawned.getInstanceId(), instId, "inventory"); } catch (Exception ignore) {}
                                        com.example.tassmud.model.Modifier m = new com.example.tassmud.model.Modifier("inventory#" + instId, com.example.tassmud.model.Stat.ATTACK_HIT_BONUS, com.example.tassmud.model.Modifier.Op.ADD, 0, 0L, 0);
                                        java.util.UUID mid = spawned.addModifier(m);
                                        spawned.addEquipModifier(mid);
                                        SpawnEventLogger.info("[SpawnEvent] Gave mob " + spawned.getName() + " inventory item " + itemVnum + " (instance #" + instId + ")" + (containerInstanceId > 0 ? " inside container #" + containerInstanceId : ""));
                                    } catch (Exception ex) {
                                        SpawnEventLogger.error("[SpawnEvent] Failed to attach inventory marker to mob: " + ex.getMessage());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        SpawnEventLogger.error("[SpawnEvent] Failed to give inventory to spawned mob: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                SpawnEventLogger.error("[SpawnEvent] Failed to spawn mapped mob: " + e.getMessage());
            }
        }
    }

    /**
     * Apply item template equip bonuses to a mobile at runtime by adding Modifiers.
     * This does not persist mobile equipment; it applies effects in-memory so mobs
     * behave as if they have the item equipped.
     */
    private void applyEquipmentToMobile(Mobile mob, long itemInstanceId, ItemTemplate tmpl) {
        if (mob == null || tmpl == null) return;
        // Armor/save bonuses
        try {
            java.time.Instant never = java.time.Instant.ofEpochMilli(0);
            long expires = 0L; // permanent for mob lifetime
            // Armor
            if (tmpl.armorSaveBonus != 0) {
                Modifier m = new Modifier("equip#" + itemInstanceId, Stat.ARMOR, Modifier.Op.ADD, tmpl.armorSaveBonus, expires, 0);
                java.util.UUID id = mob.addModifier(m);
                mob.addEquipModifier(id);
            }
            if (tmpl.fortSaveBonus != 0) {
                Modifier m = new Modifier("equip#" + itemInstanceId, Stat.FORTITUDE, Modifier.Op.ADD, tmpl.fortSaveBonus, expires, 0);
                java.util.UUID id = mob.addModifier(m);
                mob.addEquipModifier(id);
            }
            if (tmpl.refSaveBonus != 0) {
                Modifier m = new Modifier("equip#" + itemInstanceId, Stat.REFLEX, Modifier.Op.ADD, tmpl.refSaveBonus, expires, 0);
                java.util.UUID id = mob.addModifier(m);
                mob.addEquipModifier(id);
            }
            if (tmpl.willSaveBonus != 0) {
                Modifier m = new Modifier("equip#" + itemInstanceId, Stat.WILL, Modifier.Op.ADD, tmpl.willSaveBonus, expires, 0);
                java.util.UUID id = mob.addModifier(m);
                mob.addEquipModifier(id);
            }
        } catch (Exception ignored) {}
    }
    
    /**
     * Clean up empty corpses in this room.
     * Called during mob respawn to remove corpses that have been looted.
     */
    private void cleanupEmptyCorpses() {
        try {
            int deleted = itemDao.deleteEmptyCorpsesInRoom(config.roomId);
            if (deleted > 0) {
                SpawnEventLogger.info("[SpawnEvent] Cleaned up " + deleted + " empty corpse(s) in room " + config.roomId);
            }
        } catch (Exception e) {
            SpawnEventLogger.error("[SpawnEvent] Failed to cleanup corpses in room " + config.roomId + ": " + e.getMessage());
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
                SpawnEventLogger.error("[SpawnEvent] Container template " + config.containerTemplateId + " not found or not a container");
                return null;
            }
            
            long containerInstanceId = itemDao.createInstance(config.containerTemplateId, config.roomId, null);
            SpawnEventLogger.info("[SpawnEvent] Created container " + containerTemplate.name + " (instance #" + containerInstanceId + ") in room " + config.roomId);
            return containerInstanceId;
        } catch (Exception e) {
            SpawnEventLogger.error("[SpawnEvent] Failed to create container: " + e.getMessage());
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
