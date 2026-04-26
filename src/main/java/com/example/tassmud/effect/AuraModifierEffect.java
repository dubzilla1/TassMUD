package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.model.Stat;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for the {@code AURA_MODIFIER} child effect type (id "1027").
 * <p>
 * Applied by {@link AuraManager} to all PCs in the room of a paladin who has cast
 * <em>Aura of Protection</em>. Applies a flat ADD modifier to the {@code ARMOR} stat
 * equal to the paladin's level / 5 (stored in extraParams as {@code "value"}).
 * <p>
 * When applied by an aura ({@code extra.aura_source = "true"}), the
 * {@code expiresAtMs} is set to 0 (never expires naturally). {@link AuraManager}
 * calls {@link EffectRegistry#removeAllEffectsOfType} to strip it when the
 * caster moves or the parent aura expires.
 */
public class AuraModifierEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuraModifierEffect.class);

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        String statName = p.getOrDefault("stat", "ARMOR");
        String opName   = p.getOrDefault("op",   "ADD");
        String valueStr = p.get("value");
        if (valueStr == null) {
            logger.warn("[AuraModifierEffect] No 'value' in params for target={}", targetId);
            return null;
        }

        Stat stat;
        try { stat = Stat.valueOf(statName); }
        catch (Exception e) {
            logger.warn("[AuraModifierEffect] Unknown stat '{}', defaulting to ARMOR", statName);
            stat = Stat.ARMOR;
        }
        Modifier.Op op;
        try { op = Modifier.Op.valueOf(opName); }
        catch (Exception e) { op = Modifier.Op.ADD; }

        double value;
        try { value = Double.parseDouble(valueStr); }
        catch (Exception e) {
            logger.warn("[AuraModifierEffect] Unparseable value '{}', skipping", valueStr);
            return null;
        }

        boolean auraSource = "true".equals(p.get("aura_source"));
        long nowMs = System.currentTimeMillis();
        // aura_source = infinite duration — AuraManager removes it explicitly.
        long expiresAtMs = auraSource ? 0L : nowMs + (long)(def.getDurationSeconds() * 1_000L);

        UUID instanceId = UUID.randomUUID();
        String sourceName = def.getName();
        Modifier modifier = new Modifier(instanceId, sourceName, stat, op, value, expiresAtMs, def.getPriority());

        CharacterDAO dao = DaoProvider.characters();
        CombatManager cm = CombatManager.getInstance();
        Combatant combatant = cm.getCombatantForCharacter(targetId);
        if (combatant != null && combatant.getAsCharacter() != null) {
            GameCharacter ch = combatant.getAsCharacter();
            ch.addModifier(modifier);
            dao.saveModifiersForCharacter(targetId, ch);
        } else {
            List<Modifier> mods = dao.getModifiersForCharacter(targetId);
            mods.add(modifier);
            dao.saveModifierListForCharacter(targetId, mods);
        }

        logger.debug("[AuraModifierEffect] Applied {} {} {} to target={} expiresAt={}",
                op, value, stat, targetId, expiresAtMs);

        return new EffectInstance(instanceId, def.getId(), casterId, targetId, p, nowMs, expiresAtMs, def.getPriority());
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic logic — stat modifier is applied once and lasts until removed.
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        Integer targetId = instance.getTargetId();
        if (targetId == null) return;

        CharacterDAO dao = DaoProvider.characters();
        CombatManager cm = CombatManager.getInstance();
        Combatant combatant = cm.getCombatantForCharacter(targetId);
        if (combatant != null && combatant.getAsCharacter() != null) {
            GameCharacter ch = combatant.getAsCharacter();
            ch.removeModifier(instance.getId());
            dao.saveModifiersForCharacter(targetId, ch);
        } else {
            List<Modifier> mods = dao.getModifiersForCharacter(targetId);
            boolean changed = mods.removeIf(m -> m.id().equals(instance.getId()));
            if (changed) dao.saveModifierListForCharacter(targetId, mods);
        }
        EffectRegistry.removeInstance(instance.getId());
    }
}
