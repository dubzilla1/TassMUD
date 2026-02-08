package com.example.tassmud.util;

import com.example.tassmud.model.Mobile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory registry of all live mobile (NPC/monster) instances.
 * <p>
 * Replaces the expensive pattern of calling {@code MobileDAO.getAllInstances()}
 * (full DB SELECT + JOIN) on every tick of {@code MobileRoamingService} (1 s)
 * and {@code RegenerationService} (10 s).  Also replaces per-room DB queries
 * ({@code MobileDAO.getMobilesInRoom()}) used by combat, look, give, etc.
 * <p>
 * <b>Contract</b>:
 * <ul>
 *   <li>{@link #register(Mobile)} after every {@code MobileDAO.spawnMobile()}.</li>
 *   <li>{@link #unregister(long)} before/after every
 *       {@code MobileDAO.deleteInstance()}.</li>
 *   <li>{@link #moveToRoom(long, int, int)} whenever a mob changes rooms
 *       (roaming, teleport).</li>
 *   <li>{@link #clear()} on server startup (matches
 *       {@code MobileDAO.clearAllInstances()}).</li>
 * </ul>
 * <p>
 * All public methods are thread-safe. The registry holds the <em>canonical</em>
 * in-memory {@code Mobile} reference — the same object is returned to every
 * caller, so mutations (HP changes, stance, etc.) are immediately visible
 * everywhere without an additional DB round-trip.
 */
public class MobileRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MobileRegistry.class);

    private static final MobileRegistry INSTANCE = new MobileRegistry();

    /** Primary index: instanceId → Mobile */
    private final ConcurrentHashMap<Long, Mobile> byId = new ConcurrentHashMap<>();

    /** Secondary index: roomId → set of instanceIds in that room */
    private final ConcurrentHashMap<Integer, Set<Long>> byRoom = new ConcurrentHashMap<>();

    private MobileRegistry() {}

    public static MobileRegistry getInstance() {
        return INSTANCE;
    }

    // ── Mutation ────────────────────────────────────────────────────────

    /**
     * Register a newly-spawned mobile. Call immediately after
     * {@code MobileDAO.spawnMobile()}.
     */
    public void register(Mobile mob) {
        if (mob == null) return;
        long id = mob.getInstanceId();
        byId.put(id, mob);

        Integer room = mob.getCurrentRoom();
        if (room != null) {
            byRoom.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(id);
        }

        logger.debug("[MobileRegistry] Registered mob {} (instance #{}) in room {}",
                mob.getName(), id, room);
    }

    /**
     * Unregister a mobile (death, GM slay, despawn).  Call when the instance
     * is removed from the world.
     */
    public void unregister(long instanceId) {
        Mobile mob = byId.remove(instanceId);
        if (mob != null) {
            Integer room = mob.getCurrentRoom();
            if (room != null) {
                Set<Long> set = byRoom.get(room);
                if (set != null) {
                    set.remove(instanceId);
                    // Don't bother removing empty sets — negligible memory.
                }
            }
            logger.debug("[MobileRegistry] Unregistered mob {} (instance #{})",
                    mob.getName(), instanceId);
        }
    }

    /**
     * Update the room index when a mobile moves.
     *
     * @param instanceId  the mobile's instance ID
     * @param oldRoomId   the room it is leaving
     * @param newRoomId   the room it is entering
     */
    public void moveToRoom(long instanceId, int oldRoomId, int newRoomId) {
        Set<Long> oldSet = byRoom.get(oldRoomId);
        if (oldSet != null) oldSet.remove(instanceId);

        byRoom.computeIfAbsent(newRoomId, k -> ConcurrentHashMap.newKeySet()).add(instanceId);
    }

    /**
     * Remove all tracked mobiles (server restart).
     */
    public void clear() {
        int count = byId.size();
        byId.clear();
        byRoom.clear();
        logger.info("[MobileRegistry] Cleared {} registered mobiles", count);
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /**
     * Get all registered (alive) mobiles. Returns the canonical in-memory
     * objects — callers may read/modify their state directly.
     */
    public Collection<Mobile> getAll() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /**
     * Get all alive mobiles in a specific room.  Filters out dead mobs
     * defensively (in case unregister hasn't been called yet).
     */
    public List<Mobile> getByRoom(int roomId) {
        Set<Long> ids = byRoom.get(roomId);
        if (ids == null || ids.isEmpty()) return Collections.emptyList();

        List<Mobile> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Mobile mob = byId.get(id);
            if (mob != null && !mob.isDead()) {
                result.add(mob);
            }
        }
        return result;
    }

    /**
     * Look up a single mobile by instance ID, or {@code null} if not found.
     */
    public Mobile getById(long instanceId) {
        return byId.get(instanceId);
    }

    /**
     * Total number of registered mobile instances.
     */
    public int size() {
        return byId.size();
    }
}
