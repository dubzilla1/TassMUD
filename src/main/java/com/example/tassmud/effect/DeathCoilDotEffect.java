package com.example.tassmud.effect;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Follow-up DOT for Death Coil critical hits.
 * Deals fixed damage every 3 seconds for 5 ticks.
 */
public class DeathCoilDotEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeathCoilDotEffect.class);
    private static final int DEFAULT_TICK_INTERVAL_MS = 3_000;

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        int dotDamage = parseInt(p.get("dot_damage"), 0);
        int ticksRemaining = parseInt(p.get("ticks_remaining"), 5);
        if (dotDamage <= 0 || ticksRemaining <= 0) return null;

        long now = System.currentTimeMillis();
        p.put("dot_damage", String.valueOf(dotDamage));
        p.put("ticks_remaining", String.valueOf(ticksRemaining));
        p.put("last_tick_ms", String.valueOf(now));
        p.putIfAbsent("tick_interval", "3");

        long durationMs = (long) (def.getDurationSeconds() * 1000L);
        long expiresAt = now + Math.max(durationMs, 20_000L);

        return new EffectInstance(UUID.randomUUID(), def.getId(), casterId, targetId, p, now, expiresAt, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        Map<String, String> p = instance.getParams();
        if (p == null) return;

        int ticksRemaining = parseInt(p.get("ticks_remaining"), 0);
        if (ticksRemaining <= 0) {
            EffectRegistry.removeInstance(instance.getId());
            return;
        }

        int tickIntervalMs = (int) (parseDouble(p.get("tick_interval"), 3.0) * 1000.0);
        if (tickIntervalMs <= 0) tickIntervalMs = DEFAULT_TICK_INTERVAL_MS;

        long lastTick = parseLong(p.get("last_tick_ms"), 0L);
        if (nowMs - lastTick < tickIntervalMs) return;

        int dotDamage = parseInt(p.get("dot_damage"), 0);
        if (dotDamage <= 0) {
            EffectRegistry.removeInstance(instance.getId());
            return;
        }

        applyDamageToTarget(instance.getTargetId(), dotDamage);

        Integer targetId = instance.getTargetId();
        Integer casterId = instance.getCasterId();
        notifyTarget(targetId, "Necrotic residue wracks you for " + dotDamage + " damage.");
        notifyCaster(casterId, "Necrotic residue wracks your target for " + dotDamage + " damage.");

        ticksRemaining--;
        p.put("ticks_remaining", String.valueOf(ticksRemaining));
        p.put("last_tick_ms", String.valueOf(nowMs));

        if (ticksRemaining <= 0) {
            notifyTarget(targetId, "The necrotic residue finally dissipates.");
            EffectRegistry.removeInstance(instance.getId());
        }
    }

    @Override
    public void expire(EffectInstance instance) {
        notifyTarget(instance.getTargetId(), "The necrotic residue finally dissipates.");
    }

    private void applyDamageToTarget(Integer targetId, int damage) {
        if (targetId == null || damage <= 0) return;

        CombatManager cm = CombatManager.getInstance();
        CharacterDAO dao = DaoProvider.characters();

        boolean isMobTarget = targetId < 0;
        Combatant targetCombatant = null;

        if (isMobTarget) {
            long mobInstanceId = -(long) targetId;
            for (Combat combat : cm.getAllActiveCombats()) {
                targetCombatant = combat.findByMobileInstanceId(mobInstanceId);
                if (targetCombatant != null) break;
            }
        } else {
            targetCombatant = cm.getCombatantForCharacter(targetId);
        }

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            targetCombatant.damage(damage);
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                GameCharacter ch = targetCombatant.getAsCharacter();
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
            return;
        }

        if (!isMobTarget) {
            CharacterDAO.CharacterRecord rec = dao.findById(targetId);
            if (rec != null) {
                int newHp = Math.max(0, rec.hpCur - damage);
                dao.saveCharacterStateByName(rec.name, newHp, rec.mpCur, rec.mvCur, rec.currentRoom);
            }
        }
    }

    private void notifyCaster(Integer casterId, String msg) {
        if (casterId != null && casterId > 0) {
            ClientHandler s = ClientHandler.charIdToSession.get(casterId);
            if (s != null) s.sendRaw(msg);
        }
    }

    private void notifyTarget(Integer targetId, String msg) {
        if (targetId != null && targetId > 0) {
            ClientHandler s = ClientHandler.charIdToSession.get(targetId);
            if (s != null) s.sendRaw(msg);
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
