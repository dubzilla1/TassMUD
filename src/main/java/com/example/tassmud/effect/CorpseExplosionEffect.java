package com.example.tassmud.effect;

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
 * Effect handler for Corpse Explosion — deals AoE negative energy damage to a single target.
 *
 * This handler is called once per target by the OccultSpellHandler's handleCorpseExplosion,
 * which pre-rolls the AoE damage and passes it via extraParams["prerolledDamage"].
 * If no prerolled damage is provided, it rolls its own dice.
 *
 * Damage formula: NdM (proficiency-scaled) + level_multiplier * casterLevel
 */
public class CorpseExplosionEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(CorpseExplosionEffect.class);

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null || casterId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Check for prerolled damage (AoE: same damage for all targets)
        int totalDamage;
        if (p.containsKey("prerolledDamage")) {
            try {
                totalDamage = Integer.parseInt(p.get("prerolledDamage"));
            } catch (Exception e) {
                logger.warn("[corpse explosion] Invalid prerolledDamage: {}", p.get("prerolledDamage"));
                return null;
            }
        } else {
            // Roll damage ourselves (fallback)
            String raw = def.getDiceMultiplierRaw();
            if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
            if (raw == null || raw.isEmpty()) {
                logger.warn("[corpse explosion] No dice defined");
                return null;
            }

            raw = raw.trim().toLowerCase();
            int dIdx = raw.indexOf('d');
            if (dIdx <= 0) return null;
            int baseN, dieM;
            try {
                baseN = Integer.parseInt(raw.substring(0, dIdx));
                dieM = Integer.parseInt(raw.substring(dIdx + 1));
            } catch (Exception e) {
                logger.warn("[corpse explosion] Invalid dice format: {}", raw);
                return null;
            }

            int proficiency = 1;
            int casterLevel = 1;
            try { proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1")); } catch (Exception ignored) {}
            try { casterLevel = Integer.parseInt(p.getOrDefault("casterLevel", "1")); } catch (Exception ignored) {}
            proficiency = Math.max(1, Math.min(100, proficiency));

            int scaledN = baseN;
            if (def.getProficiencyImpact().contains(EffectDefinition.ProficiencyImpact.DICE_MULTIPLIER)) {
                scaledN = (int) Math.floor(baseN * (proficiency / 100.0));
                if (scaledN < 1) scaledN = 1;
            }

            int diceTotal = 0;
            for (int i = 0; i < scaledN; i++) {
                diceTotal += ThreadLocalRandom.current().nextInt(1, dieM + 1);
            }

            int levelBonus = def.getLevelMultiplier() * casterLevel;
            totalDamage = diceTotal + levelBonus;
        }

        // Resolve target name
        CharacterDAO dao = DaoProvider.characters();
        String targetName = "someone";
        CharacterDAO.CharacterRecord targetRec = dao.findById(targetId);
        if (targetRec != null) targetName = targetRec.name;

        // Apply damage to target
        CombatManager cm = CombatManager.getInstance();
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

        // Per-target damage message
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("\u001B[31mThe explosion shreds you for " + totalDamage + " damage!\u001B[0m");
        }

        logger.debug("[corpse explosion] {} took {} damage (target={})",
                targetName, totalDamage, targetId);

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
