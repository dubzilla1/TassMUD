package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Effect handler for CURSED debuff.
 * 
 * When a character is cursed, their combat skills and spells have a chance to fail.
 * The failure chance scales with the caster's proficiency from 25% to 75%.
 * Duration also scales with proficiency from 10 to 60 seconds.
 * 
 * Systems should call CursedEffect.checkCurseFails(characterId) before executing
 * skills or spells to determine if the action should fail due to the curse.
 */
public class CursedEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(CursedEffect.class);
    private static final Random rng = new Random();
    
    /** Effect ID for the cursed effect */
    public static final String EFFECT_CURSED = "1012";
    
    /** Base failure chance (at 1% proficiency) */
    private static final double BASE_FAIL_CHANCE = 0.25;
    
    /** Maximum failure chance (at 100% proficiency) */
    private static final double MAX_FAIL_CHANCE = 0.75;

    /**
     * Check if a character is currently cursed.
     * @param characterId The character to check
     * @return true if the character has the cursed effect active
     */
    public static boolean isCursed(Integer characterId) {
        if (characterId == null) return false;
        return EffectRegistry.hasEffect(characterId, EFFECT_CURSED);
    }
    
    /**
     * Check if a cursed character's action should fail.
     * Returns true if the character is cursed AND the curse triggers a failure.
     * The failure chance is stored in the effect instance params.
     * 
     * @param characterId The character attempting an action
     * @return true if the action should fail due to curse, false if it proceeds normally
     */
    public static boolean checkCurseFails(Integer characterId) {
        if (characterId == null) return false;
        
        // Find the curse effect instance to get its failure chance
        for (EffectInstance inst : EffectRegistry.getActiveForTarget(characterId)) {
            if (EFFECT_CURSED.equals(inst.getDefId())) {
                // Get failure chance from params (stored during apply)
                Map<String, String> params = inst.getParams();
                double failChance = BASE_FAIL_CHANCE;
                try {
                    failChance = Double.parseDouble(params.getOrDefault("fail_chance", "0.25"));
                } catch (Exception ignored) {}
                
                // Roll for failure
                if (rng.nextDouble() < failChance) {
                    return true; // Curse triggers - action fails
                }
                return false; // Curse didn't trigger this time
            }
        }
        return false; // Not cursed
    }

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId, Map<String, String> extraParams) {
        if (targetId == null) return null;

        Map<String, String> p = new HashMap<>();
        if (def.getParams() != null) p.putAll(def.getParams());
        if (extraParams != null) p.putAll(extraParams);

        // Get proficiency for duration and fail chance scaling
        int proficiency = 1;
        try {
            proficiency = Integer.parseInt(p.getOrDefault("proficiency", "1"));
        } catch (Exception ignored) {}
        proficiency = Math.max(1, Math.min(100, proficiency));

        // Calculate duration: scales from min to max based on proficiency
        double baseDuration = def.getDurationSeconds(); // 60 seconds max
        double minDuration = 10.0;
        try {
            minDuration = Double.parseDouble(p.getOrDefault("min_duration", "10"));
        } catch (Exception ignored) {}
        
        // Linear scale for duration
        double scaledDuration = minDuration + (baseDuration - minDuration) * (proficiency / 100.0);
        long durationMs = (long)(scaledDuration * 1000);

        // Calculate failure chance: 25% + (proficiency/2)% = 25% to 75%
        double baseFailChance = BASE_FAIL_CHANCE;
        double maxFailChance = MAX_FAIL_CHANCE;
        try {
            baseFailChance = Double.parseDouble(p.getOrDefault("base_fail_chance", "25")) / 100.0;
            maxFailChance = Double.parseDouble(p.getOrDefault("max_fail_chance", "75")) / 100.0;
        } catch (Exception ignored) {}
        
        // Scale: base + (proficiency * (max - base) / 100)
        double failChance = baseFailChance + (proficiency * (maxFailChance - baseFailChance) / 100.0);
        p.put("fail_chance", String.valueOf(failChance));

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
        String applyMsg = "A dark aura envelops " + targetName + " as they are cursed!";
        ClientHandler.broadcastRoomMessage(roomId, applyMsg);

        // Notify target
        ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
        if (targetSession != null) {
            int failPct = (int)(failChance * 100);
            targetSession.sendRaw("\u001B[35mYou feel a dark curse settle upon you! Your abilities may fail (" + failPct + "% chance).\u001B[0m");
        }

        logger.debug("[cursed] {} cursed {} for {} seconds, {}% fail chance ({}% proficiency)", 
                casterName, targetName, scaledDuration, (int)(failChance * 100), proficiency);

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
            ClientHandler.broadcastRoomMessage(roomId, "The dark curse on " + targetName + " fades away.");
            
            // Notify target
            ClientHandler targetSession = ClientHandler.charIdToSession.get(targetId);
            if (targetSession != null) {
                targetSession.sendRaw("\u001B[32mThe curse lifts from you! Your abilities return to normal.\u001B[0m");
            }
            
            logger.debug("[cursed] Effect expired on {}", targetName);
        }
    }

    @Override
    public void tick(EffectInstance instance, long nowMs) {
        // No periodic effect, just waits for expiration
    }
}
