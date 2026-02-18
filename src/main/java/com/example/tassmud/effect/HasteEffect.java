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
 * Effect handler for HASTE buff — the opposite of {@link SlowEffect}.
 * <p>
 * While hasted a combatant receives one guaranteed extra basic attack each
 * combat round (no proficiency roll required).  Applying Haste automatically
 * removes any active Slow effect on the target, and vice-versa.
 * <p>
 * Duration scales with caster proficiency (default 30–120 seconds).
 */
public class HasteEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(HasteEffect.class);

    /** Effect definition ID — must match effects.yaml. */
    public static final String EFFECT_HASTE = "1020";

    // ── apply ──────────────────────────────────────────────────────

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Proficiency from caster (passed by spell handler)
        int proficiency = 1;
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Duration: linear scale min→max based on proficiency
        double baseDuration = def.getDurationSeconds();     // max (e.g. 120s)
        double minDuration = 30.0;
        try {
            minDuration = Double.parseDouble(
                    def.getParams().getOrDefault("min_duration", "30"));
        } catch (Exception ignored) {}
        double scaledDuration = minDuration + (baseDuration - minDuration) * (proficiency / 100.0);
        long durationMs = (long) (scaledDuration * 1000);

        // Cancel any active Slow on the target (opposite effect)
        EffectRegistry.removeAllEffectsOfType(targetId, SlowEffect.EFFECT_SLOW);

        // Messaging
        CharacterDAO dao = DaoProvider.characters();
        String targetName = "someone";
        Integer roomId = null;
        CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) {
            targetName = trec.name;
            roomId = trec.currentRoom;
        }

        ClientHandler.broadcastRoomMessage(roomId,
                targetName + "'s movements accelerate to a blur!");
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw(
                    "\u001B[1;36mA surge of magical energy quickens your every motion! ("
                    + String.format("%.0f", scaledDuration) + "s)\u001B[0m");
        }

        logger.debug("[haste] applied to {} for {}s ({}% proficiency)",
                targetId, scaledDuration, proficiency);

        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        return new EffectInstance(id, def.getId(), casterId, targetId, p,
                now, now + durationMs, def.getPriority());
    }

    // ── expire ─────────────────────────────────────────────────────

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        Integer targetId = instance.getTargetId();
        if (targetId == null) return;

        CharacterDAO.CharacterRecord trec = DaoProvider.characters().findById(targetId);
        if (trec != null) {
            ClientHandler.broadcastRoomMessage(trec.currentRoom,
                    trec.name + "'s movements slow back to normal speed.");
        }
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw(
                    "\u001B[36mThe burst of speed fades and your movements return to normal.\u001B[0m");
        }
        logger.debug("[haste] expired on target {}", targetId);
    }

    // ── tick ────────────────────────────────────────────────────────

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic tick — combat manager checks isHasted() each round.
    }

    // ── static queries ─────────────────────────────────────────────

    /**
     * Check whether a character currently has the Haste effect.
     */
    public static boolean isHasted(Integer characterId) {
        return EffectRegistry.hasEffect(characterId, EFFECT_HASTE);
    }
}
