package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Character;
import com.example.tassmud.persistence.CharacterDAO;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler that provides instant healing to a target.
 * Uses EffectDefinition.diceMultiplierRaw (e.g., "4d8") for base dice healing
 * and EffectDefinition.levelMultiplier for the "+N x level" bonus.
 * 
 * Example: Cure Light Wounds heals 4d8 + (1 x caster_level) HP
 *          Cure Critical Wounds heals 32d8 + (8 x caster_level) HP
 * 
 * When proficiencyImpact contains DICE_MULTIPLIER, the number of dice scales with proficiency.
 */
public class InstantHealEffect implements EffectHandler {

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) return null;

        // Determine proficiency (1-100)
        int prof = 1;
        try { prof = Integer.parseInt(p.getOrDefault("proficiency", "1")); } catch (Exception ignored) {}
        prof = Math.max(1, Math.min(100, prof));

        // Determine caster level (defaults to 1)
        int casterLevel = 1;
        try { casterLevel = Integer.parseInt(p.getOrDefault("caster_level", "1")); } catch (Exception ignored) {}
        casterLevel = Math.max(1, casterLevel);

        // Parse NdM dice notation
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return null;
        String nStr = raw.substring(0, dIdx);
        String mStr = raw.substring(dIdx + 1);
        int baseN = 0, dieM = 0;
        try { baseN = Integer.parseInt(nStr); } catch (Exception e) { return null; }
        try { dieM = Integer.parseInt(mStr); } catch (Exception e) { return null; }

        // Scale number of dice by proficiency if configured
        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (prof / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        // Roll healing dice
        int diceTotal = 0;
        for (int i = 0; i < scaledN; i++) {
            diceTotal += (int) (Math.random() * dieM) + 1;
        }

        // Add level-based bonus: levelMultiplier * casterLevel
        int levelBonus = def.getLevelMultiplier() * casterLevel;
        int totalHeal = diceTotal + levelBonus;

        // Optional flat bonus param
        int bonus = 0;
        try { bonus = Integer.parseInt(p.getOrDefault("bonus", "0")); } catch (Exception ignored) {}
        totalHeal += bonus;

        // Apply healing to target
        CharacterDAO dao = new CharacterDAO();
        
        String casterName = null;
        if (casterId != null) {
            CharacterDAO.CharacterRecord crec = dao.findById(casterId);
            if (crec != null) casterName = crec.name;
        }

        String targetName = null;
        CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) targetName = trec.name;

        // Check if target is in combat (use live combatant for real-time HP tracking)
        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = cm.getCombatantForCharacter(targetId);

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            // Heal the combatant (respects max HP)
            Character ch = targetCombatant.getAsCharacter();
            int oldHp = ch.getHpCur();
            ch.heal(totalHeal);
            int actualHeal = ch.getHpCur() - oldHp;

            // Broadcast healing message to room
            Integer roomId = null;
            com.example.tassmud.combat.Combat combat = cm.getCombatForCharacter(targetId);
            if (combat != null) roomId = combat.getRoomId();
            
            String healer = casterName != null ? casterName : def.getName();
            String target = targetName != null ? targetName : "someone";
            String msg;
            if (casterId != null && casterId.equals(targetId)) {
                msg = healer + " heals themselves for " + actualHeal + " hit points!";
            } else {
                msg = healer + " heals " + target + " for " + actualHeal + " hit points!";
            }
            com.example.tassmud.net.ClientHandler.broadcastRoomMessage(roomId, msg);

            // Persist HP for players
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }

        } else {
            // Not in combat: update persisted HP (cap at max)
            if (trec != null) {
                int newHp = Math.min(trec.hpMax, trec.hpCur + totalHeal);
                int actualHeal = newHp - trec.hpCur;
                dao.saveCharacterStateByName(trec.name, newHp, trec.mpCur, trec.mvCur, trec.currentRoom);
                
                Integer roomId = trec.currentRoom;
                String healer = casterName != null ? casterName : def.getName();
                String msg;
                if (casterId != null && casterId.equals(targetId)) {
                    msg = healer + " heals themselves for " + actualHeal + " hit points!";
                } else {
                    msg = healer + " heals " + trec.name + " for " + actualHeal + " hit points!";
                }
                com.example.tassmud.net.ClientHandler.broadcastRoomMessage(roomId, msg);
            }
        }

        // Create an EffectInstance record for lifecycle tracking (instant effects expire immediately)
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EffectInstance inst = new EffectInstance(id, def.getId(), casterId, targetId, p, now, now, def.getPriority());
        return inst;
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        // Instant heal has no persistent state to cleanup
        EffectRegistry.removeInstance(instance.getId());
    }
}
