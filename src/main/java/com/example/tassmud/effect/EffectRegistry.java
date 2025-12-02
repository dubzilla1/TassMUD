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
        if (inst != null) activeInstances.put(inst.getId(), inst);
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
}
