package com.example.tassmud.effect;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.combat.Combat;
import com.example.tassmud.combat.Combatant;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Effect handler for Cause Light/Serious/Critical Wounds spells.
 * 
 * These OCCULT spells deal negative energy damage to living creatures,
 * but HEAL undead targets instead.
 * 
 * Damage formula: NdM + caster_level * (0.5 + proficiency%)
 * Where proficiency% ranges from 0.01 (1%) to 1.00 (100%)
 * 
 * So at minimum (1% prof): level * 0.51
 * At maximum (100% prof): level * 1.50
 * 
 * Dice by severity:
 * - Light: 1d8 (level 1)
 * - Serious: 5d8 (level 4)  
 * - Critical: 10d8 (level 7)
 */
public class CauseWoundsEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(CauseWoundsEffect.class);

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Get dice from definition (e.g., "1d8", "5d8", "10d8")
        String raw = def.getDiceMultiplierRaw();
        if ((raw == null || raw.isEmpty()) && p.containsKey("dice")) raw = p.get("dice");
        if (raw == null || raw.isEmpty()) {
            logger.warn("[cause wounds] No dice defined");
            return null;
        }

        // Parse NdM
        raw = raw.trim().toLowerCase();
        int dIdx = raw.indexOf('d');
        if (dIdx <= 0) return null;
        String nStr = raw.substring(0, dIdx);
        String mStr = raw.substring(dIdx + 1);
        int numDice = 0, dieSides = 0;
        try {
            numDice = Integer.parseInt(nStr);
            dieSides = Integer.parseInt(mStr);
        } catch (Exception e) {
            logger.warn("[cause wounds] Invalid dice format: {}", raw);
            return null;
        }

        // Get caster level and proficiency
        int casterLevel = 1;
        int proficiency = 1;
        try {
            casterLevel = Integer.parseInt(p.getOrDefault("casterLevel", "1"));
        } catch (Exception ignored) {}
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Check if target is undead
        boolean isUndead = Boolean.parseBoolean(p.getOrDefault("targetIsUndead", "false"));
        String severity = p.getOrDefault("severity", "light");

        // Roll dice
        int diceTotal = 0;
        for (int i = 0; i < numDice; i++) {
            diceTotal += (int) (ThreadLocalRandom.current().nextDouble() * dieSides) + 1;
        }

        // Calculate level bonus: caster_level * (0.5 + proficiency%)
        // proficiency is 1-100, so proficiency% = proficiency / 100.0
        double profPercent = proficiency / 100.0;
        double levelMultiplier = 0.5 + profPercent; // 0.51 to 1.50
        int levelBonus = (int) Math.round(casterLevel * levelMultiplier);

        int totalAmount = diceTotal + levelBonus;

        // Get character info for messages
        CharacterDAO dao = new CharacterDAO();
        String casterName = "Someone";
        if (casterId != null) {
            CharacterDAO.CharacterRecord crec = dao.findById(casterId);
            if (crec != null) casterName = crec.name;
        }

        String targetName = "someone";
        CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) targetName = trec.name;

        CombatManager cm = CombatManager.getInstance();
        Combatant targetCombatant = cm.getCombatantForCharacter(targetId);
        Integer roomId = null;
        Combat combat = cm.getCombatForCharacter(targetId);
        if (combat != null) roomId = combat.getRoomId();
        if (roomId == null && trec != null) roomId = trec.currentRoom;

        if (isUndead) {
            // HEAL the undead target
            applyHealing(targetCombatant, targetId, totalAmount, dao, trec);
            
            String msg = buildHealMessage(casterName, targetName, severity, totalAmount);
            ClientHandler.broadcastRoomMessage(roomId, msg);
            
            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("\u001B[32mThe negative energy invigorates your undead form! (+" + totalAmount + " HP)\u001B[0m");
            }
            
            logger.debug("[cause {} wounds] {} healed undead {} for {} HP", severity, casterName, targetName, totalAmount);
        } else {
            // DAMAGE the living target
            applyDamage(targetCombatant, targetId, totalAmount, dao, trec);
            
            String msg = buildDamageMessage(casterName, targetName, severity, totalAmount);
            ClientHandler.broadcastRoomMessage(roomId, msg);
            
            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("\u001B[31mNegative energy tears at your life force! (-" + totalAmount + " HP)\u001B[0m");
            }
            
            logger.debug("[cause {} wounds] {} dealt {} damage to {}", severity, casterName, totalAmount, targetName);
        }

        // Create instance for lifecycle tracking (instant effect)
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        return new EffectInstance(id, def.getId(), casterId, targetId, p, now, now, def.getPriority());
    }

    private void applyDamage(Combatant combatant, Integer targetId, int amount, CharacterDAO dao, CharacterDAO.CharacterRecord trec) {
        if (combatant != null && combatant.getAsCharacter() != null) {
            combatant.damage(amount);
            
            // Persist HP for players
            if (combatant.isPlayer() && combatant.getCharacterId() != null) {
                GameCharacter ch = combatant.getAsCharacter();
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
        } else if (trec != null) {
            // Offline or not in combat
            int newHp = Math.max(1, trec.hpCur - amount);
            dao.saveCharacterStateByName(trec.name, newHp, trec.mpCur, trec.mvCur, trec.currentRoom);
        }
    }

    private void applyHealing(Combatant combatant, Integer targetId, int amount, CharacterDAO dao, CharacterDAO.CharacterRecord trec) {
        if (combatant != null && combatant.getAsCharacter() != null) {
            GameCharacter ch = combatant.getAsCharacter();
            int newHp = Math.min(ch.getHpMax(), ch.getHpCur() + amount);
            ch.setHpCur(newHp);
            
            // Persist HP for players
            if (combatant.isPlayer() && combatant.getCharacterId() != null) {
                dao.saveCharacterStateByName(ch.getName(), ch.getHpCur(), ch.getMpCur(), ch.getMvCur(), ch.getCurrentRoom());
            }
        } else if (trec != null) {
            // Offline or not in combat - need to get hpMax
            int hpMax = trec.hpMax; // Assuming CharacterRecord has hpMax
            int newHp = Math.min(hpMax, trec.hpCur + amount);
            dao.saveCharacterStateByName(trec.name, newHp, trec.mpCur, trec.mvCur, trec.currentRoom);
        }
    }

    private String buildDamageMessage(String casterName, String targetName, String severity, int amount) {
        switch (severity) {
            case "light":
                return casterName + "'s touch sends a jolt of negative energy into " + targetName + " for " + amount + " damage!";
            case "serious":
                return casterName + " channels waves of negative energy that tear through " + targetName + " for " + amount + " damage!";
            case "critical":
                return casterName + " unleashes devastating negative energy that rips through " + targetName + " for " + amount + " damage!";
            default:
                return casterName + " damages " + targetName + " with negative energy for " + amount + " damage!";
        }
    }

    private String buildHealMessage(String casterName, String targetName, String severity, int amount) {
        switch (severity) {
            case "light":
                return casterName + "'s negative energy flows into " + targetName + ", mending their undead form for " + amount + " HP!";
            case "serious":
                return "Waves of negative energy from " + casterName + " rejuvenate " + targetName + "'s undead essence for " + amount + " HP!";
            case "critical":
                return casterName + "'s devastating negative energy greatly empowers " + targetName + "'s undead form for " + amount + " HP!";
            default:
                return casterName + " heals " + targetName + "'s undead form with negative energy for " + amount + " HP!";
        }
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
