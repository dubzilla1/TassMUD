package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
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
 * Effect handler for Regen — a divine HOT (Heal Over Time).
 * Every 3 seconds, heals the target for 1d8 HP.
 * Duration: 20s base + 1s per proficiency % (e.g. 21s at 1%, 120s at 100%).
 */
public class RegenEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(RegenEffect.class);
    private static final long TICK_INTERVAL_MS = 3_000L;
    private static final String KEY_LAST_TICK = "last_tick_ms";

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        int proficiency = 1;
        if (extraParams != null && extraParams.containsKey("proficiency")) {
            try { proficiency = Integer.parseInt(extraParams.get("proficiency")); } catch (Exception ignored) {}
        }
        proficiency = Math.max(1, Math.min(100, proficiency));

        // When applied by an aura, use infinite duration (AuraManager removes it explicitly).
        boolean auraSource = extraParams != null && "true".equals(extraParams.get("aura_source"));

        long nowMs = System.currentTimeMillis();
        // Duration scales with proficiency: 20s base + 1s per % = 21s to 120s
        long durationMs = (20L + proficiency) * 1_000L;
        // expiresAtMs = 0 means never expires naturally (managed externally by AuraManager).
        long expiresAtMs = auraSource ? 0L : nowMs + durationMs;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);
        p.put(KEY_LAST_TICK, String.valueOf(nowMs));

        // Notify caster and target only for direct casts; aura applications send their own messages.
        if (!auraSource) {
            String targetName = resolveTargetName(targetId);
            notifyTarget(targetId, "\u001B[92mYou are suffused with a warm, divine regeneration.\u001B[0m");
            if (casterId != null && !casterId.equals(targetId)) {
                notifyCaster(casterId, "\u001B[92mA warm glow settles over " + targetName + " as divine regeneration takes hold.\u001B[0m");
            }
            logger.debug("[RegenEffect] Applied to {} for {}ms (prof={})", targetName, durationMs, proficiency);
        } else {
            logger.debug("[RegenEffect] Aura-sourced application to targetId={} (infinite duration)", targetId);
        }

        return new EffectInstance(UUID.randomUUID(), def.getId(), casterId, targetId, p, nowMs, expiresAtMs, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        Map<String, String> p = instance.getParams();

        long lastTick = 0;
        try { lastTick = Long.parseLong(p.getOrDefault(KEY_LAST_TICK, "0")); } catch (Exception ignored) {}

        if (nowMs - lastTick < TICK_INTERVAL_MS) return;
        p.put(KEY_LAST_TICK, String.valueOf(nowMs));

        int roll = ThreadLocalRandom.current().nextInt(1, 9); // 1d8
        applyHeal(instance.getTargetId(), instance.getCasterId(), roll);
    }

    @Override
    public void expire(EffectInstance instance) {
        notifyTarget(instance.getTargetId(), "\u001B[33mThe divine regeneration fades.\u001B[0m");
    }

    // ---- Private helpers ----

    private void applyHeal(Integer targetId, Integer casterId, int amount) {
        if (targetId == null || amount <= 0) return;

        CombatManager cm = CombatManager.getInstance();
        CharacterDAO dao = DaoProvider.characters();
        Combatant targetCombatant = cm.getCombatantForCharacter(targetId);

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            GameCharacter ch = targetCombatant.getAsCharacter();
            int oldHp = ch.getHpCur();
            ch.heal(amount);
            int actualHeal = ch.getHpCur() - oldHp;

            if (actualHeal <= 0) return; // already at max HP

            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }

            notifyTarget(targetId, "\u001B[92mDivine energy mends your wounds. [+" + actualHeal + " HP]\u001B[0m");
        } else if (targetId > 0) {
            // Out-of-combat player
            CharacterDAO.CharacterRecord rec = dao.findById(targetId);
            if (rec == null) return;
            int newHp = Math.min(rec.hpMax, rec.hpCur + amount);
            int actualHeal = newHp - rec.hpCur;
            if (actualHeal <= 0) return;
            dao.saveCharacterStateByName(rec.name, newHp, rec.mpCur, rec.mvCur, rec.currentRoom);
            notifyTarget(targetId, "\u001B[92mDivine energy mends your wounds. [+" + actualHeal + " HP]\u001B[0m");
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

    private String resolveTargetName(Integer targetId) {
        if (targetId == null) return "someone";
        if (targetId > 0) {
            CharacterDAO.CharacterRecord rec = DaoProvider.characters().findById(targetId);
            return rec != null ? rec.name : "someone";
        }
        com.example.tassmud.model.Mobile mob = MobileRegistry.getInstance().getById(-(long) targetId);
        return mob != null ? mob.getName() : "something";
    }
}
