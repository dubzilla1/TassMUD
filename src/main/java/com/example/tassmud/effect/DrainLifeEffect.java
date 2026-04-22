package com.example.tassmud.effect;

import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.DaoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Effect handler for Drain Life — deals negative energy damage to the target
 * and heals the caster for the full amount drained.
 *
 * Damage formula: NdM (proficiency-scaled) + level_multiplier * casterLevel
 * Healing: caster receives HP equal to the damage dealt.
 */
public class DrainLifeEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(DrainLifeEffect.class);

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null || casterId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Get dice from definition (e.g., "10d8")
        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) {
            logger.warn("[drain life] No dice defined");
            return null;
        }

        // Parse NdM
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return null;
        int baseN, dieM;
        try {
            baseN = Integer.parseInt(raw.substring(0, dIdx));
            dieM = Integer.parseInt(raw.substring(dIdx + 1));
        } catch (Exception e) {
            logger.warn("[drain life] Invalid dice format: {}", raw);
            return null;
        }

        // Get proficiency and caster level
        int proficiency = 1;
        int casterLevel = 1;
        try { proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1")); } catch (Exception ignored) {}
        try { casterLevel = Integer.parseInt(p.getOrDefault("casterLevel", "1")); } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Scale dice count by proficiency if configured
        int scaledN = baseN;
        if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
            scaledN = (int) Math.floor(baseN * (proficiency / 100.0));
            if (scaledN < 1) scaledN = 1;
        }

        // Roll damage
        int diceTotal = 0;
        for (int i = 0; i < scaledN; i++) {
            diceTotal += ThreadLocalRandom.current().nextInt(1, dieM + 1);
        }

        // Level bonus
        int levelBonus = def.getLevelMultiplier() * casterLevel;
        int totalDamage = diceTotal + levelBonus;

        // Resolve names
        CharacterDAO dao = DaoProvider.characters();
        String casterName = "Someone";
        CharacterDAO.CharacterRecord casterRec = dao.findById(casterId);
        if (casterRec != null) casterName = casterRec.name;

        String targetName = "someone";
        CharacterDAO.CharacterRecord targetRec = dao.findById(targetId);
        if (targetRec != null) targetName = targetRec.name;

        CombatManager cm = CombatManager.getInstance();
        Integer roomId = null;
        Combat combat = cm.getCombatForCharacter(targetId);
        if (combat != null) roomId = combat.getRoomId();
        if (roomId == null && targetRec != null) roomId = targetRec.currentRoom;

        // --- Deal damage to target ---
        Combatant targetCombatant = cm.getCombatantForCharacter(targetId);
        if (targetCombatant != null && targetCombatant.getAsCharacter() != null) {
            targetCombatant.damage(totalDamage);
            if (targetCombatant.isPlayer() && targetCombatant.getCharacterId() != null) {
                GameCharacter ch = targetCombatant.getAsCharacter();
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
        } else if (targetRec != null) {
            int newHp = Math.max(1, targetRec.hpCur - totalDamage);
            dao.saveCharacterStateByName(targetRec.name, newHp, targetRec.mpCur, targetRec.mvCur, targetRec.currentRoom);
        }

        // --- Heal caster for the amount drained ---
        Combatant casterCombatant = cm.getCombatantForCharacter(casterId);
        if (casterCombatant != null && casterCombatant.getAsCharacter() != null) {
            GameCharacter ch = casterCombatant.getAsCharacter();
            int newHp = Math.min(ch.getHpMax(), ch.getHpCur() + totalDamage);
            ch.setHpCur(newHp);
            if (casterCombatant.isPlayer() && casterCombatant.getCharacterId() != null) {
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
        } else if (casterRec != null) {
            int newHp = Math.min(casterRec.hpMax, casterRec.hpCur + totalDamage);
            dao.saveCharacterStateByName(casterRec.name, newHp, casterRec.mpCur, casterRec.mvCur, casterRec.currentRoom);
        }

        // --- Messages ---
        String roomMsg = casterName + " drains the life from " + targetName
                + " for " + totalDamage + " damage, absorbing the stolen vitality!";
        ClientHandler.broadcastRoomMessage(roomId, roomMsg);

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("\u001B[31m" + casterName + " drains " + totalDamage
                    + " life from you!\u001B[0m");
        }

        // Notify caster
        ClientHandler casterSession = ClientHandler.charIdToSession.get(casterId);
        if (casterSession != null) {
            casterSession.sendRaw("\u001B[32mYou drain " + totalDamage + " life from "
                    + targetName + ", healing yourself!\u001B[0m");
        }

        logger.debug("[drain life] {} drained {} HP from {} (dice={}, levelBonus={}, prof={}%)",
                casterName, totalDamage, targetName, diceTotal, levelBonus, proficiency);

        // Instant effect — create instance for lifecycle tracking
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        return new EffectInstance(id, def.getId(), casterId, targetId, p, now, now, def.getPriority());
    }

    @Override
    public void expire(EffectInstance instance) {
        // Instant effect — nothing to clean up
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // Instant effect — no ticking
    }
}
