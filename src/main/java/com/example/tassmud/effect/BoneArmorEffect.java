package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.model.Stat;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bone Armor effect — applies damage reduction (melee, ranged, spell) equal to
 * half the caster's level for 24 hours. All three DR modifiers share one
 * EffectInstance so they are removed together on expiry/dispel.
 */
public class BoneArmorEffect implements EffectHandler {

    private static final Stat[] DR_STATS = {
        Stat.MELEE_DAMAGE_REDUCTION,
        Stat.RANGED_DAMAGE_REDUCTION,
        Stat.SPELL_DAMAGE_REDUCTION
    };

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Compute DR value from caster level
        int casterLevel = 1;
        String lvlStr = p.get("casterLevel");
        if (lvlStr != null) {
            try { casterLevel = Integer.parseInt(lvlStr); } catch (Exception ignored) {}
        }
        int drValue = Math.max(1, casterLevel / 2);

        // Duration (24 hours = 86400s from effect definition, no proficiency scaling)
        long now = System.currentTimeMillis();
        long expiresAt = 0;
        double effectiveDuration = def.getDurationSeconds();
        if (effectiveDuration > 0) expiresAt = now + (long) (effectiveDuration * 1000.0);

        // Shared UUID for the effect instance
        UUID instanceId = UUID.randomUUID();

        // Human-friendly source name
        String sourceName = def.getName();
        try {
            if (casterId != null) {
                CharacterDAO.CharacterRecord casterRec = DaoProvider.characters().findById(casterId);
                if (casterRec != null && casterRec.name != null && !casterRec.name.isEmpty()) {
                    sourceName = def.getName() + " (" + casterRec.name + ")";
                }
            }
        } catch (Exception ignored) {}

        // Build three DR modifiers sharing the same instance UUID prefix for cleanup
        CharacterDAO dao = DaoProvider.characters();
        CombatManager cm = CombatManager.getInstance();
        Combatant combatant = cm.getCombatantForCharacter(targetId);

        if (combatant != null && combatant.getAsCharacter() != null) {
            GameCharacter ch = combatant.getAsCharacter();
            for (Stat stat : DR_STATS) {
                Modifier m = new Modifier(UUID.randomUUID(), sourceName, stat, Modifier.Op.ADD, drValue, expiresAt, def.getPriority());
                ch.addModifier(m);
            }
            dao.saveModifiersForCharacter(targetId, ch);
        } else {
            List<Modifier> mods = dao.getModifiersForCharacter(targetId);
            for (Stat stat : DR_STATS) {
                Modifier m = new Modifier(UUID.randomUUID(), sourceName, stat, Modifier.Op.ADD, drValue, expiresAt, def.getPriority());
                mods.add(m);
            }
            dao.saveModifierListForCharacter(targetId, mods);
        }

        return new EffectInstance(instanceId, def.getId(), casterId, targetId, p, now, expiresAt, def.getPriority());
    }
}
