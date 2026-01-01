package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for FLYING buff.
 * 
 * When a character is flying:
 * - They do not use movement points when moving between rooms
 * - They can enter rooms with FLYING, WATER_SWIM, and WATER_NOSWIM sector types
 * - They are immune to being tripped
 * 
 * Duration scales with proficiency from 60 seconds (1 min) to 600 seconds (10 min).
 */
public class FlyingEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(FlyingEffect.class);
    
    /** Effect ID for the flying effect */
    public static final String EFFECT_FLYING = "1013";

    /**
     * Check if a character is currently flying.
     * @param characterId The character to check
     * @return true if the character has the flying effect active
     */
    public static boolean isFlying(Integer characterId) {
        if (characterId == null) return false;
        return EffectRegistry.hasEffect(characterId, EFFECT_FLYING);
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

        // Calculate duration: scales from min (60s) to max (600s) based on proficiency
        double baseDuration = def.getDurationSeconds(); // 600 seconds max
        double minDuration = 60.0; // Minimum 60 seconds at 1% proficiency
        try {
            minDuration = Double.parseDouble(p.getOrDefault("min_duration", "60"));
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

        // Determine if self-cast for message
        boolean selfCast = casterId != null && casterId.equals(targetId);
        
        // Send room message
        String applyMsg;
        if (selfCast) {
            applyMsg = targetName + "'s feet leave the ground as divine magic grants them flight!";
        } else {
            applyMsg = casterName + " grants " + targetName + " the power of flight!";
        }
        ClientHandler.broadcastRoomMessage(roomId, applyMsg);

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            int durationSecs = (int)(scaledDuration);
            int minutes = durationSecs / 60;
            int seconds = durationSecs % 60;
            String timeStr = minutes > 0 
                ? minutes + " minute" + (minutes > 1 ? "s" : "") + (seconds > 0 ? " " + seconds + " seconds" : "")
                : seconds + " seconds";
            targetSession.sendRaw("\u001B[36mYou rise into the air! You can fly for " + timeStr + ".\u001B[0m");
        }

        logger.debug("[flying] {} granted flight to {} for {} seconds ({}% proficiency)", 
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
            ClientHandler.broadcastRoomMessage(roomId, targetName + " slowly descends as the flight magic fades.");
            
            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("\u001B[33mYour flight magic wears off and you gently return to the ground.\u001B[0m");
            }
            
            logger.debug("[flying] Effect expired on {}", targetName);
        }
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic effect, just waits for expiration
    }
}
