package com.example.tassmud.combat;

import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.Character;
import com.example.tassmud.model.Mobile;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Wraps a Character or Mobile participating in combat.
 * Tracks combat-specific state like cooldowns, queued commands, and alliance.
 */
public class Combatant {
    
    /** Unique identifier for this combatant in the combat instance */
    private final long combatantId;
    
    /** The character (for players) - mutually exclusive with mobile */
    private final Character character;
    
    /** The character ID (for players) */
    private final Integer characterId;
    
    /** The mobile (for NPCs) - mutually exclusive with character */
    private final Mobile mobile;
    
    /** Alliance/faction identifier - combatants with same alliance don't attack each other */
    private int alliance;
    
    /** Initiative roll for this round (higher goes first) */
    private int initiative;
    
    /** Combat commands queued by this combatant (for players: from input, for mobs: from AI) */
    private final Queue<CombatCommand> commandQueue = new LinkedList<>();
    
    /** The command being executed this turn (if any) */
    private CombatCommand currentCommand;
    
    /** Timestamp when this combatant can next use any combat ability (global cooldown) */
    private long globalCooldownUntil = 0;
    
    /** Timestamp when parry can next be used (parry-specific cooldown) */
    private long parryCooldownUntil = 0;
    
    /** Number of attacks remaining this round (for multi-attack) */
    private int attacksRemaining = 1;
    
    /** Whether this combatant has acted this round */
    private boolean hasActedThisRound = false;
    
    /** Whether this combatant is still alive and in combat */
    private boolean active = true;
    
    /** Timestamp when this combatant entered combat */
    private final long enteredCombatAt;
    
    /** Damage taken per armor category (for armor proficiency training) */
    private final Map<ArmorCategory, Integer> armorDamageCounters = new EnumMap<>(ArmorCategory.class);
    
    /** Active status flags on this combatant */
    private final Set<StatusFlag> statusFlags = EnumSet.noneOf(StatusFlag.class);
    
    /**
     * Status flags that can be applied to combatants.
     * These are temporary effects that modify combat behavior.
     */
    public enum StatusFlag {
        /** Next attack is interrupted/canceled */
        INTERRUPTED,
        /** Combatant cannot move */
        ROOTED,
        /** Combatant cannot act at all */
        STUNNED,
        /** Combatant is slowed (reduced attack speed) */
        SLOWED,
        /** Combatant is silenced (cannot cast spells) */
        SILENCED,
        /** Combatant is disarmed (cannot use weapon attacks) */
        DISARMED,
        /** Combatant is blinded (reduced accuracy) */
        BLINDED,
        /** Combatant is confused (may attack allies) */
        CONFUSED
    }
    
    /**
     * Create a combatant for a player character.
     */
    public Combatant(long combatantId, Character character, Integer characterId, int alliance) {
        this.combatantId = combatantId;
        this.character = character;
        this.characterId = characterId;
        this.mobile = null;
        this.alliance = alliance;
        this.enteredCombatAt = System.currentTimeMillis();
    }
    
    /**
     * Create a combatant for a mobile/NPC.
     */
    public Combatant(long combatantId, Mobile mobile, int alliance) {
        this.combatantId = combatantId;
        this.character = null;
        this.characterId = null;
        this.mobile = mobile;
        this.alliance = alliance;
        this.enteredCombatAt = System.currentTimeMillis();
    }
    
    // Identification
    
    public long getCombatantId() { return combatantId; }
    
    public boolean isPlayer() { return character != null && mobile == null; }
    
    public boolean isMobile() { return mobile != null; }
    
    public Character getCharacter() { return character; }
    
    public Integer getCharacterId() { return characterId; }
    
    public Mobile getMobile() { return mobile; }
    
    /**
     * Get the underlying Character (works for both players and mobiles since Mobile extends Character).
     */
    public Character getAsCharacter() {
        return mobile != null ? mobile : character;
    }
    
    public String getName() {
        if (mobile != null) return mobile.getName();
        if (character != null) return character.getName();
        return "Unknown";
    }
    
    // Alliance
    
    public int getAlliance() { return alliance; }
    
    public void setAlliance(int alliance) { this.alliance = alliance; }
    
