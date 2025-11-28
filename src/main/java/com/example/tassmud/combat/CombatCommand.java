package com.example.tassmud.combat;

/**
 * Interface for combat commands (skills, attacks, spells used in combat).
 * Each command knows how to execute itself and has cooldown tracking.
 */
public interface CombatCommand {
    
    /**
     * Get the name of this command.
     */
    String getName();
    
    /**
     * Get the display name shown to players.
     */
    String getDisplayName();
    
    /**
     * Get the base cooldown duration in milliseconds.
     */
    long getCooldownMs();
    
    /**
     * Check if this command can be used by the given combatant right now.
     * Considers cooldowns, resources (MP, stamina), valid targets, etc.
     */
    boolean canUse(Combatant user, Combat combat);
    
    /**
     * Execute this command.
     * Returns a CombatResult describing what happened.
     */
    CombatResult execute(Combatant user, Combatant target, Combat combat);
    
    /**
     * Get the timestamp when this command will be off cooldown for the user.
     */
    long getCooldownEndTime(Combatant user);
    
    /**
     * Whether this command requires an explicit target.
     */
    boolean requiresTarget();
    
    /**
     * Whether this command can target allies.
     */
    default boolean canTargetAlly() { return false; }
    
    /**
     * Whether this command can target enemies.
     */
    default boolean canTargetEnemy() { return true; }
    
    /**
     * Whether this command can target self.
     */
    default boolean canTargetSelf() { return false; }
    
    /**
     * Get the priority of this command for AI selection.
     * Higher priority commands are preferred when multiple are available.
     */
    default int getAiPriority() { return 0; }
}
