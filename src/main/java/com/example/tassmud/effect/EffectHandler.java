package com.example.tassmud.effect;

import java.util.Map;

public interface EffectHandler {
    /**
     * Apply the effect. Return an EffectInstance if the effect has a runtime presence (duration),
     * or null for instant effects.
     */
    EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String,String> extraParams);

    /**
     * Called periodically for ticking effects (optional).
     */
    default void tick(EffectInstance instance, long nowMs) {}

    /**
     * Called when an effect expires.
     */
    default void expire(EffectInstance instance) {}
}
