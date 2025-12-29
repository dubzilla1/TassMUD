package com.example.tassmud.spell;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for spell handlers. Similar in spirit to CommandRegistry: a central
 * place to register spell names (keys) and their handler implementations.
 */
public class SpellRegistry {

    private static final Map<String, SpellHandler> SPELLS = new HashMap<>();
    private static final Map<String, SpellSchool> SCHOOLS = new HashMap<>();

    static {
        // Example registration point. Actual spell implementations should
        // register themselves here or during application initialization.
    }

    public static void register(String name, SpellHandler handler) {
        if (name == null || handler == null) return;
        SPELLS.put(name.toLowerCase(), handler);
    }

    public static void register(String name, SpellHandler handler, SpellSchool school) {
        if (name == null || handler == null) return;
        SPELLS.put(name.toLowerCase(), handler);
        SCHOOLS.put(name.toLowerCase(), school == null ? SpellSchool.PRIMAL : school);
    }

    public static SpellHandler get(String name) {
        if (name == null) return null;
        return SPELLS.get(name.toLowerCase());
    }

    public static Map<String, SpellHandler> getAll() { return Collections.unmodifiableMap(SPELLS); }

    public static boolean exists(String name) { return name != null && SPELLS.containsKey(name.toLowerCase()); }

    public static SpellSchool getSchool(String name) { return name == null ? null : SCHOOLS.get(name.toLowerCase()); }
}
