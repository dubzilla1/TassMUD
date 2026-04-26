package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton tracking active aura effects and their recipient sets.
 * <p>
 * When an aura caster or recipient changes rooms, every movement site must call
 * {@link #onPlayerRoomChange(Integer, Integer, Integer)} so that child effects
 * are applied or removed appropriately.
 * <p>
 * Thread-safety: all maps use {@link ConcurrentHashMap}; iteration over
 * recipient sets is guarded by snapshotting with {@code new ArrayList<>()}.
 */
public class AuraManager {

    private static final Logger logger = LoggerFactory.getLogger(AuraManager.class);
    private static final AuraManager INSTANCE = new AuraManager();

    public static AuraManager getInstance() {
        return INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Inner state record
    // -----------------------------------------------------------------------

    private static final class AuraState {
        final Integer casterId;
        final String childEffectDefId;
        final int proficiency;
        volatile Integer currentRoomId;
        /** Character IDs currently receiving the child effect (includes the caster). */
        final Set<Integer> recipientIds = ConcurrentHashMap.newKeySet();
        /** Extra params forwarded to the child effect on apply (may be empty). */
        final Map<String, String> childExtraParams;
        /** Message sent to a recipient when they gain the child effect (null = default). */
        final String joinMessage;
        /** Message sent to a recipient when they lose the effect because they left the room (null = default). */
        final String leaveMessage;
        /** Message sent to a recipient when the caster departs (null = default). */
        final String departsMessage;

        AuraState(Integer casterId, String childEffectDefId, int proficiency, Integer roomId) {
            this(casterId, childEffectDefId, proficiency, roomId, null, null, null, null);
        }

        AuraState(Integer casterId, String childEffectDefId, int proficiency, Integer roomId,
                  Map<String, String> childExtraParams,
                  String joinMessage, String leaveMessage, String departsMessage) {
            this.casterId = casterId;
            this.childEffectDefId = childEffectDefId;
            this.proficiency = proficiency;
            this.currentRoomId = roomId;
            this.childExtraParams = childExtraParams != null ? new HashMap<>(childExtraParams) : new HashMap<>();
            this.joinMessage = joinMessage;
            this.leaveMessage = leaveMessage;
            this.departsMessage = departsMessage;
        }
    }

    /** caster character ID → active aura */
    private final Map<Integer, AuraState> aurasByCaster = new ConcurrentHashMap<>();
    /** room ID → set of caster IDs whose aura is currently in that room */
    private final Map<Integer, Set<Integer>> aurasByRoom = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Register a new aura for {@code casterId}. If the caster already has an aura,
     * it is fully removed first. The child effect is immediately applied to every PC
     * currently in {@code roomId} (including the caster).
     *
     * @param casterId           the character casting the aura
     * @param roomId             the room the caster is in when the aura is applied
     * @param childEffectDefId   the effect definition ID to apply to recipients
     * @param proficiency        caster proficiency, forwarded to child effect scaling
     */
    public void registerAura(Integer casterId, Integer roomId, String childEffectDefId, int proficiency) {
        registerAura(casterId, roomId, childEffectDefId, proficiency, null, null, null, null);
    }

    /**
     * Extended overload that supports per-aura extra params and flavor messages.
     *
     * @param childExtraParams  extra params merged into child effect apply call (e.g. {@code "value"} for AC bonus)
     * @param joinMessage       sent to recipient when they gain the child effect
     * @param leaveMessage      sent to recipient when they leave the aura room
     * @param departsMessage    sent to recipient when the caster departs
     */
    public void registerAura(Integer casterId, Integer roomId, String childEffectDefId, int proficiency,
                             Map<String, String> childExtraParams,
                             String joinMessage, String leaveMessage, String departsMessage) {
        if (casterId == null || roomId == null) return;
        deregisterAura(casterId); // clean up any prior aura (e.g. re-cast)

        AuraState state = new AuraState(casterId, childEffectDefId, proficiency, roomId,
                childExtraParams, joinMessage, leaveMessage, departsMessage);
        aurasByCaster.put(casterId, state);
        aurasByRoom.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(casterId);
        applyToAllPcsInRoom(state, roomId);
        logger.debug("[AuraManager] Registered aura for caster={} room={} child={}", casterId, roomId, childEffectDefId);
    }

    /**
     * Fully removes the caster's aura. The child effect is stripped from all current
     * recipients <em>without</em> sending them a notification — the caller
     * ({@link SanctuaryAuraEffect#expire}) is responsible for any room messaging.
     */
    public void deregisterAura(Integer casterId) {
        AuraState state = aurasByCaster.remove(casterId);
        if (state == null) return;

        Set<Integer> roomSet = aurasByRoom.get(state.currentRoomId);
        if (roomSet != null) roomSet.remove(casterId);

        for (Integer recipientId : new ArrayList<>(state.recipientIds)) {
            EffectRegistry.removeAllEffectsOfType(recipientId, state.childEffectDefId);
        }
        state.recipientIds.clear();
        logger.debug("[AuraManager] Deregistered aura for caster={}", casterId);
    }

    /**
     * Returns {@code true} if the given character currently has an active aura registered.
     */
    public boolean hasCasterAura(Integer casterId) {
        return casterId != null && aurasByCaster.containsKey(casterId);
    }

    /**
     * Must be called every time a player character changes rooms.
     * <ul>
     *   <li>If the character is an aura <em>caster</em>, the aura moves with them:
     *       child effects are removed from the old room's recipients and applied to
     *       the new room's PCs.</li>
     *   <li>Otherwise, child effects from auras in the old room are removed, and
     *       any aura in the new room applies its child effect to this character.</li>
     * </ul>
     *
     * @param charId     the character that moved (no-op if null)
     * @param fromRoomId the room they left (may be null)
     * @param toRoomId   the room they entered (may be null)
     */
    public void onPlayerRoomChange(Integer charId, Integer fromRoomId, Integer toRoomId) {
        if (charId == null) return;

        AuraState casterState = aurasByCaster.get(charId);
        if (casterState != null) {
            // This player is an aura caster: relocate the aura.
            moveCasterAura(casterState, fromRoomId, toRoomId);
        } else {
            // Regular recipient: drop old-room aura child effect, gain new-room one.
            removeAsRecipient(charId, fromRoomId);
            addAsRecipient(charId, toRoomId);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void moveCasterAura(AuraState state, Integer fromRoomId, Integer toRoomId) {
        // Strip child effect from all current recipients and notify non-caster ones.
        for (Integer recipientId : new ArrayList<>(state.recipientIds)) {
            EffectRegistry.removeAllEffectsOfType(recipientId, state.childEffectDefId);
            if (!recipientId.equals(state.casterId)) {
                String msg = state.departsMessage != null ? state.departsMessage
                        : "\u001B[33mThe divine sanctuary aura fades as your healer departs.\u001B[0m";
                ClientHandler.sendToCharacter(recipientId, msg);
            }
        }
        state.recipientIds.clear();

        // Remove from old room index.
        if (fromRoomId != null) {
            Set<Integer> old = aurasByRoom.get(fromRoomId);
            if (old != null) old.remove(state.casterId);
        }

        if (toRoomId == null) return; // Caster moved to a non-existent room.

        state.currentRoomId = toRoomId;
        aurasByRoom.computeIfAbsent(toRoomId, k -> ConcurrentHashMap.newKeySet()).add(state.casterId);
        applyToAllPcsInRoom(state, toRoomId);
    }

    private void removeAsRecipient(Integer charId, Integer fromRoomId) {
        if (fromRoomId == null) return;
        Set<Integer> castersInRoom = aurasByRoom.get(fromRoomId);
        if (castersInRoom == null || castersInRoom.isEmpty()) return;

        for (Integer casterId : castersInRoom) {
            AuraState state = aurasByCaster.get(casterId);
            if (state != null && state.recipientIds.remove(charId)) {
                EffectRegistry.removeAllEffectsOfType(charId, state.childEffectDefId);
                String msg = state.leaveMessage != null ? state.leaveMessage
                        : "\u001B[33mThe divine sanctuary aura fades as you step away from the sanctified area.\u001B[0m";
                ClientHandler.sendToCharacter(charId, msg);
            }
        }
    }

    private void addAsRecipient(Integer charId, Integer toRoomId) {
        if (toRoomId == null) return;
        Set<Integer> castersInRoom = aurasByRoom.get(toRoomId);
        if (castersInRoom == null || castersInRoom.isEmpty()) return;

        for (Integer casterId : castersInRoom) {
            AuraState state = aurasByCaster.get(casterId);
            if (state != null && !state.recipientIds.contains(charId)) {
                applyChildEffect(state, charId);
            }
        }
    }

    /** Applies the child effect to every online PC currently in {@code roomId}. */
    private void applyToAllPcsInRoom(AuraState state, Integer roomId) {
        // charIdToSession is a ConcurrentHashMap<Integer, ClientHandler>
        for (Map.Entry<Integer, ClientHandler> entry : ClientHandler.charIdToSession.entrySet()) {
            Integer sessionCharId = entry.getKey();
            ClientHandler session = entry.getValue();
            if (session.currentRoomId == null || !session.currentRoomId.equals(roomId)) continue;
            applyChildEffect(state, sessionCharId);
        }
    }

    private void applyChildEffect(AuraState state, Integer recipientId) {
        if (state.recipientIds.contains(recipientId)) return; // already receiving

        Map<String, String> extra = new HashMap<>();
        extra.put("proficiency", String.valueOf(state.proficiency));
        extra.put("aura_source", "true"); // tells child handler to use infinite duration
        if (state.childExtraParams != null) extra.putAll(state.childExtraParams);

        EffectInstance inst = EffectRegistry.apply(
                state.childEffectDefId, state.casterId, recipientId, extra);
        if (inst != null) {
            state.recipientIds.add(recipientId);
            String msg = state.joinMessage != null ? state.joinMessage
                    : "\u001B[92mA warm divine aura washes over you, mending your wounds.\u001B[0m";
            ClientHandler.sendToCharacter(recipientId, msg);
        }
    }
}