    /**
     * Check if this combatant is hostile to another.
     * Different alliances are hostile.
     */
    public boolean isHostileTo(Combatant other) {
        return this.alliance != other.alliance;
    }
    
    /**
     * Check if this combatant is allied with another.
     * Same alliance means allied.
     */
    public boolean isAlliedWith(Combatant other) {
        return this.alliance == other.alliance;
    }
    
    // Initiative
    
    public int getInitiative() { return initiative; }
    
    public void setInitiative(int initiative) { this.initiative = initiative; }
    
    /**
     * Roll initiative for this round. Currently random; will be DEX-based later.
     */
    public void rollInitiative() {
        // For now: random 1-20 + DEX modifier
        Character c = getAsCharacter();
        int dexMod = c != null ? (c.getDex() - 10) / 2 : 0;
        this.initiative = (int)(Math.random() * 20) + 1 + dexMod;
    }
    
    // Command Queue
    
    public void queueCommand(CombatCommand command) {
        commandQueue.offer(command);
    }
    
    public CombatCommand pollNextCommand() {
        return commandQueue.poll();
    }
    
    public CombatCommand peekNextCommand() {
        return commandQueue.peek();
    }
    
    public void clearCommandQueue() {
        commandQueue.clear();
    }
    
    public boolean hasQueuedCommands() {
        return !commandQueue.isEmpty();
    }
    
    public int getQueuedCommandCount() {
        return commandQueue.size();
    }
    
    public CombatCommand getCurrentCommand() { return currentCommand; }
    
    public void setCurrentCommand(CombatCommand currentCommand) { this.currentCommand = currentCommand; }
    
    // Cooldowns
    
    public long getGlobalCooldownUntil() { return globalCooldownUntil; }
    
    public void setGlobalCooldownUntil(long timestamp) { this.globalCooldownUntil = timestamp; }
    
    public boolean isOnGlobalCooldown() {
        return System.currentTimeMillis() < globalCooldownUntil;
    }
    
    public long getGlobalCooldownRemaining() {
        return Math.max(0, globalCooldownUntil - System.currentTimeMillis());
    }
    
    // Parry cooldown
    
    public long getParryCooldownUntil() { return parryCooldownUntil; }
    
    public void setParryCooldownUntil(long timestamp) { this.parryCooldownUntil = timestamp; }
    
    public boolean isParryOnCooldown() {
        return System.currentTimeMillis() < parryCooldownUntil;
    }
    
    public long getParryCooldownRemaining() {
        return Math.max(0, parryCooldownUntil - System.currentTimeMillis());
    }
    
    // Attack tracking
    
    public int getAttacksRemaining() { return attacksRemaining; }
    
    public void setAttacksRemaining(int attacks) { this.attacksRemaining = attacks; }
    
    public void decrementAttacksRemaining() {
        if (attacksRemaining > 0) attacksRemaining--;
    }
    
    public boolean hasAttacksRemaining() { return attacksRemaining > 0; }
    
    // Round state
    
    public boolean hasActedThisRound() { return hasActedThisRound; }
    
    public void setHasActedThisRound(boolean acted) { this.hasActedThisRound = acted; }
    
    /**
     * Reset state for a new round.
     */
    public void resetForNewRound() {
        this.hasActedThisRound = false;
        this.currentCommand = null;
        
        // Calculate attacks for the round
        int baseAttacks = calculateBaseAttacks();
        
        // SLOWED: limits to 1 attack per round, then wears off
        if (consumeSlowed()) {
            this.attacksRemaining = 1;
        } else {
            this.attacksRemaining = baseAttacks;
        }
    }
    
    /**
     * Calculate base number of attacks per round.
     * For now: 1, but could be modified by class/level/equipment later.
     */
    private int calculateBaseAttacks() {
        // TODO: Factor in class abilities, dual wield, etc.
        return 1;
    }
    
    // Active/alive state
    
    public boolean isActive() { return active; }
    
    public void setActive(boolean active) { this.active = active; }
    
    /**
     * Check if this combatant is alive (HP > 0).
     */
    public boolean isAlive() {
        Character c = getAsCharacter();
        return c != null && c.getHpCur() > 0;
    }
    
