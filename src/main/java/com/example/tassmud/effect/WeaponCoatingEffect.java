package com.example.tassmud.effect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for weapon coatings — substances applied to a weapon that trigger
 * an additional effect on the target when the attacker scores a critical hit.
 *
 * Applied to the ATTACKER as a buff. On a critical hit, the referenced
 * {@code on_crit_effect_id} is applied to the target.
 *
 * Expected params in EffectDefinition.params:
 *   coating_type      : display label, e.g. "POISON", "ACID", "VENOM"
 *   on_crit_effect_id : effect definition ID to apply to the target on crit
 *   charges           : how many crit procs before the coating is consumed
 *                       (0 = unlimited — lasts until duration expires)
 *
 * Proficiency is passed as an extraParam at application time and stored in the
 * instance params as "_proficiency". The on-crit effect receives this proficiency
 * so damage/duration can scale with the attacker's skill.
 */
public class WeaponCoatingEffect implements EffectHandler {

    /**
     * Describes an active weapon coating on a character.
     */
    public static class CoatingData {
        public final String coatingType;
        public final String onCritEffectId;
        /** 0 = unlimited charges; positive = procs remaining */
        public final int charges;
        public final int proficiency;
        public final long expiresAtMs;
        public final UUID instanceId;
        public final String effectName;

        public CoatingData(String coatingType, String onCritEffectId, int charges,
                           int proficiency, long expiresAtMs, UUID instanceId, String effectName) {
            this.coatingType = coatingType;
            this.onCritEffectId = onCritEffectId;
            this.charges = charges;
            this.proficiency = proficiency;
            this.expiresAtMs = expiresAtMs;
            this.instanceId = instanceId;
            this.effectName = effectName;
        }

        public boolean isExpired() {
            return expiresAtMs > 0 && System.currentTimeMillis() >= expiresAtMs;
        }
    }

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Parse proficiency for duration scaling
        int proficiency = 1;
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Compute duration, optionally scaled by proficiency
        long now = System.currentTimeMillis();
        double effectiveDuration = def.getDurationSeconds();
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DURATION)) {
            effectiveDuration = effectiveDuration * (0.5 + (proficiency / 100.0) * 0.5);
        }
        long expiresAt = effectiveDuration > 0 ? now + (long) (effectiveDuration * 1000) : 0;

        // Remove any existing coating on the same character (REPLACE / REFRESH policy)
        if (def.getStackPolicy() == EffectDefinition.StackPolicy.REPLACE_HIGHER_PRIORITY
                || def.getStackPolicy() == EffectDefinition.StackPolicy.REFRESH) {
            for (EffectInstance existing : EffectRegistry.getActiveForTarget(targetId)) {
                EffectDefinition existingDef = EffectRegistry.getDefinition(existing.getDefId());
                if (existingDef != null && existingDef.getType() == EffectDefinition.Type.WEAPON_COATING) {
                    EffectRegistry.removeInstance(existing.getId());
                    break;
                }
            }
        }

        // Persist all data in instance params using _ prefix convention
        p.put("_coating_type", p.getOrDefault("coating_type", "UNKNOWN"));
        p.put("_on_crit_effect_id", p.getOrDefault("on_crit_effect_id", ""));
        p.put("_charges", p.getOrDefault("charges", "0"));
        p.put("_proficiency", String.valueOf(proficiency));

        UUID instanceId = UUID.randomUUID();
        return new EffectInstance(instanceId, def.getId(), casterId, targetId, p, now, expiresAt, def.getPriority());
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance != null) {
            EffectRegistry.removeInstance(instance.getId());
        }
    }

    /**
     * Returns the active weapon coating for a character, or {@code null} if none is active.
     */
    public static CoatingData getActiveCoating(Integer characterId) {
        if (characterId == null) return null;
        long now = System.currentTimeMillis();
        for (EffectInstance ei : EffectRegistry.getActiveForTarget(characterId)) {
            if (ei.isExpired(now)) continue;
            EffectDefinition def = EffectRegistry.getDefinition(ei.getDefId());
            if (def == null || def.getType() != EffectDefinition.Type.WEAPON_COATING) continue;

            Map<String, String> params = ei.getParams();
            if (params == null) continue;

            String onCritEffectId = params.getOrDefault("_on_crit_effect_id", "");
            if (onCritEffectId.isEmpty()) continue;

            int charges = 0;
            try { charges = Integer.parseInt(params.getOrDefault("_charges", "0")); } catch (Exception ignored) {}
            int proficiency = 1;
            try { proficiency = Integer.parseInt(params.getOrDefault("_proficiency", "1")); } catch (Exception ignored) {}

            return new CoatingData(
                params.getOrDefault("_coating_type", "UNKNOWN"),
                onCritEffectId,
                charges,
                proficiency,
                ei.getExpiresAtMs(),
                ei.getId(),
                def.getName()
            );
        }
        return null;
    }

    /**
     * Consume one charge from the coating.
     * If the remaining charges reach zero the coating instance is removed (consumed).
     * Has no effect when charges == 0 (unlimited).
     */
    public static void consumeCharge(UUID instanceId) {
        EffectInstance inst = EffectRegistry.getInstance(instanceId);
        if (inst == null) return;

        Map<String, String> params = inst.getParams();
        int charges;
        try {
            charges = Integer.parseInt(params.getOrDefault("_charges", "0"));
        } catch (Exception e) {
            return;
        }

        if (charges <= 0) return; // unlimited — don't consume

        int remaining = charges - 1;
        if (remaining <= 0) {
            EffectRegistry.removeInstance(instanceId);
        } else {
            params.put("_charges", String.valueOf(remaining));
        }
    }
}
