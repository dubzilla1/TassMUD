package com.example.tassmud.spell;

/**
 * Functional interface for spell implementations. Implementations should
 * perform the spell's effects synchronously and return true if the spell
 * executed successfully.
 */
@FunctionalInterface
public interface SpellHandler {
    /**
     * Execute the spell.
     * @param casterId numeric id of caster (character id) or null if not applicable
     * @param args optional arguments (raw string) for the spell invocation
     * @param ctx optional SpellContext containing command/combat info
     * @return true if the spell executed successfully
     */
    boolean cast(Integer casterId, String args, SpellContext ctx);
}