    /**
     * Check if this combatant can act (active, alive, not on cooldown).
     */
    public boolean canAct() {
        return active && isAlive() && !isOnGlobalCooldown();
    }
    
    public long getEnteredCombatAt() { return enteredCombatAt; }
    
    // HP access convenience methods
    
    public int getHpCurrent() {
        Character c = getAsCharacter();
        return c != null ? c.getHpCur() : 0;
    }
    
    public int getHpMax() {
        Character c = getAsCharacter();
        return c != null ? c.getHpMax() : 0;
    }
    
    public void damage(int amount) {
        Character c = getAsCharacter();
        if (c != null) {
            c.setHpCur(c.getHpCur() - amount);
        }
    }
    
    /**
     * Record damage taken for a specific armor category (for proficiency training).
     */
    public void recordArmorDamage(ArmorCategory category, int damage) {
        if (category != null && damage > 0) {
            armorDamageCounters.merge(category, damage, Integer::sum);
        }
    }
    
    /**
     * Get total damage recorded for an armor category.
     */
    public int getArmorDamageCounter(ArmorCategory category) {
        return armorDamageCounters.getOrDefault(category, 0);
    }
    
    /**
     * Get all armor damage counters.
     */
    public Map<ArmorCategory, Integer> getArmorDamageCounters() {
        return armorDamageCounters;
    }
    
    /**
     * Reset all armor damage counters.
     */
    public void resetArmorDamageCounters() {
        armorDamageCounters.clear();
    }
    
    public void heal(int amount) {
        Character c = getAsCharacter();
        if (c != null) {
            c.setHpCur(c.getHpCur() + amount);
        }
    }
    
    /**
     * Get armor/AC value for defense calculations.
     */
    public int getArmor() {
        Character c = getAsCharacter();
        return c != null ? c.getArmor() : 10;
    }
    
    // Status flag methods
    
    /**
     * Add a status flag to this combatant.
     */
    public void addStatusFlag(StatusFlag flag) {
        statusFlags.add(flag);
    }
    
    /**
     * Remove a status flag from this combatant.
     */
    public void removeStatusFlag(StatusFlag flag) {
        statusFlags.remove(flag);
    }
    
    /**
     * Check if this combatant has a status flag.
     */
    public boolean hasStatusFlag(StatusFlag flag) {
        return statusFlags.contains(flag);
    }
    
    /**
     * Clear all status flags.
     */
    public void clearStatusFlags() {
        statusFlags.clear();
    }
    
    /**
     * Check if this combatant is interrupted (will skip next attack).
     */
    public boolean isInterrupted() {
        return hasStatusFlag(StatusFlag.INTERRUPTED);
    }
    
    /**
     * Consume the interrupted status (check and remove if present).
     * @return true if was interrupted (attack should be skipped)
     */
    public boolean consumeInterrupted() {
        return statusFlags.remove(StatusFlag.INTERRUPTED);
    }
    
    /**
     * Check if this combatant is stunned (cannot use skills with cooldowns).
     */
    public boolean isStunned() {
        return hasStatusFlag(StatusFlag.STUNNED);
    }
    
    /**
     * Consume the stunned status (check and remove if present).
     * Stunned wears off after attempting to use a cooldown skill.
     * @return true if was stunned
     */
    public boolean consumeStunned() {
        return statusFlags.remove(StatusFlag.STUNNED);
    }
    
    /**
     * Check if this combatant is slowed (can only make 1 attack per round).
     */
    public boolean isSlowed() {
        return hasStatusFlag(StatusFlag.SLOWED);
    }
    
    /**
     * Consume the slowed status (check and remove if present).
     * Slowed wears off at end of round.
     * @return true if was slowed
     */
    public boolean consumeSlowed() {
        return statusFlags.remove(StatusFlag.SLOWED);
    }
    
    @Override
    public String toString() {
        String type = isPlayer() ? "Player" : "Mobile";
        return String.format("Combatant[%s %s, HP=%d/%d, alliance=%d]",
            type, getName(), getHpCurrent(), getHpMax(), alliance);
    }
}
