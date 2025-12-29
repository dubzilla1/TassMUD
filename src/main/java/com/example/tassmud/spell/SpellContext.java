package com.example.tassmud.spell;

import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.combat.Combat;

/**
 * Context passed to spell handlers. May contain the originating CommandContext
 * (if the spell was invoked from user input) and the Combat instance if cast
 * within combat. Both fields are optional and may be null.
 */
public class SpellContext {
    private final CommandContext commandContext;
    private final Combat combat;

    public SpellContext(CommandContext commandContext, Combat combat) {
        this.commandContext = commandContext;
        this.combat = combat;
    }

    public CommandContext getCommandContext() { return commandContext; }
    public Combat getCombat() { return combat; }
}
