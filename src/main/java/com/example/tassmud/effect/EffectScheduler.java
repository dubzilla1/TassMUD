package com.example.tassmud.effect;

import com.example.tassmud.util.TickService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Schedules ticking and expiration of active effects. Integrates with TickService.
 */
public class EffectScheduler {
    private static final EffectScheduler instance = new EffectScheduler();
    private volatile boolean initialized = false;
    private static final Logger logger = LoggerFactory.getLogger(EffectScheduler.class);

    private EffectScheduler() {}

    public static EffectScheduler getInstance() { return instance; }

    public synchronized void initialize(TickService tickService) {
        if (initialized) return;
        // Run every second
        tickService.scheduleAtFixedRate("effect-scheduler", () -> {
            try {
                tickOnce();
            } catch (Throwable t) {
                logger.error("[EffectScheduler] tick error", t);
            }
        }, 1000, 1000);
        initialized = true;
    }

    private void tickOnce() {
        long now = System.currentTimeMillis();
        List<EffectInstance> toExpire = new ArrayList<>();
        for (EffectInstance ei : EffectRegistry.getAllActiveInstances()) {
            if (ei.isExpired(now)) {
                toExpire.add(ei);
            } else {
                EffectDefinition def = EffectRegistry.getDefinition(ei.getDefId());
                EffectHandler h = EffectRegistry.getHandlerForDef(def);
                if (h != null) {
                    try { h.tick(ei, now); } catch (Exception ignored) {}
                }
            }
        }

        for (EffectInstance ei : toExpire) {
            EffectDefinition def = EffectRegistry.getDefinition(ei.getDefId());
            EffectHandler h = EffectRegistry.getHandlerForDef(def);
            try {
                if (h != null) h.expire(ei);
            } catch (Exception e) {
                logger.warn("[EffectScheduler] expire error for {}", ei.getId(), e);
            } finally {
                EffectRegistry.removeInstance(ei.getId());
            }
        }
    }
}
