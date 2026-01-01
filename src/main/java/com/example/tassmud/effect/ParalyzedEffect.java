package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for PARALYZED debuff.
 * 
 * When a character is paralyzed, they cannot attack or execute commands.
 * The command dispatcher and combat system should check for this effect
 * and skip processing if the character is paralyzed.
 * 
 * Duration scales with proficiency (up to 60 seconds at 100% proficiency).
 * 
 * Systems should check ParalyzedEffect.isParalyzed(targetId) to determine
 * if a character's actions should be blocked.
 */
public class ParalyzedEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(ParalyzedEffect.class);
    
    /** Effect ID for the paralyzed effect */
    public static final String EFFECT_PARALYZED = "1011";

    /**
     * Check if a character is currently paralyzed.
     * @param characterId The character to check
     * @return true if the character has the paralyzed effect active
     */
    public static boolean isParalyzed(Integer characterId) {
        if (characterId == null) return false;
        return EffectRegistry.hasEffect(characterId, EFFECT_PARALYZED);
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
        double minDuration = 5.0; // Minimum 5 seconds at 1% proficiency
        try {
            minDuration = Double.parseDouble(p.getOrDefault("min_duration", "5"));
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
        String applyMsg = targetName + " freezes in place, completely paralyzed!";
        ClientHandler.broadcastRoomMessage(roomId, applyMsg);

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("\u001B[31mYour muscles lock up! You cannot move!\u001B[0m");
        }

        logger.debug("[paralyzed] {} paralyzed {} for {} seconds ({}% proficiency)", 
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
            ClientHandler.broadcastRoomMessage(roomId, targetName + " shudders as the paralysis wears off.");
            
            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("\u001B[32mFeeling returns to your limbs! You can move again!\u001B[0m");
            }
            
            logger.debug("[paralyzed] Effect expired on {}", targetName);
        }
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic effect, just waits for expiration
    }
}
