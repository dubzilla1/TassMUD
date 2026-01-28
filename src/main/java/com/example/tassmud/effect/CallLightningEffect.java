package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.Weather;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.util.WeatherService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Effect handler for Call Lightning spell.
 * 
 * Deals instant electricity damage scaled by proficiency and weather conditions:
 * - Clear/Partly Cloudy: 0.5x damage (weak bolt)
 * - Overcast/Windy/Rainy/etc: 1.0x damage (normal)
 * - Stormy/Hurricane: 2.0x damage (devastating)
 * 
 * The spell handler (ArcaneSpellHandler) is responsible for checking if the caster
 * is indoors and blocking the cast. This effect handler applies the damage with
 * the weather multiplier passed in extraParams.
 */
public class CallLightningEffect implements EffectHandler {

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) return null;

        // Determine proficiency
        int prof = 1;
        try {
            prof = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        prof = Math.max(1, Math.min(100, prof));

        // Get weather damage multiplier from params (set by ArcaneSpellHandler)
        double damageMultiplier = 1.0;
        try {
            damageMultiplier = Double.parseDouble(p.getOrDefault("damageMultiplier", "1.0"));
        } catch (Exception ignored) {}

        // Parse NdM (e.g., "10d10")
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return null;
        String nStr = raw.substring(0, dIdx);
        String mStr = raw.substring(dIdx + 1);
        int baseN = 0, dieM = 0;
        try {
            baseN = Integer.parseInt(nStr);
        } catch (Exception e) {
            return null;
        }
        try {
            dieM = Integer.parseInt(mStr);
        } catch (Exception e) {
            return null;
        }

        // Scale N by proficiency if configured
        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (prof / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        // Roll damage
        int baseDamage = 0;
        for (int i = 0; i < scaledN; i++) {
            baseDamage += (int) (ThreadLocalRandom.current().nextDouble() * dieM) + 1;
        }

        // Apply weather multiplier
        int total = (int) Math.round(baseDamage * damageMultiplier);

        // Optional flat bonus param
        int bonus = 0;
        try {
            bonus = Integer.parseInt(p.getOrDefault("bonus", "0"));
        } catch (Exception ignored) {}
        total += bonus;

        // Apply damage to target
        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = cm.getCombatantForCharacter(targetId);
        CharacterDAO dao = new CharacterDAO();

        String casterName = null;
        if (casterId != null) {
            CharacterDAO.CharacterRecord crec = dao.findById(casterId);
            if (crec != null) casterName = crec.name;
        }

        String targetName = null;
        CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) targetName = trec.name;

        // Build damage message based on weather intensity
        String damageDesc;
        if (damageMultiplier >= 2.0) {
            damageDesc = "A massive bolt of lightning tears down from the raging storm and slams into";
        } else if (damageMultiplier <= 0.5) {
            damageDesc = "A thin streak of lightning reluctantly descends from the clear sky and strikes";
        } else {
            damageDesc = "A bolt of lightning streaks down from the clouds and strikes";
        }

        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            targetCombatant.damage(total);

            // Broadcast room message
            Integer roomId = null;
            Combat combat = cm.getCombatForCharacter(targetId);
            if (combat != null) roomId = combat.getRoomId();
            
            String msg = damageDesc + " " + (targetName != null ? targetName : "someone") + " for " + total + " electricity damage!";
            ClientHandler.broadcastRoomMessage(roomId, msg);

            // Persist HP for players
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                com.example.tassmud.model.GameCharacter ch = targetCombatant.getAsCharacter();
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }

        } else {
            // Offline or not in combat
            if (trec != null) {
                int newHp = Math.max(1, trec.hpCur - total);
                dao.saveCharacterStateByName(trec.name, newHp, trec.mpCur, trec.mvCur, trec.currentRoom);
                Integer roomId = trec.currentRoom;
                String msg = damageDesc + " " + trec.name + " for " + total + " electricity damage!";
                ClientHandler.broadcastRoomMessage(roomId, msg);
            }
        }

        // Create instance for lifecycle tracking (instant effects expire immediately)
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EffectInstance inst = new EffectInstance(id, def.getId(), casterId, targetId, p, now, now, def.getPriority());
        return inst;
    }

    @Override
    public void expire(EffectInstance instance) {
        // Instant effect - nothing to clean up
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // Instant effect - no ticking
    }
}
