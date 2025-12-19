package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.persistence.CharacterDAO;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler that deals instant dice-based damage to a target.
 * Uses EffectDefinition.diceMultiplierRaw (eg "10d6") and scales the leading N by caster proficiency
 * when the definition's proficiencyImpact contains DICE_MULTIPLIER.
 */
public class InstantDamageEffect implements EffectHandler {

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String,String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) return null;

        // Determine proficiency
        int prof = 1;
        try { prof = Integer.parseInt(p.getOrDefault("proficiency", "1")); } catch (Exception ignored) {}
        prof = Math.max(1, Math.min(100, prof));

        // Parse NdM
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return null;
        String nStr = raw.substring(0, dIdx);
        String mStr = raw.substring(dIdx + 1);
        int baseN = 0, dieM = 0;
        try { baseN = Integer.parseInt(nStr); } catch (Exception e) { return null; }
        try { dieM = Integer.parseInt(mStr); } catch (Exception e) { return null; }

        // Scale N by proficiency if configured
        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int)Math.floor(baseN * (prof / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        // Roll damage
        int total = 0;
        for (int i = 0; i < scaledN; i++) {
            total += (int)(Math.random() * dieM) + 1;
        }

        // Optional flat bonus param
        int bonus = 0;
        try { bonus = Integer.parseInt(p.getOrDefault("bonus", "0")); } catch (Exception ignored) {}
        total += bonus;

        // Apply damage to target. Prefer live combatant if present so modifiers apply.
        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = cm.getCombatantForCharacter(targetId);

        CharacterDAO dao = new CharacterDAO();

        String casterName = null;
        if (casterId != null) {
            com.example.tassmud.persistence.CharacterDAO.CharacterRecord crec = dao.findById(casterId);
            if (crec != null) casterName = crec.name;
        }

        String targetName = null;
        com.example.tassmud.persistence.CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) targetName = trec.name;

        // If in combat, apply to combatant (hp tracked by Character instance)
        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            targetCombatant.damage(total);

            // Broadcast a room-style message
            Integer roomId = null;
            com.example.tassmud.combat.Combat combat = cm.getCombatForCharacter(targetId);
            if (combat != null) roomId = combat.getRoomId();
            String attacker = casterName != null ? casterName : def.getName();
            String msg = attacker + " strikes " + (targetName != null ? targetName : "someone") + " for " + total + " damage!";
            com.example.tassmud.net.ClientHandler.broadcastRoomMessage(roomId, msg);

            // Persist HP for players
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                com.example.tassmud.model.Character ch = targetCombatant.getAsCharacter();
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }

        } else {
            // Offline or not in combat: update persisted HP safely (don't allow instant-kill, floor to 1)
            if (trec != null) {
                int newHp = Math.max(1, trec.hpCur - total);
                dao.saveCharacterStateByName(trec.name, newHp, trec.mpCur, trec.mvCur, trec.currentRoom);
                Integer roomId = trec.currentRoom;
                String attacker = casterName != null ? casterName : def.getName();
                String verb = com.example.tassmud.combat.CombatManager.getDamageVerb(total, false);
                String msg = attacker + "'s attack " + verb + " " + trec.name + "!";
                com.example.tassmud.net.ClientHandler.broadcastRoomMessage(roomId, msg);
            }
        }

        // Create an EffectInstance record for lifecycle tracking (instant effects expire immediately)
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EffectInstance inst = new EffectInstance(id, def.getId(), casterId, targetId, p, now, now, def.getPriority());
        // Register instance so scheduler can remove it immediately
        return inst;
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        // Instant damage has no persistent state to cleanup
        EffectRegistry.removeInstance(instance.getId());
    }
}
