package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for CONFUSED debuff.
 * 
 * When a character is confused, their attacks target a random entity in the room,
 * which could be themselves, allies, or non-combatants. If the random target is not
 * already in combat, they will be pulled into combat.
 * 
 * Duration scales with proficiency (up to 60 seconds at 100% proficiency).
 * 
 * Combat systems should check ConfusedEffect.isConfused(targetId) to determine
 * if target selection should be randomized.
 */
public class ConfusedEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConfusedEffect.class);
    
    /** Effect ID for the confused effect */
    public static final String EFFECT_CONFUSED = "1010";

    /**
     * Check if a character is currently confused.
     * @param characterId The character to check
     * @return true if the character has the confused effect active
     */
    public static boolean isConfused(Integer characterId) {
        if (characterId == null) return false;
        return EffectRegistry.hasEffect(characterId, EFFECT_CONFUSED);
    }

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Get proficiency for duration scaling
        int proficiency = 1;
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Calculate duration: scales from min to max based on proficiency
        double baseDuration = def.getDurationSeconds(); // 60 seconds max
        double minDuration = 10.0; // Minimum 10 seconds at 1% proficiency
        try {
            minDuration = Double.parseDouble(p.getOrDefault("min_duration", "10"));
        } catch (Exception ignored) {}
        
        // Linear scale: at 1% = minDuration, at 100% = baseDuration
        double scaledDuration = minDuration + (baseDuration - minDuration) * (proficiency / 100.0);
        long durationMs = (long)(scaledDuration * 1000);

        // Get names for messaging
        CharacterDAO dao = new CharacterDAO();
        String casterName = "Someone";
        if (casterId != null) {
            CharacterDAO.CharacterRecord crec = dao.findById(casterId);
            if (crec != null) casterName = crec.name;
        }

        String targetName = "someone";
        Integer roomId = null;
        CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) {
            targetName = trec.name;
            roomId = trec.currentRoom;
        }

        // Send room message
        String applyMsg = targetName + "'s eyes glaze over with confusion!";
        ClientHandler.broadcastRoomMessage(roomId, applyMsg);

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("\u001B[35mYour mind swirls with chaos! You can't tell friend from foe!\u001B[0m");
        }

        logger.debug("[confused] {} confused {} for {} seconds ({}% proficiency)", 
                casterName, targetName, scaledDuration, proficiency);

        // Create effect instance
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expiresAt = now + durationMs;
        
        return new EffectInstance(id, def.getId(), casterId, targetId, p, now, expiresAt, def.getPriority());
    }

    @Override
    public void expire(EffectInstance instance) {
        if (instance == null) return;
        
        Integer targetId = instance.getTargetId();
        if (targetId == null) return;

        // Get target name for message
        CharacterDAO dao = new CharacterDAO();
        CharacterDAO.CharacterRecord trec = dao.findById(targetId);
        if (trec != null) {
            Integer roomId = trec.currentRoom;
            String targetName = trec.name;
            
            // Send expiration message
            ClientHandler.broadcastRoomMessage(roomId, targetName + "'s eyes clear as the confusion fades.");
            
            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("\u001B[32mYour mind clears and you can think straight again.\u001B[0m");
            }
            
            logger.debug("[confused] Effect expired on {}", targetName);
        }
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic effect, just waits for expiration
    }
}
