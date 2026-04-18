package com.example.tassmud.util.mob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps {@code spec_fun} keys (e.g. {@code "spec_cast_mage"})
 * to their {@link MobileSpecialHandler} implementations.
 * <p>
 * Handlers are registered once at server startup — see {@code Server.java} —
 * and then looked up per-tick with zero allocation overhead.
 */
public class MobileSpecialRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MobileSpecialRegistry.class);
    private static final MobileSpecialRegistry INSTANCE = new MobileSpecialRegistry();

    /** Live handler map — populated at startup, read-only during gameplay. */
    private final ConcurrentHashMap<String, MobileSpecialHandler> handlers = new ConcurrentHashMap<>();

    private MobileSpecialRegistry() {}

    public static MobileSpecialRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register a handler under the given spec-fun key.
     * Logs a warning if the key is already registered (last writer wins).
     */
    public void register(String key, MobileSpecialHandler handler) {
        if (key == null || key.isBlank() || handler == null) return;
        MobileSpecialHandler previous = handlers.put(key, handler);
        if (previous != null) {
            logger.warn("MobileSpecialRegistry: key '{}' was already registered — overwriting", key);
        } else {
            logger.debug("MobileSpecialRegistry: registered '{}'", key);
        }
    }

    /**
     * Look up a handler by key.
     *
     * @return the handler, or {@code null} if no handler is registered for the key
     */
    public MobileSpecialHandler get(String key) {
        if (key == null) return null;
        return handlers.get(key);
    }

    /** Returns the number of registered handlers (for startup logging). */
    public int count() {
        return handlers.size();
    }
}
