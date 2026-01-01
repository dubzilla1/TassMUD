package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Effect handler for SLOW debuff.
 * 
 * When a character is slowed, they are limited to a single basic attack per round
 * instead of potentially getting multiple attacks from second_attack, third_attack, etc.
 * 
 * Duration scales with proficiency (up to 60 seconds at 100% proficiency).
 * 
 * Combat systems should check EffectRegistry.hasEffect(targetId, "1009") to see
 * if a combatant is slowed and skip additional attacks accordingly.
 */
public class SlowEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(SlowEffect.class);
    
    /** Effect ID for the slow effect */
    public static final String EFFECT_SLOW = "1009";

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

        // Send messages
        String applyMsg = targetName + "'s movements become sluggish and slow!";
        ClientHandler.broadcastRoomMessage(roomId, applyMsg);

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("\u001B[36mA numbing cold seeps into your limbs, slowing your attacks!\u001B[0m");
        }

        logger.debug("[slow] {} slowed {} for {} seconds ({}% proficiency)", 
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
            String msg = trec.name + "'s movements return to normal speed.";
            ClientHandler.broadcastRoomMessage(trec.currentRoom, msg);
        }

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            targetSession.sendRaw("\u001B[32mThe numbing cold fades and your movements quicken!\u001B[0m");
        }

        logger.debug("[slow] Effect expired on target {}", targetId);
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No tick behavior for slow - it's a passive debuff
    }
    
    /**
     * Check if a character is currently slowed.
     * 
     * @param characterId the character to check
     * @return true if the character has the SLOW effect active
     */
    public static boolean isSlowed(Integer characterId) {
        return EffectRegistry.hasEffect(characterId, EFFECT_SLOW);
    }
}
