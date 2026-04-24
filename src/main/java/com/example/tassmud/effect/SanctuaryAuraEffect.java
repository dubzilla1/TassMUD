package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for the {@code SANCTUARY_AURA} effect type (id "1022").
 * <p>
 * When applied to the caster (self-cast spell), this handler:
 * <ol>
 *   <li>Locates the caster's current room via the active session map.</li>
 *   <li>Registers an aura with {@link AuraManager}, which immediately applies
 *       the child effect ({@code "1023"} AURA_REGEN_HOT) to every PC in the room.</li>
 *   <li>As the caster moves, movement hooks call
 *       {@link AuraManager#onPlayerRoomChange} to keep the recipient set in sync.</li>
 *   <li>When this effect expires, the aura is deregistered and all recipients
 *       lose the child effect.</li>
 * </ol>
 *
 * The child effect ID {@code "1023"} is separate from the normal Regen spell's
 * {@code "1021"} so that manually removing aura-sourced regen does not accidentally
 * strip a recipient's independently-cast Regen buff.
 */
public class SanctuaryAuraEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(SanctuaryAuraEffect.class);

    /** Effect definition ID of the child HOT applied to recipients. */
    static final String CHILD_EFFECT_DEF_ID = "1023"; // AURA_REGEN_HOT

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (casterId == null || targetId == null) return null;

        // Extract proficiency.
        int proficiency = 1;
        if (extraParams != null && extraParams.containsKey("proficiency")) {
            try { proficiency = Integer.parseInt(extraParams.get("proficiency")); }
            catch (NumberFormatException ignored) {}
        }
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Find the caster's current room from the active session map.
        Integer roomId = null;
        for (Map.Entry<Integer, ClientHandler> entry : ClientHandler.charIdToSession.entrySet()) {
            if (casterId.equals(entry.getKey())) {
                roomId = entry.getValue().currentRoomId;
                break;
            }
        }
        if (roomId == null) {
            logger.warn("[SanctuaryAuraEffect] Could not determine room for caster={}", casterId);
            return null;
        }

        long nowMs = System.currentTimeMillis();
        // 60 s base + up to 540 s from proficiency → max 600 s
        long durationMs = Math.min(600_000L, 60_000L + (long) proficiency * 5_400L);
        long expiresAtMs = nowMs + durationMs;

        Map<String, String> params = new HashMap<>();
        params.put("proficiency", String.valueOf(proficiency));
        params.put("child_effect_id", CHILD_EFFECT_DEF_ID);

        EffectInstance inst = new EffectInstance(
                UUID.randomUUID(), def.getId(), casterId, targetId,
                params, nowMs, expiresAtMs, def.getPriority());

        // Notify the caster.
        ClientHandler.sendToCharacter(casterId,
                "\u001B[92mYou open your soul to the divine, radiating a healing sanctuary around you.\u001B[0m");

        // Notify others in the room (the caster is excluded by broadcastRoomMessage).
        String casterName = resolveName(casterId);
        ClientHandler.broadcastRoomMessage(roomId,
                "\u001B[92mA warm divine light radiates from " + casterName +
                        ", filling the room with sacred grace.\u001B[0m",
                casterId);

        // Register the aura — applies child effect to all PCs currently in the room.
        AuraManager.getInstance().registerAura(casterId, roomId, CHILD_EFFECT_DEF_ID, proficiency);

        logger.info("[SanctuaryAuraEffect] Caster={} room={} proficiency={} duration={}ms",
                casterId, roomId, proficiency, durationMs);
        return inst;
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic logic needed — child effects tick independently via EffectScheduler.
    }

    @Override
    public void expire(EffectInstance instance) {
        Integer casterId = instance.getCasterId();
        if (casterId == null) return;

        // Deregister the aura — silently strips child effects from all recipients.
        AuraManager.getInstance().deregisterAura(casterId);

        // Notify the caster.
        ClientHandler.sendToCharacter(casterId,
                "\u001B[33mYour sanctuary aura fades, the divine light withdrawing from the room.\u001B[0m");

        // Notify others in the caster's current room.
        ClientHandler session = ClientHandler.charIdToSession.get(casterId);
        if (session != null && session.currentRoomId != null) {
            ClientHandler.broadcastRoomMessage(session.currentRoomId,
                    "\u001B[33mThe sanctuary aura fades as the divine grace is withdrawn.\u001B[0m",
                    casterId);
        }
        logger.info("[SanctuaryAuraEffect] Sanctuary expired for caster={}", casterId);
    }

    private String resolveName(Integer charId) {
        if (charId == null) return "someone";
        ClientHandler session = ClientHandler.charIdToSession.get(charId);
        return (session != null && session.playerName != null) ? session.playerName : "someone";
    }
}
