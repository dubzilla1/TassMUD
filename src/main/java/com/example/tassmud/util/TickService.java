package com.example.tassmud.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple scheduler for periodic "tick" tasks.
 * Subsystems can register Runnables at different intervals (ms).
 */
public class TickService {
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public TickService() {
        // single-threaded scheduler to serialize tick operations by default
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tassmud-tick");
            t.setDaemon(true);
            return t;
        });
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs) {
        return scheduler.scheduleAtFixedRate(task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(String name, Runnable task, long initialDelayMs, long periodMs) {
        ScheduledFuture<?> f = scheduleAtFixedRate(task, initialDelayMs, periodMs);
        tasks.put(name, f);
        return f;
    }

    public boolean cancel(String name) {
        ScheduledFuture<?> f = tasks.remove(name);
        if (f == null) return false;
        return f.cancel(false);
    }

    public void shutdown() {
        try {
            for (Map.Entry<String, ScheduledFuture<?>> e : tasks.entrySet()) {
                try { e.getValue().cancel(false); } catch (Exception ignored) {}
            }
            scheduler.shutdownNow();
        } catch (Exception ignored) {}
    }
}
