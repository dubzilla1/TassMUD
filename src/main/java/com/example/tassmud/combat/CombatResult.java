package com.example.tassmud.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of executing a combat command or attack.
 * Contains all information needed to display the combat action to players.
 */
public class CombatResult {
    
    /** Whether the action was successful (hit, not miss) */
    private final boolean success;
    
    /** The type of result */
    private final ResultType type;
    
    /** Damage dealt (0 if miss or heal) */
    private final int damage;
    
    /** Healing done (0 if damage or miss) */
    private final int healing;
    
    /** The attacker/user */
    private final Combatant attacker;
    
    /** The target */
    private final Combatant target;
    
    /** Roll values for display (attack roll, damage roll, etc.) */
    private int attackRoll;
    private int damageRoll;
    
    /** Messages to display */
    private String attackerMessage;
    private String targetMessage;
    private String roomMessage;
    
    /** Additional effects that occurred */
    private final List<String> effects = new ArrayList<>();
    
    public enum ResultType {
        HIT,            // Normal hit with damage
        MISS,           // Attack missed
        CRITICAL_HIT,   // Critical hit (extra damage)
        GLANCING_BLOW,  // Partial hit (reduced damage)
        BLOCKED,        // Target blocked the attack
        PARRIED,        // Target parried the attack
        DODGED,         // Target dodged the attack
        INTERRUPTED,    // Attack was interrupted/canceled
        HEAL,           // Healing effect
        BUFF,           // Positive status effect applied
        DEBUFF,         // Negative status effect applied
        DEATH,          // Target was killed
        FLEE,           // Combatant fled
        ERROR           // Something went wrong
    }
    
    private CombatResult(ResultType type, boolean success, int damage, int healing, 
                         Combatant attacker, Combatant target) {
        this.type = type;
        this.success = success;
        this.damage = damage;
        this.healing = healing;
        this.attacker = attacker;
        this.target = target;
    }
    
    // Static factory methods
    
    public static CombatResult hit(Combatant attacker, Combatant target, int damage) {
        return new CombatResult(ResultType.HIT, true, damage, 0, attacker, target);
    }
    
    public static CombatResult criticalHit(Combatant attacker, Combatant target, int damage) {
        return new CombatResult(ResultType.CRITICAL_HIT, true, damage, 0, attacker, target);
    }
    
    public static CombatResult miss(Combatant attacker, Combatant target) {
        return new CombatResult(ResultType.MISS, false, 0, 0, attacker, target);
    }
    
    public static CombatResult blocked(Combatant attacker, Combatant target) {
        return new CombatResult(ResultType.BLOCKED, false, 0, 0, attacker, target);
    }
    
    public static CombatResult dodged(Combatant attacker, Combatant target) {
        return new CombatResult(ResultType.DODGED, false, 0, 0, attacker, target);
    }
    
    public static CombatResult parried(Combatant attacker, Combatant target) {
        return new CombatResult(ResultType.PARRIED, false, 0, 0, attacker, target);
    }
    
    public static CombatResult heal(Combatant healer, Combatant target, int healing) {
        return new CombatResult(ResultType.HEAL, true, 0, healing, healer, target);
    }
    
    public static CombatResult death(Combatant attacker, Combatant target, int finalDamage) {
        return new CombatResult(ResultType.DEATH, true, finalDamage, 0, attacker, target);
    }
    
    public static CombatResult flee(Combatant fleer) {
        return new CombatResult(ResultType.FLEE, true, 0, 0, fleer, null);
    }
    
    public static CombatResult interrupted(Combatant combatant) {
        CombatResult r = new CombatResult(ResultType.INTERRUPTED, false, 0, 0, combatant, null);
        r.attackerMessage = combatant.getName() + " is interrupted and cannot attack!";
        r.roomMessage = combatant.getName() + "'s attack is interrupted!";
        return r;
    }
    
    public static CombatResult error(String message) {
        CombatResult r = new CombatResult(ResultType.ERROR, false, 0, 0, null, null);
        r.attackerMessage = message;
        return r;
    }
    
    // Getters
    
    public boolean isSuccess() { return success; }
    public ResultType getType() { return type; }
    public int getDamage() { return damage; }
    public int getHealing() { return healing; }
    public Combatant getAttacker() { return attacker; }
    public Combatant getTarget() { return target; }
    
    public int getAttackRoll() { return attackRoll; }
    public CombatResult setAttackRoll(int roll) { this.attackRoll = roll; return this; }
    
    public int getDamageRoll() { return damageRoll; }
    public CombatResult setDamageRoll(int roll) { this.damageRoll = roll; return this; }
    
    public String getAttackerMessage() { return attackerMessage; }
    public CombatResult setAttackerMessage(String msg) { this.attackerMessage = msg; return this; }
    
    public String getTargetMessage() { return targetMessage; }
    public CombatResult setTargetMessage(String msg) { this.targetMessage = msg; return this; }
    
    public String getRoomMessage() { return roomMessage; }
    public CombatResult setRoomMessage(String msg) { this.roomMessage = msg; return this; }
    
    public List<String> getEffects() { return effects; }
    public CombatResult addEffect(String effect) { this.effects.add(effect); return this; }
    
    public boolean isHit() { return type == ResultType.HIT || type == ResultType.CRITICAL_HIT; }
    public boolean isMiss() { return type == ResultType.MISS || type == ResultType.DODGED || 
                                     type == ResultType.BLOCKED || type == ResultType.PARRIED; }
    public boolean isDeath() { return type == ResultType.DEATH; }
    public boolean isInterrupted() { return type == ResultType.INTERRUPTED; }
    
    @Override
    public String toString() {
        if (type == ResultType.ERROR) {
            return "CombatResult[ERROR: " + attackerMessage + "]";
        }
        String attackerName = attacker != null ? attacker.getName() : "?";
        String targetName = target != null ? target.getName() : "?";
        return String.format("CombatResult[%s %s -> %s, damage=%d, healing=%d]",
            type, attackerName, targetName, damage, healing);
    }
}
