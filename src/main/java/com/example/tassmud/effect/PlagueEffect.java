package com.example.tassmud.effect;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.util.MobileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Effect handler for Plague — an escalating disease DOT.
 *
 * Each tick rolls 1d6 and adds it to accumulated damage from prior ticks,
 * then deals that total to the target. After each tick, a 1d100 resist roll
 * is made; if the roll is less than the damage dealt this tick, the plague
 * ends early as the target fights it off.
 *
 * Duration: (20 + proficiency) seconds, capped naturally by the effect.
 * Tick interval: 3 seconds.
 * Undead are immune (checked via EFFECT_UNDEAD effect flag).
 */
public class PlagueEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(PlagueEffect.class);
    private static final long TICK_INTERVAL_MS = 3_000L;
    private static final String KEY_LAST_TICK = "last_tick_ms";
    private static final String KEY_ACCUM = "accumulated_damage";
    /** Effect ID for the UNDEAD flag — undead are immune to disease. */
    private static final String EFFECT_UNDEAD = "2000";

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        // Undead are immune to plague
        if (EffectRegistry.hasEffect(targetId, EFFECT_UNDEAD)) {
            notifyCaster(casterId, "The undead are immune to plague.");
            return null;
        }

        // Compute proficiency (1–100%)
        int proficiency = 1;
        if (extraParams != null && extraParams.containsKey("proficiency")) {
            try { proficiency = Integer.parseInt(extraParams.get("proficiency")); } catch (Exception ignored) {}
        }
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Duration: 20s base + 1s per proficiency % (e.g. 20s at 1%, 120s at 100%)
        long nowMs = System.currentTimeMillis();
        long durationMs = (20L + proficiency) * 1000L;
        long expiresAtMs = nowMs + durationMs;

        // Init params map for tick state
        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);
        p.put(KEY_ACCUM, "0");
        p.put(KEY_LAST_TICK, String.valueOf(nowMs));

        // Apply messages
        String targetName = resolveTargetName(targetId);
        notifyTarget(targetId, "You are overtaken by a hideous plague!");
        notifyCaster(casterId, targetName + " is overtaken by a hideous plague!");

        logger.debug("[PlagueEffect] Applied to {} for {}ms (prof={})", targetName, durationMs, proficiency);
        return new EffectInstance(UUID.randomUUID(), def.getId(), casterId, targetId, p, nowMs, expiresAtMs, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        Map<String, String> p = instance.getParams();

        // Enforce tick interval
        long lastTick = 0;
        try { lastTick = Long.parseLong(p.getOrDefault(KEY_LAST_TICK, "0")); } catch (Exception ignored) {}
        if (nowMs - lastTick < TICK_INTERVAL_MS) return;
        p.put(KEY_LAST_TICK, String.valueOf(nowMs));

        // Accumulate damage: prior total + 1d6
        int accumulated = 0;
        try { accumulated = Integer.parseInt(p.getOrDefault(KEY_ACCUM, "0")); } catch (Exception ignored) {}
        int roll = ThreadLocalRandom.current().nextInt(1, 7);
        int tickDamage = accumulated + roll;
        p.put(KEY_ACCUM, String.valueOf(tickDamage));

        Integer targetId = instance.getTargetId();
        Integer casterId = instance.getCasterId();

        // Deal accumulated damage to target
        dealDamage(targetId, casterId, tickDamage);

        // Roll 1d100 — if roll < tickDamage, target fights off the plague early
        int resistRoll = ThreadLocalRandom.current().nextInt(1, 101);
        if (resistRoll < tickDamage) {
            sendEndMessages(targetId, casterId);
            EffectRegistry.removeInstance(instance.getId());
        }
    }

    @Override
    public void expire(EffectInstance instance) {
        sendEndMessages(instance.getTargetId(), instance.getCasterId());
    }

    // ---- Private helpers ----

    private void dealDamage(Integer targetId, Integer casterId, int damage) {
        if (targetId == null || damage <= 0) return;

        boolean isMob = targetId < 0;
        String targetName = resolveTargetName(targetId);
        String verb = getVerb(damage);

        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = null;

        if (isMob) {
            long mobInstanceId = -(long) targetId;
            for (Combat combat : cm.getAllActiveCombats()) {
                targetCombatant = combat.findByMobileInstanceId(mobInstanceId);
                if (targetCombatant != null) break;
            }
        } else if (targetId > 0) {
            targetCombatant = cm.getCombatantForCharacter(targetId);
        }

        CharacterDAO dao = DaoProvider.characters();

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            com.example.tassmud.model.GameCharacter ch = targetCombatant.getAsCharacter();
            int oldHp = ch.getHpCur();
            ch.setHpCur(oldHp - damage);
            int newHp = ch.getHpCur();

            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                dao.saveCharacterStateByName(ch.getName(), newHp, ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }

            if (targetCombatant.isPlayer()) {
                notifyTarget(targetId, "The vicious plague " + verb + " you for " + damage + " damage! [" + newHp + " HP]");
            }
            notifyCaster(casterId, "The vicious plague " + verb + " " + targetName + " for " + damage + " damage!");

            if (newHp <= 0) {
                logger.info("[PlagueEffect] Plague killed {}", targetName);
            }
        } else if (!isMob && targetId > 0) {
            // Out-of-combat player
            CharacterDAO.CharacterRecord rec = dao.findById(targetId);
            if (rec == null) return;
            int newHp = Math.max(0, rec.hpCur - damage);
            dao.saveCharacterStateByName(rec.name, newHp, rec.mpCur, rec.mvCur, rec.currentRoom);
            notifyTarget(targetId, "The vicious plague " + verb + " you for " + damage + " damage! [" + newHp + " HP]");
            notifyCaster(casterId, "The vicious plague " + verb + " " + targetName + " for " + damage + " damage!");
        }
    }

    private void sendEndMessages(Integer targetId, Integer casterId) {
        String targetName = resolveTargetName(targetId);
        notifyTarget(targetId, "You manage to fight off the plague.");
        notifyCaster(casterId, targetName + " manages to fight off the plague.");
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

    private String resolveTargetName(Integer targetId) {
        if (targetId == null) return "someone";
        if (targetId > 0) {
            CharacterDAO.CharacterRecord rec = DaoProvider.characters().findById(targetId);
            return rec != null ? rec.name : "someone";
        }
        // Negative ID = mob; instanceId is -targetId
        com.example.tassmud.model.Mobile mob = MobileRegistry.getInstance().getById(-(long) targetId);
        return mob != null ? mob.getName() : "something";
    }

    /** Damage verb that scales with total accumulated damage. */
    private String getVerb(int damage) {
        if (damage <= 5)  return "festers upon";
        if (damage <= 10) return "ravages";
        if (damage <= 20) return "devastates";
        if (damage <= 30) return "obliterates";
        return "annihilates";
    }
}
