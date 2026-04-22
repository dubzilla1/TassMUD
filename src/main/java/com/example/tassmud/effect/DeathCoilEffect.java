package com.example.tassmud.effect;

import com.example.tassmud.combat.Combat;
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
 * Death Coil: instant negative damage that can critically strike.
 * On crit, applies a fixed DOT for 5 ticks (every 3 seconds), where
 * per-tick DOT damage is half the crit damage dealt.
 */
public class DeathCoilEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeathCoilEffect.class);
    private static final String CRIT_DOT_EFFECT_ID = "1019";
    private static final int BASE_CRIT_THRESHOLD = 20;
    private static final double CRIT_MULTIPLIER = 2.0;

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (casterId == null || targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) {
            logger.warn("[death coil] No dice defined");
            return null;
        }

        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return null;

        int baseN;
        int dieM;
        try {
            baseN = Integer.parseInt(raw.substring(0, dIdx));
            dieM = Integer.parseInt(raw.substring(dIdx + 1));
        } catch (Exception e) {
            logger.warn("[death coil] Invalid dice format: {}", raw);
            return null;
        }

        int proficiency = parseInt(p.get("proficiency"), 1);
        proficiency = Math.max(1, Math.min(100, proficiency));

        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (proficiency / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        int damage = 0;
        for (int i = 0; i < scaledN; i++) {
            damage += ThreadLocalRandom.current().nextInt(1, dieM + 1);
        }

        int critThreshold = BASE_CRIT_THRESHOLD + getCasterCritThresholdBonus(casterId);
        critThreshold = Math.max(2, critThreshold);
        int critRoll = ThreadLocalRandom.current().nextInt(1, 21);
        boolean isCrit = critRoll >= critThreshold;

        if (isCrit) {
            damage = (int) Math.max(1, Math.round(damage * CRIT_MULTIPLIER));
        }

        applyDamageToTarget(targetId, damage);

        String targetName = resolveTargetName(targetId);
        notifyTarget(targetId, isCrit
                ? "A CRITICAL Death Coil devastates you for " + damage + " damage!"
                : "Death Coil strikes you for " + damage + " damage!");
        notifyCaster(casterId, isCrit
                ? "CRITICAL! Your Death Coil devastates " + targetName + " for " + damage + " damage!"
                : "Your Death Coil hits " + targetName + " for " + damage + " damage.");

        if (isCrit && damage > 0) {
            int dotPerTick = Math.max(1, damage / 2);
            Map<String, String> dotParams = new HashMap<>();
            dotParams.put("dot_damage", String.valueOf(dotPerTick));
            dotParams.put("ticks_remaining", "5");
            dotParams.put("tick_interval", "3");
            EffectRegistry.apply(CRIT_DOT_EFFECT_ID, casterId, targetId, dotParams);

            notifyCaster(casterId, "Necrotic residue clings to " + targetName + " for " + dotPerTick + " damage per tick.");
            notifyTarget(targetId, "Necrotic residue clings to you, continuing to deal damage!");
        }

        logger.debug("[death coil] caster={} target={} damage={} prof={} crit={} roll={} threshold={}",
                casterId, targetId, damage, proficiency, isCrit, critRoll, critThreshold);

        long now = System.currentTimeMillis();
        return new EffectInstance(UUID.randomUUID(), def.getId(), casterId, targetId, p, now, now, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // Instant effect; no ticks.
    }

    @Override
    public void expire(EffectInstance instance) {
        // Instant effect; nothing to expire.
    }

    private int getCasterCritThresholdBonus(Integer casterId) {
        Combatant casterCombatant = CombatManager.getInstance().getCombatantForCharacter(casterId);
        if (casterCombatant != null) {
            return casterCombatant.getCriticalThresholdBonus();
        }
        return 0;
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

    private String resolveTargetName(Integer targetId) {
        if (targetId == null) return "someone";
        if (targetId > 0) {
            CharacterDAO.CharacterRecord rec = DaoProvider.characters().findById(targetId);
            return rec != null ? rec.name : "someone";
        }
        com.example.tassmud.model.Mobile mob = MobileRegistry.getInstance().getById(-(long) targetId);
        return mob != null ? mob.getName() : "something";
    }

    private int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
