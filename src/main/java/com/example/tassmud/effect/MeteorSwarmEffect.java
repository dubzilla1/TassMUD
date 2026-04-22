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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Meteor Swarm: a persistent field effect centered on the caster.
 *
 * Every 3 seconds, roll 1d10 meteors. Each meteor randomly selects one
 * hostile target and deals spell-scaled damage. Tick count scales strongly
 * with proficiency to give meaningful growth from novice to mastery.
 */
public class MeteorSwarmEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(MeteorSwarmEffect.class);
    private static final int TICK_INTERVAL_MS = 3_000;

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (casterId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        int proficiency = parseInt(p.get("proficiency"), 1);
        proficiency = Math.max(1, Math.min(100, proficiency));

        int tickIntervalMs = TICK_INTERVAL_MS;
        if (p.containsKey("tick_interval")) {
            tickIntervalMs = Math.max(500, (int) (parseDouble(p.get("tick_interval"), 3.0) * 1000.0));
        }

        int maxTicks = Math.max(1, (int) Math.floor((def.getDurationSeconds() * 1000.0) / tickIntervalMs));
        int ticksRemaining = maxTicks;

        // Best progression for this spell fantasy: scale ticks linearly from 1..maxTicks by proficiency.
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.TICKS_REMAINING)
                || def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DURATION)) {
            ticksRemaining = Math.max(1, (int) Math.round(maxTicks * (proficiency / 100.0)));
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + (long) ticksRemaining * tickIntervalMs;

        p.put("proficiency", String.valueOf(proficiency));
        p.put("tick_interval_ms", String.valueOf(tickIntervalMs));
        p.put("ticks_remaining", String.valueOf(ticksRemaining));
        p.put("last_tick_ms", String.valueOf(now));

        notifyCaster(casterId,
                "You invoke Meteor Swarm! Fiery stones will rain for " + ticksRemaining + " volleys.");

        return new EffectInstance(UUID.randomUUID(), def.getId(), casterId, casterId, p, now, expiresAt, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        EffectDefinition def = EffectRegistry.getDefinition(instance.getDefId());
        if (def == null) return;

        Map<String, String> p = instance.getParams();
        if (p == null) return;

        int ticksRemaining = parseInt(p.get("ticks_remaining"), 0);
        if (ticksRemaining <= 0) {
            EffectRegistry.removeInstance(instance.getId());
            return;
        }

        int tickIntervalMs = parseInt(p.get("tick_interval_ms"), TICK_INTERVAL_MS);
        long lastTickMs = parseLong(p.get("last_tick_ms"), 0L);
        if (nowMs - lastTickMs < tickIntervalMs) return;

        Integer casterId = instance.getCasterId();
        if (casterId == null) {
            EffectRegistry.removeInstance(instance.getId());
            return;
        }

        Combat combat = CombatManager.getInstance().getCombatForCharacter(casterId);
        Combatant casterCombatant = combat != null ? combat.findByCharacterId(casterId) : null;

        if (combat != null && casterCombatant != null && casterCombatant.isAlive() && casterCombatant.isActive()) {
            runMeteorVolley(def, casterCombatant, combat, p);
        }

        ticksRemaining--;
        p.put("ticks_remaining", String.valueOf(ticksRemaining));
        p.put("last_tick_ms", String.valueOf(nowMs));

        if (ticksRemaining <= 0) {
            notifyCaster(casterId, "The heavens fall silent as your Meteor Swarm ends.");
            EffectRegistry.removeInstance(instance.getId());
        }
    }

    @Override
    public void expire(EffectInstance instance) {
        notifyCaster(instance.getCasterId(), "The heavens fall silent as your Meteor Swarm ends.");
    }

    private void runMeteorVolley(EffectDefinition def, Combatant caster, Combat combat, Map<String, String> p) {
        List<Combatant> enemies = combat.getValidTargets(caster);
        if (enemies.isEmpty()) return;

        int meteors = ThreadLocalRandom.current().nextInt(1, 11); // 1d10 meteors

        CharacterDAO dao = DaoProvider.characters();
        Map<Combatant, Integer> damageByTarget = new HashMap<>();
        int totalDamage = 0;

        for (int i = 0; i < meteors; i++) {
            Combatant target = enemies.get(ThreadLocalRandom.current().nextInt(enemies.size()));
            int dmg = rollMeteorDamage(def, p);
            if (dmg <= 0) continue;

            target.damage(dmg);
            totalDamage += dmg;
            damageByTarget.merge(target, dmg, Integer::sum);

            if (target.isPlayer() && target.getCharacterId() != null && target.getAsCharacter() != null) {
                GameCharacter ch = target.getAsCharacter();
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
        }

        if (damageByTarget.isEmpty()) return;

        String casterName = caster.getName();
        ClientHandler.broadcastRoomMessage(combat.getRoomId(),
                casterName + " calls down " + meteors + " blazing meteors!");

        StringBuilder sb = new StringBuilder("Meteor impacts: ");
        boolean first = true;
        for (Map.Entry<Combatant, Integer> e : damageByTarget.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey().getName()).append(" (").append(e.getValue()).append(")");
            first = false;

            if (e.getKey().isPlayer() && e.getKey().getCharacterId() != null) {
                ClientHandler targetSession = ClientHandler.charIdToSession.get(e.getKey().getCharacterId());
                if (targetSession != null) {
                    targetSession.sendRaw("A meteor slams into you for " + e.getValue() + " total damage this volley!");
                }
            }
        }

        ClientHandler.broadcastRoomMessage(combat.getRoomId(), sb.toString());
        notifyCaster(caster.getCharacterId(), "Your meteor volley deals " + totalDamage + " total damage.");
    }

    private int rollMeteorDamage(EffectDefinition def, Map<String, String> p) {
        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p != null) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) return 0;

        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return 0;

        int baseN;
        int dieM;
        try {
            baseN = Integer.parseInt(raw.substring(0, dIdx));
            dieM = Integer.parseInt(raw.substring(dIdx + 1));
        } catch (Exception e) {
            return 0;
        }

        int proficiency = parseInt(p.get("proficiency"), 1);
        proficiency = Math.max(1, Math.min(100, proficiency));

        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (proficiency / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        int total = 0;
        for (int i = 0; i < scaledN; i++) {
            total += ThreadLocalRandom.current().nextInt(1, dieM + 1);
        }

        total += Math.max(0, def.getLevelMultiplier());
        return total;
    }

    private void notifyCaster(Integer casterId, String msg) {
        if (casterId != null && casterId > 0) {
            ClientHandler s = ClientHandler.charIdToSession.get(casterId);
            if (s != null) s.sendRaw(msg);
        }
    }

    static int computeScaledTicksForTest(int maxTicks, int proficiency) {
        int prof = Math.max(1, Math.min(100, proficiency));
        return Math.max(1, (int) Math.round(maxTicks * (prof / 100.0)));
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
