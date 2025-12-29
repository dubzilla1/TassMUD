package com.example.tassmud.persistence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple manager to ensure expensive migration/setup tasks run only once per key
 * during JVM startup. DAOs can delegate their per-class ensureTables() calls
 * to this manager to avoid repeated checks when multiple instances are created.
 */
public final class MigrationManager {

    private static final Set<String> executed = ConcurrentHashMap.newKeySet();

    private MigrationManager() { }

    /**
     * Ensure the migration identified by `key` has been executed once. If not,
     * runs the provided migration Runnable under a class-level lock and records
     * it as executed.
     */
    public static void ensureMigration(String key, Runnable migration) {
        if (key == null) key = "default";
        if (executed.contains(key)) return;
        synchronized (MigrationManager.class) {
            if (executed.contains(key)) return;
            migration.run();
            executed.add(key);
        }
    }
}
