package com.example.tassmud.effect;

import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.model.Stat;
import com.example.tassmud.model.Character;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/**
 * Effect handler that applies a stat modifier based on definition params.
 * Expected params in EffectDefinition.params:
 *   stat: STAT_NAME (required)
 *   op: ADD|MULTIPLY|OVERRIDE (optional, default ADD)
 *   value: numeric value (required)
 */
public class ModifierEffect implements EffectHandler {

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String,String> extraParams) {
        if (targetId == null) return null;
        Map<String,String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        String statName = p.get("stat");
        String opName = p.getOrDefault("op", "ADD");
        String valueStr = p.get("value");
        if (statName == null || valueStr == null) return null;

        Stat stat;
        try { stat = Stat.valueOf(statName); } catch (Exception e) { return null; }
        Modifier.Op op;
        try { op = Modifier.Op.valueOf(opName); } catch (Exception e) { op = Modifier.Op.ADD; }
        double value = 0.0;
        try { value = Double.parseDouble(valueStr); } catch (Exception e) { return null; }

        long now = System.currentTimeMillis();
        long expiresAt = 0;
        double effectiveDuration = def.getDurationSeconds();
        // If proficiency impacts duration, scale by proficiency fraction (1-100 -> 0.01-1.0)
        if (def.getProficiencyImpact().contains(com.example.tassmud.effect.EffectDefinition.ProficiencyImpact.DURATION)) {
            String profStr = p.get("proficiency");
            int prof = 1;
            try { prof = Integer.parseInt(profStr == null ? "1" : profStr); } catch (Exception ignored) {}
            double frac = Math.max(1, prof) / 100.0;
            effectiveDuration = def.getDurationSeconds() * frac;
        }
        if (effectiveDuration > 0) expiresAt = now + (long)(effectiveDuration * 1000.0);

        // Use a single UUID for both the effect instance and the underlying Modifier so we can reliably remove it on expiry
        UUID instanceId = UUID.randomUUID();
        // Prefer a human-friendly source: effect definition name, optionally including caster name
        String sourceName = def.getName();
        try {
            if (casterId != null) {
                com.example.tassmud.persistence.CharacterDAO dao = new com.example.tassmud.persistence.CharacterDAO();
                com.example.tassmud.persistence.CharacterDAO.CharacterRecord casterRec = dao.findById(casterId);
                if (casterRec != null && casterRec.name != null && !casterRec.name.isEmpty()) {
                    sourceName = def.getName() + " (" + casterRec.name + ")";
                }
            }
        } catch (Exception ignored) {}

        Modifier m = new Modifier(instanceId, sourceName, stat, op, value, expiresAt, def.getPriority());

        CharacterDAO dao = new CharacterDAO();

        // If target has live Character instance via CombatManager, apply directly
        CombatManager cm = CombatManager.getInstance();
        Combatant combatant = cm.getCombatantForCharacter(targetId);
        if (combatant != null && combatant.getAsCharacter() != null) {
            Character ch = combatant.getAsCharacter();
            ch.addModifier(m);
            dao.saveModifiersForCharacter(targetId, ch);
        } else {
            // Offline or non-combat: append to persisted modifier list
            java.util.List<Modifier> mods = dao.getModifiersForCharacter(targetId);
            mods.add(m);
            dao.saveModifierListForCharacter(targetId, mods);
        }

        EffectInstance inst = new EffectInstance(instanceId, def.getId(), casterId, targetId, p, now, expiresAt, def.getPriority());
        return inst;
    }

    // Helper to compute a scaled cooldown given base cooldown and proficiency (1-100).
    private int computeScaledCooldown(double baseCooldown, int proficiency) {
        if (baseCooldown <= 0) return 0;
        double minCooldown = 3.0; // floor minimum in seconds
        // linear interpolation from baseCooldown at prof=1 to minCooldown at prof=100
        double t = Math.max(0, Math.min(1, (proficiency - 1) / 99.0));
        double scaled = baseCooldown * (1.0 - t) + minCooldown * t;
        return (int) Math.max(1, Math.round(scaled));
    }

    // Helper to compute scaled dice multiplier: "NdM" -> scale N by proficiency, min 1 at 1%.
    private int computeScaledDiceMultiplier(String raw, int proficiency) {
        if (raw == null || raw.isEmpty()) return 0;
        String s = raw.trim();
        int dIdx = s.indexOf('d');
        if (dIdx < 0) return 0;
        String nStr = s.substring(0, dIdx);
        try {
            int baseN = Integer.parseInt(nStr);
            int scaled = (int) Math.floor(baseN * (Math.max(1, proficiency) / 100.0));
            if (scaled < 1) scaled = 1;
            return scaled;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        Integer targetId = instance.getTargetId();
        if (targetId == null) return;

        CharacterDAO dao = new CharacterDAO();

        // If target has live character, remove modifier from in-memory Character and persist
        com.example.tassmud.combat.Combatant combatant = com.example.tassmud.combat.CombatManager.getInstance().getCombatantForCharacter(targetId);
        if (combatant != null && combatant.getAsCharacter() != null) {
            Character ch = combatant.getAsCharacter();
            ch.removeModifier(instance.getId());
            dao.saveModifiersForCharacter(targetId, ch);
        } else {
            // Offline: remove from persisted modifier list by UUID
            java.util.List<Modifier> mods = dao.getModifiersForCharacter(targetId);
            boolean changed = mods.removeIf(m -> m.id().equals(instance.getId()));
            if (changed) dao.saveModifierListForCharacter(targetId, mods);
        }
        // Remove from registry
        EffectRegistry.removeInstance(instance.getId());
    }
}
