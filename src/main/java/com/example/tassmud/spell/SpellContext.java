package com.example.tassmud.spell;

import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.combat.Combat;
import com.example.tassmud.model.Spell;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context passed to spell handlers. Contains all information needed to execute a spell:
 * - CommandContext for player I/O
 * - Combat instance if cast within combat
 * - Resolved target IDs
 * - Spell definition
 * - Extra parameters (proficiency, etc.)
 */
public class SpellContext {
    private final CommandContext commandContext;
    private final Combat combat;
    private final Spell spell;
    private final List<Integer> targetIds;
    private final Map<String, String> extraParams;
    private final Integer casterId;

    public SpellContext(CommandContext commandContext, Combat combat) {
        this(commandContext, combat, null, null, Collections.emptyList(), Collections.emptyMap());
    }
    
    public SpellContext(CommandContext commandContext, Combat combat, Integer casterId, 
                        Spell spell, List<Integer> targetIds, Map<String, String> extraParams) {
        this.commandContext = commandContext;
        this.combat = combat;
        this.casterId = casterId;
        this.spell = spell;
        this.targetIds = targetIds != null ? targetIds : Collections.emptyList();
        this.extraParams = extraParams != null ? new HashMap<>(extraParams) : new HashMap<>();
    }

    public CommandContext getCommandContext() { return commandContext; }
    public Combat getCombat() { return combat; }
    public Spell getSpell() { return spell; }
    public List<Integer> getTargetIds() { return targetIds; }
    public Map<String, String> getExtraParams() { return extraParams; }
    public Integer getCasterId() { return casterId; }
    
    /**
     * Get proficiency from extraParams (defaults to 1).
     */
    public int getProficiency() {
        try {
            return Integer.parseInt(extraParams.getOrDefault("proficiency", "1"));
        } catch (Exception e) {
            return 1;
        }
    }
    
    // ========== Aggro/Threat Management ==========
    
    /**
     * Add aggro for a damaging spell (10x spell level + damage dealt).
     * Should be called by spell handlers after dealing damage.
     * @param damage the damage dealt by the spell
     */
    public void addDamageSpellAggro(int damage) {
        if (combat == null || casterId == null || spell == null) return;
        combat.addDamageSpellAggro(casterId, spell.getLevel(), damage);
    }
    
    /**
     * Add aggro for a non-damaging spell (100x spell level).
     * Should be called by spell handlers for buffs, heals, debuffs, etc.
     */
    public void addUtilitySpellAggro() {
        if (combat == null || casterId == null || spell == null) return;
        combat.addUtilitySpellAggro(casterId, spell.getLevel());
    }
    
    /**
     * Add custom aggro amount (for special abilities).
     * @param amount the amount of aggro to add
     */
    public void addAggro(long amount) {
        if (combat == null || casterId == null) return;
        combat.addAggro(casterId, amount);
    }
}
