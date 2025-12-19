package com.example.tassmud.effect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Simple in-memory registry for effect definitions and handlers.
 * For now this keeps active instances in memory; persistence is handled by handlers themselves when needed.
 */
public class EffectRegistry {
    private static final Map<String, EffectDefinition> defs = new ConcurrentHashMap<>();
    private static final Map<String, EffectHandler> handlers = new ConcurrentHashMap<>();
    private static final Map<UUID, EffectInstance> activeInstances = new ConcurrentHashMap<>();

    public static void registerDefinition(EffectDefinition def) {
        if (def != null) defs.put(def.getId(), def);
    }

    public static EffectDefinition getDefinition(String id) { return defs.get(id); }

    public static void registerHandler(String defId, EffectHandler handler) {
        if (defId != null && handler != null) handlers.put(defId, handler);
    }

    public static EffectHandler getHandler(String defId) { return handlers.get(defId); }

    public static EffectHandler getHandlerForDef(EffectDefinition def) {
        if (def == null) return null;
        EffectHandler h = handlers.get(def.getId());
        if (h != null) return h;
        return handlers.get(def.getType().name());
    }

    public static EffectInstance apply(String defId, Integer casterId, Integer targetId, Map<String,String> extraParams) {
        EffectDefinition def = defs.get(defId);
        if (def == null) return null;
        EffectHandler h = handlers.get(defId);
        if (h == null) {
            // try a generic handler by type
            h = handlers.get(def.getType().name());
        }
        if (h == null) return null;
        EffectInstance inst = h.apply(def, casterId, targetId, extraParams);
        // Only track persistent effects with duration > 0 in activeInstances
        // Instant effects (heals, damage) should not be tracked
        if (inst != null && def.isPersistent() && def.getDurationSeconds() > 0) {
            activeInstances.put(inst.getId(), inst);
        }
        return inst;
    }

    public static List<EffectInstance> getActiveForTarget(Integer targetId) {
        List<EffectInstance> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (EffectInstance ei : activeInstances.values()) {
            if (ei.getTargetId() != null && ei.getTargetId().equals(targetId) && !ei.isExpired(now)) {
                out.add(ei);
            }
        }
        return out;
    }

    public static java.util.Collection<EffectInstance> getAllActiveInstances() {
        return activeInstances.values();
    }

    public static void removeInstance(UUID id) {
        activeInstances.remove(id);
    }
    
    // === Visibility Effect Constants ===
    public static final String EFFECT_INVISIBILITY = "110";
    public static final String EFFECT_SEE_INVISIBILITY = "111";
    public static final String EFFECT_GM_INVISIBILITY = "112";
    
    // === Combat Information Effect Constants ===
    public static final String EFFECT_INSIGHT = "115";
    
    /**
     * Check if a target has a specific effect active.
     */
    public static boolean hasEffect(Integer targetId, String effectDefId) {
        if (targetId == null || effectDefId == null) return false;
        long now = System.currentTimeMillis();
        for (EffectInstance ei : activeInstances.values()) {
            if (ei.getTargetId() != null && ei.getTargetId().equals(targetId) 
                    && effectDefId.equals(ei.getDefId()) && !ei.isExpired(now)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a target is invisible (has invisibility or GM invisibility effect).
     */
    public static boolean isInvisible(Integer targetId) {
        return hasEffect(targetId, EFFECT_INVISIBILITY) || hasEffect(targetId, EFFECT_GM_INVISIBILITY);
    }
    
    /**
     * Check if an observer can see invisible targets.
     * GM invisibility cannot be seen by anyone.
     */
    public static boolean canSeeInvisible(Integer observerId) {
        return hasEffect(observerId, EFFECT_SEE_INVISIBILITY);
    }
    
    /**
     * Check if an observer can see a target (considering invisibility).
     * @param observerId The character who is looking
     * @param targetId The character being looked at
     * @return true if observer can see target
     */
    public static boolean canSee(Integer observerId, Integer targetId) {
        if (targetId == null) return true;
        
        // GM invisibility is absolute - no one can see it
        if (hasEffect(targetId, EFFECT_GM_INVISIBILITY)) {
            return false;
        }
        
        // If target is not invisible, anyone can see them
        if (!hasEffect(targetId, EFFECT_INVISIBILITY)) {
            return true;
        }
        
        // Target is invisible - check if observer can see invisible
        return canSeeInvisible(observerId);
    }
    
    /**
     * Check if a character has the Insight effect, allowing them to see enemy HP.
     * @param characterId The character to check
     * @return true if the character has the Insight effect active
     */
    public static boolean hasInsight(Integer characterId) {
        return hasEffect(characterId, EFFECT_INSIGHT);
    }
    
    /**
     * Remove all invisibility effects from a target.
     * Called when combat starts.
     */
    public static void removeInvisibility(Integer targetId) {
        if (targetId == null) return;
        List<UUID> toRemove = new ArrayList<>();
        for (EffectInstance ei : activeInstances.values()) {
            if (ei.getTargetId() != null && ei.getTargetId().equals(targetId)) {
                String defId = ei.getDefId();
                if (EFFECT_INVISIBILITY.equals(defId)) {
                    toRemove.add(ei.getId());
                }
            }
        }
        for (UUID id : toRemove) {
            activeInstances.remove(id);
        }
    }
}
