package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for Flurry of Blows (Monk ki skill).
 *
 * While active:
 * <ul>
 *   <li>Each combat round the monk has a chance for an extra basic attack
 *       (25% + flurry proficiency / 2).</li>
 *   <li>Normal damage hits no longer generate ki – only critical hits do.</li>
 * </ul>
 *
 * Duration: 10 seconds + 1 second per flurry proficiency (10–110s).
 * Stack policy: REFRESH (re-using flurry resets the timer).
 */
public class FlurryEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(FlurryEffect.class);

    /** Effect definition ID (must match effects.yaml). */
    public static final String EFFECT_FLURRY = "flurry";

    // ── apply / expire ─────────────────────────────────────────────

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Read proficiency from extra params (set by the command handler)
        int proficiency = 1;
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Duration: 10s base + 1s per proficiency point
        double durationSec = 10.0 + proficiency;
        long durationMs = (long) (durationSec * 1000);

        // Messaging
        CharacterDAO.CharacterRecord trec = DaoProvider.characters().findById(targetId);
        if (trec != null) {
            ClientHandler.broadcastRoomMessage(trec.currentRoom,
                    trec.name + "'s fists blur as they enter a flurry of strikes!");
        }
        ClientHandler ts = ClientHandler.charIdToSession.get(targetId);
        if (ts != null) {
            ts.sendRaw("\u001B[1;33mYou focus your ki and unleash a flurry of blows! ("
                    + String.format("%.0f", durationSec) + "s)\u001B[0m");
        }

        logger.debug("[flurry] applied to {} for {}s (prof {}%)", targetId, durationSec, proficiency);

        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        return new EffectInstance(id, def.getId(), casterId, targetId, p, now, now + durationMs, def.getPriority());
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        Integer targetId = instance.getTargetId();
        if (targetId == null) return;

        CharacterDAO.CharacterRecord trec = DaoProvider.characters().findById(targetId);
        if (trec != null) {
            ClientHandler.broadcastRoomMessage(trec.currentRoom,
                    trec.name + "'s striking speed returns to normal.");
        }
        ClientHandler ts = ClientHandler.charIdToSession.get(targetId);
        if (ts != null) {
            ts.sendRaw("\u001B[33mYour flurry of blows subsides.\u001B[0m");
        }
        logger.debug("[flurry] expired on target {}", targetId);
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic tick — flurry is checked during combat round processing.
    }

    // ── static queries ─────────────────────────────────────────────

    /**
     * Check whether a character currently has the Flurry of Blows effect.
     */
    public static boolean hasFlurry(Integer characterId) {
        return EffectRegistry.hasEffect(characterId, EFFECT_FLURRY);
    }
}
