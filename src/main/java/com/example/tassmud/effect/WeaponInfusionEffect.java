package com.example.tassmud.effect;

import com.example.tassmud.model.WeaponFamily;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Effect handler for weapon infusions that modify how basic attacks work.
 * 
 * Weapon infusions can change:
 * - Whether attacks are ranged or melee
 * - Which stat is used for attack/damage rolls (e.g., INT instead of STR)
 * - Which defense is targeted (e.g., reflex instead of armor)
 * - Whether attacks hit all enemies (AoE)
 * 
 * Expected params in EffectDefinition.params:
 *   infusion_type: ARCANE|DIVINE|PRIMAL|OCCULT (type of infusion for display)
 *   weapon_family: STAVES|SWORDS|etc. (which weapon family this applies to)
 *   attack_stat: STRENGTH|DEXTERITY|INTELLIGENCE|WISDOM|CHARISMA (stat for damage)
 *   defense_stat: ARMOR|REFLEX|FORTITUDE|WILL (which defense to target)
 *   ranged: true|false (whether attacks become ranged)
 *   aoe: true|false (whether attacks hit all enemies in the room)
 */
public class WeaponInfusionEffect implements EffectHandler {

    /**
     * Data class representing an active weapon infusion on a character.
     */
    public static class InfusionData {
        public final String infusionType;
        public final WeaponFamily weaponFamily;
        public final String attackStat;
        public final String defenseStat;
        public final boolean isRanged;
        public final boolean isAoE;
        public final long expiresAtMs;
        public final UUID instanceId;
        public final String effectName;

        public InfusionData(String infusionType, WeaponFamily weaponFamily, String attackStat,
                           String defenseStat, boolean isRanged, boolean isAoE, long expiresAtMs, 
                           UUID instanceId, String effectName) {
            this.infusionType = infusionType;
            this.weaponFamily = weaponFamily;
            this.attackStat = attackStat;
            this.defenseStat = defenseStat;
            this.isRanged = isRanged;
            this.isAoE = isAoE;
            this.expiresAtMs = expiresAtMs;
            this.instanceId = instanceId;
            this.effectName = effectName;
        }

        public boolean isExpired() {
            return expiresAtMs > 0 && System.currentTimeMillis() >= expiresAtMs;
        }
    }

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String,String> extraParams) {
        if (targetId == null) return null;
        
        Map<String,String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Parse weapon family
        String weaponFamilyStr = p.get("weapon_family");
        WeaponFamily weaponFamily = null;
        if (weaponFamilyStr != null) {
            try {
                weaponFamily = WeaponFamily.valueOf(weaponFamilyStr.toUpperCase());
            } catch (Exception ignored) {}
        }

        // Get other params
        String infusionType = p.getOrDefault("infusion_type", "ARCANE");
        String attackStat = p.getOrDefault("attack_stat", "STRENGTH");
        String defenseStat = p.getOrDefault("defense_stat", "ARMOR");
        boolean isRanged = Boolean.parseBoolean(p.getOrDefault("ranged", "false"));
        boolean isAoE = Boolean.parseBoolean(p.getOrDefault("aoe", "false"));

        // Calculate duration based on proficiency
        long now = System.currentTimeMillis();
        long expiresAt = 0;
        double effectiveDuration = def.getDurationSeconds();
        
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DURATION)) {
            String profStr = p.get("proficiency");
            int prof = 1;
            try { prof = Integer.parseInt(profStr == null ? "1" : profStr); } catch (Exception ignored) {}
            double frac = Math.max(1, prof) / 100.0;
            effectiveDuration = def.getDurationSeconds() * frac;
        }
        if (effectiveDuration > 0) expiresAt = now + (long)(effectiveDuration * 1000.0);

        UUID instanceId = UUID.randomUUID();

        // Store the infusion data in the params for later retrieval
        Map<String, String> instanceParams = new HashMap<>(p);
        instanceParams.put("_weapon_family", weaponFamily != null ? weaponFamily.name() : "");
        instanceParams.put("_infusion_type", infusionType);
        instanceParams.put("_attack_stat", attackStat);
        instanceParams.put("_defense_stat", defenseStat);
        instanceParams.put("_ranged", String.valueOf(isRanged));
        instanceParams.put("_aoe", String.valueOf(isAoE));

        EffectInstance inst = new EffectInstance(instanceId, def.getId(), casterId, targetId, 
                                                  instanceParams, now, expiresAt, def.getPriority());
        return inst;
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        EffectRegistry.removeInstance(instance.getId());
    }

    /**
     * Check if a character has an active weapon infusion for a specific weapon family.
     * 
     * @param characterId the character to check
     * @param weaponFamily the weapon family to check for
     * @return InfusionData if an active infusion exists, null otherwise
     */
    public static InfusionData getActiveInfusion(Integer characterId, WeaponFamily weaponFamily) {
        if (characterId == null) return null;
        
        long now = System.currentTimeMillis();
        InfusionData bestInfusion = null;
        int bestPriority = Integer.MIN_VALUE;
        
        for (EffectInstance ei : EffectRegistry.getActiveForTarget(characterId)) {
            if (ei.isExpired(now)) continue;
            
            EffectDefinition def = EffectRegistry.getDefinition(ei.getDefId());
            if (def == null || def.getType() != EffectDefinition.Type.WEAPON_INFUSION) continue;
            
            Map<String, String> params = ei.getParams();
            if (params == null) continue;
            
            // Check if this infusion applies to the specified weapon family
            String familyStr = params.get("_weapon_family");
            if (familyStr == null || familyStr.isEmpty()) continue;
            
            try {
                WeaponFamily infusionFamily = WeaponFamily.valueOf(familyStr);
                if (infusionFamily == weaponFamily) {
                    // Track the highest priority infusion (Greater > Lesser)
                    if (ei.getPriority() > bestPriority) {
                        bestPriority = ei.getPriority();
                        bestInfusion = new InfusionData(
                            params.getOrDefault("_infusion_type", "ARCANE"),
                            infusionFamily,
                            params.getOrDefault("_attack_stat", "STRENGTH"),
                            params.getOrDefault("_defense_stat", "ARMOR"),
                            Boolean.parseBoolean(params.getOrDefault("_ranged", "false")),
                            Boolean.parseBoolean(params.getOrDefault("_aoe", "false")),
                            ei.getExpiresAtMs(),
                            ei.getId(),
                            def.getName()
                        );
                    }
                }
            } catch (Exception ignored) {}
        }
        
        return bestInfusion;
    }

    /**
     * Check if a character has any active weapon infusion.
     * 
     * @param characterId the character to check
     * @return true if any weapon infusion is active
     */
    public static boolean hasAnyActiveInfusion(Integer characterId) {
        if (characterId == null) return false;
        
        long now = System.currentTimeMillis();
        for (EffectInstance ei : EffectRegistry.getActiveForTarget(characterId)) {
            if (ei.isExpired(now)) continue;
            
            EffectDefinition def = EffectRegistry.getDefinition(ei.getDefId());
            if (def != null && def.getType() == EffectDefinition.Type.WEAPON_INFUSION) {
                return true;
            }
        }
        
        return false;
    }
}
