package com.example.tassmud.util;

import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.AllyBehavior;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.DaoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages all live NPC-ally-to-player relationships.
 *
 * <p>This is a singleton with two concurrent maps for O(1) access in both directions:
 * <ul>
 *   <li>{@link #alliesByOwner} — owner character ID → list of bindings (for owner's perspective)</li>
 *   <li>{@link #bindingByMobInstance} — mob instance ID → binding (for mob's perspective)</li>
 * </ul>
 *
 * <h3>Integration Points</h3>
 * <ul>
 *   <li>{@code CombatManager.initiateCombat} / {@code mobileInitiateCombat} — DEFENDER allies
 *       auto-join combat via {@link #getDefenderAlliesInRoom}</li>
 *   <li>{@code CombatManager.processAutoflee} — PERMANENT allies flee with owner via
 *       {@link #getAlliesFollowingOwner}</li>
 *   <li>{@code MovementCommandHandler} — allies with {@code followsOwner=true} move with player
 *       via {@link #getFollowingAlliesInRoom}</li>
 *   <li>{@code DeathHandler} — ally mob death triggers {@link #onAllyDeath}</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * Both maps are {@link ConcurrentHashMap}; per-owner lists are {@code synchronizedList}-wrapped
 * copies.  All mutation methods synchronize on the per-owner list to avoid lost updates.
 */
public class AllyManager {

    private static final Logger logger = LoggerFactory.getLogger(AllyManager.class);

    // ── singleton ─────────────────────────────────────────────────────

    private static final AllyManager INSTANCE = new AllyManager();

    private AllyManager() {}

    public static AllyManager getInstance() {
        return INSTANCE;
    }

    // ── state ─────────────────────────────────────────────────────────

    /** owner characterId → list of bindings. */
    private final ConcurrentHashMap<Integer, List<AllyBinding>> alliesByOwner = new ConcurrentHashMap<>();

    /** mob instanceId → binding (reverse-lookup). */
    private final ConcurrentHashMap<Long, AllyBinding> bindingByMobInstance = new ConcurrentHashMap<>();

    // ── mutation ──────────────────────────────────────────────────────

    /**
     * Register a new ally relationship.  If the mob is already bound (to anyone),
     * the previous binding is first removed.
     */
    public void bindAlly(AllyBinding binding) {
        long mobId = binding.getMobInstanceId();
        int ownerId = binding.getOwnerCharacterId();

        // Remove any pre-existing binding for this mob instance
        AllyBinding old = bindingByMobInstance.remove(mobId);
        if (old != null) {
            removeFromOwnerList(old.getOwnerCharacterId(), mobId);
            logger.debug("[AllyManager] Replaced existing binding for mob {} (was owned by {})",
                    mobId, old.getOwnerCharacterId());
        }

        // Add the new binding
        bindingByMobInstance.put(mobId, binding);
        alliesByOwner.computeIfAbsent(ownerId, k -> Collections.synchronizedList(new ArrayList<>()))
                     .add(binding);

        logger.debug("[AllyManager] Bound mob {} to owner {} as {} / {}",
                mobId, ownerId, binding.getBehavior(), binding.getPersistence());
    }

    /**
     * Explicitly release an ally from its owner (e.g. {@code release} command or quest end).
     * Removes the binding regardless of persistence.
     */
    public void releaseAlly(int ownerCharacterId, long mobInstanceId) {
        AllyBinding removed = bindingByMobInstance.remove(mobInstanceId);
        if (removed != null) {
            removeFromOwnerList(ownerCharacterId, mobInstanceId);
            logger.debug("[AllyManager] Released mob {} from owner {}", mobInstanceId, ownerCharacterId);
        }
    }

    /**
     * Called when an ally mob dies.
     * <ul>
     *   <li>TEMPORARY bindings are removed immediately.</li>
     *   <li>PERMANENT bindings retain the {@link AllyBinding} in {@link #alliesByOwner}
     *       (keyed by owner) but the instance-ID reverse lookup is cleared so the dead
     *       mob is no longer treated as an active combatant.  When the mob respawns, the
     *       caller should invoke {@link #reattachPermanentAlly} to update the instance ID.</li>
     * </ul>
     *
     * @param mobInstanceId instance ID of the mob that just died
     */
    public void onAllyDeath(long mobInstanceId) {
        AllyBinding binding = bindingByMobInstance.remove(mobInstanceId);
        if (binding == null) return;

        if (binding.shouldSurviveDeath()) {
            // Keep in owner list for re-attachment; just clear instance-based lookup
            logger.debug("[AllyManager] Permanent ally {} died — kept binding for owner {}, awaiting respawn",
                    mobInstanceId, binding.getOwnerCharacterId());
        } else {
            removeFromOwnerList(binding.getOwnerCharacterId(), mobInstanceId);
            logger.debug("[AllyManager] Temporary ally {} died — binding removed for owner {}",
                    mobInstanceId, binding.getOwnerCharacterId());
        }
    }

    /**
     * Re-attach a PERMANENT ally binding to a freshly respawned mob instance.
     * Looks up the owner's pending (dead) binding by template ID and updates it to point
     * at the new instance ID.
     *
     * @param ownerCharacterId the player who owns the permanent ally
     * @param mobTemplateId    the template ID of the respawned mob
     * @param newMobInstanceId the new instance ID of the spawned mob
     * @return true if a pending binding was found and updated
     */
    public boolean reattachPermanentAlly(int ownerCharacterId, long mobTemplateId, long newMobInstanceId) {
        List<AllyBinding> list = alliesByOwner.get(ownerCharacterId);
        if (list == null) return false;

        synchronized (list) {
            for (AllyBinding b : list) {
                if (b.getMobTemplateId() == mobTemplateId
                        && !bindingByMobInstance.containsKey(b.getMobInstanceId())) {
                    // This is a dead permanent binding — create a replacement with the new instanceId
                    AllyBinding updated = new AllyBinding(
                            newMobInstanceId,
                            b.getOwnerCharacterId(),
                            b.getMobTemplateId(),
                            b.getBehavior(),
                            b.getPersistence(),
                            b.isFollowsOwner(),
                            b.isObeys(),
                            b.getExpiresAt());
                    list.remove(b);
                    list.add(updated);
                    bindingByMobInstance.put(newMobInstanceId, updated);
                    logger.debug("[AllyManager] Re-attached permanent ally template {} as instance {} for owner {}",
                            mobTemplateId, newMobInstanceId, ownerCharacterId);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove all bindings for a player who has disconnected or whose character was deleted.
     */
    public void releaseAllAllies(int ownerCharacterId) {
        List<AllyBinding> list = alliesByOwner.remove(ownerCharacterId);
        if (list == null) return;
        synchronized (list) {
            for (AllyBinding b : list) {
                bindingByMobInstance.remove(b.getMobInstanceId());
            }
        }
        logger.debug("[AllyManager] Released all allies for owner {}", ownerCharacterId);
    }

    // ── queries ───────────────────────────────────────────────────────

    /**
     * Returns all live bindings for an owner (snapshot copy — safe to iterate).
     */
    public List<AllyBinding> getAlliesForOwner(int ownerCharacterId) {
        List<AllyBinding> list = alliesByOwner.get(ownerCharacterId);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /**
     * Returns bindings for DEFENDER/HUNTER allies in a specific room.
     * Used by {@code CombatManager} to find allies that should auto-join combat.
     *
     * @param ownerCharacterId the player entering combat
     * @param roomId           the room where combat is happening
     */
    public List<AllyBinding> getDefenderAlliesInRoom(int ownerCharacterId, int roomId) {
        return getAlliesForOwner(ownerCharacterId).stream()
                .filter(b -> !b.isExpired())
                .filter(AllyBinding::shouldAutoDefend)
                .filter(b -> {
                    Mobile mob = MobileRegistry.getInstance().getById(b.getMobInstanceId());
                    return mob != null && Integer.valueOf(roomId).equals(mob.getCurrentRoom());
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns bindings for allies that are following the owner and are currently in {@code roomId}.
     * Used by {@code MovementCommandHandler} after the owner moves.
     *
     * @param ownerCharacterId the player who just moved
     * @param fromRoomId       the room the player moved away from
     */
    public List<AllyBinding> getFollowingAlliesInRoom(int ownerCharacterId, int fromRoomId) {
        return getAlliesForOwner(ownerCharacterId).stream()
                .filter(b -> !b.isExpired())
                .filter(AllyBinding::isFollowsOwner)
                .filter(b -> {
                    Mobile mob = MobileRegistry.getInstance().getById(b.getMobInstanceId());
                    return mob != null && Integer.valueOf(fromRoomId).equals(mob.getCurrentRoom());
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns bindings for allies that should flee with the owner.
     * Used by {@code CombatManager.processAutoflee} — only PERMANENT allies flee with owner.
     *
     * @param ownerCharacterId the player who is fleeing
     * @param fromRoomId       the room being fled from
     */
    public List<AllyBinding> getAlliesFollowingOwner(int ownerCharacterId, int fromRoomId) {
        return getAlliesForOwner(ownerCharacterId).stream()
                .filter(b -> !b.isExpired())
                .filter(AllyBinding::shouldFleeWithOwner)
                .filter(b -> {
                    Mobile mob = MobileRegistry.getInstance().getById(b.getMobInstanceId());
                    return mob != null && Integer.valueOf(fromRoomId).equals(mob.getCurrentRoom());
                })
                .collect(Collectors.toList());
    }

    /**
     * Look up the binding for a specific mob instance (e.g. to check if a mob is allied
     * before determining its combat alliance).
     */
    public AllyBinding getBindingForMob(long mobInstanceId) {
        return bindingByMobInstance.get(mobInstanceId);
    }

    /**
     * Returns true if the given mob instance is currently allied to any player.
     */
    public boolean isAlly(long mobInstanceId) {
        AllyBinding b = bindingByMobInstance.get(mobInstanceId);
        return b != null && !b.isExpired();
    }

    /**
     * Returns the behavior of the given allied mob, or {@code null} if not allied.
     */
    public AllyBehavior getBehavior(long mobInstanceId) {
        AllyBinding b = bindingByMobInstance.get(mobInstanceId);
        return (b != null && !b.isExpired()) ? b.getBehavior() : null;
    }

    /**
     * Returns true if the owner currently has a live tamed companion binding
     * (created via the Ranger's Tame skill).
     */
    public boolean hasCompanion(int ownerCharacterId) {
        return getCompanionBinding(ownerCharacterId) != null;
    }

    /**
     * Returns the tamed companion binding for the owner, or null if they have none.
     */
    public AllyBinding getCompanionBinding(int ownerCharacterId) {
        return getAlliesForOwner(ownerCharacterId).stream()
                .filter(b -> !b.isExpired() && b.isTamedCompanion())
                .findFirst()
                .orElse(null);
    }

    /** Total number of active (live) bindings tracked. */
    public int size() {
        return bindingByMobInstance.size();
    }

    // ── expiry cleanup ────────────────────────────────────────────────

    /**
     * Sweep all bindings and remove expired ones.  For TEMPORARY expired bindings,
     * the mob is also killed (marked dead, removed from MobileRegistry) and the
     * owner is notified.
     *
     * <p>Intended to be called periodically from a TickService task (e.g. every 10s).
     */
    public void sweepExpiredBindings() {
        long now = System.currentTimeMillis();
        // Snapshot the values — iterating ConcurrentHashMap is safe
        for (AllyBinding b : bindingByMobInstance.values()) {
            if (b.getExpiresAt() > 0 && now > b.getExpiresAt()) {
                long mobId = b.getMobInstanceId();
                int ownerId = b.getOwnerCharacterId();

                // Remove the binding
                bindingByMobInstance.remove(mobId);
                removeFromOwnerList(ownerId, mobId);

                // Despawn the mob
                Mobile mob = MobileRegistry.getInstance().getById(mobId);
                if (mob != null) {
                    Integer roomId = mob.getCurrentRoom();
                    String mobName = mob.getName();
                    MobileRegistry.getInstance().unregister(mobId);
                    DaoProvider.mobiles().deleteInstance(mobId);

                    // Notify owner and room
                    ClientHandler.sendToCharacter(ownerId,
                            mobName + " crumbles to dust as the dark magic fades.");
                    if (roomId != null) {
                        ClientHandler.broadcastRoomMessage(roomId,
                                mobName + " crumbles to dust.", ownerId);
                    }
                    logger.debug("[AllyManager] Expired binding — despawned {} for owner {}", mobId, ownerId);
                } else {
                    logger.debug("[AllyManager] Expired binding for mob {} (already gone) — cleaned up", mobId);
                }
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────

    private void removeFromOwnerList(int ownerCharacterId, long mobInstanceId) {
        List<AllyBinding> list = alliesByOwner.get(ownerCharacterId);
        if (list == null) return;
        synchronized (list) {
            list.removeIf(b -> b.getMobInstanceId() == mobInstanceId);
            if (list.isEmpty()) {
                alliesByOwner.remove(ownerCharacterId);
            }
        }
    }
}
