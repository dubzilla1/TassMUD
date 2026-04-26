package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for the {@code PROTECTION_AURA} effect type (id "1026").
 * <p>
 * The paladin's Aura of Protection — mirrors the Sanctuary aura plumbing:
 * <ol>
 *   <li>Locates the caster's current room via the active session map.</li>
 *   <li>Registers an aura with {@link AuraManager}, which immediately applies
 *       the child effect ({@code "1027"} AURA_MODIFIER) to every PC in the room.</li>
 *   <li>Movement hooks call {@link AuraManager#onPlayerRoomChange} to keep
 *       the recipient set in sync as players come and go.</li>
 *   <li>When this effect expires, the aura is deregistered and all recipients
 *       lose the child AC modifier.</li>
 * </ol>
 *
 * The AC bonus value is stored in extraParams as {@code "value"} and forwarded to
 * the child effect, which applies it as an ADD modifier to the ARMOR stat.
 */
public class ProtectionAuraEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProtectionAuraEffect.class);

    /** Effect definition ID of the child AC modifier applied to recipients. */
    static final String CHILD_EFFECT_DEF_ID = "1027"; // AURA_MODIFIER

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (casterId == null || targetId == null) return null;

        int proficiency = 1;
        if (extraParams != null && extraParams.containsKey("proficiency")) {
            try { proficiency = Integer.parseInt(extraParams.get("proficiency")); }
            catch (NumberFormatException ignored) {}
        }
        proficiency = Math.max(1, Math.min(100, proficiency));

        // AC bonus is computed by the spell handler (paladin level / 5) and passed in.
        String acValueStr = extraParams != null ? extraParams.get("value") : null;
        if (acValueStr == null) acValueStr = "1";

        // Find the caster's current room.
        Integer roomId = null;
        for (Map.Entry<Integer, ClientHandler> entry : ClientHandler.charIdToSession.entrySet()) {
            if (casterId.equals(entry.getKey())) {
                roomId = entry.getValue().currentRoomId;
                break;
            }
        }
        if (roomId == null) {
            logger.warn("[ProtectionAuraEffect] Could not determine room for caster={}", casterId);
            return null;
        }

        long nowMs = System.currentTimeMillis();

        // Base 120s scaled by proficiency (0.5–1.0 range to match proficiency_impact: DURATION)
        double frac = Math.max(1, proficiency) / 100.0;
        long durationMs = (long)(120_000L * frac);
        long expiresAtMs = nowMs + Math.max(1_000L, durationMs);

        Map<String, String> params = new HashMap<>();
        params.put("proficiency", String.valueOf(proficiency));
        params.put("child_effect_id", CHILD_EFFECT_DEF_ID);
        params.put("value", acValueStr);

        EffectInstance inst = new EffectInstance(
                UUID.randomUUID(), def.getId(), casterId, targetId,
                params, nowMs, expiresAtMs, def.getPriority());

        String casterName = resolveName(casterId);

        ClientHandler.sendToCharacter(casterId,
                "\u001B[1;96mYou radiate a protective aura, shielding nearby allies!\u001B[0m");
        ClientHandler.broadcastRoomMessage(roomId,
                "\u001B[96mA protective divine aura radiates from " + casterName +
                        ", bolstering the defenses of all nearby!\u001B[0m",
                casterId);

        // Register the aura — child effect applied to all PCs in room now, and as they enter.
        Map<String, String> childExtra = new HashMap<>();
        childExtra.put("value", acValueStr);
        AuraManager.getInstance().registerAura(casterId, roomId, CHILD_EFFECT_DEF_ID, proficiency,
                childExtra,
                "\u001B[96mA protective divine aura washes over you, fortifying your defenses.\u001B[0m",
                "\u001B[33mThe protective aura fades as you step away from the holy ward.\u001B[0m",
                "\u001B[33mThe protective aura fades as your paladin departs.\u001B[0m");

        logger.info("[ProtectionAuraEffect] Caster={} room={} acBonus={} proficiency={} duration={}ms",
                casterId, roomId, acValueStr, proficiency, expiresAtMs - nowMs);
        return inst;
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic logic needed.
    }

    @Override
    public void expire(EffectInstance instance) {
        Integer casterId = instance.getCasterId();
        if (casterId == null) return;

        AuraManager.getInstance().deregisterAura(casterId);

        ClientHandler.sendToCharacter(casterId,
                "\u001B[33mYour protective aura fades, the holy ward withdrawing.\u001B[0m");

        ClientHandler session = ClientHandler.charIdToSession.get(casterId);
        if (session != null && session.currentRoomId != null) {
            ClientHandler.broadcastRoomMessage(session.currentRoomId,
                    "\u001B[33mThe protective aura fades as the holy ward is withdrawn.\u001B[0m",
                    casterId);
        }
        logger.info("[ProtectionAuraEffect] Aura of Protection expired for caster={}", casterId);
    }

    private String resolveName(Integer charId) {
        if (charId == null) return "someone";
        ClientHandler session = ClientHandler.charIdToSession.get(charId);
        return (session != null && session.playerName != null) ? session.playerName : "someone";
    }
}
